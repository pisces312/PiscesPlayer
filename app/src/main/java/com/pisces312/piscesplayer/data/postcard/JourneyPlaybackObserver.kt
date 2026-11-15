/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §4.2, §4.2.1, §4.2.2, §10.1).
 */
package com.pisces312.piscesplayer.data.postcard

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.pisces312.piscesplayer.data.database.PostcardAttemptDao
import com.pisces312.piscesplayer.data.database.PostcardAttemptEntity
import com.pisces312.piscesplayer.data.database.PostcardAttemptStatus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Listens to the single master player (via `MusicService`) and drives the attempt state
 * machine (design §4.2.2). It does not touch playback: no pause, no seek, no queue edit.
 *
 * Threading (design §10.1): every `Player.Listener` callback runs on the main thread and only
 * mutates an in-memory snapshot plus launches a coroutine for the IO work. Room, tag reads and
 * master-chart reads all happen off the callback.
 *
 * Validity rules (design §4.2):
 * - a natural completion (AUTO_TRANSITION old item / STATE_ENDED / AUTO_TRANSITION fallback)
 *   with accumulated `isPlaying` time >= max(90 s, 60% of duration) settles;
 * - any forward seek over 2 s, a manual track change, a queue replacement or a playback error
 *   cancels the attempt — no reward;
 * - backward seeks do not reset history, and accumulated time is capped at the song duration
 *   so repeated seeking cannot inflate it;
 * - Quick Play previews and cloud rows never start an attempt.
 *
 * M0 registers manually ("带上它"), so an attempt begins at the current position and only
 * counts from that moment (design §4.4).
 */
