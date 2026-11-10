// SPDX-License-Identifier: GPL-3.0-or-later (Original work: ported from the author's china-only PixelPlayer fork)
package com.pisces312.piscesplayer.data.importer.poweramp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Poweramp tracks 行归一化规则的单元测试（poweramp-import-feature-plan §2.1 N1–N6）。
 * 纯 Kotlin 逻辑（Cursor/SQLite 读取部分不在本地单测范围内）。
 */
class PowerampTrackNormalizationTest {

    private fun row(
        path: String = "primary/Music/a.mp3",
        playlistId: Long? = null,
        rating: Int = 0,
        playedAt: Long = 0,
        playedTimes: Int = 0,
        cueOffsetMs: Int = 0,
        isLibraryRow: Boolean = true
    ) = TrackRow(
        path = path,
        playlistId = playlistId,
        readableName = null,
        rating = rating,
        playedAt = playedAt,
        playedFullyAt = 0,
        playCount = normalizePlayedTimes(playedAt, playedTimes),
        cueOffsetMs = cueOffsetMs,
        isLibraryRow = isLibraryRow
    )

    // ---- N3：played_at > 0 && played_times == 0 → playCount = 1（实测 424 行矛盾）----

    @Test
    fun `N3 played_at positive with zero played_times normalizes to 1`() {
        assertEquals(1, normalizePlayedTimes(playedAt = 1766075055093L, playedTimes = 0))
    }

    @Test
    fun `N3 normal played_times is kept`() {
        assertEquals(7, normalizePlayedTimes(playedAt = 1766075055093L, playedTimes = 7))
    }

    @Test
    fun `N3 never played stays zero`() {
        assertEquals(0, normalizePlayedTimes(playedAt = 0L, playedTimes = 0))
    }

    // ---- N1 / toRecord：评分与时间戳落地 ----

    @Test
    fun `toRecord clamps rating into 0-5`() {
        assertEquals(5, row(rating = 9).toRecord().rating)
        assertEquals(0, row(rating = 0).toRecord().rating)
    }

    @Test
    fun `toRecord maps zero playedAt to null`() {
        assertNull(row(playedAt = 0).toRecord().lastPlayedAt)
        assertEquals(123L, row(playedAt = 123, playedTimes = 1).toRecord().lastPlayedAt)
    }

    @Test
    fun `toRecord never fabricates duration (N2)`() {
        assertNull(row().toRecord().totalPlayDurationMs)
    }

    // ---- N6：曲库行判定（export_type 优先，playlist_id 回退）----

    @Test
    fun `N6 export_type 3 means library row`() {
        assertTrue(isLibraryRow(exportType = 3, playlistId = 12L))
        assertFalse(isLibraryRow(exportType = 1, playlistId = null))
    }

    @Test
    fun `N6 legacy without export_type falls back to playlist_id`() {
        assertTrue(isLibraryRow(exportType = -1, playlistId = null))
        assertFalse(isLibraryRow(exportType = -1, playlistId = 5L))
    }

    // ---- N4/N5：去重 ----

    @Test
    fun `N4 duplicate path prefers library row`() {
        val ref = row(playlistId = 3L, isLibraryRow = false)
        val lib = row(isLibraryRow = true)
        val dedup = dedupeTracks(listOf(ref, lib))
        assertEquals(1, dedup.size)
        assertTrue(dedup.values.first().isLibraryRow)
    }

    @Test
    fun `N4 library row is not overwritten by later reference row`() {
        val lib = row(isLibraryRow = true)
        val ref = row(playlistId = 3L, isLibraryRow = false)
        val dedup = dedupeTracks(listOf(lib, ref))
        assertEquals(1, dedup.size)
        assertTrue(dedup.values.first().isLibraryRow)
    }

    @Test
    fun `N5 cue offset splits same path into distinct keys`() {
        val a = row(cueOffsetMs = 0)
        val b = row(cueOffsetMs = 120_000)
        val dedup = dedupeTracks(listOf(a, b))
        assertEquals(2, dedup.size)
        assertTrue(dedup.containsKey("primary/Music/a.mp3#120000"))
    }
}
