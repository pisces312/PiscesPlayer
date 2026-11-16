package com.pisces312.piscesplayer.data.ai

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.preferences.AiPreferencesRepository
import com.pisces312.piscesplayer.data.service.player.DualPlayerEngine
import com.pisces312.piscesplayer.di.AppScope
import com.pisces312.piscesplayer.utils.MediaItemBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Similar Radio: session-scoped, in-memory state machine that keeps the queue alive with
 * AI-suggested songs (design: docs/similar-radio-design.md in the private design repo).
 *
 * While RUNNING, the session watches queue drain: when at most [PREFETCH_REMAINING] items are
 * left after the current one and no request is in flight, it asks [AiPlaylistGenerator] for one
 * batch anchored on the most recently started song, dedups against the live queue and this
 * session's history, appends the survivors and marks them with [MediaItemBuilder.EXTRA_SIMILAR_RADIO]
 * so the queue UI can badge them. The anchor is always the queue tail at generation time, not
 * the song that originally enabled the radio.
 *
 * Lifecycle (all per design §3.1 / §4):
 * - Enabling forces [Player.REPEAT_MODE_OFF] (repeat and endless radio are mutually exclusive).
 *   If the user re-enables repeat later we do not fight it: prefetch simply does not trigger
 *   while repeat is on, which equals a pause of the radio.
 * - Two consecutive failed batches (after one backoff retry) silently drop back to OFF; so do
 *   the session caps ([MAX_SESSION_SONGS] appended or [MAX_SESSION_REQUESTS] requests) so an
 *   unattended radio cannot burn tokens forever. Both paths never disturb playback.
 * - The toggle lives in memory only: process death resets the radio, matching radio semantics.
 *
 * The session is a @Singleton driven by events pushed from `MusicService` (it cannot hold a
 * Service reference, and must outlive any ViewModel). Queue appends go through
 * [DualPlayerEngine.masterPlayer]; the MediaController mirrors those timeline changes like any
 * other queue write.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SimilarRadioSession
