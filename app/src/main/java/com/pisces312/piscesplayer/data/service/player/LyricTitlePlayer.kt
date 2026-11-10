package com.pisces312.piscesplayer.data.service.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Outermost session-facing player wrapper that lets the service replace the outgoing media
 * metadata (typically the title) without touching the inner player's playlist.
 *
 * Media3's [androidx.media3.session.MediaSession] caches metadata from
 * `Player.Listener.onMediaMetadataChanged` and pushes it to the legacy session stub, which is
 * exactly the channel the Bluetooth AVRCP stack reads (see `MediaSessionImpl.onMediaMetadataChanged`).
 * Overriding [getMediaMetadata] alone is therefore not enough: external consumers only refresh
 * when that listener callback fires. The only supported way to produce the callback through the
 * inner player is `replaceMediaItem`/`setMediaItem`, which also emits `onMediaItemTransition`
 * and triggers unrelated side effects (listening-stats sync, scrobble, snapshot persistence).
 *
 * This wrapper keeps its own reference to every registered listener so it can dispatch
 * `onMediaMetadataChanged` directly, giving session consumers a metadata update with zero
 * playlist mutation on the inner player.
 *
 * While an override is published this wrapper also **withdraws [Player.COMMAND_GET_TIMELINE]**.
 * The Bluetooth AVRCP stack refuses to forward a metadata update while the playing queue does not
 * agree with it, and retries two seconds later — a title that changes every line therefore never
 * reaches the head unit. With the command withdrawn, Media3 reports no active queue item
 * (`MediaSessionLegacyStub`), which is one of that gate's documented short-circuits, so every
 * override is forwarded immediately. See `docs/car-lyrics-avrcp-gate.md`.
 *
 * Listeners registered through [addListener] are still forwarded to the inner player, so all
 * other events behave exactly as before.
 */
@OptIn(UnstableApi::class)
class LyricTitlePlayer(
    val innerPlayer: Player
) : ForwardingPlayer(innerPlayer) {

    /**
     * Mirror of the listeners registered on this wrapper. Media3 requires session consumers to
     * receive exactly the events they subscribed to; we reuse this list to emit the synthetic
     * metadata update without going through the inner player.
     */
    private val registeredListeners = CopyOnWriteArrayList<Player.Listener>()

    @Volatile
    private var metadataOverride: MediaMetadata? = null

    /**
     * Whether [Player.COMMAND_GET_TIMELINE] is currently withdrawn. Kept in sync with
     * [metadataOverride] being non-null: only a published override can disagree with the playing
     * queue, so the queue only has to disappear for exactly as long as it would.
     */
    @Volatile
    private var timelineHidden = false

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        if (!registeredListeners.contains(listener)) {
            registeredListeners.add(listener)
        }
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        registeredListeners.remove(listener)
    }

    override fun getMediaMetadata(): MediaMetadata {
        return metadataOverride ?: super.getMediaMetadata()
    }

    /**
     * The commands this wrapper reports to session consumers: the inner player's, minus
     * [Player.COMMAND_GET_TIMELINE] while an override is published.
     *
     * Both this method and [isCommandAvailable] have to report the withdrawal. `ForwardingPlayer`
     * implements them independently of each other, and Media3 reads the latter when it derives the
     * legacy session's active queue item.
     */
    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        if (!timelineHidden) return commands
        return commands.buildUpon().removeIf(Player.COMMAND_GET_TIMELINE, true).build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        if (timelineHidden && command == Player.COMMAND_GET_TIMELINE) return false
        return super.isCommandAvailable(command)
    }

    /**
     * Replaces the metadata exposed to session consumers. Pass `null` to fall back to the inner
     * player's metadata. Must be called on the application thread, because Media3 verifies the
     * thread inside the listener callback.
     */
    fun publishMetadataOverride(override: MediaMetadata?) {
        if (metadataOverride == override) return
        metadataOverride = override
        // Commands first: the session rebuilds its playback state (and hence the active queue item)
        // from this event, so the queue must already be gone by the time the new title is handed
        // over — otherwise the AVRCP gate would see the disagreement it is waiting on.
        setTimelineHidden(override != null)
        val published = mediaMetadata
        registeredListeners.forEach { listener ->
            runCatching { listener.onMediaMetadataChanged(published) }
        }
    }

    /**
     * Withdraws or restores [Player.COMMAND_GET_TIMELINE], telling the session about it. The inner
     * player's commands never change, so nobody would emit this event for us.
     */
    private fun setTimelineHidden(hidden: Boolean) {
        if (timelineHidden == hidden) return
        timelineHidden = hidden
        val commands = availableCommands
        registeredListeners.forEach { listener ->
            runCatching { listener.onAvailableCommandsChanged(commands) }
        }
    }
}
