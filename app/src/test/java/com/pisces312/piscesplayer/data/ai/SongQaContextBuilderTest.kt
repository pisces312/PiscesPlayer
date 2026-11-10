/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: privacy boundary tests for the current-song Q&A context builder.
 */
package com.pisces312.piscesplayer.data.ai

import com.google.common.truth.Truth.assertThat
import com.pisces312.piscesplayer.data.model.Lyrics
import com.pisces312.piscesplayer.data.model.SyncedLine
import com.pisces312.piscesplayer.data.model.Song
import org.junit.jupiter.api.Test

class SongQaContextBuilderTest {

    private val song =
        Song.emptySong().copy(
            id = "42",
            title = "Track",
            artist = "Artist",
            album = "Album",
            albumArtist = "Album Artist",
            genre = "Ambient",
            year = 2024,
            releaseDate = "2024-03-01",
            duration = 185_000L,
            trackNumber = 3,
            discNumber = 1,
            mimeType = "audio/flac",
            bitrate = 1411,
            sampleRate = 44100,
            path = "/storage/emulated/0/Music/private.flac",
            contentUriString = "content://media/external/audio/media/42",
            navidromeId = "cloud-track-id",
            jellyfinId = "jellyfin-track-id"
        )

    @Test
    fun `metadata exposes music facts but never paths or service ids`() {
        val lines = SongQaContextBuilder.songMetadataLines(song)

        assertThat(lines).contains("Title: Track")
        assertThat(lines).contains("Artists: Artist")
        assertThat(lines).contains("Album: Album")
        assertThat(lines).contains("Year: 2024")
        assertThat(lines).contains("Duration: 3:05")
        assertThat(lines.none { it.contains("private.flac") }).isTrue()
        assertThat(lines.none { it.contains("content://") }).isTrue()
        assertThat(lines.none { it.contains("cloud-track-id") }).isTrue()
        assertThat(lines.none { it.contains("jellyfin-track-id") }).isTrue()
    }

    @Test
    fun `song context is omitted when the user removes it`() {
        val prompt = SongQaContextBuilder.systemPrompt(
            song = song,
            lyrics = null,
            includeSongContext = false,
            appLanguageTag = "zh-CN"
        )

        assertThat(prompt).contains("PiscesPlayer")
        assertThat(prompt).doesNotContain("Current song facts")
        assertThat(prompt).doesNotContain("Track")
    }

    @Test
    fun `lyrics are sent only when explicitly included and are marked as data`() {
        val lyrics = Lyrics(plain = listOf("first line", "second line"))
        val withoutLyrics = SongQaContextBuilder.systemPrompt(
            song = song,
            lyrics = null,
            includeSongContext = true,
            appLanguageTag = "en-US"
        )
        val withLyrics = SongQaContextBuilder.systemPrompt(
            song = song,
            lyrics = lyrics,
            includeSongContext = true,
            appLanguageTag = "en-US"
        )

        assertThat(withoutLyrics).contains("Current song facts")
        assertThat(withoutLyrics).doesNotContain("<lyrics>")
        assertThat(withLyrics).contains("<lyrics>")
        assertThat(withLyrics).contains("first line")
    }

    @Test
    fun `overlong lyrics are truncated with a marker`() {
        val lyrics = Lyrics(plain = listOf("x".repeat(SongQaContextBuilder.MAX_LYRICS_CHARS + 100)))
        val prompt = SongQaContextBuilder.systemPrompt(
            song = song,
            lyrics = lyrics,
            includeSongContext = true,
            appLanguageTag = "en-US"
        )

        assertThat(prompt).contains("[lyrics truncated]")
    }

    @Test
    fun `synced lyrics contribute text lines`() {
        val lyrics = Lyrics(
            synced = listOf(
                SyncedLine(time = 0, line = "line one"),
                SyncedLine(time = 100, line = "line two")
            )
        )

        assertThat(SongQaContextBuilder.lyricsText(lyrics)).isEqualTo("line one\nline two")
    }

    @Test
    fun `history is bounded to the newest turns and keeps the system prompt first`() {
        val history = (1..15).map { index ->
            AiChatMessage(
                role = if (index % 2 == 0) AiChatMessage.Role.ASSISTANT else AiChatMessage.Role.USER,
                content = "turn $index"
            )
        }

        val messages = SongQaContextBuilder.requestMessages("system prompt", history)

        assertThat(messages.first().role).isEqualTo(AiChatMessage.Role.SYSTEM)
        assertThat(messages.first().content).isEqualTo("system prompt")
        assertThat(messages.size).isEqualTo(SongQaContextBuilder.MAX_HISTORY_MESSAGES + 1)
        assertThat(messages.last().content).isEqualTo("turn 15")
        assertThat(messages.drop(1).first().content).isEqualTo("turn 4")
    }
}
