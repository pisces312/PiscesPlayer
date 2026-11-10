package com.pisces312.piscesplayer.data.backup.restore

import com.pisces312.piscesplayer.data.database.SongSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the shared backup playlist song matcher: direct-ID verification, metadata
 * remapping with album/duration disambiguation, and the empty-library path that backs the
 * deferred post-restore resolution.
 */
class PlaylistSongMatcherTest {

    private fun summary(
        id: Long,
        title: String,
        artist: String,
        album: String = "Album",
        duration: Long = 200_000L
    ) = SongSummary(id = id, title = title, artistName = artist, albumName = album, duration = duration)

    private fun ref(
        title: String,
        artist: String,
        album: String = "Album",
        duration: Long = 200_000L
    ) = PendingSongRef(title = title, artist = artist, album = album, duration = duration)

    @Test
    fun `empty library reports empty and never resolves`() {
        val matcher = PlaylistSongMatcher(emptyList())
        assertTrue(matcher.isLibraryEmpty)
        assertNull(matcher.resolve("42", ref("Song", "Artist")))
        assertNull(matcher.resolve("42", null))
    }

    @Test
    fun `direct id hit without metadata is kept`() {
        val matcher = PlaylistSongMatcher(listOf(summary(7, "Song", "Artist")))
        assertEquals("7", matcher.resolve("7", null))
    }

    @Test
    fun `entry without identity is treated as no metadata and keeps a direct id hit`() {
        val matcher = PlaylistSongMatcher(listOf(summary(7, "Song", "Artist")))
        // A payload written with field names this build does not recognise deserializes blank.
        assertEquals("7", matcher.resolve("7", PendingSongRef()))
    }

    @Test
    fun `entry without identity never matches by metadata`() {
        val matcher = PlaylistSongMatcher(listOf(summary(11, "Target", "Target Artist")))
        assertNull(matcher.resolve("999", PendingSongRef()))
    }

    @Test
    fun `entry with only a title is treated as no metadata and keeps a direct id hit`() {
        val matcher = PlaylistSongMatcher(listOf(summary(7, "Song", "Artist")))
        // A half-filled entry can never satisfy the title+artist comparison, so it must not be
        // allowed to veto an otherwise reliable direct-ID hit.
        assertEquals("7", matcher.resolve("7", PendingSongRef(title = "Song")))
    }

    @Test
    fun `direct id hit with matching metadata is kept`() {
        val matcher = PlaylistSongMatcher(listOf(summary(7, "Song", "Artist")))
        assertEquals("7", matcher.resolve("7", ref("  song ", "ARTIST")))
    }

    @Test
    fun `direct id with mismatched metadata remaps by title and artist`() {
        val matcher = PlaylistSongMatcher(
            listOf(
                summary(7, "Completely Different", "Other"),
                summary(99, "My Song", "My Artist")
            )
        )
        // Backup id 7 points at a different song on this device; the metadata locates id 99.
        assertEquals("99", matcher.resolve("7", ref("My Song", "My Artist")))
    }

    @Test
    fun `missing id resolves through unique title-artist match`() {
        val matcher = PlaylistSongMatcher(
            listOf(
                summary(10, "Foo", "Someone"),
                summary(11, "Target", "Target Artist")
            )
        )
        assertEquals("11", matcher.resolve("999", ref("target", "target artist")))
    }

    @Test
    fun `ambiguous title-artist pair disambiguated by album`() {
        val matcher = PlaylistSongMatcher(
            listOf(
                summary(10, "Same", "Duo", album = "Live"),
                summary(11, "Same", "Duo", album = "Studio")
            )
        )
        assertEquals("11", matcher.resolve("500", ref("Same", "Duo", album = "studio")))
    }

    @Test
    fun `ambiguous album match disambiguated by duration tolerance`() {
        val matcher = PlaylistSongMatcher(
            listOf(
                summary(10, "Same", "Duo", album = "Studio", duration = 180_000L),
                summary(11, "Same", "Duo", album = "Studio", duration = 240_000L)
            )
        )
        assertEquals("11", matcher.resolve("500", ref("Same", "Duo", "Studio", duration = 241_000L)))
    }

    @Test
    fun `fully ambiguous candidates return null instead of guessing`() {
        val matcher = PlaylistSongMatcher(
            listOf(
                summary(10, "Same", "Duo", album = "Studio", duration = 240_000L),
                summary(11, "Same", "Duo", album = "Studio", duration = 240_500L)
            )
        )
        assertNull(matcher.resolve("500", ref("Same", "Duo", "Studio", duration = 240_200L)))
    }

    @Test
    fun `no candidate returns null`() {
        val matcher = PlaylistSongMatcher(listOf(summary(1, "Here", "Present")))
        assertFalse(matcher.isLibraryEmpty)
        assertNull(matcher.resolve("2", ref("Missing", "Nobody")))
    }

    @Test
    fun `id missing and no metadata returns null`() {
        val matcher = PlaylistSongMatcher(listOf(summary(1, "Here", "Present")))
        assertNull(matcher.resolve("2", null))
    }
}
