/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/plans/manual-tap-chart-mobile-plan.md §4, §6).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlin.math.roundToInt

/**
 * Manual tap take -> playable chart conversion (pure, JVM-testable).
 *
 * Timing is an identity map: takes are recorded in the same audio-clock domain the judgement
 * chain reads ([RhythmAudioClock.nowMs]), so a hit's time becomes the note's `timeMs` unchanged,
 * plus an optional constant [offsetMs] fine-tune. Lanes are kept as pressed (the key maps to the
 * lane like the PC recorder's LANE_KEYS).
 *
 * Save-time cleanup is deliberately minimal (manual-tap-chart-mobile-plan.md §6):
 * same-lane taps closer than [MERGE_WINDOW_MS] are one debounced double-tap (first is kept),
 * while same-lane pairs closer than [CLOSE_PAIR_MS] are only reported — deleting them would
 * silently change what the player tapped.
 */
object RhythmTapChart {

    /** Same-lane taps strictly closer than this collapse into the earlier one. */
    const val MERGE_WINDOW_MS = 20.0

    /** Same-lane gaps below the judgement good window are tight but playable; warn, don't delete. */
    const val CLOSE_PAIR_MS = 140.0

    /** What the save panel surfaces; nothing here is silently applied except the merge. */
    data class TapChartWarnings(
        /** Hits dropped by the debounced double-tap merge. */
        val mergedCount: Int = 0,
        /** Kept same-lane pairs closer than [CLOSE_PAIR_MS], as (earlier, later) stored times. */
        val closePairs: List<Pair<Int, Int>> = emptyList(),
        /** Kept notes inside the play-time lead-in: stored, but the game does not show them. */
        val leadInCount: Int = 0,
    )

    data class TapChartResult(
        val chart: RhythmChart,
        val warnings: TapChartWarnings,
    )

    /**
     * Build the playable chart for [take]. [difficultyName] is chart body content (it changes the
     * content hash), so the caller passes the localized name it wants on the entry.
     *
     * Throws [IllegalArgumentException] on an empty take — a chart must contain at least one note.
     */
    fun toChart(
        take: RhythmTapTake,
        difficultyName: String,
        offsetMs: Double = 0.0,
    ): TapChartResult {
        require(take.hits.isNotEmpty()) { "tap take has no hits" }

        // Sort, then debounced double-tap merge. Unknown keys fall back to a round-robin lane so
        // a hand-written take still produces a playable chart instead of failing.
        val sorted = take.hits
            .mapIndexed { index, hit ->
                val lane = RhythmTapTake.laneOf(hit.key) ?: (index % LANE_COUNT)
                (hit.timeMs + offsetMs) to lane
            }
            .sortedWith(compareBy({ it.first }, { it.second }))

        val kept = mutableListOf<Pair<Double, Int>>()
        val lastKeptInLane = DoubleArray(LANE_COUNT) { Double.NEGATIVE_INFINITY }
        var merged = 0
        for (hit in sorted) {
            val (timeMs, lane) = hit
            if (timeMs - lastKeptInLane[lane] < MERGE_WINDOW_MS) {
                merged++
                continue
            }
            kept += hit
            lastKeptInLane[lane] = timeMs
        }
        // A merge can never empty a non-empty take (the first hit of each lane always survives),
        // so no extra guard is needed here.

        val notes = kept.mapIndexed { index, (timeMs, lane) ->
            RhythmNote(
                id = index,
                timeMs = timeMs.roundToInt().coerceAtLeast(0),
                lane = lane,
                strength = 0f,
                band = -1,
                rms = 0f,
            )
        }

        val closePairs = mutableListOf<Pair<Int, Int>>()
        for (lane in 0 until LANE_COUNT) {
            val laneNotes = notes.filter { it.lane == lane }
            for (pair in laneNotes.zipWithNext()) {
                if (pair.second.timeMs - pair.first.timeMs < CLOSE_PAIR_MS) {
                    closePairs += pair.first.timeMs to pair.second.timeMs
                }
            }
        }
        val leadInCount = notes.count { it.timeMs < RhythmChart.LEAD_IN_MS }

        val durationMs = if (take.audio.durationMs > 0) {
            take.audio.durationMs
        } else {
            notes.last().timeMs
        }
        return TapChartResult(
            chart = RhythmChart(
                source = RhythmChart.SOURCE_TAP,
                difficultyName = difficultyName,
                notes = notes,
                durationMs = durationMs,
            ),
            warnings = TapChartWarnings(
                mergedCount = merged,
                closePairs = closePairs,
                leadInCount = leadInCount,
            ),
        )
    }

    private const val LANE_COUNT = 4
}
