/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS (tools/rhythm-lab/rhythm_lab/tracker.py).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Piecewise beat-grid estimate: the timing the derived grids snap to.
 *
 * Pipeline: 64-band novelty -> dynamic-programming beat tracking at half the chart rate ->
 * drop beats that only sit in a silent tail -> tricube-weighted local linear fit, one timing
 * anchor per tracked beat. The chart's beat is half a music beat (eighth notes against the
 * reference's quarter), so each anchor's `beatMs` is half the fitted music-beat period.
 *
 * The rate guard keeps a wrong lock out of the chart: when the fitted median beat length
 * disagrees with the requested `60000 / chartBpm` by more than [MAX_RATE_DEVIATION], the
 * tracker followed a different lattice than requested (or the request itself is wrong), and a
 * single anchor at the requested rate is safer than the fitted one.
 */
internal object RhythmBeatTracker {

    private const val BAND_COUNT = 64
    private const val DP_TIGHTNESS = 600.0
    private const val REFINE_RADIUS = 2
    private const val TAIL_RMS_FRACTION = 0.25
    private const val FIT_HALF_WINDOW = 10
    private const val FIT_RESIDUAL_MS = 45.0
    private const val FIT_ITERATIONS = 4

    /**
     * Onset evidence within this far of a lattice line votes for that line's phase. Tight on
     * purpose: the beat's kick/snare attacks are sharp and land dead on the line, while the
     * offbeat texture (soft bass, smeared hats) spreads out and loses the squared-strength
     * vote. Measured on Far Away from Home: ±50 ms / squared strength gives the beat phase
     * twice the score of the offbeat phase.
     */
    private const val PHASE_VOTE_WINDOW_MS = 50.0

    /** How much the offbeat phase must outscore the default before the lattice flips. */
    private const val PHASE_VOTE_MARGIN = 1.2

    /** Tracked gaps on each side whose median is the lattice's local spacing. */
    private const val PERIOD_SMOOTH = 15

    /**
     * The local spacing may stray this far from the consensus: wide enough for a real tempo
     * change (a 120 -> 90 BPM step is +33%), the long window being what keeps the walk out.
     */
    private const val PERIOD_LIMIT = 0.35

    /**
     * When the lattice falls this far from the tracked beat, the chain may have found a real
     * tempo change the smoothed period lags behind — but the *onset evidence* has to prefer
     * the chain's position before the lattice follows it. A chain walk onto the offbeat
     * (Far Away from Home) scores worse and is ignored.
     */
    private const val PERIOD_RESNAP_FRACTION = 0.12

    /** How far the fitted median beat may sit from the requested rate before it is rejected. */
    private const val MAX_RATE_DEVIATION = 0.05

    class Result(
        val timing: List<RhythmTimingPoint>,
        val trackedBeats: Int,
        val keptBeats: Int,
        /** The trimmed tracked beats, exposed for diagnostics (the auto-play tool). */
        val tracked: DoubleArray = DoubleArray(0),
    )

