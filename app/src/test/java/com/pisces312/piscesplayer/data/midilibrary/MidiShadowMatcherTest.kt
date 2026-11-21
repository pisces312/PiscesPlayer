package com.pisces312.piscesplayer.data.midilibrary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MidiShadowMatcherTest {

    private fun entry(
        songId: String,
        fileSha256: String,
        musicHash: String? = null
    ) = MidiFileEntry(
        songId = songId,
        uri = "content://documents/$songId",
        displayName = "$songId.mid",
        directoryUri = "content://tree/1",
        directoryName = "midi",
        fileSha256 = fileSha256,
        musicHash = musicHash
    )

    @Test
    fun `L1 identical file hash shadows the built-in`() {
        val result = MidiShadowMatcher.match(
            files = listOf(entry("u1", "aaa")),
            builtInFileHashes = mapOf(11L to "aaa"),
            builtInMusicHashes = emptyMap()
        )
        assertEquals(setOf(11L), result.keys)
        assertEquals("u1", result.getValue(11L).songId)
    }

    @Test
    fun `L2 identical music hash shadows when file hashes differ`() {
        val result = MidiShadowMatcher.match(
            files = listOf(entry("u1", "user-file-hash", musicHash = "music-1")),
            builtInFileHashes = mapOf(11L to "built-in-file-hash"),
            builtInMusicHashes = mapOf(11L to "music-1")
        )
        assertEquals(setOf(11L), result.keys)
    }

    @Test
    fun `no hash match leaves built-ins unshadowed`() {
        val result = MidiShadowMatcher.match(
            files = listOf(entry("u1", "user-file-hash", musicHash = "music-1")),
            builtInFileHashes = mapOf(11L to "built-in-file-hash"),
            builtInMusicHashes = mapOf(11L to "music-2")
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `L1 wins over L2 and first entry wins duplicates`() {
        val result = MidiShadowMatcher.match(
            files = listOf(
                entry("u2", "zzz", musicHash = "music-1"), // L2 for 11, but later
                entry("u1", "aaa"),                        // L1 for 11
                entry("u3", "yyy", musicHash = "music-1")  // L2 duplicate for 11
            ),
            builtInFileHashes = mapOf(11L to "aaa"),
            builtInMusicHashes = mapOf(11L to "music-1")
        )
        assertEquals(setOf(11L), result.keys)
        assertEquals("u1", result.getValue(11L).songId)
    }

    @Test
    fun `music hash match without L1 still requires a non-blank user music hash`() {
        val result = MidiShadowMatcher.match(
            files = listOf(entry("u1", "zzz", musicHash = null)),
            builtInFileHashes = emptyMap(),
            builtInMusicHashes = mapOf(11L to "music-1")
        )
        assertTrue(result.isEmpty())
    }
}
