/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, GPL-3.0-or-later.
 */
package com.pisces312.piscesplayer.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.ai.AiChatMessage
import com.pisces312.piscesplayer.data.ai.AiHandler
import com.pisces312.piscesplayer.data.ai.AiLyricsTranslationCache
import com.pisces312.piscesplayer.data.ai.AiProgressEvent
import com.pisces312.piscesplayer.data.ai.AiProgressListener
import com.pisces312.piscesplayer.data.ai.SongQaContextBuilder
import com.pisces312.piscesplayer.data.ai.provider.AiErrorKind
import com.pisces312.piscesplayer.data.ai.provider.AiProviderException
import com.pisces312.piscesplayer.data.model.Lyrics
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.preferences.AiPreferencesRepository
import com.pisces312.piscesplayer.data.repository.LyricsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the player's current-song Q&A session.
 *
 * The conversation is deliberately in-memory: closing the sheet cancels any request but keeps the
 * transcript, while "new conversation" or switching the context song clears it. Nothing here is
 * written to Room or DataStore beyond the existing AI usage / request-log diagnostics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiSongQaViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val handler: AiHandler,
    private val preferences: AiPreferencesRepository,
    private val lyricsRepository: LyricsRepository,
    private val translationCache: AiLyricsTranslationCache
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiSongQaUiState())
    val uiState: StateFlow<AiSongQaUiState> = _uiState.asStateFlow()

    private val configuredFlow: StateFlow<Boolean> =
        preferences.providerFlow
            .flatMapLatest { provider ->
                if (provider.requiresApiKey) {
                    preferences.getApiKey(provider).map { it.isNotBlank() }
                } else {
                    preferences.getBaseUrl(provider).map { it.isNotBlank() }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var requestJob: Job? = null
    private var lyricsJob: Job? = null
    private var requestGeneration = 0
    private var storedLyrics: Lyrics? = null
    private var failedQuestion: String? = null
    private var failedCacheKey: String? = null

    init {
        viewModelScope.launch {
            configuredFlow.collect { configured ->
                _uiState.update { it.copy(isConfigured = configured) }
            }
        }
    }

    /**
     * Opens or re-opens the sheet. Reopening the same song keeps its in-memory transcript; opening
     * another song starts a clean session so one answer can never be attributed to two tracks.
     *
     * [preset] is how the lyrics screen starts a question the user has already chosen. It runs only
     * after the stored lyrics are resolved, otherwise a translation request would leave without the
     * lyrics it was asked about.
     */
    fun openSession(song: Song, preset: AiSongQaPreset? = null) {
        val current = _uiState.value
        if (current.song?.id == song.id) {
            _uiState.update { it.copy(currentPlayerSongId = song.id) }
            preset?.let(::requestPreset)
            return
        }

        requestGeneration++
        requestJob?.cancel()
        requestJob = null
        lyricsJob?.cancel()
        failedQuestion = null
        failedCacheKey = null
        storedLyrics = null
        _uiState.update {
            AiSongQaUiState(
                song = song,
                currentPlayerSongId = song.id,
                isConfigured = it.isConfigured
            )
        }
        lyricsJob = viewModelScope.launch {
            val loaded = readStoredLyrics(song)
            // Another song can win the race while the repository is reading.
            if (_uiState.value.song?.id != song.id) return@launch
            storedLyrics = loaded
            _uiState.update { it.copy(hasStoredLyrics = loaded != null) }
            preset?.let(::requestPreset)
        }
    }

    /** Tracks the live player song without replacing the conversation's frozen context. */
    fun updateCurrentPlayerSong(song: Song?) {
        _uiState.update { it.copy(currentPlayerSongId = song?.id) }
    }

    /** Closing the sheet stops paying for tokens but keeps the in-memory transcript. */
    fun dismissSession() {
        requestJob?.cancel()
    }

    fun setIncludeSongContext(include: Boolean) {
        _uiState.update { it.copy(includeSongContext = include) }
    }

    fun setIncludeLyrics(include: Boolean) {
        _uiState.update {
            it.copy(includeLyrics = include && it.hasStoredLyrics)
        }
    }

    /** Draft text stays in memory so closing the sheet does not throw away a half-written question. */
    fun setDraft(text: String) {
        _uiState.update { it.copy(draft = text) }
    }

    /** Clears the transcript while keeping the same song snapshot and context switches. */
    fun startNewConversation() {
        cancelActiveRequest()
        failedQuestion = null
        failedCacheKey = null
        _uiState.update {
            it.copy(
                messages = emptyList(),
                isSending = false,
                errorMessage = null,
                includeLyrics = false,
                draft = ""
            )
        }
    }

    /** Starts a clean session for the live current song after the user explicitly switches. */
    fun switchToCurrentSong(currentSong: Song?) {
        if (currentSong == null || currentSong.id == _uiState.value.song?.id) return
        openSession(currentSong)
    }

    fun sendMessage(question: String = "", includeLyrics: Boolean? = null) {
        val state = _uiState.value
        val text = question.ifBlank { state.draft }.trim()
        if (text.isEmpty() || state.isSending || !state.isConfigured) return
        val useLyrics = includeLyrics ?: state.includeLyrics
        if (includeLyrics != null) setIncludeLyrics(includeLyrics)
        failedQuestion = null
        failedCacheKey = null
        _uiState.update { it.copy(draft = "") }
        startRequest(text, reuseLastUserMessage = false, includeLyrics = useLyrics)
    }

    /** Re-runs the failed question without duplicating its user bubble. */
    fun retryLastFailed() {
        val question = failedQuestion ?: return
        val state = _uiState.value
        val canReuseBubble = state.messages.lastOrNull()?.let {
            it.role == AiChatMessage.Role.USER && it.content == question
        } == true
        val cacheKey = failedCacheKey
        failedQuestion = null
        failedCacheKey = null
        _uiState.update { it.copy(errorMessage = null) }
        startRequest(
            question = question,
            reuseLastUserMessage = canReuseBubble,
            includeLyrics = state.includeLyrics,
            cacheKey = cacheKey
        )
    }

    fun stopGeneration() {
        requestJob?.cancel()
    }

    private fun startRequest(
        question: String,
        reuseLastUserMessage: Boolean,
        includeLyrics: Boolean,
        cacheKey: String? = null
    ) {
        val state = _uiState.value
        val song = state.song ?: return
        if (state.isSending) return

        val generation = ++requestGeneration
        val userMessage =
            if (reuseLastUserMessage) {
                state.messages.lastOrNull()
            } else {
                AiSongQaMessage(
                    id = UUID.randomUUID().toString(),
                    role = AiChatMessage.Role.USER,
                    content = question
                )
            }
        if (userMessage == null) return

        val assistantId = UUID.randomUUID().toString()
        val visibleMessages = state.messages
            .let { messages -> if (reuseLastUserMessage) messages else messages + userMessage }
            .plus(
                AiSongQaMessage(
                    id = assistantId,
                    role = AiChatMessage.Role.ASSISTANT,
                    content = "",
                    isStreaming = true
                )
            )
        _uiState.update {
            it.copy(messages = visibleMessages, isSending = true, errorMessage = null)
        }

        val lyrics = storedLyrics.takeIf { includeLyrics }
        val systemPrompt = SongQaContextBuilder.systemPrompt(
            song = song,
            lyrics = lyrics,
            includeSongContext = state.includeSongContext,
            appLanguageTag = Locale.getDefault().toLanguageTag()
        )
        val history = visibleMessages.map { message ->
            AiChatMessage(role = message.role, content = message.content)
        }
        val requestMessages = SongQaContextBuilder.requestMessages(systemPrompt, history)

        requestJob = viewModelScope.launch {
            try {
                val answer = handler.chat(
                    messages = requestMessages,
                    promptType = AiHandler.PROMPT_TYPE_SONG_QA,
                    listener = AiProgressListener { event ->
                        if (generation != requestGeneration) return@AiProgressListener
                        if (event is AiProgressEvent.Answer) {
                            updateAssistantMessage(assistantId, event.full, isStreaming = true)
                        }
                    }
                )
                if (generation == requestGeneration) {
                    updateAssistantMessage(assistantId, answer, isStreaming = false)
                    cacheKey?.let { translationCache.write(it, answer) }
                }
            } catch (t: Throwable) {
                if (generation != requestGeneration) return@launch
                if (t is CancellationException) {
                    finishCancelledAssistant(assistantId)
                } else {
                    failedQuestion = question
                    failedCacheKey = cacheKey
                    _uiState.update { current ->
                        current.copy(
                            messages = current.messages.filterNot { it.id == assistantId },
                            errorMessage = errorText(t)
                        )
                    }
                }
            } finally {
                if (generation == requestGeneration) {
                    _uiState.update { it.copy(isSending = false) }
                }
            }
        }
    }

    private fun updateAssistantMessage(id: String, content: String, isStreaming: Boolean) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == id) {
                        message.copy(content = content, isStreaming = isStreaming)
                    } else {
                        message
                    }
                }
            )
        }
    }

    private fun finishCancelledAssistant(id: String) {
        _uiState.update { state ->
            val assistant = state.messages.firstOrNull { it.id == id }
            state.copy(
                messages =
                    if (assistant?.content.isNullOrBlank()) {
                        state.messages.filterNot { it.id == id }
                    } else {
                        state.messages.map { message ->
                            if (message.id == id) message.copy(isStreaming = false) else message
                        }
                    }
            )
        }
    }

    private fun cancelActiveRequest() {
        requestGeneration++
        requestJob?.cancel()
        requestJob = null
    }

    private suspend fun readStoredLyrics(song: Song): Lyrics? {
        val loaded = runCatching { lyricsRepository.getStoredLyrics(song)?.first }.getOrNull()
            ?: song.lyrics
                ?.takeIf { it.isNotBlank() }
                ?.let { Lyrics(plain = it.lines().filter(String::isNotBlank)) }
        return loaded?.takeIf { SongQaContextBuilder.lyricsText(it).isNotBlank() }
    }

    /** Runs a question the user already chose, e.g. the translation started from the lyrics screen. */
    private fun requestPreset(preset: AiSongQaPreset) {
        when (preset) {
            AiSongQaPreset.TRANSLATE_TITLE_AND_LYRICS -> translateTitleAndLyrics()
        }
    }

    /**
     * Sends the built-in translation question, reusing a cached answer when the song, the lyric text,
     * the target language and the model are unchanged. A re-imported lyric sheet or a different
     * target language therefore falls through to a fresh request instead of showing stale text.
     */
    private fun translateTitleAndLyrics() {
        val state = _uiState.value
        val song = state.song ?: return
        if (!state.isConfigured || state.isSending) return
        val useLyrics = state.hasStoredLyrics
        if (useLyrics != state.includeLyrics) setIncludeLyrics(useLyrics)
        val question = context.getString(R.string.ai_song_qa_suggest_translate)

        viewModelScope.launch {
            val cacheKey = translationCacheKey(song, if (useLyrics) storedLyrics else null)
            val cached = cacheKey?.let { translationCache.read(it) }
            if (_uiState.value.song?.id != song.id) return@launch
            if (cached != null) {
                _uiState.update {
                    it.copy(
                        messages =
                            it.messages +
                                userMessage(question) +
                                assistantMessage(cached),
                        draft = "",
                        errorMessage = null
                    )
                }
            } else {
                startRequest(
                    question = question,
                    reuseLastUserMessage = false,
                    includeLyrics = useLyrics,
                    cacheKey = cacheKey
                )
            }
        }
    }

    /** Null when the provider/model cannot be read; the request still runs, it just is not cached. */
    private suspend fun translationCacheKey(song: Song, lyrics: Lyrics?): String? = runCatching {
        val provider = preferences.providerFlow.first()
        val model = preferences.getModel(provider).first()
        val source = buildString {
            append(song.title)
            append('\n')
            append(song.displayArtist)
            append('\n')
            lyrics?.let { append(SongQaContextBuilder.lyricsText(it)) }
        }
        AiLyricsTranslationCache.keyFor(
            songId = song.id,
            languageTag = Locale.getDefault().toLanguageTag(),
            modelLabel = "${provider.name}/$model",
            source = source
        )
    }.getOrNull()

    private fun userMessage(text: String) =
        AiSongQaMessage(
            id = UUID.randomUUID().toString(),
            role = AiChatMessage.Role.USER,
            content = text
        )

    private fun assistantMessage(text: String) =
        AiSongQaMessage(
            id = UUID.randomUUID().toString(),
            role = AiChatMessage.Role.ASSISTANT,
            content = text
        )

    private fun errorText(throwable: Throwable): String {
        val kind = (throwable as? AiProviderException)?.kind
        val resId = when (kind) {
            AiErrorKind.UNAUTHORIZED -> R.string.ai_error_unauthorized
            AiErrorKind.QUOTA_EXCEEDED -> R.string.ai_error_quota
            AiErrorKind.MODEL_NOT_FOUND -> R.string.ai_error_model_not_found
            AiErrorKind.RATE_LIMITED -> R.string.ai_error_rate_limited
            AiErrorKind.SERVER -> R.string.ai_error_server
            AiErrorKind.NETWORK -> R.string.ai_error_network
            AiErrorKind.RESPONSE_PARSE -> R.string.ai_error_parse
            else -> R.string.ai_error_unknown
        }
        val text = context.getString(resId)
        val detail = (throwable as? AiProviderException)?.message
        return if (detail.isNullOrBlank()) text else "$text\n$detail"
    }
}

/**
 * A question the user picked before the sheet opened. Kept as an enum rather than a raw string so
 * callers cannot pass arbitrary text through the "open with a preset" path.
 */
enum class AiSongQaPreset {
    TRANSLATE_TITLE_AND_LYRICS
}
