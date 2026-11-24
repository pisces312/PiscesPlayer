/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (JVM tests for MIDI-driven chart generation).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        laneStrategy: MidiLaneStrategy = MidiLaneStrategy.MELODIC,
        repeatMode: MidiRepeatMode = MidiRepeatMode.TWO_LANE,
        weakNoteVelocity: Int = 16,
        phraseBreathing: Boolean = true,
        phraseBreathFactor: Double = 0.75,
        densityGradient: Boolean = true,
        jumpPenalty: Boolean = true,
    ) = MidiChartOptions(
        difficulties = difficulties,
        minNotesPerPart = minNotesPerPart,
        chordWindowMs = chordWindowMs,
        laneStrategy = laneStrategy,
        repeatMode = repeatMode,
        weakNoteVelocity = weakNoteVelocity,
        phraseBreathing = phraseBreathing,
        phraseBreathFactor = phraseBreathFactor,
        densityGradient = densityGradient,
        jumpPenalty = jumpPenalty,
    )

    // ---- quantization and chord merge ------------------------------------------------

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

        // 1250 ms shares the 1000 ms cell with a quieter note; 2600 ms wins its own cell.
        assertEquals(listOf(1000, 2500), chart.notes.map { it.timeMs })
        assertEquals(90f / 127f, chart.notes[0].strength, 1e-6f)
        assertEquals(100f / 127f, chart.notes[1].strength, 1e-6f)
    }

    @Test
    fun `slot selection prefers the note closest to the cell time`() {
        // Both notes land in the 2000 ms cell: the louder one drifts 300 ms off the snapped
        // time, and the A2 distance penalty hands the primary to the on-slot note instead.
        val s = score(part(pairs = arrayOf(Triple(2000, 60, 60), Triple(2300, 64, 100))))

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxChord = 1))),
        ).single()

        assertEquals(1, chart.notes.size)
        assertEquals(2000, chart.notes[0].timeMs)
        assertEquals(60f / 127f, chart.notes[0].strength, 1e-6f)
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
    fun `chord keys take distinct lanes under melodic mapping`() {
        val s = score(
            part(pairs = arrayOf(Triple(2000, 60, 100), Triple(2010, 64, 90), Triple(2005, 67, 80))),
        )

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxChord = 3))),
        ).single()

        assertEquals(3, chart.notes.size)
        assertEquals(listOf(2000, 2000, 2000), chart.notes.map { it.timeMs })
        assertEquals(3, chart.notes.map { it.lane }.toSet().size)
    }

    // ---- lane mapping -----------------------------------------------------------------

    @Test
    fun `pitch range maps low and high pitches to outer lanes`() {
        val s = score(
            part(pairs = arrayOf(Triple(2000, 36, 100), Triple(2500, 96, 100))),
        )

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(laneStrategy = MidiLaneStrategy.PITCH_RANGE),
        ).single()

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
    fun `melodic repeats alternate between two adjacent lanes`() {
        // 24 repeated pitches: every hit wants one lane, so the A-B-A-B repeat run takes over
        // and no hit ever lands on its predecessor's lane.
        val s = score(part(pairs = Array(24) { i -> Triple(2000 + i * 500, 60, 100) }))

        val chart = RhythmMidiChartGenerator.generate(s, options()).single()
        val lanes = chart.notes.map { it.lane }

        assertEquals(24, lanes.size)
        // "No long same-lane run": consecutive hits never share a lane at all.
        assertTrue(lanes.zipWithNext().all { (a, b) -> a != b })
        assertTrue(lanes.zipWithNext().all { (a, b) -> abs(a - b) == 1 })
        // Once the run settles it is exactly A-B-A-B on two adjacent lanes.
        val tail = lanes.takeLast(6)
        assertEquals(2, tail.toSet().size)
        assertEquals(tail[0], tail[2])
        assertEquals(tail[0], tail[4])
        assertEquals(tail[1], tail[3])
        assertEquals(tail[1], tail[5])
        assertEquals(1, abs(tail[0] - tail[1]))
    }

    @Test
    fun `melodic repeats rotate over three adjacent lanes`() {
        val s = score(part(pairs = Array(24) { i -> Triple(2000 + i * 500, 60, 100) }))

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(repeatMode = MidiRepeatMode.THREE_LANE),
        ).single()
        val lanes = chart.notes.map { it.lane }

        assertEquals(24, lanes.size)
        assertTrue(lanes.zipWithNext().all { (a, b) -> a != b })
        // A-B-C-A: the settled stretch cycles through exactly three adjacent lanes.
        val mid = lanes.subList(6, 12)
        assertEquals(3, mid.toSet().size)
        assertEquals(mid[0], mid[3])
        assertEquals(mid[1], mid[4])
        assertEquals(mid[2], mid[5])
    }

    @Test
    fun `a narrow range part spreads over all four lanes`() {
        // A melody that never leaves five semitones — the old whole-song quartiles dumped
        // this into one or two lanes; the local rank uses all four.
        val pattern = intArrayOf(60, 62, 64, 65, 64, 62)
        val s = score(
            part(pairs = Array(24) { i -> Triple(2000 + i * 500, pattern[i % pattern.size], 100) }),
        )

        val chart = RhythmMidiChartGenerator.generate(s, options()).single()
        val counts = chart.notes.groupingBy { it.lane }.eachCount()

        assertEquals(4, counts.size)
        assertTrue(
            counts.values.max() * 100 < chart.notes.size * 45,
            "dominant lane holds ${counts.values.max()} of ${chart.notes.size} notes: $counts",
        )
    }

    // ---- per-part and per-difficulty structure -----------------------------------------

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
    fun `empty score produces no charts`() {
        val s = MidiScore(durationMs = 0, timing = emptyList(), parts = emptyList())

        assertTrue(RhythmMidiChartGenerator.generate(s, options()).isEmpty())
    }

    // ---- defaults and determinism -------------------------------------------------------

    @Test
    fun `the default difficulties cover three density rungs`() {
        val defaults = MidiChartOptions().difficulties

        assertEquals(listOf("简单", "标准", "困难"), defaults.map { it.label })
        assertEquals(listOf(1, 2, 4), defaults.map { it.gridDiv })
        assertEquals(listOf(1.0, 2.0, 4.0), defaults.map { it.maxHitsPerSecond })
        assertEquals(listOf(1, 2, 2), defaults.map { it.maxChord })
    }

    @Test
    fun `the default options follow the lively preset`() {
        val defaults = MidiChartOptions()

        assertEquals(MidiLaneStrategy.MELODIC, defaults.laneStrategy)
        assertEquals(MidiRepeatMode.TWO_LANE, defaults.repeatMode)
        assertEquals(16, defaults.weakNoteVelocity)
        assertTrue(defaults.phraseBreathing)
        assertEquals(0.75, defaults.phraseBreathFactor, 0.0)
        assertTrue(defaults.densityGradient)
        assertTrue(defaults.jumpPenalty)
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
    fun `generation is deterministic with the lively options`() {
        val s = score(
            part(
                pairs = Array(40) { i ->
                    Triple(2000 + i * 250, 58 + (i * 7) % 12, 20 + (i * 13) % 108)
                },
            ),
        )
        val options = options(
            difficulties = listOf(
                difficulty("Easy", gridDiv = 1, maxHitsPerSecond = 1.0, maxChord = 1),
                difficulty("Normal", gridDiv = 2, maxHitsPerSecond = 2.5, maxChord = 2),
            ),
            repeatMode = MidiRepeatMode.THREE_LANE,
            weakNoteVelocity = 30,
            phraseBreathing = true,
            densityGradient = true,
            jumpPenalty = true,
        )

        val a = RhythmMidiChartGenerator.generate(s, options)
        val b = RhythmMidiChartGenerator.generate(s, options)

        assertEquals(a, b)
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

    // ---- density clipping (A2) -----------------------------------------------------------

    @Test
    fun `eight notes inside one second survive as the single strongest hit`() {
        // 8 notes packed into 800 ms quantize to two beat cells (2000 / 2500); at one hit
        // per second only one cell survives, and the chord cap leaves one key. The strongest
        // note sits on the bar-head cell, which tops the survival priority.
        val s = score(
            part(
                pairs = arrayOf(
                    Triple(2000, 60, 120), Triple(2100, 61, 30), Triple(2200, 62, 31),
                    Triple(2300, 63, 32), Triple(2500, 64, 33), Triple(2600, 65, 34),
                    Triple(2700, 66, 35), Triple(2800, 67, 36),
                ),
            ),
        )

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxHitsPerSecond = 1.0, maxChord = 1))),
        ).single()

        assertEquals(1, chart.notes.size)
        assertEquals(2000, chart.notes[0].timeMs)
        assertEquals(120f / 127f, chart.notes[0].strength, 1e-6f)
    }

    @Test
    fun `a bar-head hit outranks a stronger later hit`() {
        // 2000 ms is the head of the second bar (beat 4 on the 4/4 grid); at the one-hit cap
        // the bar head survives even though the 2500 ms hit is much louder.
        val s = score(part(pairs = arrayOf(Triple(2000, 60, 50), Triple(2500, 62, 120))))

        val chart = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxHitsPerSecond = 1.0, maxChord = 1))),
        ).single()

        assertEquals(listOf(2000), chart.notes.map { it.timeMs })
        assertEquals(50f / 127f, chart.notes[0].strength, 1e-6f)
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
    fun `one hit per cell and the density cap hold with the lively defaults`() {
        // Three keys in the 2000 ms cell merge into one hit (maxChord 1), and the 2500 ms hit
        // only survives when the cap's spacing allows it.
        val s = score(
            part(
                pairs = arrayOf(
                    Triple(2000, 60, 100), Triple(2010, 62, 90), Triple(2020, 64, 80),
                    Triple(2500, 66, 100),
                ),
            ),
        )

        val tight = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxHitsPerSecond = 1.5, maxChord = 1))),
        ).single()
        val loose = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = listOf(difficulty(maxHitsPerSecond = 4.0, maxChord = 1))),
        ).single()

        assertEquals(listOf(2000), tight.notes.map { it.timeMs })
        assertEquals(listOf(2000, 2500), loose.notes.map { it.timeMs })
    }

    // ---- rhythm breathing (A2) -----------------------------------------------------------

    @Test
    fun `weak notes below the filter threshold are dropped`() {
        val s = score(
            part(pairs = arrayOf(Triple(2000, 60, 10), Triple(2500, 62, 100), Triple(3000, 64, 15))),
        )

        val filtered = RhythmMidiChartGenerator.generate(
            s,
            options(weakNoteVelocity = 16),
        ).single()
        val unfiltered = RhythmMidiChartGenerator.generate(
            s,
            options(weakNoteVelocity = 0),
        ).single()

        assertEquals(listOf(2500), filtered.notes.map { it.timeMs })
        assertEquals(listOf(2000, 2500, 3000), unfiltered.notes.map { it.timeMs })
    }

    @Test
    fun `phrase breathing thins every fourth bar`() {
        // One hit per half beat from 2 s to 18 s; with 500 ms beats a 4/4 bar is 2 s here.
        val s = score(part(pairs = Array(32) { i -> Triple(2000 + i * 500, 60, 100) }))
        val rung = listOf(difficulty(gridDiv = 1, maxHitsPerSecond = 2.0, maxChord = 1))

        val flat = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = rung, phraseBreathing = false),
        ).single()
        val breath = RhythmMidiChartGenerator.generate(
            s,
            options(difficulties = rung, phraseBreathing = true, phraseBreathFactor = 0.75),
        ).single()

        fun countBar(chart: RhythmChart, bar: Int) = chart.notes.count { it.timeMs / 2000 == bar }

        // Bars 2 and 3 both hold four half-beat hits; only bar 3 (the 4th) breathes.
        assertEquals(4, countBar(flat, 2))
        assertEquals(4, countBar(flat, 3))
        assertEquals(4, countBar(breath, 2))
        assertEquals(2, countBar(breath, 3))
    }

    @Test
    fun `density gradient thins quiet stretches but never beats the cap`() {
        // Quiet verse (20 velocity) then loud chorus (120), one hit per half beat.
        val s = score(
            part(
                pairs = Array(24) { i ->
                    val time = 2000 + i * 500
                    Triple(time, 60, if (time < 8000) 20 else 120)
                },
            ),
        )
        val rung = listOf(difficulty(gridDiv = 1, maxHitsPerSecond = 2.0, maxChord = 1))

        fun generateWith(gradient: Boolean) = RhythmMidiChartGenerator.generate(
            s,
            options(
                difficulties = rung,
                weakNoteVelocity = 16,
                phraseBreathing = false,
                densityGradient = gradient,
            ),
        ).single()

        fun countBar(chart: RhythmChart, bar: Int) = chart.notes.count { it.timeMs / 2000 == bar }

        val gradient = generateWith(gradient = true)
        val flat = generateWith(gradient = false)

        // Quiet bar 2 thins to 2 of its 4 hits while loud bar 5 keeps all four: the envelope
        // only ever widens the spacing, never tightens it below the difficulty cap.
        assertEquals(2, countBar(gradient, 2))
        assertEquals(4, countBar(gradient, 5))
        assertEquals(4, countBar(flat, 2))
        assertEquals(4, countBar(flat, 5))
    }
}
