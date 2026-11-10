package com.pisces312.piscesplayer.data.metadata

data class SongMetadataSnapshot(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val year: Int,
    val releaseDate: String?,
    val trackNumber: Int,
    val discNumber: Int?,
    val genre: String?,
    val hasCoverArt: Boolean,
    val titleUserEdited: Boolean = false,
    val artistUserEdited: Boolean = false,
    val albumUserEdited: Boolean = false,
    val genreUserEdited: Boolean = false
)

/**
 * Single source of truth for which fields start checked in the preview.
 *
 * Only fields that are currently missing are selected, and fields the user edited by hand are
 * never selected. Genre is opt-in even when blank: MusicBrainz genre names come from a different
 * taxonomy and rarely match how a local library is organised.
 */
object MetadataMergePolicy {

    private val UNKNOWN_TITLES = setOf("unknown title", "unknown")
    private val UNKNOWN_ARTISTS = setOf("unknown artist", "unknown")
    private val UNKNOWN_ALBUMS = setOf("unknown album", "unknown")

    fun defaultSelection(
        snapshot: SongMetadataSnapshot,
        candidate: MetadataCandidate,
        fillBlankOnly: Boolean = true
    ): MetadataFieldSelection {
        if (!fillBlankOnly) return MetadataFieldSelection()

        return MetadataFieldSelection(
            title = candidate.title.isNotBlank() &&
                !snapshot.titleUserEdited &&
                isBlank(snapshot.title, UNKNOWN_TITLES),
            artist = candidate.artist.isNotBlank() &&
                !snapshot.artistUserEdited &&
                isBlank(snapshot.artist, UNKNOWN_ARTISTS),
            album = candidate.album.isNotBlank() &&
                !snapshot.albumUserEdited &&
                isBlank(snapshot.album, UNKNOWN_ALBUMS),
            albumArtist = !candidate.albumArtist.isNullOrBlank() &&
                snapshot.albumArtist.isNullOrBlank(),
            releaseDate = hasReleaseDate(candidate) &&
                (snapshot.year <= 0 || snapshot.releaseDate.isNullOrBlank()),
            trackNumber = (candidate.trackNumber ?: 0) > 0 && snapshot.trackNumber <= 0,
            discNumber = (candidate.discNumber ?: 0) > 0 && (snapshot.discNumber ?: 0) <= 0,
            genre = false,
            coverArt = !candidate.coverArtUrl.isNullOrBlank() && !snapshot.hasCoverArt
        )
    }

    fun hasReleaseDate(candidate: MetadataCandidate): Boolean =
        !candidate.releaseDate.isNullOrBlank() || candidate.year > 0

    fun resolvedReleaseDate(candidate: MetadataCandidate): String? {
        val date = candidate.releaseDate?.trim()
        if (!date.isNullOrBlank()) return date
        return candidate.year.takeIf { it > 0 }?.let { "$it-01-01" }
    }

    fun isBlank(value: String?, placeholders: Set<String> = emptySet()): Boolean {
        val normalized = value?.trim()?.lowercase() ?: return true
        return normalized.isEmpty() || normalized in placeholders
    }
}
