package com.pisces312.piscesplayer.data.service.player

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Covers the two things `LyricTitlePlayer` adds on top of a plain `ForwardingPlayer`: the metadata
 * override, and the withdrawal of [Player.COMMAND_GET_TIMELINE] that comes with it (the AVRCP
 * "metadata sync" gate short-circuit — see `docs/car-lyrics-avrcp-gate.md`).
 *
 * `Player.Commands` is mocked rather than built: its backing `FlagSet` goes through an `android.jar`
 * method that this module's unit tests answer with a default value
 * (`testOptions.unitTests.isReturnDefaultValues = true`), so a real `Commands` would report itself
 * empty here. The mock keeps the assertions about *our* behaviour — which command we drop, when,
 * and what consumers are told — which is what this class is responsible for.
 */
@OptIn(UnstableApi::class)
class LyricTitlePlayerTest {

    /** A `Commands` stand-in: everything is available except, when [timeline] is false, the timeline. */
    private fun commandsOf(timeline: Boolean): Player.Commands {
        val commands = mockk<Player.Commands>()
        every { commands.contains(any()) } returns true
        every { commands.contains(Player.COMMAND_GET_TIMELINE) } returns timeline
        return commands
    }

    private fun innerPlayer(hasTimeline: Boolean = true): Player {
        val commands = commandsOf(hasTimeline)
        // What `commands.buildUpon().removeIf(...).build()` yields: the same set minus the timeline.
        val withoutTimeline = commandsOf(false)
        val builder = mockk<Player.Commands.Builder>(relaxed = true)
        every { commands.buildUpon() } returns builder
        every { builder.removeIf(any(), any()) } returns builder
        every { builder.build() } returns withoutTimeline

        val player = mockk<Player>(relaxed = true)
        every { player.availableCommands } returns commands
        every { player.isCommandAvailable(any()) } answers { commands.contains(firstArg()) }
        return player
    }

