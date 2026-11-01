package com.lostf1sh.pixelplayeross.data.rhythm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RhythmLibraryStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun root(): File = temp.newFolder("rhythm")

    private fun osu(version: String = "Normal", times: List<Int> = listOf(1000, 1500, 2000)): String =
        buildString {
            appendLine("osu file format v14")
            appendLine("[General]")
            appendLine("Mode: 3")
            appendLine("[Metadata]")
            appendLine("Version: $version")
            appendLine("[Difficulty]")
            appendLine("CircleSize: 4")
            appendLine("[TimingPoints]")
            appendLine("0,500,4,2,0,60,1,0")
            appendLine("[HitObjects]")
            times.forEachIndexed { index, time ->
                appendLine("${index % 4 * 128},192,$time,1,0,0:0:0:0:")
            }
        }

    @Test
    fun reimportIsIdempotentAndKeepsBestScore() {
        val store = RhythmLibraryStore(root())
        val first = store.importText(songId = "song-a", text = osu(), nowMs = 100)
        store.recordScore(first.chartId, 500_000)

        val second = store.importText(songId = "song-a", text = osu(), nowMs = 200)

        assertEquals(first.chartId, second.chartId)
        assertEquals(1, store.chartsForSong("song-a").size)
        assertEquals(500_000, store.chartsForSong("song-a").single().bestScore)
        assertNotNull(store.readChart(first.chartId))
    }

    @Test
    fun sameChartBoundToTwoSongsSharesContentFile() {
        val store = RhythmLibraryStore(root())
        val a = store.importText(songId = "song-a", text = osu(), nowMs = 100)
        val b = store.importText(songId = "song-b", text = osu(), nowMs = 200)

        assertEquals(a.chartId, b.chartId)
        assertEquals(1, store.chartsForSong("song-a").size)
        assertEquals(1, store.chartsForSong("song-b").size)

        store.delete(songId = "song-a", chartId = a.chartId)

        assertTrue(store.chartsForSong("song-a").isEmpty())
        // Still bound to song-b, so the chart body must survive.
        assertNotNull(store.readChart(a.chartId))
    }

    @Test
    fun bestScoreOnlyIncreases() {
        val store = RhythmLibraryStore(root())
        val meta = store.importText(songId = "song-a", text = osu(), nowMs = 100)

        assertTrue(store.recordScore(meta.chartId, 400_000))
        assertFalse(store.recordScore(meta.chartId, 400_000))
        assertFalse(store.recordScore(meta.chartId, 399_999))
        assertTrue(store.recordScore(meta.chartId, 600_000))
        assertFalse(store.recordScore("missing-chart", 999_999))

        assertEquals(600_000, store.chartsForSong("song-a").single().bestScore)
    }

    @Test
    fun deleteRemovesMetaAndChartBody() {
        val store = RhythmLibraryStore(root())
        val meta = store.importText(songId = "song-a", text = osu(), nowMs = 100)

        assertTrue(store.delete(songId = "song-a", chartId = meta.chartId))

        assertTrue(store.chartsForSong("song-a").isEmpty())
        assertNull(store.readChart(meta.chartId))
        assertFalse(store.delete(songId = "song-a", chartId = meta.chartId))
    }

    @Test
    fun corruptIndexDoesNotBreakImport() {
        val root = root()
        File(root, "library.json").writeText("{ this is not json")
        val store = RhythmLibraryStore(root)

        assertTrue(store.chartsForSong("song-a").isEmpty())
        val meta = store.importText(songId = "song-a", text = osu(), nowMs = 100)

        assertEquals(1, store.chartsForSong("song-a").size)
        assertEquals(meta.chartId, store.chartsForSong("song-a").single().chartId)
    }

    @Test
    fun rejectsNonFourKeyChartWithoutWritingAnything() {
        val root = root()
        val store = RhythmLibraryStore(root)

        val failure = runCatching {
            store.importText(songId = "song-a", text = osu().replace("CircleSize: 4", "CircleSize: 5"))
        }.exceptionOrNull()

        assertTrue(failure is OsuParseException)
        assertTrue(store.chartsForSong("song-a").isEmpty())
        assertFalse(File(root, "charts").listFiles().orEmpty().isNotEmpty())
    }

    private fun dspChart(
        name: String,
        times: List<Int> = listOf(500, 1000, 1500),
    ): RhythmChart = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = name,
        timing = listOf(RhythmTimingPoint(0, 500.0)),
        notes = times.mapIndexed { index, time -> RhythmNote(index, time, index % 4) },
        durationMs = 2000,
    )

    @Test
    fun importChartIsIdempotentForIdenticalContent() {
        val store = RhythmLibraryStore(root())

        val first = store.importChart("song-a", dspChart("DSP Easy · 19:01"), nowMs = 100)
        store.recordScore(first.chartId, 777)
        val second = store.importChart("song-a", dspChart("DSP Easy · 19:01"), nowMs = 200)

        assertEquals(first.chartId, second.chartId)
        assertEquals(1, store.chartsForSong("song-a").size)
        assertEquals(777, store.chartsForSong("song-a").single().bestScore)
    }

    @Test
    fun importChartKeepsEveryGeneration() {
        val store = RhythmLibraryStore(root())

        store.importChart("song-a", dspChart("DSP Easy · 19:01"), nowMs = 100)
        store.importChart("song-a", dspChart("DSP Easy · 19:02"), nowMs = 200)
        store.importChart("song-a", dspChart("DSP Normal · 19:02"), nowMs = 300)

        val charts = store.chartsForSong("song-a")
        assertEquals(3, charts.size)
        assertEquals(3, charts.map { it.chartId }.distinct().size)
        assertTrue(charts.all { it.source == RhythmChart.SOURCE_DSP })
    }

    @Test
    fun importChartRejectsOsuSource() {
        val store = RhythmLibraryStore(root())
        val osuShaped = dspChart("DSP Easy").copy(source = RhythmChart.SOURCE_OSU)

        val failure = runCatching { store.importChart("song-a", osuShaped) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(store.chartsForSong("song-a").isEmpty())
    }

    @Test
    fun importChartDoesNotDisturbImportedOsuChart() {
        val store = RhythmLibraryStore(root())
        val imported = store.importText("song-a", osu(), nowMs = 100)
        store.recordScore(imported.chartId, 123_456)

        store.importChart("song-a", dspChart("DSP Easy · 19:01"), nowMs = 200)

        val charts = store.chartsForSong("song-a")
        assertEquals(2, charts.size)
        val stillThere = charts.first { it.chartId == imported.chartId }
        assertEquals(123_456, stillThere.bestScore)
        assertEquals("Normal", stillThere.difficultyName)
        assertNotNull(store.readChart(imported.chartId))
    }
}
