package com.pisces312.piscesplayer.presentation.musicquiz

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.musicquiz.MUSIC_QUIZ_CLIP_DURATION_MS
import com.pisces312.piscesplayer.data.musicquiz.MUSIC_QUIZ_ROUND_COUNT
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizAudioPlayer
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizEngineState
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizGameEngine
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizQuestion
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizQuestionFactory
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizResolution
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizRunSummary
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizStats
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizStatsRepository
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizTrack
import com.pisces312.piscesplayer.data.offline.CloudOfflineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class MusicQuizPhase {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    REVEALING,
    FINISHED,
    ERROR,
}

enum class MusicQuizLibraryIssue {
    NOT_ENOUGH_SONGS,
    NOT_ENOUGH_TITLES,
}

sealed interface MusicQuizEvent {
    /** The result-screen preview could not play the tapped song. */
    data object PreviewPlayFailed : MusicQuizEvent
}

data class MusicQuizUiState(
    val phase: MusicQuizPhase = MusicQuizPhase.IDLE,
    val hasLibrarySnapshot: Boolean = false,
    val libraryIssue: MusicQuizLibraryIssue? = MusicQuizLibraryIssue.NOT_ENOUGH_SONGS,
    val eligibleTrackCount: Int = 0,
    val stats: MusicQuizStats = MusicQuizStats(),
    val currentQuestion: MusicQuizQuestion? = null,
    val roundIndex: Int = 0,
    val totalRounds: Int = MUSIC_QUIZ_ROUND_COUNT,
    val score: Int = 0,
    val currentStreak: Int = 0,
    val remainingMs: Long = MUSIC_QUIZ_CLIP_DURATION_MS,
    val resolution: MusicQuizResolution? = null,
    val summary: MusicQuizRunSummary? = null,
    val isNewBestScore: Boolean = false,
    val isNewBestStreak: Boolean = false,
    val showExitConfirmation: Boolean = false,
    val previewTrackId: String? = null,
    val previewPaused: Boolean = false,
) {
    val canStart: Boolean
        get() = phase == MusicQuizPhase.IDLE && hasLibrarySnapshot && libraryIssue == null
}

