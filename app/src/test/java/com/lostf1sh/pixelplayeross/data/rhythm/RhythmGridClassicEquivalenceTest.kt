/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §10.4).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * On a single-point timing the live grid derivation and the frozen classic replica apply the
 * same rule. Pinning that equality while both exist catches accidental edits to either side; the
 * classic deriver's own test remains the contract for its frozen output.
 */
class RhythmGridClassicEquivalenceTest {

    private val master = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = "DSP Raw",
        timing = listOf(RhythmTimingPoint(20, 500.0)),
        notes = listOf(
            RhythmNote(0, 1_020, lane = 0, strength = 0.9f, band = 0),
            RhythmNote(1, 1_070, lane = 3, strength = 0.8f, band = 7),
            RhythmNote(2, 1_260, lane = 2, strength = 0.7f, band = 5),
            RhythmNote(3, 1_520, lane = 1, strength = 0.6f, band = 2),
            RhythmNote(4, 1_980, lane = 0, strength = 0.5f, band = 1),
            RhythmNote(5, 2_020, lane = 3, strength = 0.4f, band = 6),
        ),
        durationMs = 10_000,
    )

    @Test
    fun bothDeriversProduceTheSameNotesOnASinglePointTiming() {
        for (difficulty in DspDifficulty.entries) {
            for (maxChord in 1..2) {
                val live = RhythmChartDeriver.derive(
                    master = master,
                    difficulty = difficulty,
                    laneStrategy = LaneStrategy.BAND_ORDER,
                    maxChord = maxChord,
                    timing = master.timing,
                )
                val classic = RhythmClassicGridDeriver.derive(
                    master = master,
                    difficulty = difficulty,
                    laneStrategy = LaneStrategy.BAND_ORDER,
                    maxChord = maxChord,
                    timing = master.timing,
                )

                assertEquals(
                    "difficulty=$difficulty maxChord=$maxChord",
                    classic.notes.map { it.timeMs to it.lane },
                    live.notes.map { it.timeMs to it.lane },
                )
            }
        }
    }
}
