/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (tools/rhythm-lab/docs/2026-11-03-1354-dual-derive-modes-and-segmented-beat-grid.md §4).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the one global beat grid into a piecewise one that follows tempo drift.
 *
 * The grid-aligned derivation path snaps notes onto beats, so a global BPM that is off by a
 * fraction of a percent slowly walks out of sync (`docs/rhythm-dsp-chart-generation-plan.md`
 * §11.2). Every 8 beats this refiner re-anchors the grid: the next anchor is predicted from the
 * previous anchor plus 8 local beats, then moved by at most ±40 ms to minimise the
 * strength-weighted squared distance between the segment's onsets and the interpolated beat
 * lines. Anchors are monotonic, the local beat may only change by ±3% per segment, and the beat
 * sequence is median-smoothed so a weak segment can not bend the grid.
 *
 * The result is a [RhythmTimingPoint] list: each point marks an anchor beat and carries the beat
 * length up to the next anchor. It needs no PCM, so an existing master can be re-derived without
 * decoding the song again. When no segment improves on the global grid, the input point is
 * returned untouched.
 */
internal object RhythmTimingRefiner {

    /** Minimum data for a decision: below this the global grid is as good as any guess. */
    private const val MIN_SEGMENT_ONSETS = 4

    private const val BEATS_PER_SEGMENT = 8

    /** How far an anchor may move from the local prediction, in milliseconds. */
    private const val SEARCH_RADIUS_MS = 40

    /**
     * Reject a segment unless it cuts the residual by this fraction. A perfect global grid has a
     * zero residual, and noise on a handful of onsets should not be "corrected" into a wobble.
     */
    private const val MIN_RESIDUAL_GAIN = 0.15

    /** A segment may not change the local beat by more than this, relative to the previous one. */
    private const val MAX_TEMPO_STEP = 0.03

    /** Smoothing needs enough accepted neighbours to be meaningful. */
    private const val MIN_SEGMENTS_TO_SMOOTH = 3

    /** One analysed onset: the refiner only cares about time and weight. */
    class Onset(val timeMs: Int, val strength: Float)

    fun refine(
        onsets: List<Onset>,
        global: RhythmTimingPoint,
        durationMs: Int,
    ): List<RhythmTimingPoint> {
        val beatMs = global.beatMs
        if (!beatMs.isFinite() || beatMs <= 0.0) return listOf(global)
        if (durationMs <= global.timeMs) return listOf(global)

        val sorted = onsets
            .filter { it.timeMs in 0..durationMs }
            .sortedBy { it.timeMs }
        if (sorted.size < MIN_SEGMENT_ONSETS) return listOf(global)

        val segmentMs = beatMs * BEATS_PER_SEGMENT
        val segmentCount = ((durationMs - global.timeMs) / segmentMs).toInt()
        if (segmentCount <= 0) return listOf(global)

        val hasStrength = sorted.any { it.strength > 0f }
        val anchors = IntArray(segmentCount + 1)
        val beatLengths = DoubleArray(segmentCount) { beatMs }
        anchors[0] = global.timeMs
        var accepted = 0

        for (segment in 0 until segmentCount) {
            val start = anchors[segment]
            val localBeat = beatLengths[segment]
            val expected = start + (localBeat * BEATS_PER_SEGMENT).roundToInt()
            val segmentOnsets = sorted.filter {
                it.timeMs >= start && it.timeMs < start + (localBeat * BEATS_PER_SEGMENT).roundToInt()
            }
            if (segmentOnsets.size < MIN_SEGMENT_ONSETS) {
                anchors[segment + 1] = expected
                beatLengths[segment] = localBeat
                if (segment + 1 < segmentCount) beatLengths[segment + 1] = localBeat
                continue
            }

            val baseScore = residual(segmentOnsets, start, expected, hasStrength)
            var bestAnchor = expected
            var bestScore = baseScore
            for (delta in -SEARCH_RADIUS_MS..SEARCH_RADIUS_MS) {
                val candidate = expected + delta
                val candidateBeat = (candidate - start) / BEATS_PER_SEGMENT.toDouble()
                // Reject tempo jumps outright; the anchor is only a small correction.
                if (abs(candidateBeat - localBeat) > localBeat * MAX_TEMPO_STEP) continue
                val score = residual(segmentOnsets, start, candidate, hasStrength)
                if (score < bestScore) {
                    bestScore = score
                    bestAnchor = candidate
                }
            }

            val improved = baseScore > 0.0 &&
                bestScore < baseScore * (1.0 - MIN_RESIDUAL_GAIN)
            if (!improved) {
                anchors[segment + 1] = expected
                beatLengths[segment] = localBeat
                if (segment + 1 < segmentCount) beatLengths[segment + 1] = localBeat
                continue
            }
            anchors[segment + 1] = bestAnchor
            beatLengths[segment] = (bestAnchor - start) / BEATS_PER_SEGMENT.toDouble()
            if (segment + 1 < segmentCount) beatLengths[segment + 1] = beatLengths[segment]
            accepted++
        }

        if (accepted == 0) return listOf(global)

        val smoothed = if (accepted >= MIN_SEGMENTS_TO_SMOOTH) {
            DoubleArray(segmentCount) { index ->
                when (index) {
                    0, segmentCount - 1 -> beatLengths[index]
                    else -> median(
                        beatLengths[index - 1],
                        beatLengths[index],
                        beatLengths[index + 1],
                    )
                }
            }
        } else {
            beatLengths
        }

        val points = ArrayList<RhythmTimingPoint>(segmentCount)
        var anchor = global.timeMs
        for (segment in 0 until segmentCount) {
            points += RhythmTimingPoint(anchor, smoothed[segment])
            anchor += (smoothed[segment] * BEATS_PER_SEGMENT).roundToInt()
        }
        return points
    }

    /**
     * Weighted squared distance from each onset to the nearest beat line of the grid that starts
     * at [start] and ends at [anchor]. Interpolating between the two anchors is what makes the
     * search judge a tempo, not just a phase shift.
     */
    private fun residual(
        onsets: List<Onset>,
        start: Int,
        anchor: Int,
        hasStrength: Boolean,
    ): Double {
        val localBeat = (anchor - start) / BEATS_PER_SEGMENT.toDouble()
        if (localBeat <= 0.0) return Double.MAX_VALUE
        var score = 0.0
        for (onset in onsets) {
            val beats = (onset.timeMs - start) / localBeat
            val nearest = Math.round(beats)
            val offsetMs = onset.timeMs - (start + nearest * localBeat)
            // The 0.01 floor keeps silence from acting as a perfect fit.
            val weight = if (hasStrength) onset.strength.toDouble().coerceAtLeast(0.01) else 1.0
            score += weight * offsetMs * offsetMs
        }
        return score
    }

    private fun median(a: Double, b: Double, c: Double): Double =
        maxOf(minOf(a, b), minOf(maxOf(a, b), c))
}
