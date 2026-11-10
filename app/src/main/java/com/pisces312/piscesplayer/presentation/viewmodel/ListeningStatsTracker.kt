package com.pisces312.piscesplayer.presentation.viewmodel

import android.os.SystemClock
import androidx.media3.common.C
import com.pisces312.piscesplayer.data.DailyMixManager
import com.pisces312.piscesplayer.data.listenbrainz.ScrobbleManager
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.stats.PlaybackStatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Tracks listening statistics for songs.
 * Extracted from PlayerViewModel to reduce its size and improve modularity.
 *
 * Responsibilities:
 * - Track active listening sessions
 * - Record play statistics when session ends
 * - Handle voluntary vs automatic plays
 */
@Singleton
class ListeningStatsTracker @Inject constructor(
    private val dailyMixManager: DailyMixManager,
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val scrobbleManager: ScrobbleManager
) {
    private var currentSession: ActiveSession? = null
    private var pendingVoluntarySongId: String? = null
    private var scope: CoroutineScope? = null
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _playbackHistory = MutableStateFlow<List<PlaybackStatsRepository.PlaybackHistoryEntry>>(emptyList())
    val playbackHistory: StateFlow<List<PlaybackStatsRepository.PlaybackHistoryEntry>> = _playbackHistory.asStateFlow()

    /**
     * The ViewModel that owns this holder. This is a `@Singleton`, so a second
     * ViewModel instance must never be able to re-bind (or tear down) the first one.
     */
    private var owner: Any? = null

    /**
     * Must be called to set the coroutine scope for async operations.
     *
     * Unlike the other holders, this one has two legitimate callers (`PlayerViewModel` and
     * `MusicService`). Initialize is therefore open to both — only [onCleared] is owner-guarded
     * so a stray second `PlayerViewModel` cannot finalize the in-flight listening session.
     */
    fun initialize(owner: Any, coroutineScope: CoroutineScope) {
        if (this.owner == null) {
            this.owner = owner
        }
        val activeScope = scope
        if (activeScope == null || activeScope.coroutineContext[Job]?.isActive != true) {
            scope = coroutineScope
        }
        coroutineScope.launch(Dispatchers.IO) {
            _playbackHistory.value = playbackStatsRepository.loadPlaybackHistory(
                limit = MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS
            )
        }
    }

    @Synchronized
    fun onVoluntarySelection(songId: String) {
        pendingVoluntarySongId = songId
    }

    fun onSongChanged(
        song: Song?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        onTrackChanged(
            songId = song?.id,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = song?.duration ?: 0L,
            isPlaying = isPlaying
        )
    }

    @Synchronized
    fun onTrackChanged(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        onTrackChanged(
            songId = songId,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = 0L,
            isPlaying = isPlaying
        )
    }

    @Synchronized
    fun onTrackChanged(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        fallbackDurationMs: Long,
        isPlaying: Boolean
    ) {
        finalizeCurrentSession()
        val safeSongId = songId?.takeIf { it.isNotBlank() }
        if (safeSongId == null) {
            return
        }

        val nowRealtime = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        val normalizedDuration = normalizeDuration(durationMs, fallbackDurationMs)

        currentSession = ActiveSession(
            songId = safeSongId,
            totalDurationMs = normalizedDuration,
            startedAtEpochMs = nowEpoch,
            lastKnownPositionMs = positionMs.coerceAtLeast(0L),
            accumulatedListeningMs = 0L,
            lastRealtimeMs = nowRealtime,
            lastUpdateEpochMs = nowEpoch,
            isPlaying = isPlaying,
            isVoluntary = pendingVoluntarySongId == safeSongId
        )
        if (pendingVoluntarySongId == safeSongId) {
            pendingVoluntarySongId = null
        }
        if (isPlaying) {
            scrobbleManager.onPlayingNow(safeSongId)
        }
    }

    @Synchronized
    fun onPlayStateChanged(isPlaying: Boolean, positionMs: Long) {
        val session = currentSession ?: return
        val wasPlaying = session.isPlaying
        val nowRealtime = SystemClock.elapsedRealtime()
        accumulateRealtimeListening(session, nowRealtime)
        session.isPlaying = isPlaying
        session.lastRealtimeMs = nowRealtime
        session.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
        session.lastUpdateEpochMs = System.currentTimeMillis()
        if (!wasPlaying && isPlaying) {
            scrobbleManager.onPlayingNow(session.songId)
        }
    }

    @Synchronized
    fun onProgress(positionMs: Long, isPlaying: Boolean) {
        val session = currentSession ?: return
        val nowRealtime = SystemClock.elapsedRealtime()
        accumulateRealtimeListening(session, nowRealtime)
        session.isPlaying = isPlaying
        session.lastRealtimeMs = nowRealtime
        session.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
        session.lastUpdateEpochMs = System.currentTimeMillis()
    }

    fun ensureSession(
        song: Song?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        ensureSession(
            songId = song?.id,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = song?.duration ?: 0L,
            isPlaying = isPlaying
        )
    }

    @Synchronized
    fun ensureSession(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        ensureSession(
            songId = songId,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = 0L,
            isPlaying = isPlaying
        )
    }

    @Synchronized
    fun ensureSession(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        fallbackDurationMs: Long,
        isPlaying: Boolean
    ) {
        val safeSongId = songId?.takeIf { it.isNotBlank() }
        if (safeSongId == null) {
            finalizeCurrentSession()
            return
        }
        val existing = currentSession
        if (existing?.songId == safeSongId) {
            val wasPlaying = existing.isPlaying
            updateDuration(normalizeDuration(durationMs, fallbackDurationMs))
            val nowRealtime = SystemClock.elapsedRealtime()
            accumulateRealtimeListening(existing, nowRealtime)
            existing.isPlaying = isPlaying
            existing.lastRealtimeMs = nowRealtime
            existing.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
            existing.lastUpdateEpochMs = System.currentTimeMillis()
            if (!wasPlaying && isPlaying) {
                scrobbleManager.onPlayingNow(existing.songId)
            }
            return
        }
        onTrackChanged(
            songId = safeSongId,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = fallbackDurationMs,
            isPlaying = isPlaying
        )
    }

    @Synchronized
    fun updateDuration(durationMs: Long) {
        val session = currentSession ?: return
        if (durationMs > 0 && durationMs != C.TIME_UNSET) {
            session.totalDurationMs = durationMs
        }
    }

    @Synchronized
    fun finalizeCurrentSession(forceSynchronousPersistence: Boolean = false) {
        val session = currentSession ?: return
        val nowRealtime = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        accumulateRealtimeListening(session, nowRealtime)
        val listened = session.accumulatedListeningMs.coerceAtLeast(0L)
        scrobbleManager.onSessionFinalized(
            songId = session.songId,
            startedAtEpochMs = session.startedAtEpochMs,
            listenedMs = listened,
            trackDurationMs = session.totalDurationMs
        )
        if (listened >= MIN_SESSION_LISTEN_MS) {
            val rawEndTimestamp = when {
                session.isPlaying -> nowEpoch
                session.lastUpdateEpochMs > 0L -> session.lastUpdateEpochMs
                else -> session.startedAtEpochMs + listened
            }
            val timestamp = rawEndTimestamp
                .coerceAtLeast(session.startedAtEpochMs.coerceAtLeast(0L))
                .coerceAtMost(nowEpoch)
            val songId = session.songId
            val historyEntry = PlaybackStatsRepository.PlaybackHistoryEntry(
                songId = songId,
                timestamp = timestamp,
                durationMs = listened
            )
            _playbackHistory.update { current ->
                (listOf(historyEntry) + current).take(MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS)
            }
            persistPlayback(
                songId = songId,
                listened = listened,
                timestamp = timestamp,
                forceSynchronous = forceSynchronousPersistence
            )
        }
        currentSession = null
        if (pendingVoluntarySongId == session.songId) {
            pendingVoluntarySongId = null
        }
    }

    @Synchronized
    fun onPlaybackStopped() {
        finalizeCurrentSession()
    }

    @Synchronized
    fun onCleared(owner: Any) {
        val currentOwner = this.owner
        if (currentOwner != null && currentOwner !== owner) {
            Timber.w(
                "ListeningStatsTracker.onCleared ignored: called by %s but owned by %s",
                owner::class.java.simpleName,
                currentOwner::class.java.simpleName
            )
            return
        }
        finalizeCurrentSession(forceSynchronousPersistence = true)
        scope = null
        this.owner = null
    }

    /**
     * 当前这段「还没落盘」的收听，供统计页当作内存事件叠加 —— 这样打开统计页既能看到最新时长，
     * 又不必为了看到它而重写一次整个历史文件。
     *
     * 本函数**只读**：不改动 session 的任何字段，因此对同一个 now 是幂等的 —— 连点多次刷新也只会
     * 返回同一个区间，不会像写回的累加器那样把时长翻倍。
     *
     * 落盘只发生在 [finalizeCurrentSession]（换歌 / 播放停止 / `onCleared`），所以这里返回的**总是
     * 「本 session 从开始至今的整段」**，与最终落盘的那条事件区间严格相等 —— 既不会重复计时，
     * 也不会把一次连续播放记成多次。
     *
     * 返回 null 表示没有活跃会话，或累计时长还不到 [MIN_SESSION_LISTEN_MS]。
     */
    @Synchronized
    fun pendingFragment(
        nowMillis: Long = System.currentTimeMillis()
    ): PlaybackStatsRepository.PlaybackEvent? {
        val session = currentSession ?: return null
        // 不能复用 accumulateRealtimeListening：它会写 session.accumulatedListeningMs。
        val unsampledMs = if (session.isPlaying) {
            (SystemClock.elapsedRealtime() - session.lastRealtimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val listened = (session.accumulatedListeningMs + unsampledMs).coerceAtLeast(0L)
        if (listened < MIN_SESSION_LISTEN_MS) return null
        val rawEndTimestamp = when {
            session.isPlaying -> nowMillis
            session.lastUpdateEpochMs > 0L -> session.lastUpdateEpochMs
            else -> session.startedAtEpochMs + listened
        }
        // 区间口径与 finalizeCurrentSession 一致：end 落在 [startedAtEpochMs, now]，区间为 [end − listened, end]。
        // 落盘只在 finalize 发生，所以这里给出的就是那条待落盘事件的区间，二者严格相等（不重复计时）。
        val endTimestamp = rawEndTimestamp
            .coerceAtLeast(session.startedAtEpochMs.coerceAtLeast(0L))
            .coerceAtMost(nowMillis)
        return PlaybackStatsRepository.PlaybackEvent(
            songId = session.songId,
            timestamp = endTimestamp,
            durationMs = listened,
            startTimestamp = (endTimestamp - listened).coerceAtLeast(0L),
            endTimestamp = endTimestamp
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun persistPlayback(
        songId: String,
        listened: Long,
        timestamp: Long,
        forceSynchronous: Boolean
    ) {
        persistenceScope.launch {
            runCatching {
                persistPlaybackInternal(songId = songId, listened = listened, timestamp = timestamp)
            }.onFailure { throwable ->
                Timber.e(throwable, "Failed to persist listening session for song=%s", songId)
            }
        }
    }

    private suspend fun persistPlaybackInternal(songId: String, listened: Long, timestamp: Long) {
        dailyMixManager.recordPlay(
            songId = songId,
            songDurationMs = listened,
            timestamp = timestamp
        )
        playbackStatsRepository.recordPlayback(
            songId = songId,
            durationMs = listened,
            timestamp = timestamp
        )
    }

    private fun accumulateRealtimeListening(session: ActiveSession, nowRealtime: Long) {
        if (!session.isPlaying) return
        val delta = (nowRealtime - session.lastRealtimeMs).coerceAtLeast(0L)
        if (delta > 0L) {
            session.accumulatedListeningMs += delta
        }
    }

    private fun normalizeDuration(durationMs: Long, fallbackDurationMs: Long): Long {
        return when {
            durationMs > 0 && durationMs != C.TIME_UNSET -> durationMs
            fallbackDurationMs > 0 && fallbackDurationMs != C.TIME_UNSET -> fallbackDurationMs
            else -> 0L
        }
    }

    companion object {
        private val MIN_SESSION_LISTEN_MS = TimeUnit.SECONDS.toMillis(5)
        /**
         * Kept in memory for the Recently Played screen. History itself is no longer pruned by
         * age, so this is a UI cap rather than a data retention limit.
         */
        private const val MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS = 2_000
    }
}

/**
 * Represents an active listening session for a song.
 */
data class ActiveSession(
    val songId: String,
    var totalDurationMs: Long,
    var startedAtEpochMs: Long,
    var lastKnownPositionMs: Long,
    var accumulatedListeningMs: Long,
    var lastRealtimeMs: Long,
    var lastUpdateEpochMs: Long,
    var isPlaying: Boolean,
    val isVoluntary: Boolean
)
