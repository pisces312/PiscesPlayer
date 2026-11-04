/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, GPL-3.0-or-later
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The game-start A/B re-bake: same notes, different placement. Each logic places against its
 * own timing points through the shared osu-style snap ([RhythmChartDeriver.snapTime]).
 */
class RhythmPlayLogicTest {

    private fun note(id: Int, timeMs: Int, lane: Int = id % 4) =
        RhythmNote(id = id, timeMs = timeMs, lane = lane)

    private fun chart(
        timing: List<RhythmTimingPoint>,
        notes: List<RhythmNote>,
        durationMs: Int = 60_000,
    ) = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = "Easy grid",
        timing = timing,
        notes = notes,
        durationMs = durationMs,
    )

    /** Two 500 ms segments and a slower 400 ms one, so the logics visibly disagree. */
    private val lattice = listOf(
        RhythmTimingPoint(0, 500.0),
        RhythmTimingPoint(10_000, 500.0),
        RhythmTimingPoint(20_000, 400.0),
    )

    @Test
    fun asStoredKeepsEveryTime() {
        val chart = chart(lattice, listOf(note(0, 40), note(1, 5210), note(2, 20_210)))
        val retimed = RhythmPlayLogic.AS_STORED.retime(chart = chart, gridDiv = 1)
        assertEquals(chart.notes, retimed.notes)
        assertEquals(chart.timing, retimed.timing)
    }

    @Test
    fun adaptiveGridSnapsOntoTheTrackedLattice() {
        val chart = chart(lattice, listOf(note(0, 40), note(1, 5210), note(2, 20_210)))
        val retimed = RhythmPlayLogic.ADAPTIVE_GRID.retime(chart = chart, gridDiv = 1)
        // 40 -> 0 and 5210 -> 5000 on the 500 ms segment; 20210 -> 20400 on the 400 ms one.
        assertEquals(listOf(0, 5000, 20_400), retimed.notes.map { it.timeMs })
    }

    @Test
    fun adaptiveGridIsIdempotentOnGridNotes() {
        val chart = chart(
            lattice,
            listOf(note(0, 0), note(1, 5000), note(2, 20_000), note(3, 20_400)),
        )
        val retimed = RhythmPlayLogic.ADAPTIVE_GRID.retime(chart = chart, gridDiv = 1)
        assertEquals(chart.notes.map { it.timeMs }, retimed.notes.map { it.timeMs })
    }

    @Test
    fun osuStyleUsesOneGlobalBeat() {
        val chart = chart(lattice, listOf(note(0, 40), note(1, 5210), note(2, 20_210)))
        val retimed = RhythmPlayLogic.OSU_STYLE.retime(chart = chart, gridDiv = 1)
        // Median beat 500 ms anchored at 0: everything lands on k * 500, including the notes
        // the adaptive lattice would put on the slower 400 ms segment.
        assertEquals(listOf(0, 5000, 20_000), retimed.notes.map { it.timeMs })
        assertEquals(1, retimed.timing.size)
    }

    @Test
    fun legacyRefineUsesTheMastersOnsets() {
        val masterNotes = (0 until 120).map { note(it, it * 500) }
        val master = chart(lattice, masterNotes)
        val derived = chart(lattice, listOf(note(0, 40), note(1, 5210)))
        val retimed = RhythmPlayLogic.LEGACY_REFINE.retime(
            chart = derived,
            master = master,
            gridDiv = 1,
        )

        // The wiring contract: the refiner is fed the master's full onset set, not the thinned
        // derived notes, and the baked grid is exactly its output.
        val expected = RhythmTimingRefiner.refine(
            onsets = masterNotes.map { RhythmTimingRefiner.Onset(it.timeMs, it.strength) },
            global = lattice.first(),
            durationMs = 60_000,
        ).sortedBy { it.timeMs }
        assertEquals(expected, retimed.timing)
    }

    @Test
    fun retimingKeepsNoteCountLanesAndIds() {
        val chart = chart(
            lattice,
            listOf(note(0, 40, lane = 1), note(1, 5210, lane = 2), note(2, 20_210, lane = 3)),
        )
        val logics = listOf(
            RhythmPlayLogic.ADAPTIVE_GRID,
            RhythmPlayLogic.OSU_STYLE,
            RhythmPlayLogic.LEGACY_REFINE,
        )
        for (logic in logics) {
            val retimed = logic.retime(chart = chart, gridDiv = 2)
            assertEquals("$logic count", chart.notes.size, retimed.notes.size)
            assertEquals("$logic lanes", chart.notes.map { it.lane }, retimed.notes.map { it.lane })
            assertEquals("$logic ids", chart.notes.map { it.id }, retimed.notes.map { it.id })
        }
    }

    @Test
    fun fromParsesRouteNames() {
        assertEquals(RhythmPlayLogic.OSU_STYLE, RhythmPlayLogic.from("OSU_STYLE"))
        assertEquals(RhythmPlayLogic.LEGACY_REFINE, RhythmPlayLogic.from("legacy_refine"))
        assertNull(RhythmPlayLogic.from(null))
        assertNull(RhythmPlayLogic.from("nonsense"))
    }
}