@Inject
constructor(
    private val generator: AiPlaylistGenerator,
    private val engine: DualPlayerEngine,
    aiPreferences: AiPreferencesRepository,
    @AppScope private val scope: CoroutineScope
) {

    /** UI-facing snapshot of the radio. */
    data class State(
        val enabled: Boolean = false,
        val generating: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Media ids appended by the radio during this session — drives the ✨ badge in the queue. */
    private val _suggestedIds = MutableStateFlow<Set<String>>(emptySet())
    val suggestedIds: StateFlow<Set<String>> = _suggestedIds.asStateFlow()

    /**
     * Whether the active provider can run (key, or a base url for custom ones). Same rule as
     * PlaylistViewModel.isAiConfigured / AiSongQaViewModel.configuredFlow — the UI greys the
     * entry out when this is false.
     */
    val isConfigured: StateFlow<Boolean> =
            aiPreferences.providerFlow
                    .flatMapLatest { provider ->
                        if (provider.requiresApiKey) {
                            aiPreferences.getApiKey(provider).map { it.isNotBlank() }
                        } else {
                            aiPreferences.getBaseUrl(provider).map { it.isNotBlank() }
                        }
                    }
                    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    private var generationJob: Job? = null
    private var checkJob: Job? = null
    private var retryJob: Job? = null

    // Session bookkeeping (in-memory only, resets with the toggle).
    private var consecutiveFailures = 0
    private var requestCount = 0
    private var appendedCount = 0
    private val playedIds = mutableSetOf<String>()

    fun toggle() {
        setEnabled(!_state.value.enabled)
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled == _state.value.enabled) return
        if (!enabled) {
            disable()
            return
        }
        scope.launch {
            if (!isConfigured.first()) {
                Timber.tag(TAG).w("enable() ignored: AI provider not configured")
                return@launch
            }
            // Radio and repeat are mutually exclusive: entering RUNNING drops repeat. We do
            // not restore it on disable (design §2) — the user can re-enable it manually.
            engine.masterPlayer.repeatMode = Player.REPEAT_MODE_OFF
            consecutiveFailures = 0
            requestCount = 0
            appendedCount = 0
            playedIds.clear()
            _suggestedIds.value = emptySet()
            _state.value = State(enabled = true)
            scheduleCheck()
        }
    }

    /** MusicService: a new media item became current. */
    fun onMediaItemTransition(mediaItem: MediaItem?) {
        if (!_state.value.enabled) return
        mediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let(playedIds::add)
        scheduleCheck()
    }

    /** MusicService: the timeline changed (append, removal, reorder). */
    fun onTimelineChanged() {
        if (!_state.value.enabled) return
        scheduleCheck()
    }

    /**
     * PlayerViewModel: the user replaced the whole queue (played a different list). The radio
     * stays RUNNING but the dedup context of the old queue is meaningless — drop it so the new
     * queue's songs are not excluded (design §3.1).
     */
    fun onQueueReplaced() {
        if (!_state.value.enabled) return
        playedIds.clear()
        scheduleCheck()
    }

    private fun disable() {
        generationJob?.cancel()
        retryJob?.cancel()
        _state.value = State(enabled = false)
    }

    private fun scheduleCheck() {
        checkJob?.cancel()
        checkJob =
                scope.launch {
                    delay(CHECK_DEBOUNCE_MS)
                    maybePrefetch()
                }
    }

    private suspend fun maybePrefetch() {
        val snapshot = _state.value
        if (!snapshot.enabled || snapshot.generating) return
        if (generationJob?.isActive == true) return
        val player = engine.masterPlayer
        // While repeat is on the queue never drains; the user's explicit repeat wins and the
        // radio effectively pauses (design §4).
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0) return
        val remaining = player.mediaItemCount - currentIndex - 1
        if (remaining > PREFETCH_REMAINING) return
        val anchor = player.currentMediaItem ?: return
        generateBatch(anchor)
    }

    private fun generateBatch(anchor: MediaItem) {
        _state.update { it.copy(generating = true) }
        generationJob =
                scope.launch {
                    try {
                        val batch = produceBatch(anchor)
                        if (batch.isEmpty()) {
                            onBatchFailed()
                            return@launch
                        }
                        appendToQueue(batch)
                        consecutiveFailures = 0
                        _state.update { it.copy(generating = false) }
                        if (appendedCount >= MAX_SESSION_SONGS || requestCount >= MAX_SESSION_REQUESTS) {
                            Timber.tag(TAG).i("Session cap reached, radio off")
                            disable()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Similar radio batch failed")
                        onBatchFailed()
                    }
                }
    }

    /**
     * Up to [MAX_BATCH_ROUNDS] generation rounds per trigger. Each round asks for [BATCH_SIZE]
     * songs anchored on the current item; rounds after the first append an exclusion clause
     * naming what was already tried, so a library corner cannot deadlock the radio. A round
     * counts as usable once at least [MIN_ACCEPTED] fresh songs survive dedup; anything
     * non-empty after the last round is still appended rather than dropped (partial batches
     * beat silence), only a fully empty result is a failure.
     */
    private suspend fun produceBatch(anchor: MediaItem): List<Song> {
        val title = anchor.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
        val artist = anchor.mediaMetadata.artist?.toString().orEmpty()
        if (title == null) return emptyList()

        val tried = mutableListOf<String>()
        var fresh: List<Song> = emptyList()
        var round = 0
        while (round < MAX_BATCH_ROUNDS) {
            round++
            val excludeIds = currentQueueIds() + playedIds
            val description = buildString {
                append("Based on '").append(title).append("' by ").append(artist)
                append(", suggest ").append(BATCH_SIZE).append(" similar songs")
                if (tried.isNotEmpty()) {
                    append(". Suggest other songs than: ").append(tried.joinToString("; "))
                }
            }
            val matched =
                    generator.generate(
                            description = description,
                            maxLength = BATCH_SIZE,
                            sampleMode = AiLibrarySampleMode.RANDOM,
                            sampleSize = LIBRARY_SAMPLE_SIZE,
                            promptType = AiHandler.PROMPT_TYPE_SIMILAR_RADIO,
                            // Anchor differs every batch, so a cache entry could never be read
                            // back — same argument as generateSerendipity.
                            useCache = false
                    )
            requestCount++
            tried += matched.distinctBy { it.id }.map { "${it.title} - ${it.displayArtist}" }
            fresh =
                    matched.filter { it.id !in excludeIds }.distinctBy { it.id }.take(BATCH_SIZE)
            if (fresh.size >= MIN_ACCEPTED) break
        }
        return fresh
    }

    private fun appendToQueue(songs: List<Song>) {
        val items =
                songs.map { song ->
                    val base = MediaItemBuilder.build(song)
                    val extras = Bundle(base.mediaMetadata.extras ?: Bundle()).apply {
                        putBoolean(MediaItemBuilder.EXTRA_SIMILAR_RADIO, true)
                    }
                    base.buildUpon()
                            .setMediaMetadata(
                                    base.mediaMetadata.buildUpon().setExtras(extras).build()
                            )
                            .build()
                }
        engine.masterPlayer.addMediaItems(items)
        appendedCount += songs.size
        _suggestedIds.update { it + songs.map { song -> song.id } }
    }

    private fun currentQueueIds(): Set<String> =
            engine.getFullQueue().mapTo(HashSet()) { it.mediaId }

    private fun onBatchFailed() {
        consecutiveFailures++
        _state.update { it.copy(generating = false) }
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            // Silent OFF: playback and the queue are untouched (design §3.1/§4).
            Timber.tag(TAG).w("Two consecutive failures, radio off")
            disable()
        } else {
            // One exponential-backoff retry before giving up.
            retryJob =
                    scope.launch {
                        delay(RETRY_BACKOFF_MS)
                        scheduleCheck()
                    }
        }
    }

    private companion object {
        const val TAG = "SimilarRadioSession"

        const val BATCH_SIZE = 15
        const val MIN_ACCEPTED = BATCH_SIZE / 2
        const val MAX_BATCH_ROUNDS = 2
        const val LIBRARY_SAMPLE_SIZE = 200
        const val PREFETCH_REMAINING = 2
        const val MAX_CONSECUTIVE_FAILURES = 2
        const val MAX_SESSION_SONGS = 500
        const val MAX_SESSION_REQUESTS = 20
        const val RETRY_BACKOFF_MS = 10_000L
        const val CHECK_DEBOUNCE_MS = 300L
    }
}
