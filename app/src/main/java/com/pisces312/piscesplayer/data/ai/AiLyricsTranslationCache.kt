/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, GPL-3.0-or-later.
 */
package com.pisces312.piscesplayer.data.ai

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * File-backed cache for AI title/lyrics translations.
 *
 * A translation is derived data whose only rebuild cost is tokens, so it does not earn a Room table
 * and the schema migration that would come with one. Entries live in app-private storage and are
 * keyed by everything that can change the answer (see [keyFor]), so a re-imported lyric sheet, a new
 * target language or a new model simply misses instead of serving a stale translation.
 */
class AiLyricsTranslationCache(private val directory: File) {

    @Serializable
    private data class Entry(
        val version: Int = ENTRY_VERSION,
        val answer: String,
        val savedAt: Long
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        val entry = runCatching {
            json.decodeFromString<Entry>(File(directory, "$key.json").readText())
        }.getOrNull()
        entry?.answer?.takeIf { it.isNotBlank() }
    }

    suspend fun write(key: String, answer: String) {
        if (answer.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val entry = Entry(answer = answer, savedAt = System.currentTimeMillis())
                File(directory, "$key.json").writeText(json.encodeToString(entry))
                trim()
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) { directory.listFiles()?.forEach { it.delete() } }
    }

    /** Oldest-first eviction: an install that listens to thousands of songs must not grow forever. */
    private fun trim() {
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.dropLast(MAX_ENTRIES).forEach { it.delete() }
    }

    companion object {
        const val DIRECTORY_NAME = "ai-lyrics"

        /** Upper bound on cached songs; one entry is small, so normal use never reaches it. */
        internal const val MAX_ENTRIES = 200

        private const val ENTRY_VERSION = 1

        /** Bump when the translation prompt changes shape, so old answers stop being served. */
        private const val KEY_VERSION = "1"

        /**
         * Identity of one translation: the song row, the target language, the provider/model label
         * and the exact source text that was sent. [source] should already contain the title and the
         * lyric text, because editing either one must invalidate the answer.
         *
         * The result is a plain hex hash rather than a readable id: song ids can be cloud URIs, which
         * are not safe as file names.
         */
        fun keyFor(
            songId: String,
            languageTag: String,
            modelLabel: String,
            source: String
        ): String =
            shortHash(KEY_VERSION, songId, languageTag, modelLabel, source)

        internal fun shortHash(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            // Separator byte: without it "ab"+"c" and "a"+"bc" would hash identically.
            parts.forEach {
                digest.update(it.toByteArray())
                digest.update(0)
            }
            return digest.digest().take(6).joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
        }
    }
}
