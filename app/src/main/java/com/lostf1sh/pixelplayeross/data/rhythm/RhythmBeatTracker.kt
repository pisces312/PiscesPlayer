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

    /** Intro lock-in: how many consecutive intervals must match the whole-song tempo. */
    private const val LOCK_IN_INTERVALS = 8
    private const val LOCK_IN_TOLERANCE = 0.03

    /** How far the fitted median beat may sit from the requested rate before it is rejected. */
    private const val MAX_RATE_DEVIATION = 0.05

    class Result(
        val timing: List<RhythmTimingPoint>,
        val trackedBeats: Int,
        val keptBeats: Int,
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

        val fitted = piecewiseTiming(kept, durationMs).map {
            RhythmTimingPoint(it.timeMs + extraOffsetMs, it.beatMs)
        }
        if (fitted.size >= 2) {
            val medianBeatMs = median(fitted.map { it.beatMs }.sorted().toDoubleArray())
            if (abs(medianBeatMs - nominalBeatMs) <= MAX_RATE_DEVIATION * nominalBeatMs) {
                return Result(fitted, tracked.size, kept.size)
            }
        }

        // Fallback: the requested rate, phased on the first tracked beat.
        val phase = if (kept.isEmpty()) 0.0 else kept[0] % nominalBeatMs
        return Result(
            timing = listOf(RhythmTimingPoint(Math.round(phase).toInt() + extraOffsetMs, nominalBeatMs)),
            trackedBeats = tracked.size,
            keptBeats = kept.size,
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
     * One timing anchor per tracked beat, from a trimmed local-linear fit in beat-index space
     * (`time = slope * index + intercept`). Tricube weights centred on the anchor keep a
     * distant beat from dragging the local tempo, and trimming residual outliers keeps a single
     * misplaced beat from bending the grid. Each anchor's `beatMs` is half the fitted slope
     * (chart beat = half a music beat).
     */
    internal fun piecewiseTiming(
        beatTimesMs: DoubleArray,
        durationMs: Int,
    ): List<RhythmTimingPoint> {
        if (beatTimesMs.size < 2) return emptyList()
        // Intro fix: the first tracked beats can lock onto a wrong rate when the intro's
        // evidence is too thin. Beats before the lock-in point are rebuilt from the lock-in
        // anchor at the whole-song median period instead of trusting their own fits. The
        // whole-song rate beat the lock-in fit's local rate in
        // tools/rhythm-lab/experiments/intro_fix.py, and a clean intro finds lock = 0 and is
        // untouched.
        val lock = lockInIndex(beatTimesMs)
        val introPeriod = medianPeriod(beatTimesMs)
        val introOrigin = if (lock >= 1) fitAt(beatTimesMs, lock)?.intercept else null
        val timing = ArrayList<RhythmTimingPoint>(beatTimesMs.size)
        var lastAnchorMs = Double.NEGATIVE_INFINITY
        for (anchor in beatTimesMs.indices) {
            val anchorMs: Double
            val beatMs: Double
            if (introOrigin != null && anchor < lock) {
                anchorMs = introOrigin + (anchor - lock) * introPeriod
                beatMs = introPeriod / 2.0
            } else {
                val fit = fitAt(beatTimesMs, anchor) ?: continue
                if (!fit.slope.isFinite() || fit.slope <= 0.0) continue
                anchorMs = fit.intercept
                beatMs = fit.slope / 2.0
            }
            if (anchorMs <= 0.0 || anchorMs >= durationMs) continue
            if (anchorMs <= lastAnchorMs + 1.0) continue
            lastAnchorMs = anchorMs
            timing += RhythmTimingPoint(Math.round(anchorMs).toInt(), beatMs)
        }
        return timing
    }

    /**
     * First beat whose next [LOCK_IN_INTERVALS] intervals all match the whole-song median
     * period within [LOCK_IN_TOLERANCE]; 0 means the intro already tracks the song's tempo.
     * The whole-song median is robust to a corrupted intro of a few beats.
     */
    private fun lockInIndex(beatTimesMs: DoubleArray): Int {
        if (beatTimesMs.size < LOCK_IN_INTERVALS + 1) return 0
        val intervals = DoubleArray(beatTimesMs.size - 1) { beatTimesMs[it + 1] - beatTimesMs[it] }
        val period = medianPeriod(beatTimesMs)
        if (period <= 0.0) return 0
        for (index in 0..intervals.size - LOCK_IN_INTERVALS) {
            var run = true
            for (offset in 0 until LOCK_IN_INTERVALS) {
                if (abs(intervals[index + offset] - period) > LOCK_IN_TOLERANCE * period) {
                    run = false
                    break
                }
            }
            if (run) return index
        }
        return 0
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
