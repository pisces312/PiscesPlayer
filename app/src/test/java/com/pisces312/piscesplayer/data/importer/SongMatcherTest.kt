// SPDX-License-Identifier: GPL-3.0-or-later (Original work: ported from the author's china-only PixelPlayer fork)
package com.pisces312.piscesplayer.data.importer

import com.pisces312.piscesplayer.data.database.ImportSongProjection
import com.pisces312.piscesplayer.data.importer.poweramp.PowerampPathNormalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * SongMatcher 三级匹配单元测试（poweramp-import-feature-plan §3）。
 * 覆盖：绝对路径 / 文件名（裸文件名）/ 元数据双向试探 / 歧义放弃（宁缺勿错）。
 */
class SongMatcherTest {

    private val normalizer = PowerampPathNormalizer(
        mapOf(
            "primary" to "/storage/emulated/0",
            "9c33-6bbd" to "/storage/9C33-6BBD"
        )
    )

    private fun song(
        id: Long,
        filePath: String,
        title: String,
        artist: String,
        album: String = "album",
        duration: Long = 200_000
    ) = ImportSongProjection(
        id = id, filePath = filePath, title = title,
        artistName = artist, albumName = album, duration = duration
    )

    private val songs = listOf(
        song(1, "/storage/emulated/0/Music4Phone/02 - 煎熬.mp3", "煎熬", "李佳薇", "天堂/悬崖"),
        song(2, "/storage/emulated/0/Music4Phone/track07.flac", "宫保鸡丁", "陶喆"),
        song(3, "/storage/emulated/0/Music4Phone/track08.mp3", "キセキ", "GReeeeN"),
        song(4, "/storage/9C33-6BBD/Music/sd_song.mp3", "SD Song", "Artist A"),
        song(5, "/storage/emulated/0/Download/01 - 彩云追月.mp3", "彩云追月", "Various Artists"),
        song(6, "/storage/emulated/0/Music/a_frontier.mp3", "Frontier", "Artist X"),
        song(7, "/storage/emulated/0/Music/b_frontier.mp3", "Frontier", "Artist Y")
    )

    private val matcher = SongMatcher(normalizer, songs)

    private fun record(path: String, titleHint: String? = null) = ImportSongRecord(
        path = path,
        titleHint = titleHint,
        artistHint = null,
        albumHint = null,
        rating = 0,
        playCount = 0,
        lastPlayedAt = null,
        playedFullyAt = null,
        totalPlayDurationMs = null
    )

    // ---- 第 1 级：绝对路径精确匹配 ----

    @Test
    fun `level 1 absolute path match on primary volume`() {
        assertEquals(1L, matcher.match(record("primary/Music4Phone/02 - 煎熬.mp3"))?.id)
    }

    @Test
    fun `level 1 absolute path match on sd card volume`() {
        assertEquals(4L, matcher.match(record("9C33-6BBD/Music/sd_song.mp3"))?.id)
    }

    // ---- 第 2 级：文件名匹配 ----

    @Test
    fun `level 2 bare file name matches by file name`() {
        // 列表独有歌曲：无卷前缀裸文件名（实测 2 首）
        assertEquals(5L, matcher.match(record("01 - 彩云追月.mp3"))?.id)
    }

    @Test
    fun `level 2 tolerates storage root differences`() {
        // 同文件名但本地路径根不同（换目录后仍能命中）
        assertEquals(5L, matcher.match(record("primary/Moved/01 - 彩云追月.mp3"))?.id)
    }

    // ---- 第 3 级：元数据匹配（双向试探）----

    @Test
    fun `level 3 matches title-artist order (歌名 - 歌手)`() {
        // 本地文件已改名（track07.flac），只能靠元数据；文件名「宫保鸡丁 - 陶喆」
        val hit = matcher.match(record("primary/Music4Phone/宫保鸡丁 - 陶喆.flac", titleHint = "宫保鸡丁"))
        assertEquals(2L, hit?.id)
    }

    @Test
    fun `level 3 matches artist-title order (歌手 - 歌名)`() {
        // 「GReeeeN - キセキ」为歌手在前，必须双向试探（§3.2.2）
        val hit = matcher.match(record("primary/Music4Phone/GReeeeN - キセキ.mp3", titleHint = "キセキ"))
        assertEquals(3L, hit?.id)
    }

    @Test
    fun `level 3 readable_name alone hits unique title`() {
        // 文件名完全无法解析时，readable_name（96%+ 为纯标题）兜底
        val hit = matcher.match(record("primary/Music4Phone/zzz_unknown.mp3", titleHint = "煎熬"))
        assertEquals(1L, hit?.id)
    }

    // ---- 第 4 级：无法匹配 / 歧义放弃 ----

    @Test
    fun `ambiguous title without disambiguation hints returns null`() {
        // 两首同名 Frontier、不同歌手，无目录信号可消歧 → 宁缺勿错
        assertNull(matcher.match(record("primary/Music/Frontier.mp3", titleHint = "Frontier")))
    }

    @Test
    fun `completely unknown song returns null`() {
        assertNull(matcher.match(record("primary/Music/不存在的歌 - 某人.mp3", titleHint = "不存在的歌")))
    }
}