@HiltViewModel
class MusicQuizViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsRepository: MusicQuizStatsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MusicQuizUiState())
    val uiState: StateFlow<MusicQuizUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MusicQuizEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<MusicQuizEvent> = _events.asSharedFlow()

    private var libraryTracks: List<MusicQuizTrack> = emptyList()
    private var runPool: List<MusicQuizTrack> = emptyList()
    private var reserveTracks: List<MusicQuizTrack> = emptyList()
    private var questionFactory = MusicQuizQuestionFactory()
    private var engine: MusicQuizGameEngine? = null
    private var playbackToken = 0L
    private var tickerJob: Job? = null
    private var revealAutoAdvanceJob: Job? = null
    private var previewToken = 0L
    private var previewActive = false
    private var consecutivePlaybackFailures = 0
    private var resultRecorded = false

    private val audioPlayer = lazy(LazyThreadSafetyMode.NONE) {
        MusicQuizAudioPlayer(
            context = context,
            callbacks = object : MusicQuizAudioPlayer.Callbacks {
                override fun onPlaybackStarted(token: Long) {
                    if (isPreviewToken(token)) return
                    handlePlaybackStarted(token)
                }

                override fun onClipEnded(token: Long) {
                    if (isPreviewToken(token)) {
                        handlePreviewEnded()
                        return
                    }
                    handleClipEnded(token)
                }

                override fun onPlaybackInterrupted(token: Long) {
                    if (isPreviewToken(token)) {
                        handlePreviewEnded()
                        return
                    }
                    handlePlaybackInterrupted(token)
                }

                override fun onPlaybackError(token: Long) {
                    if (isPreviewToken(token)) {
                        handlePreviewError()
                        return
                    }
                    handlePlaybackError(token)
                }
            },
        )
    }

    private fun isPreviewToken(token: Long): Boolean = previewActive && token == previewToken

    init {
        viewModelScope.launch {
            statsRepository.stats.collect { stats ->
                _uiState.update { it.copy(stats = stats) }
            }
        }
    }

    /** The run keeps its own snapshot; later library emissions only affect the next run. */
    fun updateLibrary(songs: List<Song>) {
        libraryTracks = songs.asSequence()
            .filterNot(CloudOfflineRepository::isCloudSong)
            .map { it.toMusicQuizTrack() }
            .let { MusicQuizQuestionFactory.eligibleTracks(it.toList()) }
        val issue = when {
            !MusicQuizQuestionFactory.hasEnoughTracks(libraryTracks) ->
                MusicQuizLibraryIssue.NOT_ENOUGH_SONGS
            !MusicQuizQuestionFactory.hasEnoughTitles(libraryTracks) ->
                MusicQuizLibraryIssue.NOT_ENOUGH_TITLES
            else -> null
        }
        _uiState.update {
            it.copy(
                hasLibrarySnapshot = true,
                libraryIssue = issue,
                eligibleTrackCount = libraryTracks.size,
            )
        }
    }

    fun startGame() {
        val issue = when {
            !MusicQuizQuestionFactory.hasEnoughTracks(libraryTracks) ->
                MusicQuizLibraryIssue.NOT_ENOUGH_SONGS
            !MusicQuizQuestionFactory.hasEnoughTitles(libraryTracks) ->
                MusicQuizLibraryIssue.NOT_ENOUGH_TITLES
            else -> null
        }
        if (issue != null) {
            _uiState.update { it.copy(phase = MusicQuizPhase.IDLE, libraryIssue = issue) }
            return
        }

        stopPlayback()
        stopPreview()
        cancelAutoAdvance()
        questionFactory = MusicQuizQuestionFactory(Random(System.nanoTime()))
        runPool = libraryTracks
        val blueprint = questionFactory.createRun(runPool)
        reserveTracks = blueprint.reserveTracks
        engine = MusicQuizGameEngine(blueprint.questions)
        consecutivePlaybackFailures = 0
        resultRecorded = false
        _uiState.update {
            it.copy(
                phase = MusicQuizPhase.PREPARING,
                currentQuestion = engine?.currentQuestion,
                roundIndex = 0,
                totalRounds = MUSIC_QUIZ_ROUND_COUNT,
                score = 0,
                currentStreak = 0,
                remainingMs = MUSIC_QUIZ_CLIP_DURATION_MS,
                resolution = null,
                summary = null,
                isNewBestScore = false,
                isNewBestStreak = false,
                showExitConfirmation = false,
            )
        }
        loadCurrentQuestion()
    }

    fun answer(optionId: String) {
        if (_uiState.value.phase != MusicQuizPhase.PLAYING) return
        val resolution = engine?.answer(optionId) ?: return
        showResolution(resolution)
    }

    fun nextRound() {
        if (_uiState.value.phase != MusicQuizPhase.REVEALING) return
        cancelAutoAdvance()
        val currentEngine = engine ?: return
        if (!currentEngine.advance()) return
        if (currentEngine.state == MusicQuizEngineState.FINISHED) {
            finishRun(currentEngine)
        } else {
            _uiState.update {
                it.copy(
                    phase = MusicQuizPhase.PREPARING,
                    currentQuestion = currentEngine.currentQuestion,
                    roundIndex = currentEngine.currentRoundIndex,
                    score = currentEngine.score,
                    currentStreak = currentEngine.currentStreak,
                    remainingMs = MUSIC_QUIZ_CLIP_DURATION_MS,
                    resolution = null,
                )
            }
            loadCurrentQuestion()
        }
    }

    fun pauseGame(showExitConfirmation: Boolean = false) {
        if (previewActive) pausePreview()
        cancelAutoAdvance()
        val currentEngine = engine ?: return
        when (currentEngine.state) {
            MusicQuizEngineState.PLAYING -> {
                val resolution = currentEngine.pause()
                if (resolution != null) {
                    showResolution(resolution)
                    return
                }
                audioPlayer.value.pause()
                tickerJob?.cancel()
                _uiState.update {
                    it.copy(
                        phase = MusicQuizPhase.PAUSED,
                        remainingMs = nanosToMillis(currentEngine.remainingNanos()),
                        showExitConfirmation = showExitConfirmation,
                    )
                }
            }
            MusicQuizEngineState.WAITING_FOR_PLAYBACK -> {
                audioPlayer.value.pause()
                _uiState.update {
                    it.copy(
                        phase = MusicQuizPhase.PAUSED,
                        showExitConfirmation = showExitConfirmation,
                    )
                }
            }
            MusicQuizEngineState.RESOLVED -> {
                if (showExitConfirmation) {
                    _uiState.update { it.copy(showExitConfirmation = true) }
                }
            }
            MusicQuizEngineState.PAUSED -> {
                if (showExitConfirmation) {
                    _uiState.update { it.copy(showExitConfirmation = true) }
                }
            }
            MusicQuizEngineState.FINISHED -> Unit
        }
    }

    fun resumeGame() {
        val currentEngine = engine ?: return
        if (_uiState.value.phase != MusicQuizPhase.PAUSED ||
            (currentEngine.state != MusicQuizEngineState.PAUSED &&
                currentEngine.state != MusicQuizEngineState.WAITING_FOR_PLAYBACK)
        ) {
            return
        }
        // The clip may have ended while paused (queued STATE_ENDED); resolve instead of hanging.
        if (currentEngine.state == MusicQuizEngineState.PAUSED &&
            !audioPlayer.value.hasActiveClip
        ) {
            currentEngine.onClipEnded()?.let { showResolution(it) }
            return
        }
        _uiState.update {
            it.copy(phase = MusicQuizPhase.PREPARING, showExitConfirmation = false)
        }
        audioPlayer.value.resume()
    }

    fun requestExit() {
        when (_uiState.value.phase) {
            MusicQuizPhase.PREPARING,
            MusicQuizPhase.PLAYING,
            MusicQuizPhase.PAUSED,
            -> pauseGame(showExitConfirmation = true)
            MusicQuizPhase.REVEALING -> {
                cancelAutoAdvance()
                _uiState.update { it.copy(showExitConfirmation = true) }
            }
            MusicQuizPhase.IDLE,
            MusicQuizPhase.FINISHED,
            MusicQuizPhase.ERROR,
            -> Unit
        }
    }

    fun dismissExitConfirmation() {
        _uiState.update { it.copy(showExitConfirmation = false) }
        maybeScheduleAutoAdvance()
    }

    fun stopGame() {
        cancelAutoAdvance()
        stopPreview()
        stopPlayback()
        engine = null
        _uiState.update { it.copy(showExitConfirmation = false) }
    }

    /** Re-arms the reveal auto-advance after backgrounding or configuration changes. */
    fun onScreenResumed() {
        maybeScheduleAutoAdvance()
    }

    private fun loadCurrentQuestion(autoplay: Boolean = true) {
        val question = engine?.currentQuestion ?: return
        val token = ++playbackToken
        _uiState.update {
            it.copy(
                currentQuestion = question,
                phase = if (autoplay) MusicQuizPhase.PREPARING else MusicQuizPhase.PAUSED,
            )
        }
        try {
            audioPlayer.value.play(question, token, autoplay)
        } catch (error: Exception) {
            Timber.e(error, "Unable to prepare music quiz clip")
            handlePlaybackError(token)
        }
    }

    private fun handlePlaybackStarted(token: Long) {
        if (token != playbackToken) return
        val currentEngine = engine ?: return
        when (currentEngine.state) {
            MusicQuizEngineState.WAITING_FOR_PLAYBACK -> currentEngine.onPlaybackStarted()
            MusicQuizEngineState.PAUSED -> currentEngine.onPlaybackResumed()
            else -> return
        }
        consecutivePlaybackFailures = 0
        _uiState.update {
            it.copy(
                phase = MusicQuizPhase.PLAYING,
                currentQuestion = currentEngine.currentQuestion,
                remainingMs = nanosToMillis(currentEngine.remainingNanos()),
                showExitConfirmation = false,
            )
        }
        startTicker()
    }

    private fun handleClipEnded(token: Long) {
        if (token != playbackToken) return
        val resolution = engine?.onClipEnded() ?: return
        showResolution(resolution)
    }

    private fun handlePlaybackInterrupted(token: Long) {
        if (token != playbackToken) return
        pauseGame()
    }

    private fun handlePlaybackError(token: Long) {
        if (token != playbackToken) return
        val currentEngine = engine ?: return
        val remainPaused = _uiState.value.phase == MusicQuizPhase.PAUSED
        tickerJob?.cancel()
        stopPlayback()
        consecutivePlaybackFailures += 1
        val replacement = if (consecutivePlaybackFailures <= MAX_CONSECUTIVE_PLAYBACK_FAILURES) {
            questionFactory.replacement(
                allTracks = runPool,
                reserveTracks = reserveTracks,
                plannedQuestions = currentEngine.plannedQuestions,
            )
        } else {
            null
        }
        if (replacement != null && currentEngine.replaceCurrentQuestion(replacement.question)) {
            reserveTracks = replacement.remainingReserveTracks
            _uiState.update { it.copy(currentQuestion = replacement.question) }
            loadCurrentQuestion(autoplay = !remainPaused)
        } else {
            engine = null
            _uiState.update {
                it.copy(
                    phase = MusicQuizPhase.ERROR,
                    currentQuestion = null,
                    resolution = null,
                    showExitConfirmation = false,
                )
            }
        }
    }

    private fun showResolution(resolution: MusicQuizResolution) {
        tickerJob?.cancel()
        stopPlayback()
        _uiState.update {
            it.copy(
                phase = MusicQuizPhase.REVEALING,
                score = resolution.totalScore,
                currentStreak = resolution.currentStreak,
                remainingMs = 0L,
                resolution = resolution,
                showExitConfirmation = false,
            )
        }
        if (resolution.record.isCorrect) scheduleAutoAdvance()
    }

    private fun scheduleAutoAdvance() {
        revealAutoAdvanceJob?.cancel()
        revealAutoAdvanceJob = viewModelScope.launch {
            delay(REVEAL_AUTO_ADVANCE_DELAY_MS)
            nextRound()
        }
    }

    private fun cancelAutoAdvance() {
        revealAutoAdvanceJob?.cancel()
        revealAutoAdvanceJob = null
    }

    private fun maybeScheduleAutoAdvance() {
        val state = _uiState.value
        if (state.phase == MusicQuizPhase.REVEALING &&
            state.resolution?.record?.isCorrect == true &&
            !state.showExitConfirmation
        ) {
            scheduleAutoAdvance()
        }
    }

    /** Toggles the result-screen full-song preview for one of the finished run's tracks. */
    fun togglePreview(trackId: String) {
        val state = _uiState.value
        if (state.phase != MusicQuizPhase.FINISHED) return
        if (state.previewTrackId == trackId) {
            if (state.previewPaused) resumePreview() else pausePreview()
            return
        }
        val track = state.summary?.records
            ?.firstOrNull { it.question.track.id == trackId }
            ?.question?.track ?: return
        startPreview(track)
    }

    fun stopPreview() {
        if (previewActive && audioPlayer.isInitialized()) {
            audioPlayer.value.stop()
        }
        previewActive = false
        previewToken = 0L
        if (_uiState.value.previewTrackId != null) {
            _uiState.update { it.copy(previewTrackId = null, previewPaused = false) }
        }
    }

    private fun startPreview(track: MusicQuizTrack) {
        val token = ++playbackToken
        previewActive = true
        previewToken = token
        try {
            audioPlayer.value.playFull(track, token)
        } catch (error: Exception) {
            Timber.e(error, "Unable to start music quiz preview")
            handlePreviewError()
            return
        }
        _uiState.update { it.copy(previewTrackId = track.id, previewPaused = false) }
    }

    private fun pausePreview() {
        if (!previewActive) return
        audioPlayer.value.pause()
        _uiState.update { it.copy(previewPaused = true) }
    }

    private fun resumePreview() {
        if (!previewActive) return
        audioPlayer.value.resume()
        _uiState.update { it.copy(previewPaused = false) }
    }

    private fun handlePreviewEnded() {
        previewActive = false
        previewToken = 0L
        _uiState.update { it.copy(previewTrackId = null, previewPaused = false) }
    }

    private fun handlePreviewError() {
        handlePreviewEnded()
        _events.tryEmit(MusicQuizEvent.PreviewPlayFailed)
    }

    private fun finishRun(currentEngine: MusicQuizGameEngine) {
        val summary = currentEngine.summary()
        _uiState.update {
            it.copy(
                phase = MusicQuizPhase.FINISHED,
                currentQuestion = null,
                summary = summary,
                isNewBestScore = false,
                isNewBestStreak = false,
                showExitConfirmation = false,
            )
        }
        if (resultRecorded) return
        resultRecorded = true
        viewModelScope.launch {
            runCatching {
                withContext(NonCancellable) { statsRepository.recordRun(summary) }
            }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            stats = result.stats,
                            isNewBestScore = result.isNewBestScore,
                            isNewBestStreak = result.isNewBestStreak,
                        )
                    }
                }
                .onFailure { error -> Timber.e(error, "Unable to save music quiz result") }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val currentEngine = engine ?: break
                val resolution = currentEngine.tick()
                if (resolution != null) {
                    showResolution(resolution)
                    break
                }
                if (currentEngine.state != MusicQuizEngineState.PLAYING) break
                _uiState.update {
                    it.copy(remainingMs = nanosToMillis(currentEngine.remainingNanos()))
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun stopPlayback() {
        tickerJob?.cancel()
        tickerJob = null
        playbackToken += 1
        if (audioPlayer.isInitialized()) audioPlayer.value.stop()
    }

    private fun nanosToMillis(nanos: Long): Long =
        (nanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND

    override fun onCleared() {
        tickerJob?.cancel()
        if (audioPlayer.isInitialized()) audioPlayer.value.release()
        super.onCleared()
    }

    private fun Song.toMusicQuizTrack(): MusicQuizTrack {
        val playbackUri = contentUriString.takeIf(String::isNotBlank)
            ?: path.takeIf(String::isNotBlank)?.let { Uri.fromFile(File(it)).toString() }
            .orEmpty()
        return MusicQuizTrack(
            id = id,
            title = title,
            artist = displayArtist,
            albumArtUriString = albumArtUriString,
            durationMs = duration,
            playbackUri = playbackUri,
            year = year,
            genre = genre,
        )
    }

    private companion object {
        const val TICK_INTERVAL_MS = 50L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_CONSECUTIVE_PLAYBACK_FAILURES = 3

        /** Correct-answer reveal auto-advances after this delay; the button still skips early. */
        const val REVEAL_AUTO_ADVANCE_DELAY_MS = 1_200L
    }
}
