package com.pisces312.piscesplayer.data.metadata

enum class MetadataSource {
    MUSICBRAINZ
}

enum class MetadataApplyTarget {
    FILE_AND_DATABASE,
    DATABASE_ONLY
}

/**
 * The user-editable search input. It is kept separate from [MetadataSearchAttempt] because the
 * editable text and the ordered fallback queries are two different things.
 */
data class MetadataQuery(
    val title: String,
    val artist: String,
    val album: String?,
    val usedFilename: Boolean = false
)

data class MetadataSearchAttempt(
    val title: String,
    val artist: String?,
    val album: String?,
    val titleOnlyFallback: Boolean = false
)

data class MetadataCandidate(
    val source: MetadataSource = MetadataSource.MUSICBRAINZ,
    val recordingId: String?,
    val releaseId: String?,
    val releaseGroupId: String?,
    val artistId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val year: Int,
    val releaseDate: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val genre: String?,
    val durationMs: Long?,
    val score: Int,
    val disambiguation: String?,
    val releaseStatus: String?,
    val releaseType: String?,
    val coverArtUrl: String?
) {
    val key: String
        get() = listOfNotNull(recordingId, releaseId).joinToString("|")
}

data class MetadataFieldSelection(
    val title: Boolean = false,
    val artist: Boolean = false,
    val album: Boolean = false,
    val albumArtist: Boolean = false,
    val releaseDate: Boolean = false,
    val trackNumber: Boolean = false,
    val discNumber: Boolean = false,
    val genre: Boolean = false,
    val coverArt: Boolean = false
) {
    val hasAny: Boolean
        get() = title || artist || album || albumArtist || releaseDate ||
            trackNumber || discNumber || genre || coverArt
}
