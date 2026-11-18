package com.pisces312.piscesplayer.data.musicquiz

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared text normalization for catalog matching, keyword matching and question generation.
 * Matching is intentionally forgiving: library titles carry decorations like "(Live)" or
 * "（儿歌版）" that never change which song a title denotes.
 */
internal object MusicQuizText {
    private val WHITESPACE = Regex("\\s+")
    private val DECORATION = Regex("[（(\\[【][^）)\\]】]*[）)\\]】]")
    private val SUFFIX_WORDS = Regex(
        "(儿歌版|童谣|伴奏|完整版|正式版|片头曲|片尾曲|主题曲|live|remaster(ed)?|remix|ver\\.?|version)$",
        RegexOption.IGNORE_CASE,
    )
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val LRC_STAMP = Regex("\\[[0-9:.]+]")
    private val LATIN_WORD = Regex("[a-z0-9]")
    private val patternCache = ConcurrentHashMap<String, Regex>()

    /** Canonical comparison key: decorations stripped, accents folded, lowercased. */
    fun normalizeTitle(title: String): String {
        var text = fullWidthToHalfWidth(title).trim()
        text = DECORATION.replace(text, " ")
        text = SUFFIX_WORDS.replace(text.trim(), "").trim()
        return foldAccents(text).replace(WHITESPACE, " ").lowercase(Locale.ROOT)
    }

    /** Plain key without decoration stripping; for lyrics and free-text scanning. */
    fun normalizeLoose(text: String): String =
        foldAccents(fullWidthToHalfWidth(text)).replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    /**
     * Substring match where Latin needles match on word boundaries ("bach" must not hit
     * "bachelor") and CJK needles match as plain substrings.
     */
    fun containsPhrase(haystackKey: String, needleKey: String): Boolean {
        if (needleKey.isEmpty()) return false
        return if (LATIN_WORD.containsMatchIn(needleKey)) {
            wordBoundaryContains(haystackKey, needleKey)
        } else {
            haystackKey.contains(needleKey)
        }
    }

    fun wordBoundaryContains(haystackKey: String, needleKey: String): Boolean =
        patternCache.getOrPut(needleKey) {
            Regex("(?<![a-z0-9])" + Regex.escape(needleKey) + "(?![a-z0-9])")
        }.containsMatchIn(haystackKey)

    /** Normalized lyrics text for keyword scanning: LRC stamps removed, capped in size. */
    fun lyricsKey(lyrics: String, maxChars: Int = 2_000): String =
        normalizeLoose(LRC_STAMP.replace(lyrics, " ")).take(maxChars)

    private fun foldAccents(text: String): String =
        COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")

    private fun fullWidthToHalfWidth(text: String): String = buildString(text.length) {
        for (ch in text) {
            val code = ch.code
            when {
                code == 0x3000 -> append(' ')
                code in 0xFF01..0xFF5E -> append((code - 0xFEE0).toChar())
                else -> append(ch)
            }
        }
    }
}
