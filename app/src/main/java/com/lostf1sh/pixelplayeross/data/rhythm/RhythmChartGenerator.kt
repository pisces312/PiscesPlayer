/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (docs/rhythm-dsp-chart-generation-plan.md §4, docs/rhythm-chart-ladder-plan.md §4.1).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import java.util.Arrays
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Raised when the audio cannot produce a playable chart. Message is user-facing. */
class RhythmGenerationException(message: String) : Exception(message)

/**
 * Result of the expensive half of the pipeline: everything the difficulty-independent stages
 * produced, plus the tempo and phase needed to render any grid from it.
 *
 * Onsets are already filtered by the optional loudness / density constraints, so a render stage
 * does no further filtering and the master chart is literally this list turned into notes.
 */
internal class DspAnalysis(
    val onsetTimesMs: DoubleArray,
    val onsetStrength: FloatArray,
    /** Dominant frequency band per onset, 0..7. */
    val onsetBands: IntArray,
    val beatMs: Double,
    val beatOffsetMs: Double,
    val durationMs: Int,
    val detectedBpm: Double,
    val usedBpm: Double,
    val octaveCorrection: Double,
    /** Peaks found by the detector, before the optional constraints dropped any. */
    val peakCount: Int,
    private val startedAtMs: Long,
) {
    fun diagnostics(noteCount: Int): DspDiagnostics = DspDiagnostics(
        detectedBpm = detectedBpm,
        usedBpm = usedBpm,
        octaveCorrection = octaveCorrection,
        beatOffsetMs = beatOffsetMs,
        onsetCount = peakCount,
        noteCount = noteCount,
        analysisMs = System.currentTimeMillis() - startedAtMs,
    )
}

/**
 * Local DSP chart generator: one mono PCM buffer in, one 4-lane `RhythmChart` out.
 *
 * Pure Kotlin on purpose — no Android types, no third-party DSP library — so the whole
 * pipeline is JVM-testable and stays comparable with the reference Python implementation
 * (`RhythmChartLab/rhythm-lab/fourier/fourier_chartgen.py`).
 *
 * The work is split in two halves because only the last two stages depend on the difficulty:
 * [analyze] runs STFT, band whitening, spectral flux, peak picking and tempo estimation once;
 * [renderMaster] and [render] turn that analysis into notes. That split is what lets one
 * per-song master chart feed every difficulty via [RhythmChartDeriver] without decoding audio
 * again.
 *
 * The default path deliberately applies **no** loudness or NPS constraint: measurements on
 * the reference song showed grid quantization alone reaches F1 93.0 against a modern chart's
 * normal difficulty, while every extra filter reduces it.
 */
internal object RhythmChartGenerator {

    const val LANE_COUNT = LaneStrategy.LANE_COUNT

    private const val N_FFT = 2048
    private const val HOP = 512
    private const val N_BANDS = 8
    private const val WHITEN_WINDOW = 32

    private const val PEAK_LOCAL_MS = 50.0
    private const val PEAK_CONTEXT_MS = 200.0
    private const val PEAK_MAD_K = 1.5f

    private const val BPM_MIN = 60.0
    private const val BPM_MAX = 200.0
    private const val BPM_PRIOR = 130.0
    private const val BPM_PRIOR_SIGMA = 0.9

    /** Margin a non-1.0 octave must win by before it is trusted. */
    private const val OCTAVE_MIN_GAIN = 0.08

    private const val RMS_WINDOW_MS = 40.0

    // --- stages 1-7: difficulty independent -------------------------------------------------

