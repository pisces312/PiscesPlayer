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

    private fun difficulty(
        label: String = "Easy",
        gridDiv: Int = 1,
        maxHitsPerSecond: Double = Double.POSITIVE_INFINITY,
        maxChord: Int = 2,
    ) = MidiDifficulty(
        label = label,
        gridDiv = gridDiv,
        maxHitsPerSecond = maxHitsPerSecond,
        maxChord = maxChord,
    )

    private fun options(
        difficulties: List<MidiDifficulty> = listOf(difficulty()),
        minNotesPerPart: Int = 1,
        chordWindowMs: Int = 25,
        laneStrategy: MidiLaneStrategy = MidiLaneStrategy.PITCH_RANGE,
    ) = MidiChartOptions(
        difficulties = difficulties,
        minNotesPerPart = minNotesPerPart,
        chordWindowMs = chordWindowMs,
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

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxChord = 1))),
        ).single()

        // 1250 ms shares the 1000 ms cell with a louder note; 2600 ms wins its own cell.
        assertEquals(listOf(1000, 2500), chart.notes.map { it.timeMs })
        assertEquals(90f / 127f, chart.notes[0].strength, 1e-6f)
        assertEquals(100f / 127f, chart.notes[1].strength, 1e-6f)
    }

    @Test
    fun `simultaneous notes become a chord on distinct lanes`() {
        val s = score(part(pairs = arrayOf(Triple(500, 60, 100), Triple(510, 64, 90))))

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxChord = 2))),
        ).single()

        assertEquals(2, chart.notes.size)
        assertEquals(listOf(500, 500), chart.notes.map { it.timeMs })
        assertEquals(2, chart.notes.map { it.lane }.toSet().size)
    }

    @Test
    fun `maxChord one drops the secondary note`() {
        val s = score(part(pairs = arrayOf(Triple(500, 60, 100), Triple(510, 64, 90))))

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxChord = 1))),
        ).single()

        assertEquals(1, chart.notes.size)
    }

    @Test
    fun `notes further apart than the chord window in one cell stay a single note`() {
        // gridDiv 1: both notes land in the 500 ms cell but 200 ms apart — a stream, not a chord.
        val s = score(part(pairs = arrayOf(Triple(500, 60, 100), Triple(700, 64, 90))))

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxChord = 2))),
        ).single()

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
    fun `each difficulty produces its own chart per part`() {
        val difficulties = listOf(
            difficulty("Easy", gridDiv = 1, maxHitsPerSecond = 1.0, maxChord = 1),
            difficulty("Normal", gridDiv = 2, maxHitsPerSecond = 2.0, maxChord = 2),
            difficulty("Hard", gridDiv = 4, maxHitsPerSecond = 4.0, maxChord = 2),
        )
        val s = score(
            part("Piano", Triple(2000, 60, 100)),
            part("Bass", Triple(2000, 40, 100)),
        )

        val charts = RhythmMidiChartGenerator.generate(s, options(difficulties = difficulties))

        assertEquals(6, charts.size)
        assertEquals(
            listOf(
                "Bass · Easy", "Bass · Hard", "Bass · Normal",
                "Piano · Easy", "Piano · Hard", "Piano · Normal",
            ),
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

        assertEquals(listOf("Piano · Easy"), charts.map { it.difficultyName })
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
        val options = options(
            difficulties = listOf(
                difficulty("Easy", gridDiv = 1, maxHitsPerSecond = 1.0, maxChord = 1),
                difficulty("Normal", gridDiv = 2, maxHitsPerSecond = 2.0, maxChord = 2),
                difficulty("Hard", gridDiv = 4, maxHitsPerSecond = 4.0, maxChord = 2),
            ),
        )

        val a = RhythmMidiChartGenerator.generate(s, options)
        val b = RhythmMidiChartGenerator.generate(s, options)

        assertEquals(a.size, b.size)
        a.zip(b).forEach { (ca, cb) ->
            assertEquals(ca.difficultyName, cb.difficultyName)
            assertEquals(ca.notes, cb.notes)
        }
    }

    @Test
    fun `empty score produces no charts`() {
        val s = MidiScore(durationMs = 0, timing = emptyList(), parts = emptyList())

        assertTrue(RhythmMidiChartGenerator.generate(s, options()).isEmpty())
    }

    // ---- density clipping -----------------------------------------------------------

    @Test
    fun `the default difficulties cover three density rungs`() {
        val defaults = MidiChartOptions().difficulties

        assertEquals(listOf("简单", "标准", "困难"), defaults.map { it.label })
        assertEquals(listOf(1, 2, 4), defaults.map { it.gridDiv })
        assertEquals(listOf(1.0, 2.0, 4.0), defaults.map { it.maxHitsPerSecond })
        assertEquals(listOf(1, 2, 2), defaults.map { it.maxChord })
    }

    @Test
    fun `eight notes inside one second survive as the single strongest hit`() {
        // 8 notes packed into 800 ms quantize to two beat cells (2000 / 2500); at one hit
        // per second only the stronger cell survives, and the chord cap leaves one key.
        val s = score(
            part(
                pairs = arrayOf(
                    Triple(2000, 60, 30), Triple(2100, 61, 31), Triple(2200, 62, 32),
                    Triple(2300, 63, 33), Triple(2500, 64, 34), Triple(2600, 65, 120),
                    Triple(2700, 66, 35), Triple(2800, 67, 36),
                ),
            ),
        )

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxHitsPerSecond = 1.0, maxChord = 1))),
        ).single()

        assertEquals(1, chart.notes.size)
        assertEquals(2500, chart.notes[0].timeMs)
        assertEquals(120f / 127f, chart.notes[0].strength, 1e-6f)
    }

    @Test
    fun `an on-beat hit outranks a stronger off-beat one`() {
        // gridDiv 2: 1250 ms is an off-beat cell. At the one-hit cap the on-beat 1000 ms
        // cell survives even though the off-beat one is much louder.
        val s = score(part(pairs = arrayOf(Triple(1000, 60, 40), Triple(1250, 62, 120))))

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(
                difficulties = listOf(
                    difficulty(gridDiv = 2, maxHitsPerSecond = 1.0, maxChord = 1),
                ),
            ),
        ).single()

        assertEquals(listOf(1000), chart.notes.map { it.timeMs })
        assertEquals(40f / 127f, chart.notes[0].strength, 1e-6f)
    }

    @Test
    fun `the density cap scales with maxHitsPerSecond`() {
        // Beat cells at 1000 / 1500 / 2000: a one-hit cap needs 1000 ms between hits, a
        // two-hit cap 500 ms.
        val s = score(part(pairs = arrayOf(Triple(1000, 60, 100), Triple(1500, 62, 100), Triple(2000, 64, 100))))

        val one = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxHitsPerSecond = 1.0, maxChord = 1))),
        ).single()
        val two = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxHitsPerSecond = 2.0, maxChord = 1))),
        ).single()

        assertEquals(listOf(1000, 2000), one.notes.map { it.timeMs })
        assertEquals(listOf(1000, 1500, 2000), two.notes.map { it.timeMs })
    }

    @Test
    fun `a chord counts as one hit against the density cap`() {
        // The 2000 ms chord is one hit (two keys), so the 2500 ms single note fits the
        // two-hit cap instead of being clipped as a third hit.
        val s = score(
            part(pairs = arrayOf(Triple(2000, 60, 100), Triple(2010, 64, 90), Triple(2500, 62, 80))),
        )

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxHitsPerSecond = 2.0, maxChord = 2))),
        ).single()

        assertEquals(listOf(2000, 2000, 2500), chart.notes.map { it.timeMs })
    }

    @Test
    fun `density culling is deterministic`() {
        val s = score(
            part(
                pairs = arrayOf(
                    Triple(2010, 60, 90), Triple(2000, 64, 100), Triple(2490, 67, 80),
                    Triple(2550, 65, 95), Triple(3010, 61, 70), Triple(3490, 63, 85),
                ),
            ),
        )
        val options = options(
            difficulties = listOf(difficulty(maxHitsPerSecond = 1.5, maxChord = 2)),
        )

        val a = RhythmMidiChartGenerator.generate(s, options)
        val b = RhythmMidiChartGenerator.generate(s, options)

        assertEquals(a.size, b.size)
        a.zip(b).forEach { (ca, cb) ->
            assertEquals(ca.difficultyName, cb.difficultyName)
            assertEquals(ca.notes, cb.notes)
        }
    }
}
