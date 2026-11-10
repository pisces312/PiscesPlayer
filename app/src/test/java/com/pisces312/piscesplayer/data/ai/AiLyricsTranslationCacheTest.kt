/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: keying and eviction tests for the AI lyrics translation cache.
 */
package com.pisces312.piscesplayer.data.ai

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AiLyricsTranslationCacheTest {

    @TempDir lateinit var directory: File

    @Test
    fun `round trips one answer`() = runTest {
        val cache = AiLyricsTranslationCache(directory)
        val key = AiLyricsTranslationCache.keyFor(
            songId = "42",
            languageTag = "zh-CN",
            modelLabel = "MIMO/mimo-v2.5",
            source = "Title\nArtist\nfirst line"
        )

        cache.write(key, "译文")

        assertThat(cache.read(key)).isEqualTo("译文")
    }

    @Test
    fun `misses when the lyrics or the target language change`() = runTest {
        val cache = AiLyricsTranslationCache(directory)
        val original = AiLyricsTranslationCache.keyFor("42", "zh-CN", "MIMO/m", "Title\nArtist\nline")
        cache.write(original, "译文")

        val newLyrics = AiLyricsTranslationCache.keyFor("42", "zh-CN", "MIMO/m", "Title\nArtist\nline 2")
        val newLanguage = AiLyricsTranslationCache.keyFor("42", "en-US", "MIMO/m", "Title\nArtist\nline")
        val newModel = AiLyricsTranslationCache.keyFor("42", "zh-CN", "OTHER/m", "Title\nArtist\nline")

        assertThat(cache.read(newLyrics)).isNull()
        assertThat(cache.read(newLanguage)).isNull()
        assertThat(cache.read(newModel)).isNull()
        assertThat(cache.read(original)).isEqualTo("译文")
    }

    @Test
    fun `evicts the oldest entries beyond the bound`() = runTest {
        val cache = AiLyricsTranslationCache(directory)
        val overflow = AiLyricsTranslationCache.MAX_ENTRIES + 5
        repeat(overflow) { index ->
            val key = AiLyricsTranslationCache.keyFor("song-$index", "zh-CN", "MIMO/m", "source")
            cache.write(key, "译文 $index")
            // Eviction is oldest-first on lastModified, so the write order has to be visible.
            File(directory, "$key.json").setLastModified(index.toLong() * 1_000)
        }

        assertThat(directory.listFiles()?.size).isEqualTo(AiLyricsTranslationCache.MAX_ENTRIES)
        val oldest = AiLyricsTranslationCache.keyFor("song-0", "zh-CN", "MIMO/m", "source")
        assertThat(cache.read(oldest)).isNull()
    }

    @Test
    fun `clear removes every entry`() = runTest {
        val cache = AiLyricsTranslationCache(directory)
        cache.write(AiLyricsTranslationCache.keyFor("42", "zh-CN", "MIMO/m", "a"), "译文")
        cache.write(AiLyricsTranslationCache.keyFor("43", "zh-CN", "MIMO/m", "b"), "译文")

        cache.clear()

        assertThat(directory.listFiles()?.size).isEqualTo(0)
    }
}
