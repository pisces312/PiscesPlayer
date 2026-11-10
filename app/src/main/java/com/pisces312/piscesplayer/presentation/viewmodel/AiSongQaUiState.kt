/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, GPL-3.0-or-later.
 */
package com.pisces312.piscesplayer.presentation.viewmodel

import com.pisces312.piscesplayer.data.ai.AiChatMessage
import com.pisces312.piscesplayer.data.model.Song

/** One visible turn in the current-song Q&A sheet. */
data class AiSongQaMessage(
    val id: String,
    val role: AiChatMessage.Role,
    val content: String,
    val isStreaming: Boolean = false
)

/** UI state for one in-memory song Q&A session. */
data class AiSongQaUiState(
    /** Immutable snapshot of the song this conversation is about. */
    val song: Song? = null,
    /** The player's live current song, used only for the explicit "switch" action. */
    val currentPlayerSongId: String? = null,
    val includeSongContext: Boolean = true,
    val hasStoredLyrics: Boolean = false,
    val includeLyrics: Boolean = false,
    val messages: List<AiSongQaMessage> = emptyList(),
    /** Input draft survives closing the sheet, but is never sent or persisted. */
    val draft: String = "",
    val isConfigured: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null
) {
    val canSwitchToCurrentSong: Boolean
        get() = song != null && currentPlayerSongId != null && song.id != currentPlayerSongId
}