    /** Records the events a session consumer would see, in the order they are dispatched. */
    private class RecordingListener : Player.Listener {
        val events = mutableListOf<String>()

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            events += "commands(timeline=${availableCommands.contains(Player.COMMAND_GET_TIMELINE)})"
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            events += "metadata(title=${mediaMetadata.title})"
        }
    }

    @Test
    fun `timeline command is available while no override is published`() {
        val player = LyricTitlePlayer(innerPlayer())

        assertThat(player.availableCommands.contains(Player.COMMAND_GET_TIMELINE)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).isTrue()
    }

    @Test
    fun `publishing an override withdraws the timeline command on both accessors`() {
        val player = LyricTitlePlayer(innerPlayer())

        player.publishMetadataOverride(MediaMetadata.Builder().setTitle("first line").build())

        assertThat(player.availableCommands.contains(Player.COMMAND_GET_TIMELINE)).isFalse()
        assertThat(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).isFalse()
    }

    @Test
    fun `withdrawal only affects the timeline command`() {
        val player = LyricTitlePlayer(innerPlayer())

        player.publishMetadataOverride(MediaMetadata.Builder().setTitle("first line").build())

        // Everything a remote device drives over AVRCP passthrough / Android Auto / Wear has to
        // survive, otherwise a head unit would lose play-pause or track skipping for as long as a
        // lyric line is on screen. COMMAND_SEEK_TO_NEXT(_MEDIA_ITEM) is what `MediaSessionLegacyStub
        // .onSkipToNext()` dispatches; COMMAND_GET_METADATA gates the artwork the head unit reads.
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_GET_METADATA)).isTrue()
    }

    @Test
    fun `inner player commands are never mutated`() {
        val inner = innerPlayer()
        val player = LyricTitlePlayer(inner)

        player.publishMetadataOverride(MediaMetadata.Builder().setTitle("first line").build())

        assertThat(inner.availableCommands.contains(Player.COMMAND_GET_TIMELINE)).isTrue()
        assertThat(inner.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).isTrue()
    }

    @Test
    fun `a command the inner player never had stays unavailable`() {
        val player = LyricTitlePlayer(innerPlayer(hasTimeline = false))

        assertThat(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).isFalse()
    }

    @Test
    fun `command event is dispatched before the metadata event`() {
        val player = LyricTitlePlayer(innerPlayer())
        val listener = RecordingListener()
        player.addListener(listener)

        player.publishMetadataOverride(MediaMetadata.Builder().setTitle("first line").build())

        assertThat(listener.events).containsExactly(
            "commands(timeline=false)",
            "metadata(title=first line)"
        ).inOrder()
    }

    @Test
    fun `restoring the real title brings the timeline command back`() {
        val player = LyricTitlePlayer(innerPlayer())
        val listener = RecordingListener()
        player.addListener(listener)

        player.publishMetadataOverride(MediaMetadata.Builder().setTitle("first line").build())
        player.publishMetadataOverride(null)

        assertThat(listener.events).containsExactly(
            "commands(timeline=false)",
            "metadata(title=first line)",
            "commands(timeline=true)",
            "metadata(title=null)"
        ).inOrder()
        assertThat(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).isTrue()
    }

    @Test
    fun `republishing the same override dispatches nothing`() {
        val player = LyricTitlePlayer(innerPlayer())
        val listener = RecordingListener()
        player.addListener(listener)
        val override = MediaMetadata.Builder().setTitle("first line").build()

        player.publishMetadataOverride(override)
        player.publishMetadataOverride(override)

        assertThat(listener.events).hasSize(2)
    }

    @Test
    fun `consecutive overrides dispatch metadata without re-announcing commands`() {
        val player = LyricTitlePlayer(innerPlayer())
        val listener = RecordingListener()
        player.addListener(listener)

        player.publishMetadataOverride(MediaMetadata.Builder().setTitle("first line").build())
        player.publishMetadataOverride(MediaMetadata.Builder().setTitle("second line").build())

        assertThat(listener.events).containsExactly(
            "commands(timeline=false)",
            "metadata(title=first line)",
            "metadata(title=second line)"
        ).inOrder()
    }

    @Test
    fun `exposed metadata is the override and falls back to the inner player`() {
        val inner = innerPlayer()
        every { inner.mediaMetadata } returns MediaMetadata.Builder().setTitle("Real Title").build()
        val player = LyricTitlePlayer(inner)

        assertThat(player.mediaMetadata.title.toString()).isEqualTo("Real Title")

        player.publishMetadataOverride(MediaMetadata.Builder().setTitle("lyric line").build())
        assertThat(player.mediaMetadata.title.toString()).isEqualTo("lyric line")

        player.publishMetadataOverride(null)
        assertThat(player.mediaMetadata.title.toString()).isEqualTo("Real Title")
    }

    @Test
    fun `timeline reads still forward the inner player`() {
        val inner = innerPlayer()
        every { inner.currentTimeline } returns Timeline.EMPTY
        every { inner.mediaItemCount } returns 42
        every { inner.currentMediaItemIndex } returns 7
        val player = LyricTitlePlayer(inner)

        // The withdrawal must not turn into a degraded timeline of our own: Media3 derives
        // `CurrentMediaItemOnlyTimeline` from the missing command, which is what controllers see.
        player.publishMetadataOverride(MediaMetadata.Builder().setTitle("lyric line").build())

        assertThat(player.currentTimeline).isSameInstanceAs(Timeline.EMPTY)
        assertThat(player.mediaItemCount).isEqualTo(42)
        assertThat(player.currentMediaItemIndex).isEqualTo(7)
    }

    @Test
    fun `listeners removed before an override hear nothing`() {
        val player = LyricTitlePlayer(innerPlayer())
        val listener = RecordingListener()
        player.addListener(listener)
        player.removeListener(listener)

        player.publishMetadataOverride(MediaMetadata.Builder().setTitle("first line").build())

        assertThat(listener.events).isEmpty()
    }
}
