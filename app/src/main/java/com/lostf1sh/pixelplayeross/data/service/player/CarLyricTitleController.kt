package com.lostf1sh.pixelplayeross.data.service.player

import androidx.media3.common.Player
import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.data.repository.MusicRepository
import com.lostf1sh.pixelplayeross.utils.LyricCue
import com.lostf1sh.pixelplayeross.utils.buildLyricCues
import com.lostf1sh.pixelplayeross.utils.isInstrumentalPlaceholderLyrics
import com.lostf1sh.pixelplayeross.utils.nextCueTimeMs
import com.lostf1sh.pixelplayeross.utils.resolveCueIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Drives the "car lyric title" feature: instead of the track title, session consumers (Bluetooth
 * AVRCP stack, notification, SMTC) see the lyric line matching the playback position, which is how
 * head units that can only render Title/Artist end up scrolling lyrics.
 *
 * The override is published through [LyricTitlePlayer], which fakes a metadata-changed event
 * rather than mutating the playlist; see that class for why.
 *
 * **Segments.** A head unit renders only a fixed width of title, so a line wider than
 * [MAX_COLUMNS_PER_CUE] is published in several slices spread evenly over that line's own
 * duration, capped at 4 s apart so a gap or the track tail cannot push a slice into silence;
 * [buildLyricCues] does the cutting, and it measures width rather than characters — the
 * same field that fits ten Chinese characters fits thirty letters. Users whose head unit scrolls
 * long titles itself can turn the cutting off entirely, in which case every line goes out whole
 * and the unit does the scrolling.
 *
 * Scheduling, publishing and the de-duplication key all work on slices, so read "cue" below where
 * an earlier revision of this class read "line".
 *
 * **Lead.** Every cue is published `leadMs` *before* its timestamp, because the path from
 * [LyricTitlePlayer.publishMetadataOverride] to pixels on the head unit costs a few hundred
 * milliseconds (AVRCP round trip plus the unit redrawing), and a title that changes exactly on the
 * beat reads as late. The value comes from the "title lead" setting — it describes the car rather
 * than the song, so it is one value for the whole track and deliberately never a per-cue value: a
 * uniform shift leaves the interval between cues exactly as the lyrics define it, so the error
 * stays constant instead of accumulating. The one visible cost is that each cue also *ends* that
 * much earlier — unavoidable while the whole state is a single Title field.
 *
 * **Scheduling.** Media3 exposes no position callback, so the active cue has to be sampled. Rather
 * than sampling on a fixed interval, this controller sleeps until the *next cue* —
 * `delay = min(nextCue - position, WATCHDOG_INTERVAL_MS) / speed` — and recomputes from the live
 * position after every wake-up, so inaccuracy cannot accumulate. That is roughly one wake-up per
 * published cue instead of two per second, and **no** wake-up at all while paused, disabled, or
 * without synced lyrics. The watchdog cap is the self-healing fallback a fixed poll gets for free:
 * if an event we depend on never arrives, the title still catches up within
 * [WATCHDOG_INTERVAL_MS]. Buffering is the one case that takes the cap as-is — the position is
 * frozen, so there is no residual to derive and the interval is simply waited out again.
 *
 * Player events (seek, play/pause, track change, speed change, load completion) all just [signal] a
 * recompute through [Player.Listener.onEvents]; the service signals too for Bluetooth device
 * changes and for a MediaSession player replacement.
 *
 * Everything is gated — the real track title is kept unless all of these hold:
 * 1. the `car lyric title` toggle is on,
 * 2. the current output is a Bluetooth A2DP device,
 * 3. a song is loaded and it has synced lyrics,
 * 4. those lyrics carry real sung text, not just an instrumental-track placeholder.
 *
 * Condition 2 exists because the override is visible to *every* MediaSession consumer, not just the
 * head unit: the notification and the lock screen read the same metadata. Without it, staring at
 * the phone with wired headphones would also show a lyric line where the song title belongs.
 *
 * Note on "does the head unit support it": there is no public API to query the peer's AVRCP
 * version, and there does not need to be — a unit that cannot render metadata simply never issues
 * `GetElementAttributes`. The override is harmless in that case.
 */