    fun analyze(
        pcmMono: FloatArray,
        sampleRate: Int,
        durationMs: Int,
        params: RhythmAnalysisParams = RhythmAnalysisParams(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): DspAnalysis {
        val startedAt = System.currentTimeMillis()
        require(sampleRate > 0) { "sampleRate must be positive" }
        if (pcmMono.size < N_FFT) {
            throw RhythmGenerationException("Audio is too short to analyse")
        }

        val frameCount = 1 + (pcmMono.size - N_FFT) / HOP
        if (frameCount < 16) {
            throw RhythmGenerationException("Audio is too short to analyse")
        }
        val totalMs = if (durationMs > 0) durationMs else pcmMono.size * 1000 / sampleRate

        val fps = sampleRate.toDouble() / HOP
        val hopMs = HOP * 1000.0 / sampleRate

        // --- 1. STFT -> 8 log band energies (streamed, never keeps the magnitude matrix) ---
        val edges = bandEdges()
        val bandRaw = FloatArray(frameCount * N_BANDS)
        val window = FloatArray(N_FFT) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / (N_FFT - 1))).toFloat()
        }
        val fft = RealFft(N_FFT)
        val magnitudes = FloatArray(N_FFT / 2 + 1)
        val progressStep = max(1, frameCount / 100)

        for (frame in 0 until frameCount) {
            if (isCancelled()) throw CancellationException("Chart generation cancelled")
            fft.magnitude(pcmMono, frame * HOP, window, magnitudes)
            val base = frame * N_BANDS
            for (band in 0 until N_BANDS) {
                var peak = 0f
                for (bin in edges[band] until edges[band + 1]) {
                    val value = magnitudes[bin]
                    if (value > peak) peak = value
                }
                bandRaw[base + band] = ln(1.0 + peak.toDouble()).toFloat()
            }
            if (frame % progressStep == 0) onProgress(0.80f * frame / frameCount)
        }

        // --- 2. running-max whitening (window includes the current frame) ---
        val band = FloatArray(frameCount * N_BANDS)
        val deque = IntArray(frameCount)
        for (index in 0 until N_BANDS) {
            var head = 0
            var tail = 0
            for (frame in 0 until frameCount) {
                val value = bandRaw[frame * N_BANDS + index]
                while (tail > head && bandRaw[deque[tail - 1] * N_BANDS + index] <= value) {
                    tail--
                }
                deque[tail++] = frame
                val lowest = frame - WHITEN_WINDOW
                while (deque[head] < lowest) head++
                val runningMax = bandRaw[deque[head] * N_BANDS + index]
                band[frame * N_BANDS + index] = value / (runningMax + 1e-4f)
            }
        }
        onProgress(0.85f)

        // --- 3. half-wave rectified spectral flux ---
        val novelty = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            val base = frame * N_BANDS
            val previous = if (frame == 0) base else base - N_BANDS
            var sum = 0f
            for (index in 0 until N_BANDS) {
                val delta = band[base + index] - band[previous + index]
                if (delta > 0f) sum += delta
            }
            novelty[frame] = sum
        }

        // --- 4. peak picking: local max + median/MAD threshold ---
        val p95 = percentile(novelty, 0.95)
        val normalization = p95 + 1e-9f
        val novn = FloatArray(frameCount) { novelty[it] / normalization }

        val localRadius = max(1, (PEAK_LOCAL_MS / hopMs).toInt())
        val contextRadius = max(1, (PEAK_CONTEXT_MS / hopMs).toInt())
        val context = FloatArray(2 * contextRadius + 1)
        val deviations = FloatArray(2 * contextRadius + 1)
        val onsetFrames = IntArray(frameCount)
        val onsetStrength = FloatArray(frameCount)
        var onsetCount = 0

        for (frame in 0 until frameCount) {
            val localLow = max(0, frame - localRadius)
            val localHigh = min(frameCount - 1, frame + localRadius)
            var localMax = Float.NEGATIVE_INFINITY
            for (index in localLow..localHigh) {
                if (novn[index] > localMax) localMax = novn[index]
            }
            if (novn[frame] < localMax - 1e-12f) continue

            val contextLow = max(0, frame - contextRadius)
            val contextHigh = min(frameCount - 1, frame + contextRadius)
            val size = contextHigh - contextLow + 1
            novn.copyInto(context, 0, contextLow, contextHigh + 1)
            Arrays.sort(context, 0, size)
            val median = medianOfSorted(context, size)
            for (index in 0 until size) {
                deviations[index] = abs(novn[contextLow + index] - median)
            }
            Arrays.sort(deviations, 0, size)
            val mad = medianOfSorted(deviations, size) + 1e-6f
            if (novn[frame] > median + PEAK_MAD_K * mad) {
                onsetFrames[onsetCount] = frame
                onsetStrength[onsetCount] = novn[frame]
                onsetCount++
            }
        }
        onProgress(0.90f)

        if (onsetCount == 0) {
            throw RhythmGenerationException(
                "No rhythm events detected in this song; try another track or set the BPM manually",
            )
        }

        // Dominant band per onset, resolved once here so the render stage can drop the band
        // matrix. Identical to reading band[frame * N_BANDS + i] at render time.
        val onsetBands = IntArray(onsetCount)
        for (index in 0 until onsetCount) {
            val frame = onsetFrames[index]
            var dominantBand = 0
            var bandPeak = Float.NEGATIVE_INFINITY
            for (candidate in 0 until N_BANDS) {
                val value = band[frame * N_BANDS + candidate]
                if (value > bandPeak) {
                    bandPeak = value
                    dominantBand = candidate
                }
            }
            onsetBands[index] = dominantBand
        }

        // --- 5. tempo: autocorrelation + octave arbitration ---
        val detectedBpm = estimateBpm(novn, fps, frameCount)
        val octaveCorrection: Double
        val usedBpm: Double
        if (params.bpmOverride != null && params.bpmOverride > 0.0) {
            usedBpm = params.bpmOverride
            octaveCorrection = 1.0
        } else {
            if (!detectedBpm.isFinite() || detectedBpm <= 0.0) {
                throw RhythmGenerationException("Unable to detect the tempo; set the BPM manually")
            }
            octaveCorrection = chooseOctave(novn, frameCount, detectedBpm, fps)
            // The autocorrelation peak is broad: its parabolic fit is only good to ~0.3%, and
            // 0.3% slips a whole beat over ~100s, so the grid drifts in and out of sync with
            // the music ("sometimes right, sometimes fast"). The beat-frequency phase
            // integrates over the whole song, so refine the period against it.
            usedBpm = refineTempo(novn, frameCount, fps, detectedBpm * octaveCorrection)
        }
        onProgress(0.93f)

        val beatMs = 60_000.0 / usedBpm
        val periodFrames = fps * 60.0 / usedBpm
        val phasePeriod = max(1, Math.round(periodFrames).toInt())
        val comb = DoubleArray(phasePeriod)
        for (frame in 0 until frameCount) {
            comb[frame % phasePeriod] += novn[frame].toDouble()
        }
        var phaseFrame = 0
        for (index in 1 until phasePeriod) {
            if (comb[index] > comb[phaseFrame]) phaseFrame = index
        }

        // --- 6. global grid offset from the strongest onsets ---
        val strongest = (0 until onsetCount).sortedByDescending { onsetStrength[it] }
        val offsetSamples = max(1, onsetCount / 10)
        val offsets = DoubleArray(offsetSamples)
        for (index in 0 until offsetSamples) {
            val time = frameToMs(onsetFrames[strongest[index]], sampleRate)
            val shifted = time - phaseFrame * hopMs
            offsets[index] = ((shifted % beatMs) + beatMs) % beatMs
        }
        offsets.sort()
        val beatOffsetMs = median(offsets) + params.extraOffsetMs

        // --- 7. optional constraints (off by default; they measurably lower quality) ---
        val keep = BooleanArray(onsetCount) { true }
        params.minRmsPercentile?.let { cutoff ->
            val (rmsTimes, rmsValues) = rmsEnvelope(pcmMono, sampleRate, RMS_WINDOW_MS)
            val threshold = percentile(rmsValues, cutoff / 100.0)
            for (index in 0 until onsetCount) {
                val time = frameToMs(onsetFrames[index], sampleRate)
                if (interpolate(time, rmsTimes, rmsValues) < threshold) keep[index] = false
            }
        }
        params.maxLocalNps?.let { limit ->
            val keptTimes = ArrayList<Double>()
            for (index in strongest) {
                if (!keep[index]) continue
                val time = frameToMs(onsetFrames[index], sampleRate)
                var neighbours = 0
                for (kept in keptTimes) {
                    if (abs(kept - time) <= 500.0) neighbours++
                }
                if (neighbours >= limit) {
                    keep[index] = false
                    continue
                }
                keptTimes.add(time)
            }
        }

        val keptCount = keep.count { it }
        if (keptCount == 0) {
            throw RhythmGenerationException(
                "No playable notes were produced; try another song or set the BPM manually",
            )
        }
        val keptTimes = DoubleArray(keptCount)
        val keptStrength = FloatArray(keptCount)
        val keptBands = IntArray(keptCount)
        var cursor = 0
        for (index in 0 until onsetCount) {
            if (!keep[index]) continue
            keptTimes[cursor] = frameToMs(onsetFrames[index], sampleRate)
            keptStrength[cursor] = onsetStrength[index]
            keptBands[cursor] = onsetBands[index]
            cursor++
        }
        onProgress(0.97f)

        return DspAnalysis(
            onsetTimesMs = keptTimes,
            onsetStrength = keptStrength,
            onsetBands = keptBands,
            beatMs = beatMs,
            beatOffsetMs = beatOffsetMs,
            durationMs = totalMs,
            detectedBpm = detectedBpm,
            usedBpm = usedBpm,
            octaveCorrection = octaveCorrection,
            peakCount = onsetCount,
            startedAtMs = startedAt,
        )
    }

    // --- stage 8-9: the only difficulty-dependent half --------------------------------------

    /** Every detected onset as its own note: the densest chart this audio can produce. */
    fun renderMaster(
        analysis: DspAnalysis,
        laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
    ): RhythmChart {
        val notes = ArrayList<RhythmNote>(analysis.onsetTimesMs.size)
        var lastLane = -1
        for (index in analysis.onsetTimesMs.indices) {
            val timeMs = Math.round(analysis.onsetTimesMs[index]).toInt()
            if (timeMs < 0 || timeMs > analysis.durationMs) continue
            val band = analysis.onsetBands[index]
            val lane = avoidRepeatedLane(laneStrategy.laneFor(band), lastLane, band)
            notes += RhythmNote(
                id = notes.size,
                timeMs = timeMs,
                lane = lane,
                strength = analysis.onsetStrength[index],
                band = band,
            )
            lastLane = lane
        }
        if (notes.isEmpty()) {
            throw RhythmGenerationException(
                "No playable notes were produced; try another song or set the BPM manually",
            )
        }
        return RhythmChart(
            source = RhythmChart.SOURCE_DSP,
            difficultyName = "DSP Raw",
            timing = listOf(
                RhythmTimingPoint(Math.round(analysis.beatOffsetMs).toInt(), analysis.beatMs),
            ),
            notes = notes,
            durationMs = analysis.durationMs,
        )
    }

    /**
     * Grid-snapped difficulty: one note per cell, keep the strongest candidate.
     *
     * [maxChord] lets a cell carry up to that many notes in distinct lanes. The detector runs on
     * one summed onset function, so instruments that hit together become a single peak; the
     * second and third note come from the other onsets sharing the cell. Must stay identical to
     * [RhythmChartDeriver.derive] — the golden test asserts both paths agree note for note.
     */
    fun render(
        analysis: DspAnalysis,
        difficulty: DspDifficulty,
        laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
        maxChord: Int = 1,
    ): RhythmChart {
        val grid = analysis.beatMs / difficulty.gridDiv
        // The chart only stores an integer beat offset, and a derived difficulty can only see
        // that integer. Using the raw fractional offset here would shift cell times by up to
        // 0.5 ms and could round a note onto the neighbouring millisecond, so both paths use
        // the value that actually lands in the chart.
        val offset = Math.round(analysis.beatOffsetMs).toDouble()
        val chord = maxChord.coerceIn(1, RhythmChartDeriver.MAX_CHORD_NOTES)
        val cellTimes = HashMap<Long, Double>(analysis.onsetTimesMs.size * 2)
        val cellOnsets = HashMap<Long, MutableList<Int>>(analysis.onsetTimesMs.size * 2)

        for (index in analysis.onsetTimesMs.indices) {
            // Round to whole milliseconds before snapping. The master chart stores integer
            // times, and a difficulty derived from it must land in the same cells; without
            // this, an onset within 0.5 ms of a cell boundary can flip cells between the two
            // paths and change which note wins.
            val time = Math.round(analysis.onsetTimesMs[index]).toDouble()
            // Drop out-of-range onsets *before* grouping, exactly like the master does.
            // Filtering after "strongest wins a cell" would let a note past the end of the
            // song win a cell that a valid note also occupies, and then discard the whole cell.
            val onsetMs = Math.round(time).toInt()
            if (onsetMs < 0 || onsetMs > analysis.durationMs) continue
            val snapped = offset + Math.round((time - offset) / grid) * grid
            val quantized = if (abs(time - snapped) <= grid * 0.5) snapped else time
            val key = Math.round(quantized * 10.0)
            cellTimes[key] = quantized
            cellOnsets.getOrPut(key) { ArrayList(2) } += index
        }

        val keys = cellTimes.keys.sorted()
        val notes = ArrayList<RhythmNote>(keys.size)
        var lastLane = -1
        for (key in keys) {
            val timeMs = Math.round(cellTimes.getValue(key)).toInt()
            if (timeMs < 0 || timeMs > analysis.durationMs) continue

            // Strongest first, ties to the earlier onset — same order the deriver uses.
            val candidates = cellOnsets.getValue(key).sortedWith(
                compareByDescending<Int> { analysis.onsetStrength[it] }.thenBy { it }
            )
            val primary = candidates.first()
            val primaryBand = analysis.onsetBands[primary]
            var lane = avoidRepeatedLane(
                laneStrategy.laneFor(primaryBand),
                lastLane,
                primaryBand,
            )

            val previous = notes.lastOrNull()
            if (previous != null && previous.timeMs == timeMs && previous.lane == lane) {
                lane = (lane + 1) % LANE_COUNT
            }
            notes += RhythmNote(
                id = notes.size,
                timeMs = timeMs,
                lane = lane,
                strength = analysis.onsetStrength[primary],
                band = primaryBand,
            )
            lastLane = lane

            if (chord == 1 || candidates.size == 1) continue
            val used = HashSet<Int>(chord)
            used += lane
            val extras = ArrayList<Int>(chord - 1)
            for (index in 1 until candidates.size) {
                if (extras.size >= chord - 1) break
                val candidate = candidates[index]
                if (analysis.onsetStrength[candidate] <
                    analysis.onsetStrength[primary] * RhythmChartDeriver.CHORD_MIN_STRENGTH_RATIO
                ) {
                    break
                }
                // Same window the deriver uses; see RhythmChartDeriver.CHORD_WINDOW_MS.
                val gap = abs(
                    Math.round(analysis.onsetTimesMs[candidate]) -
                        Math.round(analysis.onsetTimesMs[primary])
                )
                if (gap > RhythmChartDeriver.CHORD_WINDOW_MS) continue
                if (!used.add(laneStrategy.laneFor(analysis.onsetBands[candidate]))) continue
                extras += candidate
            }
            extras.sortBy { laneStrategy.laneFor(analysis.onsetBands[it]) }
            for (extra in extras) {
                notes += RhythmNote(
                    id = notes.size,
                    timeMs = timeMs,
                    lane = laneStrategy.laneFor(analysis.onsetBands[extra]),
                    strength = analysis.onsetStrength[extra],
                    band = analysis.onsetBands[extra],
                )
            }
        }

        if (notes.isEmpty()) {
            throw RhythmGenerationException(
                "No playable notes were produced; try another song or set the BPM manually",
            )
        }

        return RhythmChart(
            source = RhythmChart.SOURCE_DSP,
            difficultyName = "DSP ${difficulty.label}",
            timing = listOf(RhythmTimingPoint(Math.round(analysis.beatOffsetMs).toInt(), analysis.beatMs)),
            notes = notes,
            durationMs = analysis.durationMs,
        )
    }

    /** Single-difficulty convenience entry point; equivalent to analyze + render. */
    fun generate(
        pcmMono: FloatArray,
        sampleRate: Int,
        durationMs: Int,
        params: RhythmChartGenParams = RhythmChartGenParams(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): GeneratedChart {
        val analysis = analyze(pcmMono, sampleRate, durationMs, params.analysis, onProgress, isCancelled)
        val chart = render(analysis, params.difficulty, params.laneStrategy)
        onProgress(1f)
        return GeneratedChart(chart = chart, diagnostics = analysis.diagnostics(chart.noteCount()))
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Log-spaced band edges expressed as FFT bin indices (not Hz) so the bands match the
     * reference implementation exactly: `[1, 2, 6, 13, 32, 76, 181, 431, 1024]` at 2048.
     * Re-indexing by frequency would silently change every downstream number.
     */
    private fun bandEdges(): IntArray {
        val highestBin = N_FFT / 2
        val edges = IntArray(N_BANDS + 1)
        val logLow = ln(1.0)
        val logHigh = ln(highestBin.toDouble())
        for (index in 0..N_BANDS) {
            val value = exp(logLow + (logHigh - logLow) * index / N_BANDS)
            edges[index] = Math.round(value).toInt()
        }
        return edges.distinct().toIntArray()
    }

    private fun frameToMs(frame: Int, sampleRate: Int): Double =
        (frame.toDouble() * HOP + N_FFT - HOP / 2.0) * 1000.0 / sampleRate

    /**
     * Autocorrelation over a narrow lag window, then a harmonic search that uses longer
     * lags for better frequency resolution. Returns NaN when the novelty curve is flat.
     */
    private fun estimateBpm(novn: FloatArray, fps: Double, frameCount: Int): Double {
        var mean = 0.0
        for (value in novn) mean += value
        mean /= frameCount
        val centered = DoubleArray(frameCount) { novn[it] - mean }
        var energy = 0.0
        for (value in centered) energy += value * value
        if (energy <= 0.0) return Double.NaN

        val lagMin = max(1, (fps * 60.0 / BPM_MAX).toInt())
        val lagMax = (fps * 60.0 / BPM_MIN).toInt()
        val acLimit = min(frameCount - 2, lagMax * 4 + 2)
        if (acLimit <= lagMin) return Double.NaN

        val ac = DoubleArray(acLimit + 1)
        for (lag in max(1, lagMin - 1)..acLimit) {
            var sum = 0.0
            val limit = frameCount - lag
            var index = 0
            while (index < limit) {
                sum += centered[index] * centered[index + lag]
                index++
            }
            ac[lag] = sum / energy
        }

        var bestLag = lagMin
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in lagMin..min(lagMax, acLimit)) {
            val ratio = log2((fps * 60.0) / lag / BPM_PRIOR) / BPM_PRIOR_SIGMA
            val prior = exp(-0.5 * ratio * ratio)
            val score = ac[lag] * prior
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        var best = bestLag
        var bestValue = Double.NEGATIVE_INFINITY
        for (multiple in 1..4) {
            val center = bestLag * multiple
            if (center + 1 >= frameCount || center + 1 > acLimit) break
            val from = max(1, center - 2)
            val to = min(acLimit, center + 2)
            var localBest = from
            var localValue = Double.NEGATIVE_INFINITY
            for (index in from..to) {
                if (ac[index] > localValue) {
                    localValue = ac[index]
                    localBest = index
                }
            }
            if (ac[localBest] > bestValue) {
                best = localBest
                bestValue = ac[localBest]
            }
        }

        val safeBest = best.coerceIn(1, acLimit - 1)
        val y0 = ac[safeBest - 1]
        val y1 = ac[safeBest]
        val y2 = ac[safeBest + 1]
        val denominator = y0 - 2 * y1 + y2
        val delta = if (abs(denominator) > 1e-12) 0.5 * (y0 - y2) / denominator else 0.0
        val multiple = max(1, Math.round(safeBest.toDouble() / bestLag).toInt())
        val periodFrames = (safeBest + delta) / multiple
        if (periodFrames <= 0.0) return Double.NaN
        return 60.0 * fps / periodFrames
    }

    /**
     * Phase-coherence refinement of the autocorrelation period.
     *
     * [estimateBpm]'s peak is a few frames wide, so its parabolic fit lands within ~0.3% of the
     * true period. That is small per beat but not per song: 0.3% is a whole beat of slip every
     * ~100 s, so the chart drifts in and out of sync with the music instead of staying locked.
     * The magnitude of the whole-song complex demodulation at the beat frequency has a much
     * sharper maximum (width ~1/T, a fraction of a BPM over a multi-minute song), so scanning
     * ±1% around the coarse estimate and interpolating the peak pins the period far tighter.
     */
    private fun refineTempo(
        novn: FloatArray,
        frameCount: Int,
        fps: Double,
        coarseBpm: Double,
    ): Double {
        if (!coarseBpm.isFinite() || coarseBpm <= 0.0 || frameCount < 16) return coarseBpm
        val window = DoubleArray(frameCount) { index ->
            val hann = 0.5 - 0.5 * cos(2.0 * PI * index / (frameCount - 1))
            hann * novn[index]
        }
        val searchFraction = 0.01
        val halfSteps = 40
        val stepBpm = coarseBpm * searchFraction / halfSteps
        var bestBpm = coarseBpm
        var bestMagnitude = Double.NEGATIVE_INFINITY
        for (index in -halfSteps..halfSteps) {
            val bpm = coarseBpm * (1.0 + searchFraction * index / halfSteps)
            val magnitude = demodulateMagnitude(window, fps, bpm)
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestBpm = bpm
            }
        }
        val y0 = demodulateMagnitude(window, fps, bestBpm - stepBpm)
        val y1 = demodulateMagnitude(window, fps, bestBpm)
        val y2 = demodulateMagnitude(window, fps, bestBpm + stepBpm)
        val denominator = y0 - 2 * y1 + y2
        if (abs(denominator) < 1e-12) return bestBpm
        val delta = (0.5 * (y0 - y2) / denominator).coerceIn(-1.0, 1.0)
        return bestBpm + delta * stepBpm
    }

    /** |Σ weighted[frame] · exp(-i·ω·frame/fps)| for a candidate beat frequency. */
    private fun demodulateMagnitude(weighted: DoubleArray, fps: Double, bpm: Double): Double {
        val omega = 2.0 * PI * (bpm / 60.0) / fps
        val cosStep = cos(omega)
        val sinStep = sin(omega)
        var cosValue = 1.0
        var sinValue = 0.0
        var re = 0.0
        var im = 0.0
        for (index in weighted.indices) {
            val value = weighted[index]
            re += value * cosValue
            im -= value * sinValue
            val nextCos = cosValue * cosStep - sinValue * sinStep
            sinValue = cosValue * sinStep + sinValue * cosStep
            cosValue = nextCos
        }
        return re * re + im * im
    }

    /**
     * The reference song's raw estimate lands on 2.008x the real tempo; an uncorrected
     * octave error doubles every difficulty and drifts 57 s by the end of the track.
     *
     * Each candidate is scored by the **mean** novelty on its best beat phase. Summing
     * would reward denser grids, while the mean drops for a double-time grid because half
     * of its beats land on empty off-beats.
     */
    private fun chooseOctave(novn: FloatArray, frameCount: Int, bpm: Double, fps: Double): Double {
        val candidates = doubleArrayOf(0.5, 1.0, 2.0)
        var bestGain = 1.0
        var bestScore = Double.NEGATIVE_INFINITY
        var runnerUp = Double.NEGATIVE_INFINITY

        for (gain in candidates) {
            val candidate = bpm * gain
            if (candidate < BPM_MIN || candidate > BPM_MAX) continue
            val score = meanBeatStrength(novn, frameCount, candidate, fps)
            if (score > bestScore) {
                runnerUp = bestScore
                bestScore = score
                bestGain = gain
            } else if (score > runnerUp) {
                runnerUp = score
            }
        }

        if (bestGain == 1.0 || runnerUp <= 0.0) return 1.0
        val margin = (bestScore - runnerUp) / runnerUp
        return if (margin >= OCTAVE_MIN_GAIN) bestGain else 1.0
    }

    private fun meanBeatStrength(
        novn: FloatArray,
        frameCount: Int,
        bpm: Double,
        fps: Double,
    ): Double {
        val period = max(1, Math.round(fps * 60.0 / bpm).toInt())
        if (period <= 1 || period > frameCount) return 0.0
        var best = 0.0
        for (phase in 0 until period) {
            var sum = 0.0
            var count = 0
            var index = phase
            while (index < frameCount) {
                sum += novn[index]
                count++
                index += period
            }
            if (count > 0) {
                val average = sum / count
                if (average > best) best = average
            }
        }
        return best
    }

    private fun rmsEnvelope(
        pcm: FloatArray,
        sampleRate: Int,
        windowMs: Double,
    ): Pair<DoubleArray, DoubleArray> {
        val window = max(1, (sampleRate * windowMs / 1000.0).toInt())
        val count = pcm.size / window
        if (count <= 0) return Pair(DoubleArray(0), DoubleArray(0))
        val times = DoubleArray(count)
        val values = DoubleArray(count)
        for (index in 0 until count) {
            var sum = 0.0
            val start = index * window
            for (offset in 0 until window) {
                val sample = pcm[start + offset].toDouble()
                sum += sample * sample
            }
            times[index] = (start + window / 2.0) * 1000.0 / sampleRate
            values[index] = sqrt(sum / window)
        }
        return Pair(times, values)
    }

    private fun interpolate(time: Double, times: DoubleArray, values: DoubleArray): Double {
        if (times.isEmpty()) return 0.0
        if (time <= times.first()) return values.first()
        if (time >= times.last()) return values.last()
        var low = 0
        var high = times.size - 1
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (times[middle] <= time) low = middle else high = middle
        }
        val span = times[high] - times[low]
        if (span <= 0.0) return values[low]
        val fraction = (time - times[low]) / span
        return values[low] + (values[high] - values[low]) * fraction
    }

    /** numpy's default linear interpolation, kept identical so fixtures stay comparable. */
    private fun percentile(values: FloatArray, fraction: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf()
        Arrays.sort(sorted)
        return percentileOfSorted(sorted, fraction)
    }

    private fun percentile(values: DoubleArray, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf()
        Arrays.sort(sorted)
        return percentileOfSorted(sorted, fraction)
    }

    private fun percentileOfSorted(sorted: FloatArray, fraction: Double): Float {
        val position = (sorted.size - 1) * fraction.coerceIn(0.0, 1.0)
        val low = position.toInt()
        val high = min(low + 1, sorted.size - 1)
        val weight = position - low
        return (sorted[low] * (1.0 - weight) + sorted[high] * weight).toFloat()
    }

    private fun percentileOfSorted(sorted: DoubleArray, fraction: Double): Double {
        val position = (sorted.size - 1) * fraction.coerceIn(0.0, 1.0)
        val low = position.toInt()
        val high = min(low + 1, sorted.size - 1)
        val weight = position - low
        return sorted[low] * (1.0 - weight) + sorted[high] * weight
    }

    private fun medianOfSorted(sorted: FloatArray, size: Int): Float =
        if (size % 2 == 1) {
            sorted[size / 2]
        } else {
            (sorted[size / 2 - 1] + sorted[size / 2]) * 0.5f
        }

    private fun median(sorted: DoubleArray): Double =
        if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5
        }
}
