package com.lostf1sh.pixelplayeross.data.metadata

import java.util.Locale

data class ParsedFileName(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null
)

/**
 * Best-effort extraction of metadata from an audio file name. The result is always shown to the
 * user as editable search input, never written straight into tags.
 */
object FileNameMetadataParser {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff",
        "wma", "ape", "dsf", "dff", "alac"
    )

    private val LEADING_TRACK_REGEX =
        Regex("""^\s*\[?(\d{1,3})\]?\s*[.\-_、)\]]+\s*""")

    private val STANDALONE_TRACK_REGEX = Regex("""^\d{1,3}$""")

    private val BRACKETED_TOKEN_REGEX = Regex("""[\[(（【]\s*([^\]\)）】]{1,24})\s*[\])）】]""")

    private val TECHNICAL_NOISE = setOf(
        "320k", "320kbps", "128k", "128kbps", "192k", "256k", "flac", "ape", "wav", "dsd",
        "hi-res", "hires", "hq", "mv", "official", "lyrics", "歌词", "无损"
    )

    fun parsePath(path: String): ParsedFileName? {
        if (path.isBlank()) return null
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val stem = fileName.substringBeforeLast('.', fileName)
        return parse(stem)
    }

    fun parse(rawStem: String): ParsedFileName? {
        var stem = rawStem.trim()
        if (stem.isBlank()) return null

        val extension = stem.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in AUDIO_EXTENSIONS) {
            stem = stem.substringBeforeLast('.')
        }

        stem = normalizeSeparators(stem)
        var trackNumber: Int? = null
        LEADING_TRACK_REGEX.find(stem)?.let { match ->
            trackNumber = match.groupValues[1].toIntOrNull()
            stem = stem.removeRange(match.range).trim()
        }
        stem = stripTechnicalNoise(stem)
        if (stem.isBlank()) return null

        val parts = stem.split(" - ")
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (parts.isEmpty()) return null

        val result = when {
            parts.size == 1 -> ParsedFileName(title = parts[0], trackNumber = trackNumber)
            parts.size == 2 -> ParsedFileName(
                title = parts[1],
                artist = parts[0],
                trackNumber = trackNumber
            )
            parts.size == 3 && STANDALONE_TRACK_REGEX.matches(parts[0]) -> ParsedFileName(
                title = parts[2],
                artist = parts[1],
                trackNumber = parts[0].toIntOrNull() ?: trackNumber
            )
            parts.size == 3 && STANDALONE_TRACK_REGEX.matches(parts[1]) -> ParsedFileName(
                title = parts[2],
                artist = parts[0],
                trackNumber = parts[1].toIntOrNull() ?: trackNumber
            )
            parts.size == 3 -> ParsedFileName(
                title = parts.drop(1).joinToString(" - "),
                artist = parts[0],
                trackNumber = trackNumber
            )
            STANDALONE_TRACK_REGEX.matches(parts[1]) -> ParsedFileName(
                title = parts.drop(2).joinToString(" - "),
                artist = parts[0],
                trackNumber = parts[1].toIntOrNull() ?: trackNumber
            )
            STANDALONE_TRACK_REGEX.matches(parts[2]) -> ParsedFileName(
                title = parts.drop(3).joinToString(" - "),
                artist = parts[0],
                album = parts[1],
                trackNumber = parts[2].toIntOrNull() ?: trackNumber
            )
            else -> ParsedFileName(
                title = parts.drop(1).joinToString(" - "),
                artist = parts[0],
                trackNumber = trackNumber
            )
        }

        return result.copy(
            title = result.title.trim().takeIf { it.isNotBlank() } ?: return null,
            artist = result.artist?.trim()?.takeIf { it.isNotBlank() },
            album = result.album?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun normalizeSeparators(value: String): String = value
        .replace('－', '-')
        .replace('—', '-')
        .replace('–', '-')
        .replace('｜', '-')
        .replace('丨', '-')
        .replace('·', '-')
        .replace('\u3000', ' ')
        .replace(Regex("""\s{2,}"""), " ")
        .replace(Regex("""\s*-\s*"""), " - ")
        .trim()

    private fun stripTechnicalNoise(value: String): String {
        var cleaned = BRACKETED_TOKEN_REGEX.replace(value) { match ->
            val token = match.groupValues[1].trim().lowercase(Locale.ROOT)
            if (isNoise(token)) "" else match.value
        }
        cleaned = cleaned.split(' ')
            .dropLastWhile { isNoise(it.trim().lowercase(Locale.ROOT)) }
            .joinToString(" ")
        return cleaned.replace(Regex("""\s{2,}"""), " ").trim()
    }

    private fun isNoise(token: String): Boolean {
        if (token.isBlank()) return false
        if (token in TECHNICAL_NOISE) return true
        if (Regex("""\d{2,3}\s*k(bps)?""").matches(token)) return true
        return false
    }
}
