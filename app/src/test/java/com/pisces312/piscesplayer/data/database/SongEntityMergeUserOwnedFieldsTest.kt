package com.pisces312.piscesplayer.data.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the upsert merge rule: sync must not wipe user-owned fields
 * (user-edited / dateAdded / MusicBrainz ids).
 */
class SongEntityMergeUserOwnedFieldsTest {

    private fun baseSong(id: Long = 10L) = SongEntity(
        id = id,
        title = "Old Title",
        artistName = "Old Artist",
        artistId = 1L,
        albumName = "Old Album",
        albumId = 2L,
        contentUriString = "content://old",
        albumArtUriString = null,
        duration = 100L,
        genre = "Old Genre",
        filePath = "/music/old.mp3",
        parentDirectoryPath = "/music",
    )

    @Test
    fun `preserves dateAdded from existing row`() {
        val existing = baseSong().copy(
            dateAdded = 111L,
        )
        val incoming = baseSong().copy(
            title = "New Title",
            dateAdded = 999L,
        )

        val merged = incoming.mergingUserOwnedFieldsFrom(existing)

        assertThat(merged.dateAdded).isEqualTo(111L)
        assertThat(merged.title).isEqualTo("New Title")
    }

    @Test
    fun `keeps user-edited display columns`() {
        val existing = baseSong().copy(
            title = "User Title",
            artistName = "User Artist",
            albumName = "User Album",
            genre = "User Genre",
            titleUserEdited = true,
            artistUserEdited = true,
            albumUserEdited = true,
            genreUserEdited = true,
        )
        val incoming = baseSong().copy(
            title = "Scan Title",
            artistName = "Scan Artist",
            albumName = "Scan Album",
            genre = "Scan Genre",
        )

        val merged = incoming.mergingUserOwnedFieldsFrom(existing)

        assertThat(merged.title).isEqualTo("User Title")
        assertThat(merged.artistName).isEqualTo("User Artist")
        assertThat(merged.albumName).isEqualTo("User Album")
        assertThat(merged.genre).isEqualTo("User Genre")
        assertThat(merged.titleUserEdited).isTrue()
        assertThat(merged.artistUserEdited).isTrue()
        assertThat(merged.albumUserEdited).isTrue()
        assertThat(merged.genreUserEdited).isTrue()
    }

    @Test
    fun `takes scan values when user did not edit`() {
        val existing = baseSong().copy(titleUserEdited = false)
        val incoming = baseSong().copy(title = "Scan Title")

        val merged = incoming.mergingUserOwnedFieldsFrom(existing)

        assertThat(merged.title).isEqualTo("Scan Title")
        assertThat(merged.titleUserEdited).isFalse()
    }

    @Test
    fun `keeps existing musicbrainz ids and fills only when empty`() {
        val existing = baseSong().copy(mbRecordingId = "rec-1")
        val incoming = baseSong().copy(
            mbRecordingId = "rec-ignored",
            mbReleaseId = "rel-2",
            mbArtistId = "art-3",
        )

        val merged = incoming.mergingUserOwnedFieldsFrom(existing)

        assertThat(merged.mbRecordingId).isEqualTo("rec-1")
        assertThat(merged.mbReleaseId).isEqualTo("rel-2")
        assertThat(merged.mbArtistId).isEqualTo("art-3")
    }
}
