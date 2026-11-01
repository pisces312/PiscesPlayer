package com.lostf1sh.pixelplayeross.data.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MetadataMergePolicyTest {

    private val blankSong = SongMetadataSnapshot(
        title = "Unknown Title",
        artist = "",
        album = "Unknown Album",
        albumArtist = null,
        year = 0,
        releaseDate = null,
        trackNumber = 0,
        discNumber = null,
        genre = null,
        hasCoverArt = false
    )

    private val candidate = MetadataCandidate(
        recordingId = "recording-id",
        releaseId = "release-id",
        releaseGroupId = "group-id",
        artistId = "artist-id",
        title = "晴天",
        artist = "周杰倫",
        album = "葉惠美",
        albumArtist = "周杰倫",
        year = 2003,
        releaseDate = "2003-07-31",
        trackNumber = 3,
        discNumber = 1,
        genre = "Pop",
        durationMs = 269_000,
        score = 100,
        disambiguation = null,
        releaseStatus = "Official",
        releaseType = "Album",
        coverArtUrl = "https://coverartarchive.org/release/release-id/front-500"
    )

    @Test
    fun `selects missing fields by default`() {
        val selection = MetadataMergePolicy.defaultSelection(blankSong, candidate)

        assertThat(selection.title).isTrue()
        assertThat(selection.artist).isTrue()
        assertThat(selection.album).isTrue()
        assertThat(selection.albumArtist).isTrue()
        assertThat(selection.releaseDate).isTrue()
        assertThat(selection.trackNumber).isTrue()
        assertThat(selection.discNumber).isTrue()
        assertThat(selection.coverArt).isTrue()
        assertThat(selection.genre).isFalse()
    }

    @Test
    fun `never selects user edited fields`() {
        val edited = blankSong.copy(
            title = "My title",
            artist = "My artist",
            album = "My album",
            titleUserEdited = true,
            artistUserEdited = true,
            albumUserEdited = true
        )

        val selection = MetadataMergePolicy.defaultSelection(edited, candidate)

        assertThat(selection.title).isFalse()
        assertThat(selection.artist).isFalse()
        assertThat(selection.album).isFalse()
    }

    @Test
    fun `leaves populated fields unchecked`() {
        val populated = blankSong.copy(
            title = "晴天",
            artist = "周杰倫",
            album = "葉惠美",
            albumArtist = "周杰倫",
            year = 2003,
            releaseDate = "2003-07-31",
            trackNumber = 3,
            discNumber = 1,
            hasCoverArt = true
        )

        val selection = MetadataMergePolicy.defaultSelection(populated, candidate)

        assertThat(selection.title).isFalse()
        assertThat(selection.artist).isFalse()
        assertThat(selection.album).isFalse()
        assertThat(selection.albumArtist).isFalse()
        assertThat(selection.releaseDate).isFalse()
        assertThat(selection.trackNumber).isFalse()
        assertThat(selection.discNumber).isFalse()
        assertThat(selection.coverArt).isFalse()
    }

    @Test
    fun `fill blank only off starts with nothing selected`() {
        val selection = MetadataMergePolicy.defaultSelection(
            snapshot = blankSong,
            candidate = candidate,
            fillBlankOnly = false
        )

        assertThat(selection.hasAny).isFalse()
    }

    @Test
    fun `year alone resolves to a release date`() {
        val yearOnly = candidate.copy(releaseDate = null)

        assertThat(MetadataMergePolicy.resolvedReleaseDate(yearOnly)).isEqualTo("2003-01-01")
    }
}
