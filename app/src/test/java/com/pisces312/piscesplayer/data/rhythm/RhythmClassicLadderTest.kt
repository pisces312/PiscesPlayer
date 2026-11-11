/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS.
 */
package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The current classic ladder is a mapping onto the frozen replica, not an edit of it:
 * Normal is the old Easy, Hard the old Normal, and Easy a new two-beat cell. These tests
 * pin the mapping; [RhythmClassicGridDeriverTest] remains the contract for the replica.
 */
class RhythmClassicLadderTest {

    private val beatMs = 500.0 // 120 BPM
    private val offsetMs = 20

    private fun master(
        times: List<Int>,
        strengths: List<Float> = times.map { 1f },
        bands: List<Int> = times.map { 0 },
        timing: List<RhythmTimingPoint> = listOf(RhythmTimingPoint(offsetMs, beatMs)),
    ): RhythmChart = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = "DSP Raw",
        timing = timing,
        notes = times.mapIndexed { index, time ->
            RhythmNote(index, time, bands[index].coerceIn(0, 3), strengths[index], bands[index])
        },
        durationMs = 20_000,
    )

    private fun ladder(
        master: RhythmChart,
        difficulty: DspDifficulty,
        maxChord: Int = 2,
    ): RhythmChart = RhythmClassicLadder.derive(
        master,
        difficulty,
        LaneStrategy.BAND_ORDER,
        maxChord,
        master.timing,
    )

    @Test
    fun normalIsTheFrozenEasyNoteForNote() {
        val chart = master((0 until 16).map { offsetMs + it * 125 })
        assertEquals(
            RhythmClassicGridDeriver.derive(
                chart, DspDifficulty.EASY, LaneStrategy.BAND_ORDER, 2,
            ).notes,
            ladder(chart, DspDifficulty.NORMAL).notes,
        )
    }

    @Test
    fun hardIsTheFrozenNormalNoteForNote() {
        val chart = master((0 until 16).map { offsetMs + it * 125 })
        assertEquals(
            RhythmClassicGridDeriver.derive(
                chart, DspDifficulty.NORMAL, LaneStrategy.BAND_ORDER, 2,
            ).notes,
            ladder(chart, DspDifficulty.HARD).notes,
        )
    }

    @Test
    fun easyIsTheFrozenEasyOnADoubledBeat() {
        val chart = master((0 until 16).map { offsetMs + it * 125 })
        val doubled = chart.timing.map { it.copy(beatMs = it.beatMs * 2) }
        assertEquals(
            RhythmClassicGridDeriver.derive(
                chart, DspDifficulty.EASY, LaneStrategy.BAND_ORDER, 2, doubled,
            ).notes,
            ladder(chart, DspDifficulty.EASY).notes,
        )
    }

    @Test
    fun ladderIsNestedAndEasySitsOnTwoBeatCells() {
        val chart = master((0 until 16).map { offsetMs + it * 125 })
        val easy = ladder(chart, DspDifficulty.EASY)
        val normal = ladder(chart, DspDifficulty.NORMAL)
        val hard = ladder(chart, DspDifficulty.HARD)

        // 16 quarter-beat onsets over four beats: two-beat cells keep 3 (round half-up),
        // one-beat cells 5, half-beat cells 9 — the frozen counts shifted one step down.
        assertEquals(3, easy.noteCount())
        assertEquals(5, normal.noteCount())
        assertEquals(9, hard.noteCount())
        assertTrue(easy.noteCount() <= normal.noteCount())
        assertTrue(normal.noteCount() <= hard.noteCount())
        assertTrue(easy.notes.all { (it.timeMs - offsetMs) % 1000 == 0 })
        // The stored timing must stay the master's, not the doubled Easy grid.
        assertEquals(chart.timing, easy.timing)
    }

    @Test
    fun chordsSurviveTheMapping() {
        // Two strong onsets 60 ms apart in distinct bands: a double note at the cell time,
        // exactly as the replica produces it for the mapped difficulty.
        val chart = master(
            listOf(offsetMs, offsetMs + 60),
            strengths = listOf(0.9f, 0.8f),
            bands = listOf(0, 3),
        )
        val normal = ladder(chart, DspDifficulty.NORMAL)
        assertEquals(2, normal.noteCount())
        assertEquals(normal.notes[0].timeMs, normal.notes[1].timeMs)
        assertEquals(setOf(0, 1), normal.notes.map { it.lane }.toSet())
    }
}