class CarLyricTitleController(
    private val scope: CoroutineScope,
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val playerProvider: () -> LyricTitlePlayer?,
    private val isBluetoothOutputActive: () -> Boolean
) {

    private var enabled = false
    private var currentSongId: String? = null
    private var cues: List<LyricCue> = emptyList()
    private var syncOffsetMs = 0

    /**
     * Source lyrics of the loaded song, kept so the cut can be redone when the user toggles
     * splitting: [cues] is derived, and re-deriving needs the lines it came from.
     */
    private var lyricLines: List<SyncedLine> = emptyList()
    private var trackDurationMs = 0L

    /**
     * True when the loaded "lyrics" only announce an instrumental track. There is then nothing to
     * publish, so [cues] stays empty and every branch that restores the real title applies for the
     * whole song. Reset on every song change, like the lines it was derived from.
     */
    private var instrumentalPlaceholder = false

    /** Mirrors the user's "split long lines" setting; see [MAX_COLUMNS_PER_CUE]. */
    private var splitLongLines = true

    /**
     * How early every cue is published, mirrored from the user's "title lead" setting. Starts at 0 —
     * the safe direction (publish on the beat) — until the store has been read, which happens within
     * the first few ticks.
     */
    private var leadMs = 0

    /**
     * [LyricCue.sequence] of the last published cue, or one of the two sentinels in the companion.
     * The sequence — not the text — is the key, because two cues of one line can read alike and a
     * text key would swallow the second one.
     */
    private var lastPublishedCue = CUE_UNPUBLISHED
    private var lyricsJob: Job? = null

    /** Pending wake-up for the next cue; null while paused or past the last cue. */
    private var boundaryJob: Job? = null

    /** What [boundaryJob] is waiting for, so repeated signals cannot re-arm it. See [WakeUp]. */
    private var armedWakeUp: WakeUp? = null

    /** Wrapper the event listener is currently attached to; the service replaces it on swaps. */
    private var attachedPlayer: Player? = null

    /** Last reported gating state; only changes are logged so the loop stays quiet. */
    private var lastReportedState: String? = null

    private var lastBluetoothOutputActive = false

    /**
     * Conflated: a burst of events (a seek emits several) collapses into one recompute, and an
     * event arriving while a tick is already running is merged into the next one rather than lost.
     */
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)

    private val playerEventsListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = signal()
    }

    fun start() {
        scope.launch {
            userPreferencesRepository.carLyricTitleEnabledFlow.collect { value ->
                // Media3 verifies the application thread inside the metadata callback, so touch the
                // player from the main dispatcher rather than trusting the upstream context.
                withContext(Dispatchers.Main.immediate) {
                    if (enabled == value) return@withContext
                    enabled = value
                    if (!value) {
                        // Opting out restores the real title immediately instead of on the next
                        // boundary, otherwise the head unit would keep the last lyric line on
                        // screen.
                        stopScheduling()
                        publish(player = playerProvider(), cue = null, reason = "toggle off")
                    }
                    Timber.tag(TAG).d("car lyric title: toggle %s", if (value) "on" else "off")
                }
                signal()
            }
        }

        scope.launch {
            userPreferencesRepository.carLyricTitleLeadMsFlow.collect { value ->
                if (leadMs == value) return@collect
                leadMs = value
                // A different lead changes which cue is active right now, so recompute instead of
                // waiting for the next boundary — otherwise the new value would only take effect
                // from the following line and the slider would feel broken.
                signal()
            }
        }

        scope.launch {
            userPreferencesRepository.carLyricTitleSplitLongLinesFlow.collect { value ->
                if (splitLongLines == value) return@collect
                splitLongLines = value
                // Re-cutting renumbers the cues, and cue N of one cut says something different
                // from cue N of the other, so the de-duplication key has to be forgotten too —
                // otherwise the switch would appear to do nothing until the next line.
                lastPublishedCue = CUE_UNPUBLISHED
                rebuildCues()
                signal()
            }
        }

        scope.launch {
            for (unused in wakeUps) {
                // Never let a single bad tick kill the loop: the feature would then be silently
                // dead for the rest of the session, with nothing surfaced to the user.
                runCatching { tick() }
                    .onFailure { Timber.tag(TAG).w(it, "car lyric title: tick failed") }
            }
        }

        signal()
    }

    /** Requests a recompute of the published title. Safe to call from any thread. */
    fun signal() {
        wakeUps.trySend(Unit)
    }

    /**
     * Called when the service hands the MediaSession a new player wrapper (crossfade, engine swap).
     * The new instance starts with no override while [lastPublishedCue] still holds the previous
     * cue, which would suppress the re-publish and leave the real track title on screen until the
     * cue changes. Forgetting it makes the next tick publish to the new wrapper.
     */
    fun onPlayerReplaced() {
        lastPublishedCue = CUE_UNPUBLISHED
        signal()
    }

    private suspend fun tick() = withContext(Dispatchers.Main.immediate) {
        val player = playerProvider()
        if (player == null) {
            reportState("idle: player not created yet")
            return@withContext
        }
        ensureListenerAttached(player)

        if (!enabled) {
            reportState("idle: toggle off")
            stopScheduling()
            publish(player, null, reason = "toggle off")
            return@withContext
        }

        if (!refreshBluetoothOutput()) {
            reportState("idle: bluetooth output not active")
            stopScheduling()
            publish(player, null, reason = "output is not bluetooth")
            return@withContext
        }

        val mediaId = player.currentMediaItem?.mediaId
        if (mediaId == null) {
            reportState("idle: nothing playing")
            stopScheduling()
            publish(player, null, reason = "nothing playing")
            return@withContext
        }

        if (mediaId != currentSongId) {
            reportState("switching to song $mediaId")
            onSongChanged(player, mediaId)
            return@withContext
        }

        if (cues.isEmpty()) {
            val reason = if (instrumentalPlaceholder) {
                "instrumental placeholder lyrics"
            } else {
                "no synced lyrics"
            }
            reportState("idle: $reason for song $mediaId")
            stopScheduling()
            publish(player, null, reason = reason)
            return@withContext
        }

        // The lead belongs to the position used for *resolving*, which is what makes every cue
        // appear that much before its own timestamp while leaving the gaps between cues alone.
        val positionMs = player.currentPosition + syncOffsetMs + leadMs
        val index = resolveCueIndex(cues, positionMs)
        reportState("active: ${cues.size} cues")
        publish(
            player,
            cues.getOrNull(index),
            // An index of -1 is the intro; past it a cue without text can only be a marker line
            // that stands for an instrumental stretch.
            reason = if (index < 0) "before the first line" else "instrumental stretch"
        )
        scheduleNextWakeUp(player, positionMs, index)
    }

    /**
     * Switches the override over to [mediaId]. The previous override is dropped right away so the
     * outgoing track's lyric line never shows up under the incoming track's title.
     */
    private fun onSongChanged(player: LyricTitlePlayer, mediaId: String) {
        currentSongId = mediaId
        cues = emptyList()
        lyricLines = emptyList()
        instrumentalPlaceholder = false
        trackDurationMs = 0L
        syncOffsetMs = 0
        stopScheduling()
        lyricsJob?.cancel()
        publish(player, null, reason = "song changed, lyrics not loaded yet")
        lyricsJob = scope.launch {
            loadLyrics(player, mediaId)
            // Lyrics arriving is what makes scheduling possible again.
            signal()
        }
    }

    private suspend fun loadLyrics(player: Player, mediaId: String) {
        val song = runCatching { musicRepository.getSong(mediaId).first() }.getOrNull()
        if (song == null) {
            Timber.tag(TAG).d("car lyric title: no song for media id %s, keeping track title", mediaId)
            return
        }

        val offset = runCatching { userPreferencesRepository.getLyricsSyncOffset(mediaId) }
            .getOrDefault(0)
        // May hit the network on a cold cache, same as opening the lyrics sheet does; the result is
        // persisted, so the next play of this song resolves offline.
        val lyrics = runCatching { musicRepository.getLyrics(song) }.getOrNull()

        if (currentSongId != mediaId) return
        // Read here rather than up front, and on the main dispatcher: Media3 wants all player
        // access from one thread, and on the last line this duration *is* the end of the timeline,
        // so the longer the lyrics take to arrive the more likely it is to be known already.
        val durationMs = withContext(Dispatchers.Main.immediate) { player.duration }

        syncOffsetMs = offset
        val syncedLines = lyrics?.synced.orEmpty()
        instrumentalPlaceholder = isInstrumentalPlaceholderLyrics(syncedLines)
        // A placeholder "line" would otherwise be the only cue, and the last cue holds until the
        // end of the track. Keeping no lines at all reuses the existing empty-cue branch.
        lyricLines = if (instrumentalPlaceholder) emptyList() else syncedLines
        trackDurationMs = durationMs
        rebuildCues()
        Timber.tag(TAG).d(
            "car lyric title: loaded %d synced lines as %d cues for %s (offset %d ms, lead %d ms, split %s, instrumental placeholder %s)",
            lyricLines.size,
            cues.size,
            mediaId,
            offset,
            leadMs,
            splitLongLines,
            instrumentalPlaceholder
        )
    }

    /**
     * Re-derives [cues] from [lyricLines]. Called on load and whenever the split setting changes;
     * a no-op on the lines side, so an unknown duration or missing lyrics just yield no cues.
     */
    private fun rebuildCues() {
        cues = buildLyricCues(
            lines = lyricLines,
            trackDurationMs = trackDurationMs,
            maxColumnsPerCue = if (splitLongLines) MAX_COLUMNS_PER_CUE else UNSLICED_COLUMNS
        )
    }

    /**
     * The identity of one pending wake-up: everything [scheduleNextWakeUp]'s deadline is derived
     * from, other than the live position. A key that leaves one of them out makes the residual wait
     * for the next boundary instead of following the change — which is exactly how dragging the
     * "title lead" slider used to do nothing until the following line. Kept as a type rather than a
     * set of loose fields so adding an input cannot silently omit it from the key.
     */
    private data class WakeUp(
        val cueIndex: Int,
        val boundaryMs: Long,
        val speed: Float,
        val leadMs: Int,
        val syncOffsetMs: Int,
        val stalled: Boolean
    )

    /**
     * Arms the single pending wake-up, for the moment [index] stops being the active cue. Also the
     * watchdog: capping the delay at [WATCHDOG_INTERVAL_MS] means a missed event or a very sparse
     * lyric still gets refreshed instead of leaving the title stale forever.
     *
     * Idempotent for one transition: a burst of player events (startup, crossfade) asks for a
     * recompute over and over, and re-arming each time would cancel and relaunch the timer
     * needlessly. Skipping is safe because the deadline for a given [WakeUp] is absolute — the
     * residual is re-derived from the live position when the timer fires anyway.
     */
    private fun scheduleNextWakeUp(player: Player, positionMs: Long, index: Int) {
        // Paused. Nothing advances on its own, and resuming always arrives as an event, so there is
        // nothing to wake up for. Buffering is not this: `playWhenReady` stays true there, the
        // position moves on again by itself, and the timer is the only thing that notices when the
        // recovery event never comes (see `stalled` below).
        if (!player.playWhenReady) {
            stopScheduling()
            return
        }
        val boundaryMs = nextCueTimeMs(cues, index) ?: run {
            stopScheduling()
            return
        }
        // Lyric timestamps are wall-clock, so a time-stretched player reaches them proportionally
        // later. The previous fixed-interval poll was immune to this by construction.
        val speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
        val stalled = !player.isPlaying

        val wakeUp = WakeUp(index, boundaryMs, speed, leadMs, syncOffsetMs, stalled)
        if (boundaryJob?.isActive == true && armedWakeUp == wakeUp) {
            return
        }

        boundaryJob?.cancel()
        armedWakeUp = wakeUp

        // A stalled player has a frozen position, so "the residual" is a guess that would repeat
        // itself; the watchdog interval is the honest answer. It only has to be short enough to
        // catch a recovery that signalled nothing, and a recovery that does signal re-arms from the
        // real position because `stalled` is part of the key.
        val remainingMs =
            if (stalled) {
                WATCHDOG_INTERVAL_MS
            } else {
                ((boundaryMs - positionMs) / speed)
                    .toLong()
                    .coerceIn(MIN_WAKE_UP_DELAY_MS, WATCHDOG_INTERVAL_MS)
            }

        boundaryJob = scope.launch {
            delay(remainingMs)
            signal()
        }
        // Verbose on purpose: the wake-up cadence *is* the design, and this is the only way to see
        // it (a fixed poll has no such line). Suppressed in release by ReleaseTree.
        Timber.tag(TAG).v(
            "car lyric title: next wake in %d ms (cue %d, lead %d ms)",
            remainingMs,
            index,
            leadMs
        )
    }

    private fun stopScheduling() {
        boundaryJob?.cancel()
        boundaryJob = null
        armedWakeUp = null
    }

    private fun ensureListenerAttached(player: LyricTitlePlayer) {
        if (attachedPlayer === player) return
        attachedPlayer?.removeListener(playerEventsListener)
        player.addListener(playerEventsListener)
        attachedPlayer = player
    }

    /**
     * Reads the current output route. Ticks now happen once per lyric line (or per watchdog
     * interval) instead of twice a second, so the audio service round-trip is far rarer than it
     * was and the short-lived cache the polling version needed is gone.
     */
    private fun refreshBluetoothOutput(): Boolean {
        val active = isBluetoothOutputActive()
        if (active != lastBluetoothOutputActive) {
            lastBluetoothOutputActive = active
            Timber.tag(TAG).d(
                "car lyric title: bluetooth output %s",
                if (active) "active" else "inactive"
            )
        }
        return active
    }

    /** Logs [state] whenever it differs from the previous tick, so the loop stays quiet. */
    private fun reportState(state: String) {
        if (state == lastReportedState) return
        lastReportedState = state
        Timber.tag(TAG).d("car lyric title: %s", state)
    }

    /**
     * Replaces the exposed title with [cue]'s text, or restores the real metadata when there is no
     * cue to show (the intro before the first line, a marker line that stands for an instrumental
     * stretch, an instrumental-track placeholder, or one of the idle branches).
     *
     * De-duplicated on the cue's sequence rather than its text: a long line split in two can yield
     * two cues that read alike, and a text key would silently swallow the second one.
     *
     * @param reason which decision is handing the title back to the track. Only logged, and only
     *   when there is no text to publish: this fallback is otherwise invisible — the title simply
     *   reappears — which makes a report of "it goes back to the song name" impossible to attribute
     *   to one of the several paths that can do it.
     */
    private fun publish(player: LyricTitlePlayer?, cue: LyricCue?, reason: String = "") {
        if (player == null) return
        val key = cue?.sequence ?: CUE_TRACK_TITLE
        if (key == lastPublishedCue) return
        lastPublishedCue = key
        val line = cue?.text?.takeIf { it.isNotEmpty() }
        player.publishMetadataOverride(
            line?.let { player.innerPlayer.mediaMetadata.buildUpon().setTitle(it).build() }
        )
        if (line != null) {
            Timber.tag(TAG).d("car lyric title: %s", line)
        } else {
            Timber.tag(TAG).d("car lyric title: back to the track title (%s)", reason)
        }
    }

    companion object {
        private const val TAG = "MusicService_PixelPlayer"

        /**
         * Title columns per published cue. The head unit truncates its title field at roughly this
         * much *width* — measured on the model this was built for as ten Chinese characters, which
         * is the same field thirty Latin letters fit into (`titleColumns` in `LyricsTimelineUtils`),
         * so a wider line is published in several slices instead of being clipped.
         */
        private const val MAX_COLUMNS_PER_CUE = 30

        /** Budget large enough for any line, i.e. splitting turned off: the unit gets whole lines. */
        private const val UNSLICED_COLUMNS = Int.MAX_VALUE

        /** [publish] keys, chosen so they cannot collide with a real cue sequence. */
        private const val CUE_UNPUBLISHED = -2
        private const val CUE_TRACK_TITLE = -1

        /** Floor for the armed delay, so a boundary already in the past cannot spin the loop. */
        private const val MIN_WAKE_UP_DELAY_MS = 100L

        /** Ceiling for the armed delay: the fallback when no event arrives to reschedule us. */
        private const val WATCHDOG_INTERVAL_MS = 5_000L
    }
}
