package com.pisces312.piscesplayer.data.metadata

import java.util.Locale

/**
 * Builds the editable search input and the ordered fallback attempts.
 *
 * MusicBrainz indexes artist names in whichever script contributors used, so a simplified
 * Chinese artist can miss a traditional-script index entry. The title-only fallback is the
 * practical escape hatch: title hits are returned regardless of the artist script, and the
 * score in the result list still reflects title/artist/duration similarity.
 */
object MetadataQueryBuilder {

    private val PLACEHOLDER_VALUES = setOf("unknown title", "unknown artist", "unknown album")

    private val AUDIO_EXTENSION_REGEX =
        Regex("""\.(mp3|flac|m4a|m4b|mp4|aac|ogg|oga|opus|wav|aif|aiff|wma|ape|dsf|dff|alac)$""", RegexOption.IGNORE_CASE)

    fun fromSong(title: String, artist: String, album: String?, path: String): MetadataQuery {
        val parsed = FileNameMetadataParser.parsePath(path)
        val stem = path.substringAfterLast('/').substringAfterLast('\\')
            .substringBeforeLast('.', path.substringAfterLast('/').substringAfterLast('\\'))

        val useParsedTitle = isMissing(title) || looksLikeFileName(title, stem)
        val useParsedArtist = isMissing(artist) || looksLikeFileName(artist, stem)
        val useParsedAlbum = isMissing(album)

        val resolvedTitle = if (useParsedTitle) {
            parsed?.title?.takeIf { it.isNotBlank() } ?: title.takeUnless(::isMissing).orEmpty()
        } else {
            title
        }
        val resolvedArtist = if (useParsedArtist) {
            parsed?.artist?.takeIf { it.isNotBlank() } ?: artist.takeUnless(::isMissing).orEmpty()
        } else {
            artist
        }
        val resolvedAlbum = if (useParsedAlbum) {
            parsed?.album?.takeIf { it.isNotBlank() }
        } else {
            album?.trim()?.takeIf { it.isNotBlank() && !isPlaceholder(it) }
        }

        return MetadataQuery(
            title = resolvedTitle.trim(),
            artist = resolvedArtist.trim(),
            album = resolvedAlbum,
            usedFilename = useParsedTitle && parsed != null ||
                useParsedArtist && parsed?.artist != null ||
                useParsedAlbum && parsed?.album != null
        )
    }

    fun searchAttempts(query: MetadataQuery): List<MetadataSearchAttempt> {
        val title = query.title.trim()
        if (title.isBlank()) return emptyList()
        val artist = query.artist.trim().takeIf { it.isNotBlank() }
        val album = query.album?.trim()?.takeIf { it.isNotBlank() }

        val attempts = buildList {
            if (artist != null) {
                if (album != null) {
                    add(MetadataSearchAttempt(title, artist, album))
                }
                add(MetadataSearchAttempt(title, artist, null))
                add(MetadataSearchAttempt(title, null, null, titleOnlyFallback = true))
            } else {
                if (album != null) {
                    add(MetadataSearchAttempt(title, null, album))
                }
                add(MetadataSearchAttempt(title, null, null, titleOnlyFallback = true))
            }
        }
        return attempts.distinctBy { listOf(it.title, it.artist, it.album) }
    }

    fun displayQuery(attempt: MetadataSearchAttempt): String {
        val terms = buildList {
            add("recording:\"${escapeLucene(attempt.title)}\"")
            attempt.artist?.let { add("artist:\"${escapeLucene(it)}\"") }
            attempt.album?.let { add("release:\"${escapeLucene(it)}\"") }
        }
        return terms.joinToString(" AND ")
    }

    fun isPlaceholder(value: String?): Boolean =
        value.isNullOrBlank() || value.trim().lowercase(Locale.ROOT) in PLACEHOLDER_VALUES

    fun isMissing(value: String?): Boolean = isPlaceholder(value)

    private fun looksLikeFileName(value: String, stem: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (AUDIO_EXTENSION_REGEX.containsMatchIn(trimmed)) return true
        return stem.isNotBlank() && trimmed.equals(stem, ignoreCase = true)
    }

    fun escapeLucene(value: String): String = buildString(value.length) {
        value.trim().forEach { char ->
            if (char in LUCENE_SPECIAL_CHARS) append('\\')
            append(char)
        }
    }

    private val LUCENE_SPECIAL_CHARS = setOf(
        '+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/'
    )
}
