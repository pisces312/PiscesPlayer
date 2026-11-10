/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §17).
 */
package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3 derivation pins one property above all others: **the master's timestamps are the only
 * truth**. These tests therefore assert subset-of-master-times, density caps and chord rules,
 * never "sits on a grid".
 */
class RhythmChartDeriverOnsetNativeTest {

    private fun master(
        times: List<Int>,
        strengths: List<Float> = times.map { 1f },
        bands: List<Int> = times.map { 0 },
        beatMs: Double = 500.0,
        offsetMs: Int = 0,
        durationMs: Int = 60_000,
    ): RhythmChart = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = "DSP Raw",
        timing = listOf(RhythmTimingPoint(offsetMs, beatMs)),
        notes = times.mapIndexed { index, time ->
            RhythmNote(
                id = index,
                timeMs = time,
                lane = bands[index].coerceIn(0, 3),
                strength = strengths[index],
                band = bands[index],
            )
        },
        durationMs = durationMs,
    )

    @Test
    fun derivedNotesAreAlwaysASubsetOfTheMaster() {
        val chart = master((0 until 400).map { 1_000 + it * 17 })
        val masterTimes = chart.notes.map { it.timeMs }.toSet()
        for (difficulty in DspDifficulty.entries) {
            for (chord in 1..RhythmChartDeriver.MAX_CHORD_NOTES) {
                val derived = RhythmChartDeriver.deriveOnsetNative(
                    chart,
                    difficulty,
                    maxChord = chord,
                )
                assertTrue(derived.noteCount() <= chart.noteCount())
                for (note in derived.notes) {
                    assertTrue(
                        "${difficulty.name}/$chord moved a note to ${note.timeMs}",
                        note.timeMs in masterTimes,
                    )
                }
            }
        }
    }

    @Test
    fun minGapSeparatesSuccessiveHits() {
        // A hit every 20 ms: without the gap constraint this is 50 notes/s.
        val chart = master((0 until 200).map { it * 20 })
        val profile = DspDeriveProfile(maxNotesPerSecond = 100.0, minGapMs = 300.0)
        val derived = RhythmChartDeriver.deriveOnsetNative(
            chart,
            DspDifficulty.EASY,
            maxChord = 1,
            profile = profile,
        )
        val times = derived.notes.map { it.timeMs }
        for (index in 1 until times.size) {
            assertTrue(
                "${times[index - 1]} -> ${times[index]} is closer than minGap",
                times[index] - times[index - 1] >= 300,
            )
        }
        assertTrue("expected more than 10 notes, got ${derived.noteCount()}", derived.noteCount() > 10)
    }

    @Test
    fun minGapAlsoSeparatesChords() {
        // Hit A (0 + 60) and hit B (200 + 240). The primaries are 240 ms apart, but A's last
        // note to B's first is only 140 ms, so a 200 ms gap can keep just one of the two hits.
        val chart = master(
            times = listOf(0, 60, 200, 240),
            strengths = listOf(0.3f, 0.2f, 0.4f, 0.95f),
            bands = listOf(0, 6, 3, 0),
        )
        val profile = DspDeriveProfile(maxNotesPerSecond = 100.0, minGapMs = 200.0)
        val derived = RhythmChartDeriver.deriveOnsetNative(
            chart,
            DspDifficulty.EASY,
            maxChord = 2,
            profile = profile,
        )
        assertEquals(listOf(200, 240), derived.notes.map { it.timeMs })
    }

    @Test
    fun everyOneSecondWindowRespectsTheNpsCap() {
        val chart = master((0 until 500).map { it * 20 }, durationMs = 20_000)
        val profile = DspDeriveProfile(maxNotesPerSecond = 2.0, minGapMs = 0.0)
        val derived = RhythmChartDeriver.deriveOnsetNative(
            chart,
            DspDifficulty.EASY,
            maxChord = 1,
            profile = profile,
        )
        val times = derived.notes.map { it.timeMs }.sorted()
        for (end in times) {
            val inWindow = times.count { it > end - 1000 && it <= end }
            assertTrue("window ending at $end holds $inWindow notes", inWindow <= 2)
        }
        assertTrue(derived.noteCount() >= 2)
    }

    @Test
    fun maxNotesKeepsTheStrongestOnsets() {
        val times = (0 until 100).map { it * 500 }
        val chart = master(times, strengths = times.indices.map { 0.1f + it * 0.009f })
        val profile = DspDeriveProfile(maxNotesPerSecond = 100.0, minGapMs = 0.0, maxNotes = 5)
        val derived = RhythmChartDeriver.deriveOnsetNative(
            chart,
            DspDifficulty.EASY,
            profile = profile,
        )
        assertEquals(5, derived.noteCount())
        assertEquals(
            listOf(47_500, 48_000, 48_500, 49_000, 49_500),
            derived.notes.map { it.timeMs },
        )
    }

    @Test
    fun chordsKeepTheirOwnTimesAndDistinctLanes() {
        val chart = master(
            times = listOf(1_000, 1_030, 1_060, 5_000),
            strengths = listOf(0.9f, 0.8f, 0.7f, 0.5f),
            bands = listOf(0, 3, 6, 0),
        )
        val derived = RhythmChartDeriver.deriveOnsetNative(
            chart,
            DspDifficulty.NORMAL,
            maxChord = 3,
        )
        val chord = derived.notes.filter { it.timeMs < 2_000 }
        assertEquals(listOf(1_000, 1_030, 1_060), chord.map { it.timeMs })
        assertEquals(3, chord.map { it.lane }.toSet().size)
    }

    @Test
    fun aWeakNeighbourIsNotPromotedIntoAChord() {
        val chart = master(
            times = listOf(1_000, 1_050),
            strengths = listOf(0.9f, 0.1f),
            bands = listOf(0, 6),
        )
        val derived = RhythmChartDeriver.deriveOnsetNative(
            chart,
            DspDifficulty.NORMAL,
            maxChord = 3,
        )
        assertEquals(1, derived.noteCount())
        assertEquals(1_000, derived.notes.single().timeMs)
    }

    @Test
    fun beatPriorBreaksNearTiesInFavourOfTheBeat() {
        // Off-beat 0.7 vs on-beat 0.6; minGap forces a choice, and the 25% accent prior wins.
        val chart = master(
            times = listOf(0, 250),
            strengths = listOf(0.6f, 0.7f),
            bands = listOf(0, 3),
        )
        val profile = DspDeriveProfile(maxNotesPerSecond = 10.0, minGapMs = 300.0)
        val derived = RhythmChartDeriver.deriveOnsetNative(
            chart,
            DspDifficulty.EASY,
            profile = profile,
        )
        assertEquals(listOf(0), derived.notes.map { it.timeMs })
    }

    @Test
    fun denserDifficultiesNeverKeepFewerNotesOnTheBeat() {
        // Every onset sits exactly on a 250 ms beat, so all scores tie and the DP maximises count.
        val chart = master(
            times = (0 until 100).map { it * 250 },
            beatMs = 250.0,
            durationMs = 25_000,
        )
        val counts = DspDifficulty.entries.map {
            RhythmChartDeriver.deriveOnsetNative(chart, it).noteCount()
        }
        assertTrue(
            "counts ${counts} are not monotonic",
            counts[0] <= counts[1] && counts[1] <= counts[2],
        )
        assertTrue(counts[2] <= chart.noteCount())
    }

    @Test
    fun adjacentHitsNeverShareALane() {
        val chart = master(listOf(1_000, 1_500, 2_000, 2_500), bands = listOf(0, 0, 0, 0))
        val derived = RhythmChartDeriver.deriveOnsetNative(chart, DspDifficulty.HARD)
        for (index in 1 until derived.notes.size) {
            assertNotEquals(
                "notes at ${derived.notes[index - 1].timeMs} and ${derived.notes[index].timeMs}",
                derived.notes[index - 1].lane,
                derived.notes[index].lane,
            )
        }
    }

    @Test
    fun derivationIsDeterministic() {
        val chart = master(
            times = (0 until 200).map { it * 37 },
            strengths = (0 until 200).map { ((it * 7) % 11) / 10f },
        )
        val first = RhythmChartDeriver.deriveOnsetNative(chart, DspDifficulty.NORMAL, maxChord = 2)
        val second = RhythmChartDeriver.deriveOnsetNative(chart, DspDifficulty.NORMAL, maxChord = 2)
        assertEquals(first.notes, second.notes)
    }

    @Test
    fun noTimestampIsEverMovedByAnOddOffset() {
        // A non-integer beat length and an odd phase: the v2 grid would round these.
        val chart = master(
            times = (0 until 120).map { 317 + it * 211 },
            beatMs = 463.7,
            offsetMs = 41,
        )
        val masterTimes = chart.notes.map { it.timeMs }.toSet()
        for (difficulty in DspDifficulty.entries) {
            val derived = RhythmChartDeriver.deriveOnsetNative(
                chart,
                difficulty,
                maxChord = 2,
            )
            for (note in derived.notes) {
                assertTrue(
                    "${difficulty.name}: ${note.timeMs} is not a master onset",
                    note.timeMs in masterTimes,
                )
            }
        }
    }

    @Test
    fun everyEmittedNoteHasAValidLane() {
        val chart = master(
            times = (0 until 200).map { it * 41 },
            bands = (0 until 200).map { it % 8 },
        )
        val derived = RhythmChartDeriver.deriveOnsetNative(
            chart,
            DspDifficulty.HARD,
            laneStrategy = LaneStrategy.LOW_OUTSIDE,
            maxChord = 3,
        )
        for (note in derived.notes) {
            assertTrue("lane ${note.lane} out of range", note.lane in 0..3)
        }
        assertEquals(derived.notes.size, derived.notes.map { it.id }.toSet().size)
    }
}
