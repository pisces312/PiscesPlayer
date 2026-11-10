package com.pisces312.piscesplayer.data.database

import com.google.common.truth.Truth.assertThat
import com.pisces312.piscesplayer.data.model.Playlist
import org.junit.jupiter.api.Test

/**
 * Guards the `ai_thinking` round trip between the domain model and its row.
 *
 * That column is the only thing carrying the streamed thought process from the AI mix sheet to the
 * playlist detail screen, so a mapping that dropped it would surface as a feature that silently
 * never works rather than as a crash.
 */
class PlaylistAiThinkingMappingTest {

    @Test
    fun thinkingSurvivesTheEntityRoundTrip() {
        val playlist =
                Playlist(
                        id = "playlist-1",
                        name = "Rainy evening",
                        songIds = listOf("a", "b"),
                        source = "AI",
                        aiPrompt = "rainy evening, no vocals",
                        aiThinking = "Weigh the mood first, then drop anything with a driving beat.",
                )

        val restored = playlist.toEntity().toPlaylist(playlist.songIds)

        assertThat(restored.aiThinking).isEqualTo(playlist.aiThinking)
        assertThat(restored.aiPrompt).isEqualTo(playlist.aiPrompt)
    }

    /** Thinking off, a manual playlist and every pre-v11 row all leave the column empty. */
    @Test
    fun aPlaylistWithoutThinkingStaysNull() {
        val playlist = Playlist(id = "playlist-2", name = "Manual", songIds = emptyList())

        assertThat(playlist.toEntity().aiThinking).isNull()
        assertThat(playlist.toEntity().toPlaylist(emptyList()).aiThinking).isNull()
    }
}