    /**
     * @param chartBpm the chart's beat rate (the rate `RhythmChartGenerator` renders a div-1
     *   grid at). Tracking runs at half of it because that is the music-beat rate.
     * @param extraOffsetMs generation-time grid phase shift applied to every anchor.
     */
    fun estimateTiming(
        pcmMono: FloatArray,
        sampleRate: Int,
        chartBpm: Double,
        durationMs: Int,
        extraOffsetMs: Int = 0,
        onsetTimesMs: DoubleArray = DoubleArray(0),
        onsetStrengths: FloatArray = FloatArray(0),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        val novelty = RhythmNovelty.compute(
            pcmMono = pcmMono,
            sampleRate = sampleRate,
            bandCount = BAND_COUNT,
            keepBands = false,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
        val tracked = beatTrack(novelty.values, novelty.fps, chartBpm / 2.0, novelty::frameToMs)
        val kept = trimSilentTail(tracked, pcmMono, sampleRate)
        val nominalBeatMs = 60_000.0 / chartBpm

        val fitted = piecewiseTiming(kept, durationMs, onsetTimesMs, onsetStrengths).map {
            RhythmTimingPoint(it.timeMs + extraOffsetMs, it.beatMs)
        }
        if (fitted.size >= 2) {
            val medianBeatMs = median(fitted.map { it.beatMs }.sorted().toDoubleArray())
            if (abs(medianBeatMs - nominalBeatMs) <= MAX_RATE_DEVIATION * nominalBeatMs) {
                return Result(fitted, tracked.size, kept.size, kept)
            }
        }

        // Fallback: the requested rate, phased on the first tracked beat.
        val phase = if (kept.isEmpty()) 0.0 else kept[0] % nominalBeatMs
        return Result(
            timing = listOf(RhythmTimingPoint(Math.round(phase).toInt() + extraOffsetMs, nominalBeatMs)),
            trackedBeats = tracked.size,
            keptBeats = kept.size,
            tracked = kept,
        )
    }

    /**
     * Dynamic-programming beat tracking (Ellis-style): normalised novelty accumulates along
     * beat chains whose inter-beat interval is penalised for straying from the expected period.
     * Returns refined beat times in milliseconds.
     */
    private fun beatTrack(
        novn: FloatArray,
        fps: Double,
        startBpm: Double,
        frameToMs: (Double) -> Double,
    ): DoubleArray {
        val frameCount = novn.size
        var mean = 0.0
        for (value in novn) mean += value
        mean /= frameCount
        var variance = 0.0
        for (value in novn) {
            val delta = value - mean
            variance += delta * delta
        }
        val std = sqrt(variance / frameCount) + 1e-9
        val local = DoubleArray(frameCount) { novn[it] / std }

        val period = 60.0 * fps / startBpm
        val tauMin = max(1, Math.round(period / 2.0).toInt())
        val tauMax = max(tauMin + 1, Math.round(2.0 * period).toInt())

        val cumulative = local.copyOf()
        val backlink = IntArray(frameCount) { -1 }
        for (frame in tauMin until frameCount) {
            val low = max(0, frame - tauMax)
            val high = frame - tauMin
            var bestScore = 0.0
            var bestCandidate = -1
            for (candidate in low..high) {
                val tau = (frame - candidate).toDouble()
                val penalty = ln(tau / period)
                val score = cumulative[candidate] - DP_TIGHTNESS * penalty * penalty
                if (bestCandidate < 0 || score > bestScore) {
                    bestScore = score
                    bestCandidate = candidate
                }
            }
            if (bestCandidate >= 0 && bestScore > 0.0) {
                cumulative[frame] = local[frame] + bestScore
                backlink[frame] = bestCandidate
            }
        }

        val searchFrom = max(0, frameCount - Math.round(2.0 * period).toInt())
        var frame = searchFrom
        for (index in searchFrom + 1 until frameCount) {
            if (cumulative[index] > cumulative[frame]) frame = index
        }
        val frames = ArrayList<Int>(frameCount / 8)
        while (frame >= 0) {
            frames.add(frame)
            frame = backlink[frame]
        }
        frames.reverse()

        val beats = DoubleArray(frames.size)
        for ((index, base) in frames.withIndex()) {
            var peak = max(0, base - REFINE_RADIUS)
            for (candidate in peak + 1..min(frameCount - 1, base + REFINE_RADIUS)) {
                if (novn[candidate] > novn[peak]) peak = candidate
            }
            var delta = 0.0
            if (peak in 1 until frameCount - 1) {
                val y0 = novn[peak - 1].toDouble()
                val y1 = novn[peak].toDouble()
                val y2 = novn[peak + 1].toDouble()
                val denominator = y0 - 2.0 * y1 + y2
                if (abs(denominator) > 1e-12) {
                    delta = (0.5 * (y0 - y2) / denominator).coerceIn(-0.5, 0.5)
                }
            }
            beats[index] = frameToMs(peak + delta)
        }
        return beats
    }

    /**
     * Drop trailing beats that only sit in reverb/fade-out: a trailing beat is kept while its
     * local RMS reaches [TAIL_RMS_FRACTION] of the song's median 100 ms RMS. Only the tail is
     * examined, so a quiet intro cannot be cut.
     */
    private fun trimSilentTail(
        beatTimesMs: DoubleArray,
        pcm: FloatArray,
        sampleRate: Int,
    ): DoubleArray {
        val frame = (0.1 * sampleRate).toInt()
        if (frame <= 0 || beatTimesMs.isEmpty() || pcm.size < frame) return beatTimesMs
        val count = pcm.size / frame
        val rms = DoubleArray(count)
        for (index in 0 until count) {
            var sum = 0.0
            val start = index * frame
            for (offset in 0 until frame) {
                val sample = pcm[start + offset].toDouble()
                sum += sample * sample
            }
            rms[index] = sqrt(sum / frame)
        }
        val sorted = rms.copyOf()
        Arrays.sort(sorted)
        val medianRms = median(sorted)
        if (medianRms <= 0.0) return beatTimesMs

        var keep = beatTimesMs.size
        while (keep > 1 && localRms(pcm, sampleRate, beatTimesMs[keep - 1]) < TAIL_RMS_FRACTION * medianRms) {
            keep--
        }
        return beatTimesMs.copyOf(keep)
    }

    /** RMS over a 100 ms window centred on [timeMs]. */
    private fun localRms(pcm: FloatArray, sampleRate: Int, timeMs: Double): Double {
        val center = (timeMs / 1000.0 * sampleRate).toInt()
        val half = (0.05 * sampleRate).toInt()
        val low = max(0, center - half)
        val high = min(pcm.size, center + half)
        if (high <= low) return 0.0
        var sum = 0.0
        for (index in low until high) {
            val sample = pcm[index].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / (high - low))
    }

    /**
     * One timing anchor per tracked beat, all on one lattice. Anchors are beat lines, so the
     * grid stays continuous: the boundary beat is both the last cell of one segment and the
     * first cell of the next. The chart beat is half a tracked beat.
     *
     * The lattice is the whole design. The trimmed local-linear fits only describe their own
     * window: one jittery stretch (a single long tracked gap is enough) drags the local slope
     * and the intercepts with it, and the grid walks off the beat — the notes feel uneven
     * first, then land half a beat out ("off-beat") once the walk passes one. Measured on Far
     * Away from Home: a 1459 ms tracked gap at ~120 s stretched the anchors to 1350-1396 ms
     * apart, and the DP chain re-locked onto the offbeat onsets from there on.
     *
     * So: the consensus period (median tracked interval) fixes the spacing — even notes, no
     * drift; the fits only vote on the lattice's origin and the onset evidence arbitrates the
     * two half-a-chart-beat candidates (see [PHASE_VOTE_WINDOW_MS]); every anchor then sits on
     * that one lattice, so a chain slip or a pulled fit can not bend the grid anywhere. The
     * true beat length is constant (validated in tools/rhythm-lab).
     */
    internal fun piecewiseTiming(
        beatTimesMs: DoubleArray,
        durationMs: Int,
        evidenceTimesMs: DoubleArray = DoubleArray(0),
        evidenceStrengths: FloatArray = FloatArray(0),
    ): List<RhythmTimingPoint> {
        if (beatTimesMs.size < 2) return emptyList()
        val consensus = medianPeriod(beatTimesMs)
        if (consensus <= 0.0) return emptyList()
        val chartBeatMs = consensus / 2.0

        // Each fit votes on the lattice origin through its intercept minus the anchor's slot.
        val offsets = ArrayList<Double>(beatTimesMs.size)
        for (anchor in beatTimesMs.indices) {
            val fit = fitAt(beatTimesMs, anchor) ?: continue
            if (!fit.slope.isFinite() || fit.slope <= 0.0) continue
            offsets += fit.intercept - anchor * consensus
        }
        if (offsets.isEmpty()) return emptyList()
        offsets.sort()
        // The votes cluster into at most two camps half a chart beat apart (the beat and the
        // offbeat the DP chain may live on): the quartile centres are the candidates. A plain
        // median would land between the camps on an even split and phase the grid a quarter
        // beat out.
        val quarter = (offsets.size - 1) / 4
        val candidates = doubleArrayOf(offsets[quarter], offsets[offsets.size - 1 - quarter])
        val hasEvidence = evidenceTimesMs.isNotEmpty() &&
            evidenceStrengths.size == evidenceTimesMs.size
        var base = candidates[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val score = if (hasEvidence) {
                phaseScore(candidate, evidenceTimesMs, evidenceStrengths, chartBeatMs)
            } else {
                // No onset evidence (unit tests, synthetic beats): the larger camp wins.
                offsets.count { abs(it - candidate) <= chartBeatMs / 4.0 }.toDouble()
            }
            if (score > bestScore) {
                bestScore = score
                base = candidate
            }
        }

        val timing = ArrayList<RhythmTimingPoint>(beatTimesMs.size)
        val minPeriodMs = consensus * (1.0 - PERIOD_LIMIT)
        val maxPeriodMs = consensus * (1.0 + PERIOD_LIMIT)
        var positionMs = base
        var lastAnchorMs = Double.NEGATIVE_INFINITY
        for (anchor in beatTimesMs.indices) {
            // The local spacing: the median tracked gap over a long window follows a real
            // tempo change (a ritardando is sustained) while a short jittery stretch is
            // outvoted — Far Away from Home's 8-beat excursion dies in the 31-gap window.
            val low = max(0, anchor - PERIOD_SMOOTH)
            val high = min(beatTimesMs.size - 1, anchor + PERIOD_SMOOTH)
            val gaps = DoubleArray(high - low) { beatTimesMs[low + it + 1] - beatTimesMs[low + it] }
            gaps.sort()
            val periodMs = median(gaps).coerceIn(minPeriodMs, maxPeriodMs)

            // The smoothed period lags a tempo step; when the lattice falls away from the
            // chain, the chain may have found the new rate — but the onset evidence has to
            // prefer the chain's position before the lattice follows it there. A chain walk
            // onto the offbeat scores worse and is ignored.
            val driftMs = abs(positionMs - beatTimesMs[anchor])
            if (hasEvidence && driftMs > PERIOD_RESNAP_FRACTION * periodMs) {
                val chainScore = phaseScore(
                    beatTimesMs[anchor], evidenceTimesMs, evidenceStrengths, periodMs / 2.0,
                )
                val latticeScore = phaseScore(
                    positionMs, evidenceTimesMs, evidenceStrengths, periodMs / 2.0,
                )
                if (chainScore > latticeScore) positionMs = beatTimesMs[anchor]
            }

            if (positionMs > 0.0 && positionMs < durationMs && positionMs > lastAnchorMs + 1.0) {
                lastAnchorMs = positionMs
                timing += RhythmTimingPoint(Math.round(positionMs).toInt(), periodMs / 2.0)
            }
            positionMs += periodMs
        }
        return timing
    }

    /** Squared onset strength within [PHASE_VOTE_WINDOW_MS] of a lattice line at [origin]. */
    private fun phaseScore(
        origin: Double,
        evidenceTimesMs: DoubleArray,
        evidenceStrengths: FloatArray,
        chartBeatMs: Double,
    ): Double {
        var score = 0.0
        for (index in evidenceTimesMs.indices) {
            val phase = ((evidenceTimesMs[index] - origin) % chartBeatMs + chartBeatMs) % chartBeatMs
            val distance = min(phase, chartBeatMs - phase)
            if (distance <= PHASE_VOTE_WINDOW_MS) {
                val strength = evidenceStrengths[index].toDouble()
                score += strength * strength
            }
        }
        return score
    }

    private fun medianPeriod(beatTimesMs: DoubleArray): Double {
        if (beatTimesMs.size < 2) return 0.0
        val intervals = DoubleArray(beatTimesMs.size - 1) { beatTimesMs[it + 1] - beatTimesMs[it] }
        Arrays.sort(intervals)
        return median(intervals)
    }

    /** The per-anchor fit: windowing, tricube weights and residual trimming at one anchor. */
    private fun fitAt(beatTimesMs: DoubleArray, anchor: Int): LineFit? {
        val low = max(0, anchor - FIT_HALF_WINDOW)
        val high = min(beatTimesMs.size, anchor + FIT_HALF_WINDOW + 1)
        val count = high - low
        val x = DoubleArray(count) { (low + it - anchor).toDouble() }
        val y = DoubleArray(count) { beatTimesMs[low + it] }
        val weights = DoubleArray(count) {
            val shaped = (1.0 - abs(x[it]) / (FIT_HALF_WINDOW + 1.0)).coerceIn(1e-3, 1.0)
            shaped * shaped * shaped
        }

        var keep = BooleanArray(count) { true }
        for (iteration in 0 until FIT_ITERATIONS) {
            if (keep.count { it } < 2) break
            val fit = weightedLineFit(x, y, weights, keep) ?: break
            val residual = DoubleArray(count) { abs(y[it] - (fit.slope * x[it] + fit.intercept)) }
            val keptResiduals = DoubleArray(keep.count { it })
            var cursor = 0
            for (index in 0 until count) {
                if (keep[index]) keptResiduals[cursor++] = residual[index]
            }
            Arrays.sort(keptResiduals)
            val cutoff = max(FIT_RESIDUAL_MS, 3.0 * median(keptResiduals))
            keep = BooleanArray(count) { residual[it] < cutoff }
        }
        if (keep.count { it } < 2) keep = BooleanArray(count) { true }
        return weightedLineFit(x, y, weights, keep)
    }

    private class LineFit(val slope: Double, val intercept: Double)

    private fun weightedLineFit(
        x: DoubleArray,
        y: DoubleArray,
        weights: DoubleArray,
        keep: BooleanArray,
    ): LineFit? {
        var sumW = 0.0
        var sumWX = 0.0
        var sumWY = 0.0
        var sumWXX = 0.0
        var sumWXY = 0.0
        for (index in x.indices) {
            if (!keep[index]) continue
            // np.polyfit(w=...) minimises (w * r)^2, so the normal-equation weight is w^2.
            val w = weights[index] * weights[index]
            sumW += w
            sumWX += w * x[index]
            sumWY += w * y[index]
            sumWXX += w * x[index] * x[index]
            sumWXY += w * x[index] * y[index]
        }
        val determinant = sumW * sumWXX - sumWX * sumWX
        if (abs(determinant) < 1e-12) return null
        val slope = (sumW * sumWXY - sumWX * sumWY) / determinant
        val intercept = (sumWXX * sumWY - sumWX * sumWXY) / determinant
        return LineFit(slope, intercept)
    }

    private fun median(sorted: DoubleArray): Double =
        if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5
        }
}
