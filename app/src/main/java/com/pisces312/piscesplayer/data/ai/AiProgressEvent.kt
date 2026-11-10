/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: streaming progress reporting for AI chat completions.
 */
package com.pisces312.piscesplayer.data.ai

/**
 * Intermediate progress of a streaming chat completion.
 *
 * [full] is everything accumulated so far, which is what the UI renders; [delta] is only the piece
 * that just arrived. Thinking text stays in memory: it is never cached, logged or persisted.
 */
sealed interface AiProgressEvent {
    data class Thinking(val delta: String, val full: String) : AiProgressEvent

    data class Answer(val delta: String, val full: String) : AiProgressEvent
}

/** Receives [AiProgressEvent]s while a stream is open. Called from the client's IO thread. */
fun interface AiProgressListener {
    fun onProgress(event: AiProgressEvent)
}
