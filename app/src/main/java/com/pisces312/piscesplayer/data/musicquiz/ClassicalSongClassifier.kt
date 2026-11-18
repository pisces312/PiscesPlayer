package com.pisces312.piscesplayer.data.musicquiz

/**
 * Classifies a track as classical music via three evidence routes (any hit qualifies):
 * piece-title catalog, composer/performer names, and title/genre tokens.
 *
 * Classification priority note: when a track matches both classical and kids evidence
 * (e.g. a Brahms lullaby), the caller treats it as classical — classical recordings are
 * recognizable and must not leak into non-classical option sets.
 */
internal object ClassicalSongClassifier {

    fun isClassical(track: MusicQuizTrack): Boolean = isClassical(
        title = track.title,
        artist = track.artist,
        album = track.album,
        genre = track.genre,
    )

    fun isClassical(
        title: String,
        artist: String,
        album: String = "",
        genre: String? = null,
    ): Boolean {
        val titleKey = MusicQuizText.normalizeTitle(title)
        if (titleKey.isNotEmpty() && ClassicalSongCatalog.pieceKeys.any {
                MusicQuizText.containsPhrase(titleKey, it)
            }
        ) {
            return true
        }

        // Composer/performer names are strong evidence anywhere in the metadata; they also
        // appear inside titles like "贝多芬 - 月光奏鸣曲".
        val nameHaystack = MusicQuizText.normalizeLoose("$title $artist $album")
        if (ClassicalSongCatalog.artistKeys.any { MusicQuizText.wordBoundaryContains(nameHaystack, it) }) {
            return true
        }

        if (ClassicalSongCatalog.titleTokenKeys.any { MusicQuizText.containsPhrase(titleKey, it) }) {
            return true
        }

        val genreHaystack = MusicQuizText.normalizeLoose("${genre.orEmpty()} $album")
        return ClassicalSongCatalog.genreTokenKeys.any {
            MusicQuizText.containsPhrase(genreHaystack, it)
        }
    }
}
