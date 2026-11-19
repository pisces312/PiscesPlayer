package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmTapChartTest {

    private fun take(vararg hits: Pair<Double, String>, durationMs: Int = 60_000): RhythmTapTake =
        RhythmTapTake(
            audio = RhythmTapAudio(path = "song.mp3", durationMs = durationMs, sampleRate = 44100),
            hitCount = hits.size,
            hits = hits.map { RhythmTapHit(it.first, it.second) },
        )

    @Test
    fun keepsTappedLanesAndOrdersByTime() {
        val result = RhythmTapChart.toChart(
            take = take(2000.0 to "k", 1000.0 to "d", 1500.0 to "f"),
            difficultyName = "手打",
        )

        assertEquals(
            listOf(Triple(1000, 0, "d"), Triple(1500, 1, "f"), Triple(2000, 3, "k")),
            result.chart.notes.map { Triple(it.timeMs, it.lane, RhythmTapTake.keyFor(it.lane)) },
        )
        assertEquals(listOf(0, 1, 2), result.chart.notes.map { it.id })
    }

    @Test
    fun identityTimingWithOptionalOffset() {
        val hits = take(1196.4 to "d", 1548.3 to "j")

        val plain = RhythmTapChart.toChart(hits, "手打")
        val shifted = RhythmTapChart.toChart(hits, "手打", offsetMs = -96.4)

        // Same audio-clock domain as recording: t becomes timeMs unchanged (rounded to Int).
        assertEquals(1196, plain.chart.notes[0].timeMs)
        assertEquals(1548, plain.chart.notes[1].timeMs)
        // The constant fine-tune shifts every note by the same amount.
        assertEquals(1100, shifted.chart.notes[0].timeMs)
        assertEquals(1452, shifted.chart.notes[1].timeMs)
    }

    @Test
    fun mergedDoubleTapsKeepTheEarlierHit() {
        // 10 ms apart on the same lane: one debounced double-tap.
        val result = RhythmTapChart.toChart(
            take = take(1000.0 to "d", 1010.0 to "d", 1400.0 to "d"),
            difficultyName = "手打",
        )

        assertEquals(listOf(1000, 1400), result.chart.notes.map { it.timeMs })
        assertEquals(1, result.warnings.mergedCount)
    }

    @Test
    fun sameLaneHitsInDifferentLanesAreNotMerged() {
        val result = RhythmTapChart.toChart(
            take = take(1000.0 to "d", 1000.0 to "f", 1010.0 to "j"),
            difficultyName = "手打",
        )

        assertEquals(3, result.chart.notes.size)
        assertEquals(0, result.warnings.mergedCount)
    }

    @Test
    fun tightSameLanePairsAreWarnedNotDeleted() {
        // 100 ms apart on the same lane: below the 140 ms good window but well above the merge
        // window — the player tapped twice on purpose, so both notes stay.
        val result = RhythmTapChart.toChart(
            take = take(1000.0 to "d", 1100.0 to "d"),
            difficultyName = "手打",
        )

        assertEquals(2, result.chart.notes.size)
        assertEquals(listOf(1000 to 1100), result.warnings.closePairs)
    }

    @Test
    fun leadInNotesAreStoredButCounted() {
        val result = RhythmTapChart.toChart(
            take = take(500.0 to "d", 900.0 to "f", 1500.0 to "j"),
            difficultyName = "手打",
        )

        assertEquals(3, result.chart.notes.size)
        assertEquals(2, result.warnings.leadInCount)
    }

    @Test
    fun unknownKeyFallsBackToRoundRobinLane() {
        val result = RhythmTapChart.toChart(
            take = take(1000.0 to "", 1500.0 to "z", 2000.0 to ""),
            difficultyName = "手打",
        )

        assertEquals(listOf(0, 1, 2), result.chart.notes.map { it.lane })
    }

    @Test
    fun chartMetadataIsSane() {
        val result = RhythmTapChart.toChart(
            take = take(1000.0 to "d", 1500.0 to "f", 2000.0 to "j", durationMs = 30_000),
            difficultyName = "手打",
        )

        val chart = result.chart
        assertEquals(RhythmChart.SOURCE_TAP, chart.source)
        assertEquals("手打", chart.difficultyName)
        assertEquals(30_000, chart.durationMs)
        assertEquals(0.1, chart.averageNps(), 1e-9) // 3 notes over 30 s
        // Tap charts carry no DSP metadata, same as osu imports.
        assertTrue(chart.notes.all { it.strength == 0f && it.band == -1 && it.rms == 0f })
        assertTrue(chart.timing.isEmpty())
        assertTrue(chart.timingVariants.isEmpty())
    }

    @Test
    fun emptyTakeIsRejected() {
        val error = runCatching {
            RhythmTapChart.toChart(take(), "手打")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