@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class JourneyPlaybackObserver @Inject constructor(
    private val attemptDao: PostcardAttemptDao,
    private val factsLoader: com.pisces312.piscesplayer.data.postcard.generate.SongFactsLoader,
    private val settlement: JourneySettlement,
    @com.pisces312.piscesplayer.di.AppScope private val scope: CoroutineScope,
) {

    /** The one live attempt; null when nothing is being tracked. */
    private var current: AttemptSnapshot? = null

    private val mutex = Mutex()
    private val ioScope = CoroutineScope(scope.coroutineContext + kotlinx.coroutines.Dispatchers.IO)

    // ------------------------------------------------------------------ registration

    /**
     * Manual registration: start tracking the song currently loaded in [player], from its
     * current position. The outcome is three-valued so the caller's status machine can tell
     * "cannot travel" (cloud / quick play / unknown row / feature off) from "another song is
     * already travelling" — those must not look the same in the UI.
     */
    fun register(player: Player, enabled: Boolean): RegisterOutcome {
        if (!enabled) return RegisterOutcome.UNAVAILABLE
        val item = player.currentMediaItem ?: return RegisterOutcome.UNAVAILABLE
        if (current != null) return RegisterOutcome.ALREADY_TRACKING
        val snapshot = AttemptSnapshot(
            attemptId = UUID.randomUUID().toString(),
            mediaItem = item,
            startedPositionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: -1L,
            startedAtEpochMs = System.currentTimeMillis(),
        )
        current = snapshot
        val isPlaying = player.isPlaying
        if (isPlaying) snapshot.resume(SystemClock.elapsedRealtime())

        ioScope.launch {
            val facts = runCatching { factsLoader.load(item) }.getOrNull()
            var droppedUnresolvable = false
            // All attempt-row writes (insert here, cancel/settle updates) serialize on [mutex]:
            // a cancel that wins the race must not be silently overwritten by a late insert.
            mutex.withLock {
                if (current?.attemptId != snapshot.attemptId) {
                    // Cancelled or settled while facts were loading: never resurrect it.
                    return@withLock
                }
                if (facts == null) {
                    // Cannot travel; drop the attempt before it ever reaches the database.
                    current = null
                    droppedUnresolvable = true
                    return@withLock
                }
                snapshot.facts = facts
                runCatching {
                    attemptDao.insert(
                        PostcardAttemptEntity(
                            attemptId = snapshot.attemptId,
                            stableSongKey = facts.stableSongKey,
                            librarySongId = facts.librarySongId,
                            startedAtEpochMs = snapshot.startedAtEpochMs,
                            startedPositionMs = snapshot.startedPositionMs,
                            accumulatedPlayMs = 0L,
                            songDurationMs = facts.durationMs,
                            status = PostcardAttemptStatus.TRACKING,
                        ),
                    )
                }.onFailure { Log.w(TAG, "attempt insert failed", it) }
            }
            // Outside the lock: the bridge only owns a StateFlow, no deadlock risk.
            if (droppedUnresolvable) PostcardTrackingBridge.markIdle()
        }
        return RegisterOutcome.STARTED
    }

    /** Drops the live attempt without reward (user pressed "取消随行"). */
    fun cancelCurrent(reason: String = REASON_USER_CANCELLED) {
        val snapshot = current ?: return
        current = null
        PostcardTrackingBridge.markIdle()
        snapshot.finishPlayClock(SystemClock.elapsedRealtime())
        cancelAsync(snapshot, reason)
    }

    /** Process start: unresolved TRACKING rows cannot be resumed, so they are cancelled. */
    fun recoverAfterProcessDeath() {
        ioScope.launch {
            runCatching { attemptDao.cancelAllTracking(REASON_SERVICE_RESTART) }
        }
    }

    // ------------------------------------------------------------------ player events

    fun onIsPlayingChanged(player: Player, isPlaying: Boolean) {
        val snapshot = current ?: return
        val now = SystemClock.elapsedRealtime()
        if (isPlaying) snapshot.resume(now) else snapshot.pause(now)
    }

    fun onPositionDiscontinuity(
        player: Player,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        when (reason) {
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                // Natural end in the middle of a queue: the old item is the finished song.
                val snapshot = current ?: return
                if (!snapshot.matches(oldPosition.mediaItem)) return
                settle(snapshot, REASON_NATURAL_AUTO_TRANSITION)
            }
            Player.DISCONTINUITY_REASON_SEEK -> {
                val snapshot = current ?: return
                // Compare the two discontinuity positions, NOT "current - registration
                // position": after 100 s of normal playback a backwards seek to 50 s would
                // otherwise look like a +50 s forward jump and invalidate a valid listen.
                // Backward seeks are allowed and never reset accumulated time (design §4.2).
                val forwardMs = newPosition.positionMs - oldPosition.positionMs
                snapshot.lastKnownPositionMs = newPosition.positionMs.coerceAtLeast(0L)
                if (forwardMs > JourneyValidity.FORWARD_SEEK_TOLERANCE_MS) {
                    // Seeking ahead skips unheard audio; the attempt can no longer settle.
                    snapshot.invalidatedBySeek = true
                }
            }
            else -> Unit
        }
    }

    fun onMediaItemTransition(player: Player, mediaItem: MediaItem?, reason: Int) {
        when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                // Fallback for players that skip onPositionDiscontinuity: settle the old one.
                val snapshot = current ?: return
                if (snapshot.matches(mediaItem)) return
                settle(snapshot, REASON_NATURAL_FALLBACK)
            }
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                // Single-track loop: the same item ends and restarts. M0 registration is
                // manual, so we settle this attempt and do NOT open a new one — design
                // §4.2.2's "every loop completion forms a journey" arrives with M1 auto-follow.
                val snapshot = current ?: return
                if (!snapshot.matches(mediaItem)) return
                settle(snapshot, REASON_NATURAL_REPEAT)
            }
            else -> {
                // Manual next / previous / user picked another song / queue replaced.
                val snapshot = current ?: return
                cancelLive(snapshot, if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    REASON_QUEUE_CHANGED
                } else {
                    REASON_MANUAL_SKIP
                })
            }
        }
    }

    fun onPlaybackStateChanged(player: Player, playbackState: Int) {
        val snapshot = current ?: return
        when (playbackState) {
            // End of queue or a single song finishing outside a queue transition.
            Player.STATE_ENDED -> settle(snapshot, REASON_NATURAL_ENDED)
            Player.STATE_IDLE -> {
                // Playback error or the player was reset: never reward, never resume this one.
                if (player.playerError != null) cancelLive(snapshot, REASON_PLAYBACK_ERROR)
            }
            else -> Unit
        }
    }

    fun onPlayerError(player: Player, error: androidx.media3.common.PlaybackException) {
        val snapshot = current ?: return
        cancelLive(snapshot, REASON_PLAYBACK_ERROR)
    }

    // ------------------------------------------------------------------ settlement

    /**
     * Validates the snapshot and hands it to [JourneySettlement] in a single Room transaction.
     * The live attempt is cleared before the DB work so duplicate callbacks cannot double-settle.
     */
    private fun settle(snapshot: AttemptSnapshot, reason: String) {
        if (current?.attemptId != snapshot.attemptId) return
        current = null
        PostcardTrackingBridge.markIdle()
        snapshot.finishPlayClock(SystemClock.elapsedRealtime())

        val facts = snapshot.facts
        if (facts == null) {
            cancelAsync(snapshot, REASON_UNRESOLVED)
            return
        }
        if (snapshot.invalidatedBySeek) {
            cancelAsync(snapshot, REASON_FORWARD_SEEK)
            return
        }
        val effectiveDurationMs = snapshot.durationMs.takeIf { it > 0L } ?: facts.durationMs
        if (!isValidListen(effectiveDurationMs, snapshot.accumulatedMs, invalidatedBySeek = false)) {
            cancelAsync(snapshot, REASON_TOO_SHORT)
            return
        }
        val completedAt = System.currentTimeMillis()
        ioScope.launch {
            // Serialize with the register-time insert on [mutex]: if registration's facts
            // load has not inserted the row yet, the transaction below finds no attempt and
            // returns null, and the insert's liveness re-check then skips — both orders are
            // consistent and can never leave a phantom TRACKING row behind.
            mutex.withLock {
                runCatching {
                    attemptDao.updateAccumulated(snapshot.attemptId, snapshot.accumulatedMs)
                    settlement.settle(
                        SettlementRequest(
                            attemptId = snapshot.attemptId,
                            facts = facts,
                            completedAtEpochMs = completedAt,
                            zoneId = java.time.ZoneId.systemDefault(),
                            languageTag = java.util.Locale.getDefault().language,
                        )
                    )
                }.onFailure { error ->
                    Log.w(TAG, "settlement failed for ${snapshot.attemptId}", error)
                    // Keep the attempt retryable per design §10.1 (retry on next start, no re-charge).
                    runCatching {
                        attemptDao.updateStatus(
                            snapshot.attemptId,
                            PostcardAttemptStatus.SETTLEMENT_FAILED,
                            reason,
                        )
                    }
                }
            }
        }
    }

    private fun cancelLive(snapshot: AttemptSnapshot, reason: String) {
        if (current?.attemptId != snapshot.attemptId) return
        current = null
        PostcardTrackingBridge.markIdle()
        snapshot.finishPlayClock(SystemClock.elapsedRealtime())
        cancelAsync(snapshot, reason)
    }

    private fun cancelAsync(snapshot: AttemptSnapshot, reason: String) {
        ioScope.launch {
            // Serialize with the register-time insert on [mutex]; the caller already cleared
            // `current` on the main thread, so a late insert sees a dead attempt and skips.
            mutex.withLock {
                runCatching {
                    attemptDao.updateAccumulated(snapshot.attemptId, snapshot.accumulatedMs)
                    attemptDao.updateStatus(snapshot.attemptId, PostcardAttemptStatus.CANCELLED, reason)
                }.onFailure { Log.w(TAG, "cancel update failed for ${snapshot.attemptId}", it) }
            }
        }
    }

    /**
     * The validity floor: both 90 s AND 60% of the duration must be reached (design §4.2).
     * Songs shorter than 90 s therefore never settle, as intended.
     */
    private fun requiredPlayMs(durationMs: Long): Long = JourneyValidity.requiredPlayMs(durationMs)

    /** Validity verdict shared with the settlement decision (see [JourneyValidity]). */
    private fun isValidListen(durationMs: Long, accumulatedMs: Long, invalidatedBySeek: Boolean): Boolean =
        JourneyValidity.isValidListen(durationMs, accumulatedMs, invalidatedBySeek)

    // ------------------------------------------------------------------ snapshot

    /**
     * Everything the settlement needs from the moment tracking began. Holding the [MediaItem]
     * (not the song id) is deliberate: after an auto transition the player already points at the
     * next song, so reading `currentMediaItem` at settle time would settle the wrong one.
     */
    private class AttemptSnapshot(
        val attemptId: String,
        val mediaItem: MediaItem,
        val startedPositionMs: Long,
        val durationMs: Long,
        val startedAtEpochMs: Long,
    ) {
        var facts: com.pisces312.piscesplayer.data.postcard.generate.SongFacts? = null
        var accumulatedMs: Long = 0L
        var invalidatedBySeek: Boolean = false
        var lastKnownPositionMs: Long = startedPositionMs

        private var playingSinceElapsedMs: Long? = null

        fun matches(item: MediaItem?): Boolean =
            item != null && item.mediaId.isNotBlank() && item.mediaId == mediaItem.mediaId

        fun resume(nowElapsedMs: Long) {
            if (playingSinceElapsedMs == null) playingSinceElapsedMs = nowElapsedMs
        }

        fun pause(nowElapsedMs: Long) {
            val since = playingSinceElapsedMs ?: return
            accumulatedMs += (nowElapsedMs - since).coerceAtLeast(0L)
            playingSinceElapsedMs = null
        }

        /** Closes an open playing interval; accumulated time is capped at the song duration. */
        fun finishPlayClock(nowElapsedMs: Long) {
            pause(nowElapsedMs)
            val cap = if (durationMs > 0L) durationMs else Long.MAX_VALUE
            accumulatedMs = accumulatedMs.coerceAtMost(cap)
        }
    }

    companion object {
        private const val TAG = "PostcardJourney"

        /** Result of [register], consumed by `PostcardTrackingBridge`'s status machine. */
        enum class RegisterOutcome { STARTED, ALREADY_TRACKING, UNAVAILABLE }

        /** Seeking forward by more than this invalidates the attempt (design §4.2). */
        const val FORWARD_SEEK_TOLERANCE_MS = JourneyValidity.FORWARD_SEEK_TOLERANCE_MS

        const val REASON_NATURAL_AUTO_TRANSITION = "natural_auto_transition"
        const val REASON_NATURAL_FALLBACK = "natural_transition_fallback"
        const val REASON_NATURAL_REPEAT = "natural_repeat"
        const val REASON_NATURAL_ENDED = "natural_state_ended"
        const val REASON_FORWARD_SEEK = "forward_seek"
        const val REASON_MANUAL_SKIP = "manual_skip"
        const val REASON_QUEUE_CHANGED = "queue_changed"
        const val REASON_PLAYBACK_ERROR = "playback_error"
        const val REASON_TOO_SHORT = "too_short"
        const val REASON_UNRESOLVED = "unresolved_song"
        const val REASON_SERVICE_RESTART = "service_restart"
        const val REASON_USER_CANCELLED = "user_cancelled"
    }
}
