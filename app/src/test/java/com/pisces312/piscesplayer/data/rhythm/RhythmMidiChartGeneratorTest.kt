/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (JVM tests for MIDI-driven chart generation).
 */
package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmMidiChartGeneratorTest {

    private val grid = listOf(RhythmTimingPoint(0, 500.0))

    private fun part(
        name: String = "Piano",
        vararg pairs: Triple<Int, Int, Int>, // startMs, pitch, velocity
    ) = MidiPart(
        name = name,
        channel = 0,
        isDrum = false,
        notes = pairs.map { (start, pitch, vel) ->
            MidiNote(startMs = start, durationMs = 100, pitch = pitch, velocity = vel)
        },
    )

    private fun score(vararg parts: MidiPart, durationMs: Int = 60_000) =
        MidiScore(durationMs = durationMs, timing = grid, parts = parts.toList())

    private fun options(
        gridDivs: List<Int> = listOf(1),
        minNotesPerPart: Int = 1,
        chordWindowMs: Int = 25,
        maxChord: Int = 2,
        laneStrategy: MidiLaneStrategy = MidiLaneStrategy.PITCH_RANGE,
    ) = MidiChartOptions(
        gridDivs = gridDivs,
        minNotesPerPart = minNotesPerPart,
        chordWindowMs = chordWindowMs,
        maxChord = maxChord,
        laneStrategy = laneStrategy,
    )

    @Test
    fun `notes snap onto the beat grid and keep the cell time`() {
        val s = score(part(pairs = arrayOf(Triple(1200, 60, 100), Triple(2600, 62, 100))))

        val chart = RhythmMidiChartGenerator.generate(s, options()).single()

        assertEquals(listOf(1000, 2500), chart.notes.map { it.timeMs })
        assertEquals(RhythmChart.SOURCE_MIDI, chart.source)
        assertEquals(grid, chart.timing)
        assertEquals(60_000, chart.durationMs)
    }

    @Test
    fun `the strongest note wins a cell`() {
        val s = score(
            part(pairs = arrayOf(Triple(1200, 60, 40), Triple(1250, 67, 90), Triple(2600, 64, 100))),
        )

        val chart = RhythmMidiChartGenerator.generate(s, options(maxChord = 1)).single()

        // 1250 ms shares the 1000 ms cell with a louder note; 2600 ms wins its own cell.
        assertEquals(listOf(1000, 2500), chart.notes.map { it.timeMs })
        assertEquals(90f / 127f, chart.notes[0].strength, 1e-6f)
        assertEquals(100f / 127f, chart.notes[1].strength, 1e-6f)
    }

    @Test
    fun `simultaneous notes become a chord on distinct lanes`() {
        val s = score(part(pairs = arrayOf(Triple(500, 60, 100), Triple(510, 64, 90))))

        val chart = RhythmMidiChartGenerator.generate(s, options(maxChord = 2)).single()

        assertEquals(2, chart.notes.size)
        assertEquals(listOf(500, 500), chart.notes.map { it.timeMs })
        assertEquals(2, chart.notes.map { it.lane }.toSet().size)
    }

    @Test
    fun `maxChord one drops the secondary note`() {
        val s = score(part(pairs = arrayOf(Triple(500, 60, 100), Triple(510, 64, 90))))

        val chart = RhythmMidiChartGenerator.generate(s, options(maxChord = 1)).single()

        assertEquals(1, chart.notes.size)
    }

    @Test
    fun `notes further apart than the chord window in one cell stay a single note`() {
        // gridDiv 1: both notes land in the 500 ms cell but 200 ms apart — a stream, not a chord.
        val s = score(part(pairs = arrayOf(Triple(500, 60, 100), Triple(700, 64, 90))))

        val chart = RhythmMidiChartGenerator.generate(s, options(maxChord = 2)).single()

        assertEquals(1, chart.notes.size)
        assertEquals(100f / 127f, chart.notes[0].strength, 1e-6f)
    }

    @Test
    fun `pitch range maps low and high pitches to outer lanes`() {
        val s = score(
            part(pairs = arrayOf(Triple(2000, 36, 100), Triple(2500, 96, 100))),
        )

        val chart = RhythmMidiChartGenerator.generate(s, options()).single()

        assertEquals(0, chart.notes[0].lane)
        assertEquals(3, chart.notes[1].lane)
    }

    @Test
    fun `round robin ignores pitch and rotates lanes`() {
        val s = score(
            part(pairs = arrayOf(Triple(2000, 60, 100), Triple(2500, 60, 100))),
        )

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(laneStrategy = MidiLaneStrategy.ROUND_ROBIN),
        ).single()

        assertEquals(listOf(0, 1), chart.notes.map { it.lane })
    }

    @Test
    fun `drum parts rotate lanes regardless of strategy`() {
        val drum = MidiPart(
            name = "Drums",
            channel = 9,
            isDrum = true,
            notes = listOf(
                MidiNote(2000, 50, 36, 100),
                MidiNote(2500, 50, 38, 100),
            ),
        )

        val chart = RhythmMidiChartGenerator.generate(score(drum), options()).single()

        assertEquals(listOf(0, 1), chart.notes.map { it.lane })
    }

    @Test
    fun `each grid divisor produces its own chart per part`() {
        val s = score(
            part("Piano", Triple(2000, 60, 100)),
            part("Bass", Triple(2000, 40, 100)),
        )

        val charts = RhythmMidiChartGenerator.generate(s, options(gridDivs = listOf(1, 2, 4)))

        assertEquals(6, charts.size)
        assertEquals(
            listOf("Bass · 1/1", "Bass · 1/2", "Bass · 1/4", "Piano · 1/1", "Piano · 1/2", "Piano · 1/4"),
            charts.map { it.difficultyName }.sorted(),
        )
    }

    @Test
    fun `small parts are skipped`() {
        val s = score(
            MidiPart("Tiny", 0, false, listOf(MidiNote(2000, 100, 60, 100))),
            part("Piano", Triple(2000, 60, 100), Triple(2500, 62, 100)),
        )

        val charts = RhythmMidiChartGenerator.generate(s, options(minNotesPerPart = 2))

        assertEquals(listOf("Piano · 1/1"), charts.map { it.difficultyName })
    }

    @Test
    fun `a chart inside the lead-in keeps its notes`() {
        val s = score(part(pairs = arrayOf(Triple(500, 60, 100))), durationMs = 800)

        val chart = RhythmMidiChartGenerator.generate(s, options()).single()

        assertEquals(1, chart.notes.size)
    }

    @Test
    fun `notes before the lead-in are trimmed`() {
        val s = score(part(pairs = arrayOf(Triple(500, 60, 100), Triple(1500, 62, 100))))

        val chart = RhythmMidiChartGenerator.generate(s, options()).single()

        assertEquals(listOf(1500), chart.notes.map { it.timeMs })
    }

    @Test
    fun `generation is deterministic`() {
        val s = score(
            part(pairs = arrayOf(Triple(510, 60, 100), Triple(500, 64, 90), Triple(1490, 67, 80))),
        )

        val a = RhythmMidiChartGenerator.generate(s, options(gridDivs = listOf(1, 2, 4)))
        val b = RhythmMidiChartGenerator.generate(s, options(gridDivs = listOf(1, 2, 4)))

        assertEquals(a.size, b.size)
        a.zip(b).forEach { (ca, cb) -> assertEquals(ca.notes, cb.notes) }
    }

    @Test
    fun `empty score produces no charts`() {
        val s = MidiScore(durationMs = 0, timing = emptyList(), parts = emptyList())

        assertTrue(RhythmMidiChartGenerator.generate(s, options()).isEmpty())
    }
}
