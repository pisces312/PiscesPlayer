package com.pisces312.piscesplayer.data.database

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Locks the cloud unified-id hash: 62-bit so `offset + hash` cannot overflow into the
 * positive MediaStore id space. See CloudUnifiedIds.
 */
class CloudUnifiedIdsTest {

    private val productionOffsets = listOf(
        9_000_000_000_000L,
        10_000_000_000_000L,
        11_000_000_000_000L,
        12_000_000_000_000L,
        13_000_000_000_000L,
        14_000_000_000_000L,
    )

    @Test
    @DisplayName("hash stays in 62-bit unsigned range")
    fun hashIsMaskedTo62Bits() {
        val bound = Long.MAX_VALUE shr 1
        val samples = listOf(
            "",
            "a",
            "ext-1",
            "navidrome://song/1",
            "cafe",
            "x".repeat(10_000),
        )
        for (s in samples) {
            val h = CloudUnifiedIds.stableHash62(s)
            assertTrue(h in 0..bound, "hash out of 62-bit range for '$s': $h")
        }
    }

    @Test
    @DisplayName("unified ids stay negative even at max production offset")
    fun unifiedIdsNeverOverflowIntoPositiveSpace() {
        val maxOffset = productionOffsets.max()
        val maxHash = Long.MAX_VALUE shr 1
        // The overflow that a 63-bit hash can hit: offset + hash > Long.MAX_VALUE.
        assertTrue(maxOffset + maxHash > 0, "sum must not overflow with 62-bit hash")
        assertTrue(maxOffset + maxHash < Long.MAX_VALUE, "sum must fit in Long")

        for (offset in productionOffsets) {
            for (external in listOf("ext-1", "track/42", "Album Name", "  Artist  ")) {
                val song = CloudUnifiedIds.unifiedSongId(offset, external)
                val album = CloudUnifiedIds.unifiedAlbumId(offset, external)
                val artist = CloudUnifiedIds.unifiedArtistId(offset, external)
                assertTrue(song < 0, "song id must be negative: $song")
                assertTrue(album < 0, "album id must be negative: $album")
                assertTrue(artist < 0, "artist id must be negative: $artist")
            }
        }
    }

    @Test
    @DisplayName("63-bit top-band hashes would overflow; 62-bit mask prevents it")
    fun documentsWhyMaskIs62Not63() {
        // Simulate the top-band hash a 63-bit mask can produce (2^63-1).
        val topBand63 = Long.MAX_VALUE
        val offset = 14_000_000_000_000L
        val overflowed = offset + topBand63 // wraps negative
        assertTrue(overflowed < 0, "precondition: 63-bit top band overflows")
        assertTrue(-overflowed > 0, "negating overflow flips into positive id space")

        val topBand62 = Long.MAX_VALUE shr 1
        val safe = offset + topBand62
        assertTrue(safe > 0 && safe < Long.MAX_VALUE)
        assertTrue(-safe < 0)
    }

    @Test
    @DisplayName("same input always maps to the same id")
    fun mappingIsStable() {
        val a = CloudUnifiedIds.unifiedSongId(9_000_000_000_000L, "ext-1")
        val b = CloudUnifiedIds.unifiedSongId(9_000_000_000_000L, "ext-1")
        assertEquals(a, b)
    }

    @Test
    @DisplayName("artist hash trims and lowercases the name")
    fun artistIdNormalizesName() {
        val a = CloudUnifiedIds.unifiedArtistId(11_000_000_000_000L, "  Radiohead ")
        val b = CloudUnifiedIds.unifiedArtistId(11_000_000_000_000L, "radiohead")
        assertEquals(a, b)
    }

    @Test
    @DisplayName("different external ids map to different song ids (sample)")
    fun distinctExternalIdsDiverge() {
        val ids = (0 until 200).map { i ->
            CloudUnifiedIds.unifiedSongId(9_000_000_000_000L, "ext-$i")
        }
        assertEquals(ids.size, ids.toSet().size)
    }
}
