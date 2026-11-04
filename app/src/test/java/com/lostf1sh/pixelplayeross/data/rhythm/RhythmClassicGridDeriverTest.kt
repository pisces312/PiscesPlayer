/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS.
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the frozen behaviour of [RhythmClassicGridDeriver]. These assertions ARE the contract:
 * the whole point of the classic mode is that its charts never move again, so a failing test
 * here means someone changed output that must stay stable — not that the test needs updating.
 */
class RhythmClassicGridDeriverTest {

    private val beatMs = 500.0 // 120 BPM
    private val offsetMs = 20

    private fun master(
        times: List<Int>,
        strengths: List<Float> = times.map { 1f },
        bands: List<Int> = times.map { 0 },
        timing: List<RhythmTimingPoint> = listOf(RhythmTimingPoint(offsetMs, beatMs)),
        durationMs: Int = 20_000,
    ): RhythmChart = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = "DSP Raw",
        timing = timing,
        notes = times.mapIndexed { index, time ->
            RhythmNote(index, time, bands[index].coerceIn(0, 3), strengths[index], bands[index])
        },
        durationMs = durationMs,
    )

    private fun derive(
        master: RhythmChart,
        difficulty: DspDifficulty = DspDifficulty.EASY,
        laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
        maxChord: Int = 1,
    ): RhythmChart = RhythmClassicGridDeriver.derive(master, difficulty, laneStrategy, maxChord)

    @Test
    fun easyKeepsOneNotePerBeatCell() {
        // Four onsets well inside one beat cell (grid = 500 ms at 120 BPM).
        val times = listOf(offsetMs, offsetMs + 50, offsetMs + 100, offsetMs + 150)
        val derived = derive(master(times))
        assertEquals(1, derived.noteCount())
        assertEquals(offsetMs, derived.notes.first().timeMs)
    }

    @Test
    fun keepsTheStrongestOnsetInEachCell() {
        val times = listOf(offsetMs, offsetMs + 60, offsetMs + 120)
        val strengths = listOf(0.2f, 0.9f, 0.4f)
        val derived = derive(master(times, strengths))

        assertEquals(1, derived.noteCount())
        assertEquals(offsetMs, derived.notes.single().timeMs)
        assertEquals(0.9f, derived.notes.single().strength, 0f)
    }

    @Test
    fun chordComesFromOnsetsThatFireTogether() {
        // Two strong onsets 60 ms apart with distinct bands -> a double note at the cell time.
        val times = listOf(offsetMs, offsetMs + 60)
        val derived = derive(
            master(times, strengths = listOf(0.9f, 0.8f), bands = listOf(0, 3)),
            maxChord = 2,
        )
        assertEquals(2, derived.noteCount())
        assertEquals(derived.notes[0].timeMs, derived.notes[1].timeMs)
        assertEquals(setOf(0, 1), derived.notes.map { it.lane }.toSet())
    }

    @Test
    fun chordWindowAndStrengthFloorAreEnforced() {
        // 150 ms apart: beyond the 70 ms window, so only the strongest survives.
        val far = derive(
            master(
                listOf(offsetMs, offsetMs + 150),
                strengths = listOf(0.9f, 0.8f),
                bands = listOf(0, 3),
            ),
            maxChord = 2,
        )
        assertEquals(1, far.noteCount())

        // Inside the window but below 20% of the primary: also dropped.
        val weak = derive(
            master(
                listOf(offsetMs, offsetMs + 30),
                strengths = listOf(0.9f, 0.1f),
                bands = listOf(0, 3),
            ),
            maxChord = 2,
        )
        assertEquals(1, weak.noteCount())
    }

    @Test
    fun oneGridForTheWholeSongUsesTheMedianBeat() {
        // A piecewise master (500 ms then 400 ms). The frozen mode ignores the segment change
        // and uses one grid: the median beat (500 ms), anchored at the first timing point.
        // The live GRID derivation would snap this note to its own segment instead — that
        // divergence is exactly what this test protects.
        val derived = derive(
            master(
                times = listOf(5_400),
                timing = listOf(RhythmTimingPoint(0, 500.0), RhythmTimingPoint(5_000, 400.0)),
            ),
        )
        assertEquals(1, derived.noteCount())
        assertEquals(5_500, derived.notes.single().timeMs)
    }

    @Test
    fun difficultiesAreNestedAndMonotonic() {
        val times = (0 until 16).map { offsetMs + it * (beatMs / 4).toInt() }
        val chart = master(times)

        val easy = derive(chart, DspDifficulty.EASY)
        val normal = derive(chart, DspDifficulty.NORMAL)
        val hard = derive(chart, DspDifficulty.HARD)

        // Exact frozen counts: cell boundaries round half-up, so a note sitting exactly on a
        // half cell joins the later one (5 cells over 16 quarter-beat onsets, not 4).
        assertEquals(5, easy.noteCount())
        assertEquals(9, normal.noteCount())
        assertEquals(16, hard.noteCount())
        assertTrue(easy.noteCount() <= normal.noteCount())
        assertTrue(normal.noteCount() <= hard.noteCount())
    }

    @Test
    fun lowOutsideMapsBassToTheOuterLanes() {
        // Bands 0, 3, 4, 7 under LOW_OUTSIDE -> lanes 0, 3, 2, 1 (the frozen table: the lowest
        // and the highest bands sit on the outside lanes, the middle bands in the centre).
        val bands = listOf(0, 3, 4, 7)
        val derived = derive(
            master(bands.map { offsetMs + it * 500 }, bands = bands),
            laneStrategy = LaneStrategy.LOW_OUTSIDE,
        )
        assertEquals(listOf(0, 3, 2, 1), derived.notes.map { it.lane })
    }
}
