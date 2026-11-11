/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work by pisces312: request logging for the AI playlist feature.
 */
package com.pisces312.piscesplayer.data.ai

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File-backed storage for full AI request payloads.
 *
 * One request == one JSON file in `cacheDir/ai_logs/`. Cache dir is intentional:
 * these are debugging artifacts, excluded from backups and reclaimable by the system.
 * A hard cap of [MAX_FILES] is enforced after every write (oldest pruned first).
 */
@Singleton
class AiRequestLogStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {

    private val logDir: File by lazy {
        File(context.cacheDir, LOG_DIR).also { it.mkdirs() }
    }

    private val nameFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Persist [log] and prune to [MAX_FILES]. Returns the generated file name (id),
     * or null when writing failed — logging must never break a generation request.
     */
    suspend fun write(log: AiRequestLog): String? = withContext(Dispatchers.IO) {
        runCatching {
            val stamp = nameFormatter.format(Date(log.timestamp))
            val name = "ai_${stamp}_${log.timestamp}_${(0..Int.MAX_VALUE).random().toString(36)}.json"
            val bounded = log.boundFields()
            File(logDir, name).writeText(gson.toJson(bounded.copy(id = name)))
            prune()
            name
        }.getOrNull()
    }

    /** Newest first. */
    suspend fun list(): List<AiRequestLog> = withContext(Dispatchers.IO) {
        logDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.mapNotNull { readFile(it) }
            ?: emptyList()
    }

    suspend fun read(id: String): AiRequestLog? = withContext(Dispatchers.IO) {
        readFile(fileFor(id))
    }

    /** Raw file, for sharing through FileProvider (`cache-path` is already declared). */
    fun fileFor(id: String): File = File(logDir, id)

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        logDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.forEach { it.delete() }
    }

    /** Drop the oldest files so at most [MAX_FILES] remain. */
    private fun prune() {
        val files = logDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name } // oldest first: name starts with the timestamp
            ?: return
        val excess = files.size - MAX_FILES
        if (excess > 0) {
            for (i in 0 until excess) {
                files[i].delete()
            }
        }
    }

    private fun readFile(file: File): AiRequestLog? {
        if (!file.isFile) return null
        return runCatching {
            gson.fromJson(file.readText(), AiRequestLog::class.java)
        }.getOrNull()
    }

    private fun AiRequestLog.boundFields(): AiRequestLog {
        var cut = false
        fun String?.bound(): String? {
            if (this == null || length <= MAX_FIELD_CHARS) return this
            cut = true
            return substring(0, MAX_FIELD_CHARS) + TRUNCATION_MARK
        }
        return copy(
            systemPrompt = systemPrompt.bound(),
            userPrompt = userPrompt.bound(),
            responseText = responseText.bound(),
            errorMessage = errorMessage.bound(),
            truncated = truncated || cut
        )
    }

    companion object {
        const val LOG_DIR = "ai_logs"
        const val MAX_FILES = 50

        private const val MAX_FIELD_CHARS = 64_000
        private const val TRUNCATION_MARK = "\n…[truncated]"

        /**
         * Remove credentials that may ride along in a provider base URL
         * (e.g. `?key=…`, `?access_token=…`) before anything is persisted.
         */
        fun redactUrl(url: String): String {
            val queryStart = url.indexOf('?')
            if (queryStart < 0) return url
            val query = url.substring(queryStart + 1)
            val redacted = query.split('&').joinToString("&") { param ->
                val eq = param.indexOf('=')
                val key = if (eq < 0) param else param.substring(0, eq)
                if (key.contains("key", ignoreCase = true) ||
                    key.contains("token", ignoreCase = true) ||
                    key.contains("secret", ignoreCase = true)
                ) {
                    "$key=***"
                } else {
                    param
                }
            }
            return url.substring(0, queryStart + 1) + redacted
        }
    }
}
