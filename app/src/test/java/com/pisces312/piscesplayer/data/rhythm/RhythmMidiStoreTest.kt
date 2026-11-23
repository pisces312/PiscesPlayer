/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (MIDI charts round-trip through the file-backed library).
 */
package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RhythmMidiStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun midiChart(name: String, times: List<Int>) = RhythmChart(
        source = RhythmChart.SOURCE_MIDI,
        difficultyName = name,
        timing = listOf(RhythmTimingPoint(0, 500.0)),
        notes = times.mapIndexed { index, time -> RhythmNote(index, time, index % 4) },
        durationMs = 60_000,
    )

    @Test
    fun `midi charts import read back and re-import idempotently`() {
        val store = RhythmLibraryStore(temp.newFolder("rhythm"))
        val charts = listOf(
            midiChart("Piano · 1/1", listOf(1000, 1500, 2000)),
            midiChart("Piano · 1/4", listOf(1000, 1125, 1250)),
        )

        val metas = charts.mapIndexed { index, chart ->
            store.importChart(songId = "song-midi", chart = chart, nowMs = 100L + index)
        }

        assertEquals(2, store.chartsForSong("song-midi").size)
        metas.forEach { meta ->
            assertEquals(RhythmChart.SOURCE_MIDI, meta.source)
            val read = store.readChart(meta.chartId)
            assertNotNull(read)
            assertEquals(meta.noteCount, read!!.notes.size)
            assertEquals(meta.difficultyName, read.difficultyName)
        }

        // Re-importing identical content re-binds instead of piling up copies.
        store.importChart(songId = "song-midi", chart = charts[0], nowMs = 300)
        assertEquals(2, store.chartsForSong("song-midi").size)
    }

    @Test
    fun `midi and osu charts coexist for one song`() {
        val store = RhythmLibraryStore(temp.newFolder("rhythm"))
        store.importChart(songId = "song-1", chart = midiChart("Piano · 1/1", listOf(1000)), nowMs = 100)
        store.importText(
            songId = "song-1",
            text = "osu file format v14\n" +
                "[General]\nMode: 3\n" +
                "[Metadata]\nVersion: Normal\n" +
                "[Difficulty]\nCircleSize: 4\n" +
                "[TimingPoints]\n0,500,4,2,0,60,1,0\n" +
                "[HitObjects]\n0,192,1000,1,0,0:0:0:0:\n",
            nowMs = 200,
        )

        val sources = store.chartsForSong("song-1").map { it.source }.toSet()

        assertEquals(setOf(RhythmChart.SOURCE_MIDI, RhythmChart.SOURCE_OSU), sources)
    }
}
