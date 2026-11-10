/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: request logging for the AI playlist feature, ported from
 * the proprietary PixelPlayer china-only branch by its author.
 */
package com.pisces312.piscesplayer.data.ai

/**
 * A single AI request, persisted as one JSON file under `cacheDir/ai_logs/`.
 *
 * File-only storage (no Room table) keeps the DB schema untouched and makes each
 * entry self-contained, so it can be shared or pulled via adb as-is for debugging.
 */
data class AiRequestLog(
    /** Log file name, also the stable identifier used by the UI. */
    val id: String = "",
    val timestamp: Long = 0L,
    /** One of [STATUS_SUCCESS], [STATUS_FAILED], [STATUS_FROM_CACHE]. */
    val status: String = STATUS_SUCCESS,
    val promptType: String = "",
    val provider: String = "",
    val model: String = "",
    /** Base URL of the endpoint, with secrets redacted. Null when not applicable. */
    val endpoint: String? = null,
    val durationMs: Long = 0L,
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val responseText: String? = null,
    val errorMessage: String? = null,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val thoughtTokens: Int = 0,
    /** True when a field was cut short by [AiRequestLogStore.MAX_FIELD_CHARS]. */
    val truncated: Boolean = false
) {
    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_FROM_CACHE = "FROM_CACHE"
    }
}
