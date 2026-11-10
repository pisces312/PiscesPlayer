package com.pisces312.piscesplayer.data.rhythm

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deriver is what makes one master chart feed every difficulty, so these tests pin the two
 * properties that matter: a difficulty is a strict thinning of the master, and thinning it the
 * same way the generator would has to give the same notes.
 */
class RhythmChartDeriverTest {

    private val beatMs = 500.0 // 120 BPM
    private val offsetMs = 20

    private fun master(
        times: List<Int>,
        strengths: List<Float> = times.map { 1f },
        bands: List<Int> = times.map { 0 },
        durationMs: Int = 20_000,
    ): RhythmChart = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = "DSP Raw",
        timing = listOf(RhythmTimingPoint(offsetMs, beatMs)),
        notes = times.mapIndexed { index, time ->
            RhythmNote(index, time, bands[index].coerceIn(0, 3), strengths[index], bands[index])
        },
        durationMs = durationMs,
    )

    @Test
    fun easyKeepsOneNotePerBeat() {
        // Four onsets well inside one beat cell (grid = 500 ms at 120 BPM).
        val times = listOf(offsetMs, offsetMs + 50, offsetMs + 100, offsetMs + 150)
        val derived = RhythmChartDeriver.derive(master(times), DspDifficulty.EASY)
        assertEquals(1, derived.noteCount())
        assertEquals(offsetMs, derived.notes.first().timeMs)
    }

    @Test
    fun keepsTheStrongestOnsetInEachCell() {
        val times = listOf(offsetMs, offsetMs + 60, offsetMs + 120)
        val strengths = listOf(0.2f, 0.9f, 0.4f)
        val derived = RhythmChartDeriver.derive(master(times, strengths), DspDifficulty.EASY)

        // A difficulty is quantized, so the survivor is re-emitted at the cell time — what it
        // keeps from the master is the strength, which is how we tell which onset won.
        assertEquals(1, derived.noteCount())
        assertEquals(offsetMs, derived.notes.single().timeMs)
        assertEquals(0.9f, derived.notes.single().strength, 0f)
    }

    @Test
    fun tiesKeepTheEarlierOnset() {
        // Equal strength: the tie-break keeps the first onset, which we can observe through the
        // lane derived from its band (band 0 -> lane 0, band 3 -> lane 1).
        val times = listOf(offsetMs, offsetMs + 60)
        val derived = RhythmChartDeriver.derive(
            master(times, listOf(0.5f, 0.5f), bands = listOf(0, 3)),
            DspDifficulty.EASY,
        )
        assertEquals(1, derived.noteCount())
        assertEquals(0, derived.notes.single().lane)
    }

    @Test
    fun difficultiesAreNestedAndMonotonic() {
        // 16th-note grid over four beats: 16 onsets.
        val times = (0 until 16).map { offsetMs + it * (beatMs / 4).toInt() }
        val chart = master(times)

        val easy = RhythmChartDeriver.derive(chart, DspDifficulty.EASY)
        val normal = RhythmChartDeriver.derive(chart, DspDifficulty.NORMAL)
        val hard = RhythmChartDeriver.derive(chart, DspDifficulty.HARD)

        assertTrue("${easy.noteCount()} !<= ${normal.noteCount()}", easy.noteCount() <= normal.noteCount())
        assertTrue("${normal.noteCount()} !<= ${hard.noteCount()}", normal.noteCount() <= hard.noteCount())
        assertTrue(hard.noteCount() <= chart.noteCount())

        // Every derived note must come from some master onset in its cell (|Δt| <= half a grid
        // step, and the coarsest step is Easy = one beat), because derived notes are re-emitted
        // at the quantized cell time rather than at the raw onset time.
        val masterTimes = chart.notes.map { it.timeMs }
        val halfEasyCell = beatMs / 2
        for (note in hard.notes + normal.notes + easy.notes) {
            assertTrue(
                "derived ${note.timeMs} has no master onset nearby",
                masterTimes.any { abs(it - note.timeMs) <= halfEasyCell },
            )
        }
    }

    @Test
    fun everyDerivedNoteSitsOnTheGrid() {
        val times = (0 until 32).map { offsetMs + it * 31 }
        val chart = master(times)
        for (difficulty in DspDifficulty.entries) {
            val grid = beatMs / difficulty.gridDiv
            for (note in RhythmChartDeriver.derive(chart, difficulty).notes) {
                val nearest = offsetMs + Math.round((note.timeMs - offsetMs) / grid) * grid
                assertTrue(
                    "${difficulty.name}: ${note.timeMs} off grid ($nearest)",
                    abs(note.timeMs - nearest) <= 1.0,
                )
            }
        }
    }

    @Test
    fun oneCellNeverHoldsTwoNotes() {
        val times = (0 until 32).map { offsetMs + it * 31 }
        val chart = master(times)
        for (difficulty in DspDifficulty.entries) {
            val derived = RhythmChartDeriver.derive(chart, difficulty)
            val grid = beatMs / difficulty.gridDiv
            val cells = derived.notes.map { Math.round((it.timeMs - offsetMs) / grid) }
            assertEquals(
                "${difficulty.name} produced duplicate cells",
                cells.size,
                cells.distinct().size,
            )
        }
    }

    @Test
    fun adjacentNotesNeverShareALane() {
        // Both onsets sit on the same band, so they map to the same lane. Without re-running
        // the de-duplication on the thinned sequence, the derived chart would show two notes
        // on lane 1 in a row — which reads on screen as one long note.
        val times = listOf(offsetMs, offsetMs + beatMs.toInt())
        val chart = master(times, bands = listOf(2, 2))
        val derived = RhythmChartDeriver.derive(chart, DspDifficulty.EASY)

        assertEquals(2, derived.noteCount())
        assertNotEquals(
            "consecutive lanes repeat in the derived chart",
            derived.notes[0].lane,
            derived.notes[1].lane,
        )
    }

    @Test
    fun laneStrategyChangesLanesWithoutChangingNotes() {
        val times = (0 until 16).map { offsetMs + it * 125 }
        val chart = master(times, bands = (0 until 16).map { it % 8 })
        val bandOrder = RhythmChartDeriver.derive(chart, DspDifficulty.NORMAL, LaneStrategy.BAND_ORDER)
        val lowOutside = RhythmChartDeriver.derive(chart, DspDifficulty.NORMAL, LaneStrategy.LOW_OUTSIDE)

        assertEquals(bandOrder.notes.map { it.timeMs }, lowOutside.notes.map { it.timeMs })
        assertNotEquals(
            bandOrder.notes.map { it.lane },
            lowOutside.notes.map { it.lane },
        )
    }

    @Test
    fun chartsWithoutBandDataKeepTheirLane() {
        val note = RhythmNote(0, offsetMs, 3, strength = 0f, band = -1)
        val chart = RhythmChart(
            source = RhythmChart.SOURCE_DSP,
            difficultyName = "DSP Raw",
            timing = listOf(RhythmTimingPoint(offsetMs, beatMs)),
            notes = listOf(note),
            durationMs = 1000,
        )
        val derived = RhythmChartDeriver.derive(chart, DspDifficulty.EASY)
        assertEquals(3, derived.notes.single().lane)
    }

    @Test
    fun singleNoteMasterStillDerives() {
        val chart = master(listOf(1234))
        for (difficulty in DspDifficulty.entries) {
            assertEquals(1, RhythmChartDeriver.derive(chart, difficulty).noteCount())
        }
    }

    @Test
    fun derivationIsDeterministic() {
        val times = (0 until 16).map { offsetMs + it * 125 }
        val chart = master(times, bands = (0 until 16).map { it % 8 })
        assertEquals(
            RhythmChartDeriver.derive(chart, DspDifficulty.HARD),
            RhythmChartDeriver.derive(chart, DspDifficulty.HARD),
        )
    }

    @Test
    fun derivedChartKeepsSourceAndTimingOfMaster() {
        val chart = master((0 until 8).map { offsetMs + it * 250 })
        val derived = RhythmChartDeriver.derive(chart, DspDifficulty.NORMAL)
        assertEquals(chart.source, derived.source)
        assertEquals(chart.timing, derived.timing)
        assertEquals(chart.durationMs, derived.durationMs)
        assertEquals(DspDifficulty.NORMAL.label, derived.difficultyName)
    }

    @Test
    fun derivingFromTheMasterMatchesTheDirectRender() {
        // The real equivalence claim: thinning the master must reproduce what the generator
        // produces when it snaps the same onsets directly. Only rounding to whole milliseconds
        // separates the two paths, hence the 1 ms tolerance.
        val audio = RhythmTestAudio.drumLoop(20.0, 44100)
        val analysis = RhythmChartGenerator.analyze(
            pcmMono = audio,
            sampleRate = 44100,
            durationMs = 20_000,
            params = RhythmAnalysisParams(),
        )
        val master = RhythmChartGenerator.renderMaster(analysis, LaneStrategy.BAND_ORDER)

        for (maxChord in 1..RhythmChartDeriver.MAX_CHORD_NOTES) {
            for (difficulty in DspDifficulty.entries) {
                val direct = RhythmChartGenerator.render(
                    analysis,
                    difficulty,
                    LaneStrategy.BAND_ORDER,
                    maxChord,
                )
                val derived = RhythmChartDeriver.derive(
                    master,
                    difficulty,
                    LaneStrategy.BAND_ORDER,
                    maxChord,
                )

                assertEquals(
                    "${difficulty.name}/$maxChord: note count ${derived.noteCount()} vs ${direct.noteCount()}",
                    direct.noteCount(),
                    derived.noteCount(),
                )
                for (index in direct.notes.indices) {
                    val expected = direct.notes[index]
                    val actual = derived.notes[index]
                    assertTrue(
                        "${difficulty.name}/$maxChord #$index: ${actual.timeMs} vs ${expected.timeMs}",
                        abs(actual.timeMs - expected.timeMs) <= 1,
                    )
                    assertEquals("${difficulty.name}/$maxChord #$index lane", expected.lane, actual.lane)
                }
            }
        }
    }

    // --- chords -----------------------------------------------------------------------------

    @Test
    fun aCellCanHoldAChord() {
        // Three onsets inside one beat cell and within the chord window (70 ms) of each other,
        // on three separated bands, so three lanes.
        val chart = master(
            times = listOf(offsetMs, offsetMs + 30, offsetMs + 60),
            strengths = listOf(0.9f, 0.5f, 0.3f),
            bands = listOf(0, 2, 4),
        )

        val derived = RhythmChartDeriver.derive(chart, DspDifficulty.EASY, maxChord = 3)

        assertEquals(3, derived.noteCount())
        assertTrue("chord notes drifted off the cell", derived.notes.all { it.timeMs == offsetMs })
        assertEquals(listOf(0, 1, 2), derived.notes.map { it.lane })
    }

    @Test
    fun chordStopsAtTheRequestedSize() {
        val chart = master(
            times = listOf(offsetMs, offsetMs + 30, offsetMs + 60),
            strengths = listOf(0.9f, 0.5f, 0.3f),
            bands = listOf(0, 2, 4),
        )

        // Strongest two win: band 0 -> lane 0, band 2 -> lane 1.
        val derived = RhythmChartDeriver.derive(chart, DspDifficulty.EASY, maxChord = 2)
        assertEquals(2, derived.noteCount())
        assertEquals(listOf(0, 1), derived.notes.map { it.lane })
    }

    @Test
    fun chordsAreOffByDefault() {
        val chart = master(
            times = listOf(offsetMs, offsetMs + 30, offsetMs + 60),
            strengths = listOf(0.9f, 0.5f, 0.3f),
            bands = listOf(0, 2, 4),
        )
        assertEquals(1, RhythmChartDeriver.derive(chart, DspDifficulty.EASY).noteCount())
    }

    @Test
    fun chordNotesNeverShareALane() {
        // All three onsets live in the same band, so all three map to the same lane and only the
        // strongest can survive: two notes on one lane at the same instant is not a chord.
        val chart = master(
            times = listOf(offsetMs, offsetMs + 30, offsetMs + 60),
            strengths = listOf(0.9f, 0.8f, 0.7f),
            bands = listOf(2, 2, 2),
        )

        val derived = RhythmChartDeriver.derive(chart, DspDifficulty.EASY, maxChord = 3)
        assertEquals(1, derived.noteCount())
    }

    @Test
    fun weakOnsetsDoNotBecomeChordNotes() {
        // 0.1 is below the noise floor relative to 1.0, so the quiet blip stays dropped.
        val chart = master(
            times = listOf(offsetMs, offsetMs + 40),
            strengths = listOf(1f, 0.1f),
            bands = listOf(0, 4),
        )
        assertEquals(
            1,
            RhythmChartDeriver.derive(chart, DspDifficulty.EASY, maxChord = 3).noteCount(),
        )
    }

    @Test
    fun chordsNeverMakeAChartLongerThanTheMaster() {
        // Chord notes can only recover onsets the grid would otherwise drop; they never invent
        // one. This is what keeps the ladder ordered once chords are switched on.
        val chart = master(
            times = (0 until 32).map { offsetMs + it * 31 },
            bands = (0 until 32).map { it % 8 },
        )

        for (maxChord in 1..RhythmChartDeriver.MAX_CHORD_NOTES) {
            for (difficulty in DspDifficulty.entries) {
                val derived = RhythmChartDeriver.derive(chart, difficulty, maxChord = maxChord)
                assertTrue(
                    "${difficulty.name}/$maxChord produced ${derived.noteCount()} > master ${chart.noteCount()}",
                    derived.noteCount() <= chart.noteCount(),
                )
            }
        }
    }

    @Test
    fun ladderStaysOrderedWithChords() {
        val chart = master(
            times = (0 until 32).map { offsetMs + it * 31 },
            bands = (0 until 32).map { it % 8 },
        )

        for (maxChord in 1..RhythmChartDeriver.MAX_CHORD_NOTES) {
            val counts = DspDifficulty.entries.map {
                RhythmChartDeriver.derive(chart, it, maxChord = maxChord).noteCount()
            }
            // DspDifficulty is declared easiest first.
            assertTrue("$maxChord: $counts", counts.zipWithNext().all { (a, b) -> a <= b })
        }
    }

    @Test
    fun piecewiseTimingSnapsToTheLocalGrid() {
        // Eight 500 ms beats, then a slower segment. 4490 ms is 10 ms past the local line at
        // 4000 + 480; a global 500 ms grid would have moved the note to 4500 instead.
        val chart = master(times = listOf(4_490)).copy(
            timing = listOf(
                RhythmTimingPoint(0, 500.0),
                RhythmTimingPoint(4_000, 480.0),
            ),
        )

        val derived = RhythmChartDeriver.derive(chart, DspDifficulty.EASY)
        assertEquals(4_480, derived.notes.single().timeMs)
    }

    @Test
    fun boundaryBeatIsEmittedOnceWithPiecewiseTiming() {
        // 3990 belongs to the first segment, 4010 to the second; both snap onto the shared
        // anchor at 4000 and must collapse into one cell.
        val chart = master(
            times = listOf(3_990, 4_010),
            strengths = listOf(1f, 0.4f),
        ).copy(
            timing = listOf(
                RhythmTimingPoint(0, 500.0),
                RhythmTimingPoint(4_000, 480.0),
            ),
        )

        val derived = RhythmChartDeriver.derive(chart, DspDifficulty.EASY)
        assertEquals(1, derived.noteCount())
        assertEquals(4_000, derived.notes.single().timeMs)
        assertEquals(1f, derived.notes.single().strength, 0f)
    }

    @Test
    fun melodyFirstReservesSlotsForMelodyBands() {
        // Per second: three strong drum onsets (band 0) and two weak melody-band onsets (band 3).
        // The balanced NORMAL budget (4/s) spends its slots on the strong drums and drops one
        // melody onset; melody-first runs the pools separately and keeps both melody onsets.
        val times = (0 until 4).flatMap { second ->
            listOf(0, 200, 400, 600, 800).map { second * 1000 + offsetMs + it }
        }
        val bands = times.mapIndexed { index, _ -> if (index % 5 < 3) 0 else 3 }
        val strengths = times.mapIndexed { index, _ -> if (index % 5 < 3) 1f else 0.2f }
        val chart = master(times, strengths, bands)

        val balanced = RhythmChartDeriver.deriveOnsetNative(chart, DspDifficulty.NORMAL)
        val melodyFirst = RhythmChartDeriver.deriveOnsetNative(
            chart,
            DspDifficulty.NORMAL,
            selection = OnsetSelection.MELODY_FIRST,
        )

        val melodyBands = 2..5
        val balancedMelody = balanced.notes.count { it.band in melodyBands }
        val melodyFirstMelody = melodyFirst.notes.count { it.band in melodyBands }
        assertTrue("melody-first $melodyFirstMelody vs balanced $balancedMelody",
            melodyFirstMelody > balancedMelody)
    }
}
