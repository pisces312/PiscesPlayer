package com.pisces312.piscesplayer.presentation.viewmodel

import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.DailyMixManager
import com.pisces312.piscesplayer.data.model.AI_MIX_SOURCE
import com.pisces312.piscesplayer.data.model.Playlist
import com.pisces312.piscesplayer.data.model.PlaylistSourceFilter
import com.pisces312.piscesplayer.data.model.SERENDIPITY_SOURCE
import com.pisces312.piscesplayer.data.model.SmartPlaylistRule
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.model.SortOption
import com.pisces312.piscesplayer.data.model.fromPlaylistSource
import com.pisces312.piscesplayer.data.model.isSmartPlaylist
import com.pisces312.piscesplayer.data.model.toPlaylistSource
import com.pisces312.piscesplayer.data.playlist.M3uManager
import com.pisces312.piscesplayer.data.playlist.NlpPlaylistGenerator
import com.pisces312.piscesplayer.data.ai.AiHandler
import com.pisces312.piscesplayer.data.ai.AiLibrarySampleMode
import com.pisces312.piscesplayer.data.ai.AiPlaylistGenerator
import com.pisces312.piscesplayer.data.ai.AiProgressEvent
import com.pisces312.piscesplayer.data.ai.AiProgressListener
import com.pisces312.piscesplayer.data.ai.AiSystemPromptEngine
import com.pisces312.piscesplayer.data.ai.serendipity.SerendipityContext
import com.pisces312.piscesplayer.data.ai.serendipity.SerendipityContextCollector
import com.pisces312.piscesplayer.data.ai.serendipity.SerendipityPromptComposer
import com.pisces312.piscesplayer.data.ai.serendipity.SerendipityWeatherSource
import com.pisces312.piscesplayer.data.preferences.AiPreferencesRepository
import com.pisces312.piscesplayer.data.ai.provider.AiErrorKind
import com.pisces312.piscesplayer.data.ai.provider.AiProviderException
import com.pisces312.piscesplayer.data.playlist.SmartPlaylistBuilder
import com.pisces312.piscesplayer.data.preferences.PlaylistPreferencesRepository
import com.pisces312.piscesplayer.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.io.OutputStreamWriter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import com.pisces312.piscesplayer.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.random.Random
import timber.log.Timber
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

data class PlaylistUiState(
    val playlists: ImmutableList<Playlist> = persistentListOf(),
    val currentPlaylistSongs: List<Song> = emptyList(),
    val currentPlaylistDetails: Playlist? = null,
    val isLoading: Boolean = false,
    val playlistNotFound: Boolean = false,

    val currentPlaylistSortOption: SortOption = SortOption.PlaylistNameAZ,
    val currentPlaylistSongsSortOption: SortOption = SortOption.SongTitleAZ,
    val playlistSongsOrderMode: PlaylistSongsOrderMode = PlaylistSongsOrderMode.Sorted(SortOption.SongTitleAZ),
    val playlistOrderModes: Map<String, PlaylistSongsOrderMode> = emptyMap(),

    /** Source filter applied by the library playlists tab; persisted across launches. */
    val playlistSourceFilter: PlaylistSourceFilter = PlaylistSourceFilter.ALL,

)

sealed class PlaylistSongsOrderMode {
    object Manual : PlaylistSongsOrderMode()
    data class Sorted(val option: SortOption) : PlaylistSongsOrderMode()
}

/** Emitted once a generated mix has been persisted, so the UI can start playback. */
data class AiMixSaved(
    val playlistId: String,
    val name: String,
    val songs: List<Song>,
    val prompt: String,
    /** True when the user picked "play" rather than "save only". */
    val startPlayback: Boolean
)

/** Which part of a streaming answer the model is currently producing. */
enum class AiGenerationStage {
    IDLE,
    THINKING,
    ANSWERING
}

/** Preview state for the offline "describe it" playlist creation flow. */
data class NlpPlaylistPreviewState(
    val isGenerating: Boolean = false,
    val songs: ImmutableList<Song> = persistentListOf(),
    /** True once a generation finished, so the UI can tell "no matches" from "not asked yet". */
    val hasResult: Boolean = false,
    /** User-facing failure text; offline NLP generation never sets it. */
    val errorMessage: String? = null,
    /** Sampling actually used for this generation, captured so saving persists the right metadata. */
    val sampleMode: AiLibrarySampleMode? = null,
    val sampleSize: Int? = null,
    /**
     * Streaming progress of the AI provider. The offline NLP flow leaves both at their defaults,
     * so its dialog is unaffected.
     */
    val stage: AiGenerationStage = AiGenerationStage.IDLE,
    /**
     * Chain of thought accumulated so far. Kept out of the request log and the response cache; a
     * saved playlist keeps it in its own `ai_thinking` column.
     */
    val thinkingText: String = "",
)

/**
 * Serendipity sheet state: the signals gathered for "right now" plus the prompt they composed.
 *
 * [context] is null while the collector is still running, and stays null-free afterwards: a signal
 * that could not be read is a null field *inside* the context, which is what lets the sheet show
 * "no weather this time" instead of pretending it never had the option.
 */
data class SerendipityUiState(
    val context: SerendipityContext? = null,
    val prompt: String = "",
    /** Bumped by "reshuffle"; the composer walks its sentence shapes with it. */
    val variant: Int = 0,
    val isCollecting: Boolean = true,
    val isRephrasing: Boolean = false,
    /** True when the optional AI rephrase call failed and the local sentence is still in place. */
    val rephraseFailed: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    private val musicRepository: MusicRepository,
    private val dailyMixManager: DailyMixManager,
    private val m3uManager: M3uManager,
    private val nlpPlaylistGenerator: NlpPlaylistGenerator,
    private val aiPlaylistGenerator: AiPlaylistGenerator,
    private val aiHandler: AiHandler,
    private val serendipityContextCollector: SerendipityContextCollector,
    private val aiPreferences: AiPreferencesRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    private val _nlpPlaylistPreviewState = MutableStateFlow(NlpPlaylistPreviewState())
    val nlpPlaylistPreviewState: StateFlow<NlpPlaylistPreviewState> = _nlpPlaylistPreviewState.asStateFlow()

    private val _aiPlaylistPreviewState = MutableStateFlow(NlpPlaylistPreviewState())
    val aiPlaylistPreviewState: StateFlow<NlpPlaylistPreviewState> = _aiPlaylistPreviewState.asStateFlow()

    /**
     * The in-flight AI generation.
     *
     * Held so a new request (or closing the sheet) cancels the previous one: the streaming call
     * closes its socket instead of finishing unseen in the background.
     */
    private var aiGenerationJob: Job? = null

    /** Non-null while the Serendipity sheet is open. */
    private val _serendipityState = MutableStateFlow<SerendipityUiState?>(null)
    val serendipityState: StateFlow<SerendipityUiState?> = _serendipityState.asStateFlow()

    /**
     * Whether Serendipity is going to read the device location.
     *
     * The sheet asks for the location permission only when this is true, so picking a city (or
     * turning weather off) really does mean the app never asks.
     */
    val serendipityWantsLocation: StateFlow<Boolean> =
            aiPreferences.getSerendipityWeatherSource()
                    .map { it == SerendipityWeatherSource.DEVICE_LOCATION }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether the active provider has what it needs to run (key, or a url for custom ones). */
    val isAiConfigured: StateFlow<Boolean> =
            aiPreferences.providerFlow
                    .flatMapLatest { provider ->
                        if (provider.requiresApiKey) {
                            aiPreferences.getApiKey(provider).map { it.isNotBlank() }
                        } else {
                            aiPreferences.getBaseUrl(provider).map { it.isNotBlank() }
                        }
                    }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _aiMixSaved = MutableSharedFlow<AiMixSaved>(extraBufferCapacity = 1)
    val aiMixSaved: SharedFlow<AiMixSaved> = _aiMixSaved.asSharedFlow()

    /**
     * Every generated mix, newest first, for the home row and the full list behind it.
     *
     * No extra storage: a generated mix is a normal playlist tagged with [AI_MIX_SOURCE], so the
     * existing playlists flow already carries everything these screens need.
     */
    val recentAiMixes: StateFlow<List<Playlist>> =
            _uiState
                    .map { state ->
                        state.playlists
                                .filter { it.source == AI_MIX_SOURCE || it.source == SERENDIPITY_SOURCE }
                                .sortedByDescending { it.createdAt }
                    }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _playlistCreationEvent = MutableSharedFlow<Boolean>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val playlistCreationEvent: SharedFlow<Boolean> = _playlistCreationEvent.asSharedFlow()

    companion object {
        const val FOLDER_PLAYLIST_PREFIX = "folder_playlist:"
        private const val MANUAL_ORDER_MODE = "manual"
        private const val SMART_PLAYLIST_MAX_ITEMS = 100

        /**
         * How many tracks a generated mix asks for when the user has not picked a length.
         *
         * Shared by the describe flow and Serendipity; it is the first chip in AI_MIX_LENGTHS.
         */
        const val DEFAULT_AI_MIX_LENGTH = 15

        /** Used when the user leaves the prompt blank and no Serendipity sentence was composed. */
        const val DEFAULT_AI_MIX_FALLBACK_PROMPT = "Pick something that fits this moment. Surprise me."

        fun sanitizeFileName(name: String): String {
            val sanitized = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").trim('_')
            return sanitized.ifEmpty { "Playlist" }
        }
    }

    private fun resolvePlaylistSortOption(optionKey: String?): SortOption {
        return SortOption.fromStorageKey(
            optionKey,
            SortOption.PLAYLISTS,
            SortOption.PlaylistNameAZ
        )
    }

    init {
        loadPlaylistsAndInitialSortOption()
        observePlaylistOrderModes()
    }

    private fun observePlaylistOrderModes() {
        viewModelScope.launch {
            playlistPreferencesRepository.playlistSongOrderModesFlow.collect { storedModes ->
                val resolvedModes = storedModes.mapValues { (_, value) ->
                    decodeOrderMode(value)
                }
                _uiState.update { it.copy(playlistOrderModes = resolvedModes) }
            }
        }
    }

    private fun loadPlaylistsAndInitialSortOption() {
        viewModelScope.launch {
            val initialSortOptionName = playlistPreferencesRepository.playlistsSortOptionFlow.first()
            val initialSortOption = resolvePlaylistSortOption(initialSortOptionName)
            val initialSourceFilter = playlistPreferencesRepository.playlistSourceFilterFlow.first()
            _uiState.update {
                it.copy(
                    currentPlaylistSortOption = initialSortOption,
                    playlistSourceFilter = initialSourceFilter
                )
            }

            playlistPreferencesRepository.userPlaylistsFlow.collect { playlists ->
                val currentSortOption =
                    _uiState.value.currentPlaylistSortOption
                val sortedPlaylists = sortPlaylistsList(playlists, currentSortOption)
                _uiState.update { it.copy(playlists = sortedPlaylists.toImmutableList()) }
            }
        }
        viewModelScope.launch {
            playlistPreferencesRepository.playlistsSortOptionFlow.collect { optionName ->
                val newSortOption = resolvePlaylistSortOption(optionName)
                if (_uiState.value.currentPlaylistSortOption != newSortOption) {
                    sortPlaylists(newSortOption)
                }
            }
        }
    }

    fun loadPlaylistDetails(playlistId: String) {
        viewModelScope.launch {
            val shouldKeepExisting = _uiState.value.currentPlaylistDetails?.id == playlistId
            _uiState.update {
                it.copy(
                    isLoading = true,
                    playlistNotFound = false,
                    currentPlaylistDetails = if (shouldKeepExisting) it.currentPlaylistDetails else null,
                    currentPlaylistSongs = if (shouldKeepExisting) it.currentPlaylistSongs else emptyList()
                )
            }
            try {
                if (isFolderPlaylistId(playlistId)) {
                    val folderPath = Uri.decode(playlistId.removePrefix(FOLDER_PLAYLIST_PREFIX))
                    val folders = musicRepository.getMusicFolders().first()
                    val folder = findFolder(folderPath, folders)

                    if (folder != null) {
                        val songsList = withContext(Dispatchers.IO) {
                            val rawSongs = folder.collectAllSongs()
                            if (rawSongs.any { it.contentUriString.isBlank() }) {
                                musicRepository.getSongsByIds(rawSongs.map { it.id }).first()
                            } else {
                                rawSongs
                            }
                        }
                        val pseudoPlaylist = Playlist(
                            id = playlistId,
                            name = folder.name,
                            songIds = songsList.map { it.id }
                        )

                        val folderSortOption = _uiState.value.currentPlaylistSongsSortOption
                        val sortedFolderSongs = withContext(Dispatchers.Default) {
                            applySortToSongs(songsList, folderSortOption)
                        }
                        _uiState.update {
                            it.copy(
                                currentPlaylistDetails = pseudoPlaylist,
                                currentPlaylistSongs = sortedFolderSongs,
                                playlistSongsOrderMode = PlaylistSongsOrderMode.Sorted(folderSortOption),
                                isLoading = false,
                                playlistNotFound = false
                            )
                        }
                    } else {
                        Timber.tag("PlaylistVM").w("Folder playlist with path $folderPath not found.")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                playlistNotFound = true,
                                currentPlaylistDetails = null,
                                currentPlaylistSongs = emptyList()
                            )
                        }
                    }
                } else {
                    val playlist = playlistPreferencesRepository.userPlaylistsFlow.first()
                        .find { it.id == playlistId }

                    if (playlist != null) {
                        val playlistForDisplay = refreshSmartPlaylistIfNeeded(playlist)
                        val orderMode = _uiState.value.playlistOrderModes[playlistId]
                            ?: PlaylistSongsOrderMode.Manual

                        val songsList: List<Song> = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            musicRepository.getSongsByIds(playlistForDisplay.songIds).first()
                        }

                        val orderedSongs = when (orderMode) {
                            is PlaylistSongsOrderMode.Sorted -> withContext(Dispatchers.Default) {
                                applySortToSongs(songsList, orderMode.option)
                            }
                            PlaylistSongsOrderMode.Manual -> songsList
                        }

                        _uiState.update {
                            it.copy(
                                currentPlaylistDetails = playlistForDisplay,
                                currentPlaylistSongs = orderedSongs,
                                currentPlaylistSongsSortOption = (orderMode as? PlaylistSongsOrderMode.Sorted)?.option
                                    ?: it.currentPlaylistSongsSortOption,
                                playlistSongsOrderMode = orderMode,
                                playlistOrderModes = it.playlistOrderModes + (playlistId to orderMode),
                                isLoading = false,
                                playlistNotFound = false
                            )
                        }
                    } else {
                        Timber.tag("PlaylistVM").w("Playlist with id $playlistId not found.")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                playlistNotFound = true,
                                currentPlaylistDetails = null,
                                currentPlaylistSongs = emptyList()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("PlaylistVM").e(e, "Error loading playlist details for id $playlistId")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        playlistNotFound = true,
                        currentPlaylistDetails = null,
                        currentPlaylistSongs = emptyList()
                    )
                }
            }
        }
    }

    fun createPlaylist(
        name: String,
        coverImageUri: String? = null,
        coverColor: Int? = null,
        coverIcon: String? = null,
        songIds: List<String> = emptyList(),
        cropScale: Float = 1f,
        cropPanX: Float = 0f,
        cropPanY: Float = 0f,
        isQueueGenerated: Boolean = false,
        coverShapeType: String? = null,
        coverShapeDetail1: Float? = null,
        coverShapeDetail2: Float? = null,
        coverShapeDetail3: Float? = null,
        coverShapeDetail4: Float? = null,
        source: String = "LOCAL",
        smartRuleKey: String? = null
    ) {
        viewModelScope.launch {
            var savedCoverPath: String? = null

            if (coverImageUri != null) {
                val imageId = UUID.randomUUID().toString()
                savedCoverPath = saveCoverImageToInternalStorage(
                    Uri.parse(coverImageUri),
                    imageId,
                    cropScale,
                    cropPanX,
                    cropPanY
                )
            }

            val resolvedSmartRule = SmartPlaylistRule.fromStorageKey(smartRuleKey)
            val resolvedSongIds = if (resolvedSmartRule != null) {
                buildSmartPlaylistSongIds(
                    rule = resolvedSmartRule,
                    limit = SMART_PLAYLIST_MAX_ITEMS
                )
            } else {
                songIds
            }
            val resolvedSource = resolvedSmartRule?.toPlaylistSource() ?: source

            playlistPreferencesRepository.createPlaylist(
                name = name,
                songIds = resolvedSongIds,
                isQueueGenerated = isQueueGenerated,
                coverImageUri = savedCoverPath,
                coverColorArgb = coverColor,
                coverIconName = coverIcon,
                coverShapeType = coverShapeType,
                coverShapeDetail1 = coverShapeDetail1,
                coverShapeDetail2 = coverShapeDetail2,
                coverShapeDetail3 = coverShapeDetail3,
                coverShapeDetail4 = coverShapeDetail4,
                source = resolvedSource
            )
            _playlistCreationEvent.emit(true)
        }
    }

    /** Runs the offline NLP engine over the library and publishes the ranked preview. */
    fun generateNlpPlaylistPreview(description: String) {
        if (description.isBlank()) return
        viewModelScope.launch {
            _nlpPlaylistPreviewState.update { it.copy(isGenerating = true) }
            val songs = try {
                nlpPlaylistGenerator.generate(description)
            } catch (e: Exception) {
                Timber.tag("PlaylistVM").e(e, "NLP playlist generation failed")
                emptyList()
            }
            _nlpPlaylistPreviewState.value = NlpPlaylistPreviewState(
                isGenerating = false,
                songs = songs.toImmutableList(),
                hasResult = true
            )
        }
    }

    /** Clears the "describe it" preview when its dialog closes. */
    fun resetNlpPlaylistPreview() {
        _nlpPlaylistPreviewState.value = NlpPlaylistPreviewState()
    }

    /**
     * Asks the configured AI provider for a track list and matches it against the library.
     *
     * Failures stay inside the preview state as a localised message so the dialog can explain
     * what to fix (bad key, exhausted quota, unknown model) instead of silently returning
     * nothing.
     */
    fun generateAiPlaylistPreview(description: String, maxLength: Int = DEFAULT_AI_MIX_LENGTH) {
        val effective = resolveEffectivePrompt(description)
        startPreview(
            effective,
            resolveSample = {
                aiPreferences.getLibrarySampleMode().first() to
                        aiPreferences.getLibrarySampleSize().first()
            }
        ) { listener -> aiPlaylistGenerator.generate(effective, maxLength, listener = listener) }
    }

    /**
     * Generation for the Serendipity entry (short press and long press share this path).
     *
     * Forced random sampling, no cache. One preview state means the result screen, the
     * "play / save only" actions and the error handling stay shared.
     */
    fun generateSerendipityPreview(description: String, maxLength: Int = DEFAULT_AI_MIX_LENGTH) {
        val effective = resolveEffectivePrompt(description)
        startPreview(
            effective,
            resolveSample = {
                aiPreferences.getSerendipitySampleMode().first() to
                        aiPreferences.getSerendipitySampleSize().first()
            }
        ) { listener ->
            aiPlaylistGenerator.generateSerendipity(effective, maxLength, listener = listener)
        }
    }

    /**
     * Empty prompt is allowed: fall back to the Serendipity sentence built from the moment's
     * signals, then to a plain "surprise me" request so a blank field still generates.
     */
    private fun resolveEffectivePrompt(description: String): String {
        if (description.isNotBlank()) return description.trim()
        return _serendipityState.value?.prompt?.takeIf { it.isNotBlank() }
                ?: DEFAULT_AI_MIX_FALLBACK_PROMPT
    }

    private fun startPreview(
        description: String,
        resolveSample: (suspend () -> Pair<AiLibrarySampleMode, Int>)? = null,
        generate: suspend (AiProgressListener?) -> List<Song>
    ) {
        if (description.isBlank()) return
        // Regenerating while a request is in flight: drop the old socket first.
        aiGenerationJob?.cancel()
        aiGenerationJob = viewModelScope.launch {
            _aiPlaylistPreviewState.update {
                it.copy(
                        isGenerating = true,
                        errorMessage = null,
                        hasResult = false,
                        stage = AiGenerationStage.IDLE,
                        thinkingText = ""
                )
            }
            // Captured before the call so the saved metadata reflects this generation even if the
            // user changes the advanced controls while the request is in flight.
            val sample = resolveSample?.invoke()
            val songs =
                    try {
                        generate(progressListener())
                    } catch (e: CancellationException) {
                        // Superseded by a newer request or dismissed: leave the state to whoever
                        // took over instead of writing an error into it.
                        throw e
                    } catch (error: Throwable) {
                        Timber.tag("PlaylistVM").e(error, "AI playlist generation failed")
                        _aiPlaylistPreviewState.value =
                                NlpPlaylistPreviewState(
                                        isGenerating = false,
                                        hasResult = true,
                                        errorMessage = describeAiFailure(error)
                                )
                        return@launch
                    }
            // copy() rather than a fresh state: the streamed thought process has to survive into
            // the result phase, and rebuilding the state would silently drop it.
            _aiPlaylistPreviewState.update {
                it.copy(
                        isGenerating = false,
                        songs = songs.toImmutableList(),
                        hasResult = true,
                        stage = AiGenerationStage.IDLE,
                        sampleMode = sample?.first,
                        sampleSize = sample?.second
                )
            }
        }
    }

    /**
     * Mirrors the provider's stream into the preview state.
     *
     * Only the answer *stage* is tracked, not its text: the raw candidate list is noisy and the
     * locally matched songs are what the result phase shows anyway. Bound to the generating scope
     * so a late chunk from a cancelled request cannot repopulate a state that was already reset.
     */
    private fun CoroutineScope.progressListener() =
            AiProgressListener { event ->
                if (!isActive) return@AiProgressListener
                when (event) {
                    is AiProgressEvent.Thinking ->
                            _aiPlaylistPreviewState.update {
                                it.copy(
                                        stage = AiGenerationStage.THINKING,
                                        thinkingText = event.full
                                )
                            }
                    is AiProgressEvent.Answer ->
                            _aiPlaylistPreviewState.update {
                                it.copy(stage = AiGenerationStage.ANSWERING)
                            }
                }
            }

    /**
     * Opens Serendipity: gather the moment's signals, compose a local prompt, show the sheet.
     *
     * The prompt is built on the device, so the sheet appears with text already in it and costs
     * nothing — the AI is only asked once the user confirms.
     */
    fun openSerendipity() {
        _serendipityState.value = SerendipityUiState()
        viewModelScope.launch {
            val composed = collectAndComposeSerendipity() ?: return@launch
            _serendipityState.value = composed
        }
    }

    /**
     * Short-press shortcut: gather the signals and immediately start generation with defaults,
     * skipping the manual confirm step. The same sheet observes the collecting/generating states,
     * so the UI needs no separate "quick" flow.
     *
     * A blank composed sentence no longer aborts: [resolveEffectivePrompt] supplies a fallback so
     * "generate with whatever fits this moment" always has a request to send.
     */
    fun quickGenerateSerendipity() {
        _serendipityState.value = SerendipityUiState()
        viewModelScope.launch {
            val composed = collectAndComposeSerendipity() ?: return@launch
            _serendipityState.value = composed
            generateSerendipityPreview(composed.prompt, DEFAULT_AI_MIX_LENGTH)
        }
    }

    /**
     * Collects the moment's signals and turns them into a local prompt sentence.
     *
     * Returns null when Serendipity was closed while collection (which may include a weather
     * network call) was still in flight.
     */
    private suspend fun collectAndComposeSerendipity(): SerendipityUiState? {
        val context = runCatching { serendipityContextCollector.collect() }.getOrNull()
        if (_serendipityState.value == null) return null
        val variant = Random.nextInt(SerendipityPromptComposer.VARIANT_COUNT)
        return SerendipityUiState(
                context = context,
                prompt = context?.let { SerendipityPromptComposer.compose(it, variant) }.orEmpty(),
                variant = variant,
                isCollecting = false
        )
    }

    /** Another wording from the same signals: no re-collection, no network, no cost. */
    fun reshuffleSerendipityPrompt() {
        val current = _serendipityState.value ?: return
        val context = current.context ?: return
        val variant = Random.nextInt(SerendipityPromptComposer.VARIANT_COUNT)
        _serendipityState.value =
                current.copy(
                        prompt = SerendipityPromptComposer.compose(context, variant),
                        variant = variant,
                        rephraseFailed = false
                )
    }

    /**
     * Optional second call: asks the provider to rewrite the local sentence.
     *
     * Failure is silent beyond a hint line — the local sentence is still perfectly usable, so
     * there is nothing to recover from.
     */
    fun rephraseSerendipityPrompt() {
        val current = _serendipityState.value ?: return
        if (current.prompt.isBlank() || current.isRephrasing) return
        viewModelScope.launch {
            _serendipityState.update { it?.copy(isRephrasing = true, rephraseFailed = false) }
            val rephrased =
                    runCatching {
                                aiHandler.generateText(
                                        systemPrompt = AiSystemPromptEngine.serendipityRephraseSystemPrompt(),
                                        userPrompt =
                                                AiSystemPromptEngine.serendipityRephraseUserPrompt(
                                                        current.prompt
                                                ),
                                        promptType = AiHandler.PROMPT_TYPE_SERENDIPITY_PROMPT
                                )
                            }
                            .getOrNull()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
            _serendipityState.update { state ->
                state?.copy(
                        prompt = rephrased ?: state.prompt,
                        isRephrasing = false,
                        rephraseFailed = rephrased == null
                )
            }
        }
    }

    /** Clears the Serendipity state when its sheet closes. */
    fun closeSerendipity() {
        _serendipityState.value = null
    }

    /** How many song titles are handed to the model; the user picks this next to the prompt. */
    val aiLibrarySampleSize: StateFlow<Int> =
            aiPreferences
                    .getLibrarySampleSize()
                    .stateIn(
                            viewModelScope,
                            SharingStarted.WhileSubscribed(5_000),
                            AiPreferencesRepository.DEFAULT_LIBRARY_SAMPLE_SIZE
                    )

    fun setAiLibrarySampleSize(size: Int) {
        viewModelScope.launch { aiPreferences.setLibrarySampleSize(size) }
    }

    val aiLibrarySampleMode: StateFlow<AiLibrarySampleMode> =
            aiPreferences
                    .getLibrarySampleMode()
                    .stateIn(
                            viewModelScope,
                            SharingStarted.WhileSubscribed(5_000),
                            AiLibrarySampleMode.MOST_PLAYED
                    )

    fun setAiLibrarySampleMode(mode: AiLibrarySampleMode) {
        viewModelScope.launch { aiPreferences.setLibrarySampleMode(mode) }
    }

    /** Serendipity's own context size; independent of the describe flow. */
    val aiSerendipitySampleSize: StateFlow<Int> =
            aiPreferences
                    .getSerendipitySampleSize()
                    .stateIn(
                            viewModelScope,
                            SharingStarted.WhileSubscribed(5_000),
                            AiPreferencesRepository.DEFAULT_SERENDIPITY_SAMPLE_SIZE
                    )

    fun setAiSerendipitySampleSize(size: Int) {
        viewModelScope.launch { aiPreferences.setSerendipitySampleSize(size) }
    }

    /** Serendipity defaults to RANDOM so a "right now" mix can still reach rarely played songs. */
    val aiSerendipitySampleMode: StateFlow<AiLibrarySampleMode> =
            aiPreferences
                    .getSerendipitySampleMode()
                    .stateIn(
                            viewModelScope,
                            SharingStarted.WhileSubscribed(5_000),
                            AiLibrarySampleMode.RANDOM
                    )

    fun setAiSerendipitySampleMode(mode: AiLibrarySampleMode) {
        viewModelScope.launch { aiPreferences.setSerendipitySampleMode(mode) }
    }

    /** Resolves songs by id, used to list the originally generated songs of an AI playlist. */
    fun songsByIds(ids: List<String>): Flow<List<Song>> = musicRepository.getSongsByIds(ids)

    /** Clears the AI preview when its dialog closes, aborting any request still in flight. */
    fun resetAiPlaylistPreview() {
        aiGenerationJob?.cancel()
        aiGenerationJob = null
        _aiPlaylistPreviewState.value = NlpPlaylistPreviewState()
    }

    /**
     * Persists a generated mix and reports it back so the caller can start playback.
     *
     * Playback lives in `PlayerViewModel`, so this only saves and emits — the screen decides
     * what to do with the result.
     */
    fun saveAiMix(
        name: String,
        songs: List<Song>,
        prompt: String,
        startPlayback: Boolean,
        source: String = AI_MIX_SOURCE
    ) {
        if (songs.isEmpty()) return
        viewModelScope.launch {
            // The preview state still holds the unedited generation: its songs are the "original"
            // snapshot, while [songs] is the user's possibly trimmed result being saved. Reading it
            // here is safe even though the caller resets the preview right after: viewModelScope
            // runs on Main.immediate, so this prologue finishes before the first suspension.
            val preview = _aiPlaylistPreviewState.value
            val playlist = playlistPreferencesRepository.createPlaylist(
                name = name,
                songIds = songs.map { it.id },
                source = source,
                // Kept so the playlist screen can show what the mix was asked for.
                aiPrompt = prompt.ifBlank { null },
                aiSampleMode = preview.sampleMode?.name,
                aiSampleSize = preview.sampleSize,
                aiOriginalSongIds = preview.songs.map { it.id },
                // Why the model picked these songs; blank when thinking was off.
                aiThinking = preview.thinkingText.ifBlank { null }
            )
            _aiMixSaved.emit(
                AiMixSaved(
                    playlistId = playlist.id,
                    name = playlist.name,
                    songs = songs,
                    prompt = prompt,
                    startPlayback = startPlayback
                )
            )
        }
    }

    private fun describeAiFailure(error: Throwable): String =
            when ((error as? AiProviderException)?.kind) {
                AiErrorKind.UNAUTHORIZED -> R.string.ai_error_unauthorized
                AiErrorKind.QUOTA_EXCEEDED -> R.string.ai_error_quota
                AiErrorKind.MODEL_NOT_FOUND -> R.string.ai_error_model_not_found
                AiErrorKind.RATE_LIMITED -> R.string.ai_error_rate_limited
                AiErrorKind.SERVER -> R.string.ai_error_server
                AiErrorKind.NETWORK -> R.string.ai_error_network
                AiErrorKind.RESPONSE_PARSE -> R.string.ai_error_parse
                else -> R.string.ai_error_unknown
            }.let { context.getString(it) }

    private suspend fun buildSmartPlaylistSongIds(
        rule: SmartPlaylistRule,
        limit: Int
    ): List<String> {
        val allSongs = musicRepository.getAllSongsOnce()
        if (allSongs.isEmpty()) return emptyList()

        return SmartPlaylistBuilder.buildSongIds(
            rule = rule,
            allSongs = allSongs,
            engagements = dailyMixManager.getAllEngagementStats(),
            favoriteIds = musicRepository.getFavoriteSongIdsOnce(),
            now = System.currentTimeMillis(),
            limit = limit
        )
    }

    private suspend fun refreshSmartPlaylistIfNeeded(playlist: Playlist): Playlist {
        val smartRule = SmartPlaylistRule.fromPlaylistSource(playlist.source) ?: return playlist
        val refreshedSongIds = buildSmartPlaylistSongIds(
            rule = smartRule,
            limit = SMART_PLAYLIST_MAX_ITEMS
        )

        if (refreshedSongIds == playlist.songIds) return playlist

        val refreshedPlaylist = playlist.copy(
            songIds = refreshedSongIds,
            lastModified = System.currentTimeMillis()
        )
        playlistPreferencesRepository.updatePlaylist(refreshedPlaylist)
        return refreshedPlaylist
    }


    suspend fun saveCoverImageToInternalStorage(
        uri: Uri,
        uniqueId: String,
        cropScale: Float,
        cropPanX: Float,
        cropPanY: Float
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = when {
                        uri.scheme == "content" -> ImageDecoder.createSource(context.contentResolver, uri)
                        uri.scheme == "file" || uri.path?.startsWith("/") == true -> {
                            ImageDecoder.createSource(File(uri.path ?: ""))
                        }
                        else -> ImageDecoder.createSource(context.contentResolver, uri)
                    }
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (uri.scheme == "content") {
                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    } else {
                        android.graphics.BitmapFactory.decodeFile(uri.path)
                    }
                }

                if (originalBitmap == null) return@withContext null

                val targetSize = 1024

                val targetBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(targetBitmap)

                val bitmapWidth = originalBitmap.width.toFloat()
                val bitmapHeight = originalBitmap.height.toFloat()
                val bitmapRatio = bitmapWidth / bitmapHeight

                val (baseWidth, baseHeight) = if (bitmapRatio > 1f) {
                    targetSize * bitmapRatio to targetSize.toFloat()
                } else {
                    targetSize.toFloat() to targetSize / bitmapRatio
                }

                val scaledWidth = baseWidth * cropScale
                val scaledHeight = baseHeight * cropScale

                val panPxX = cropPanX * targetSize
                val panPxY = cropPanY * targetSize

                val dx = (targetSize - scaledWidth) / 2f + panPxX
                val dy = (targetSize - scaledHeight) / 2f + panPxY

                val matrix = android.graphics.Matrix()
                matrix.postScale(scaledWidth / bitmapWidth, scaledHeight / bitmapHeight)
                matrix.postTranslate(dx, dy)

                canvas.drawBitmap(originalBitmap, matrix, null)

                val fileName = "playlist_cover_$uniqueId.jpg"
                val file = File(context.filesDir, fileName)
                FileOutputStream(file).use { out ->
                    targetBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                if (originalBitmap != targetBitmap) originalBitmap.recycle()

                file.absolutePath
            } catch (e: Exception) {
                Timber.e(e)
                null
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        if (isFolderPlaylistId(playlistId)) return
        viewModelScope.launch {
            playlistPreferencesRepository.deletePlaylist(playlistId)
        }
    }

    fun importM3u(uri: Uri) {
        viewModelScope.launch {
            try {
                val (name, songIds) = m3uManager.parseM3u(uri)
                if (songIds.isNotEmpty()) {
                    playlistPreferencesRepository.createPlaylist(name, songIds)
                }
            } catch (e: Exception) {
                Timber.tag("PlaylistViewModel").e(e, "Error importing M3U")
            }
        }
    }

    fun exportM3u(playlist: Playlist, uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            try {
                val songs = musicRepository.getSongsByIds(playlist.songIds).first()
                val m3uContent = m3uManager.generateM3u(playlist, songs)
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer ->
                        writer.write(m3uContent)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("PlaylistViewModel").e(e, "Error exporting M3U")
            }
        }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        if (isFolderPlaylistId(playlistId)) return
        viewModelScope.launch {
            playlistPreferencesRepository.renamePlaylist(playlistId, newName)
            if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
                _uiState.update {
                    it.copy(
                        currentPlaylistDetails = it.currentPlaylistDetails?.copy(
                            name = newName
                        )
                    )
                }
            }
        }
    }

    fun updatePlaylistParameters(
        playlistId: String,
        name: String,
        coverImageUri: String?,
        coverColor: Int?,
        coverIcon: String?,
        cropScale: Float,
        cropPanX: Float,
        cropPanY: Float,
        coverShapeType: String?,
        coverShapeDetail1: Float?,
        coverShapeDetail2: Float?,
        coverShapeDetail3: Float?,
        coverShapeDetail4: Float?
    ) {
        if (isFolderPlaylistId(playlistId)) return
        val currentPlaylist = _uiState.value.currentPlaylistDetails ?: return
        if (currentPlaylist.id != playlistId) return

        viewModelScope.launch {
            var savedCoverPath: String? = currentPlaylist.coverImageUri

            val isNewImage = coverImageUri != null && coverImageUri != currentPlaylist.coverImageUri
            val isAdjusted = cropScale != 1f || cropPanX != 0f || cropPanY != 0f

            if (coverImageUri != null && (isNewImage || isAdjusted)) {
                val imageId = UUID.randomUUID().toString()
                val newPath = saveCoverImageToInternalStorage(
                    Uri.parse(coverImageUri),
                    imageId,
                    cropScale,
                    cropPanX,
                    cropPanY
                )
                if (newPath != null) {
                    currentPlaylist.coverImageUri?.let { oldPath ->
                        if (oldPath.contains("playlist_cover_")) {
                            try { File(oldPath).delete() } catch (e: Exception) {}
                        }
                    }
                    savedCoverPath = newPath
                }
            } else if (coverImageUri == null) {
                currentPlaylist.coverImageUri?.let { oldPath ->
                    if (oldPath.contains("playlist_cover_")) {
                        try { File(oldPath).delete() } catch (e: Exception) {}
                    }
                }
                savedCoverPath = null
            }


            val updatedPlaylist = currentPlaylist.copy(
                name = name,
                coverImageUri = savedCoverPath,
                coverColorArgb = coverColor,
                coverIconName = coverIcon,
                coverShapeType = coverShapeType,
                coverShapeDetail1 = coverShapeDetail1,
                coverShapeDetail2 = coverShapeDetail2,
                coverShapeDetail3 = coverShapeDetail3,
                coverShapeDetail4 = coverShapeDetail4
            )

            _uiState.update {
                it.copy(currentPlaylistDetails = updatedPlaylist)
            }

            playlistPreferencesRepository.updatePlaylist(updatedPlaylist)
        }
    }

    fun addSongsToPlaylist(playlistId: String, songIdsToAdd: List<String>) {
        if (isFolderPlaylistId(playlistId)) return
        viewModelScope.launch {
            if (playlistPreferencesRepository.getPlaylistsOnce().any { it.id == playlistId && it.isSmartPlaylist }) {
                return@launch
            }
            playlistPreferencesRepository.addSongsToPlaylist(playlistId, songIdsToAdd)
            if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
                loadPlaylistDetails(playlistId)
            }
        }
    }

    /**
     * @param playlistIds Ids of playlists to add the song to
     * */
    fun addOrRemoveSongFromPlaylists(
        songId: String,
        playlistIds: List<String>,
        currentPlaylistId: String?
    ) {
        viewModelScope.launch {
            val smartPlaylistIds = playlistPreferencesRepository.getPlaylistsOnce()
                .filter { it.isSmartPlaylist }
                .map { it.id }
                .toSet()
            val editablePlaylistIds = playlistIds.filterNot { it in smartPlaylistIds }
            val removedFromPlaylists =
                playlistPreferencesRepository.addOrRemoveSongFromPlaylists(songId, editablePlaylistIds)
            if (currentPlaylistId != null && removedFromPlaylists.contains (currentPlaylistId)) {
                removeSongFromPlaylist(currentPlaylistId, songId)
            }
        }
    }

    fun addSongsToPlaylists(songIds: List<String>, playlistIds: List<String>) {
        viewModelScope.launch {
            val smartPlaylistIds = playlistPreferencesRepository.getPlaylistsOnce()
                .filter { it.isSmartPlaylist }
                .map { it.id }
                .toSet()
            playlistIds.filterNot { it in smartPlaylistIds }.forEach { playlistId ->
                playlistPreferencesRepository.addSongsToPlaylist(playlistId, songIds)
            }
        }
    }

    fun removeSongFromPlaylist(playlistId: String, songIdToRemove: String) {
        if (isFolderPlaylistId(playlistId)) return
        viewModelScope.launch {
            if (playlistPreferencesRepository.getPlaylistsOnce().any { it.id == playlistId && it.isSmartPlaylist }) {
                return@launch
            }
            playlistPreferencesRepository.removeSongFromPlaylist(playlistId, songIdToRemove)
            if (_uiState.value.currentPlaylistDetails?.id == playlistId) {
                _uiState.update {
                    it.copy(currentPlaylistSongs = it.currentPlaylistSongs.filterNot { s -> s.id == songIdToRemove })
                }
            }
        }
    }

    fun reorderSongsInPlaylist(playlistId: String, fromIndex: Int, toIndex: Int) {
        if (isFolderPlaylistId(playlistId)) return
        val currentPlaylist = _uiState.value.currentPlaylistDetails ?: return
        if (currentPlaylist.id != playlistId || currentPlaylist.isSmartPlaylist) return
        viewModelScope.launch {
            val currentSongs = _uiState.value.currentPlaylistSongs.toMutableList()
            if (fromIndex in currentSongs.indices && toIndex in currentSongs.indices) {
                val item = currentSongs.removeAt(fromIndex)
                currentSongs.add(toIndex, item)
                val newSongOrderIds = currentSongs.map { it.id }
                playlistPreferencesRepository.reorderSongsInPlaylist(playlistId, newSongOrderIds)
                playlistPreferencesRepository.setPlaylistSongOrderMode(
                    playlistId,
                    MANUAL_ORDER_MODE
                )
                _uiState.update {
                    val updatedModes = it.playlistOrderModes + (playlistId to PlaylistSongsOrderMode.Manual)
                    it.copy(
                        currentPlaylistSongs = currentSongs,
                        playlistSongsOrderMode = PlaylistSongsOrderMode.Manual,
                        playlistOrderModes = updatedModes
                    )
                }
            }
        }
    }

    fun sortPlaylists(sortOption: SortOption) {
        if (_uiState.value.currentPlaylistSortOption.storageKey == sortOption.storageKey) {
            return
        }

        _uiState.update { it.copy(currentPlaylistSortOption = sortOption) }

        val currentPlaylists = _uiState.value.playlists
        val sortedPlaylists = sortPlaylistsList(currentPlaylists, sortOption)

        _uiState.update { it.copy(playlists = sortedPlaylists.toImmutableList()) }

        viewModelScope.launch {
            playlistPreferencesRepository.setPlaylistsSortOption(sortOption.storageKey)
        }
    }

    /** Cycles the library playlists-tab source filter and persists it for the next launch. */
    fun cyclePlaylistSourceFilter() {
        val next = when (_uiState.value.playlistSourceFilter) {
            PlaylistSourceFilter.ALL -> PlaylistSourceFilter.AI
            PlaylistSourceFilter.AI -> PlaylistSourceFilter.NORMAL
            PlaylistSourceFilter.NORMAL -> PlaylistSourceFilter.ALL
        }
        _uiState.update { it.copy(playlistSourceFilter = next) }
        viewModelScope.launch {
            playlistPreferencesRepository.setPlaylistSourceFilter(next)
        }
    }

    /** True when [playlist] was generated by AI (mix flow or Serendipity). */
    fun isAiGenerated(playlist: Playlist): Boolean =
        playlist.source == AI_MIX_SOURCE || playlist.source == SERENDIPITY_SOURCE

    fun sortPlaylistSongs(sortOption: SortOption) {
        val playlistId = _uiState.value.currentPlaylistDetails?.id

        if (sortOption == SortOption.SongDefaultOrder) {
            if (playlistId != null) {
                viewModelScope.launch {
                    playlistPreferencesRepository.setPlaylistSongOrderMode(
                        playlistId,
                        MANUAL_ORDER_MODE
                    )
                    loadPlaylistDetails(playlistId)
                }
            }
            return
        }

        val currentSongs = _uiState.value.currentPlaylistSongs
        viewModelScope.launch {
            val sortedSongs = withContext(Dispatchers.Default) {
                sortSongsList(currentSongs, sortOption)
            }

            _uiState.update {
                if (playlistId != it.currentPlaylistDetails?.id) {
                    return@update it
                }
                val updatedModes = if (playlistId != null) {
                    it.playlistOrderModes + (playlistId to PlaylistSongsOrderMode.Sorted(sortOption))
                } else {
                    it.playlistOrderModes
                }
                it.copy(
                    currentPlaylistSongs = sortedSongs,
                    currentPlaylistSongsSortOption = sortOption,
                    playlistSongsOrderMode = PlaylistSongsOrderMode.Sorted(sortOption),
                    playlistOrderModes = updatedModes
                )
            }

            if (playlistId != null) {
                playlistPreferencesRepository.setPlaylistSongOrderMode(
                    playlistId,
                    sortOption.storageKey
                )
            }
        }
    }

    private fun isFolderPlaylistId(playlistId: String): Boolean =
        playlistId.startsWith(FOLDER_PLAYLIST_PREFIX)

    private fun findFolder(
        targetPath: String,
        folders: List<com.pisces312.piscesplayer.data.model.MusicFolder>
    ): com.pisces312.piscesplayer.data.model.MusicFolder? {
        val queue: ArrayDeque<com.pisces312.piscesplayer.data.model.MusicFolder> = ArrayDeque(folders)
        while (queue.isNotEmpty()) {
            val folder = queue.removeFirst()
            if (folder.path == targetPath) {
                return folder
            }
            folder.subFolders.forEach { queue.addLast(it) }
        }
        return null
    }

    private fun com.pisces312.piscesplayer.data.model.MusicFolder.collectAllSongs(): List<Song> {
        return songs + subFolders.flatMap { it.collectAllSongs() }
    }

    private fun applySortToSongs(songs: List<Song>, sortOption: SortOption): List<Song> {
        return sortSongsList(songs, sortOption)
    }

    private fun sortPlaylistsList(
        playlists: List<com.pisces312.piscesplayer.data.model.Playlist>,
        sortOption: SortOption
    ): List<com.pisces312.piscesplayer.data.model.Playlist> {
        return when (sortOption) {
            SortOption.PlaylistNameAZ -> playlists.sortedWith(
                compareBy<com.pisces312.piscesplayer.data.model.Playlist> { it.name.lowercase() }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.id }
            )
            SortOption.PlaylistNameZA -> playlists.sortedWith(
                compareByDescending<com.pisces312.piscesplayer.data.model.Playlist> { it.name.lowercase() }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.id }
            )
            SortOption.PlaylistDateCreated -> playlists.sortedWith(
                compareByDescending<com.pisces312.piscesplayer.data.model.Playlist> { it.lastModified }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.PlaylistDateCreatedAsc -> playlists.sortedWith(
                compareBy<com.pisces312.piscesplayer.data.model.Playlist> { it.lastModified }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.id }
            )
            else -> playlists.sortedWith(
                compareBy<com.pisces312.piscesplayer.data.model.Playlist> { it.name.lowercase() }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.id }
            )
        }
    }

    private fun sortSongsList(
        songs: List<Song>,
        sortOption: SortOption
    ): List<Song> {
        return when (sortOption) {
            SortOption.SongTitleAZ -> songs.sortedWith(
                compareBy<Song> { it.title.lowercase() }
                    .thenBy { it.artist.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongTitleZA -> songs.sortedWith(
                compareByDescending<Song> { it.title.lowercase() }
                    .thenBy { it.artist.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongArtist -> songs.sortedWith(
                compareBy<Song> { it.artist.lowercase() }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongArtistDesc -> songs.sortedWith(
                compareByDescending<Song> { it.artist.lowercase() }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongAlbum -> songs.sortedWith(
                compareBy<Song> { it.album.lowercase() }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongAlbumDesc -> songs.sortedWith(
                compareByDescending<Song> { it.album.lowercase() }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongDuration -> songs.sortedWith(
                compareByDescending<Song> { it.duration }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongDurationAsc -> songs.sortedWith(
                compareBy<Song> { it.duration }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongDateAdded -> songs.sortedWith(
                compareByDescending<Song> { it.dateAdded }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            SortOption.SongDateAddedAsc -> songs.sortedWith(
                compareBy<Song> { it.dateAdded }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id }
            )
            else -> songs
        }
    }

    private fun decodeOrderMode(value: String): PlaylistSongsOrderMode {
        return if (value == MANUAL_ORDER_MODE) {
            PlaylistSongsOrderMode.Manual
        } else {
            val option = SortOption.fromStorageKey(value, SortOption.SONGS, SortOption.SongTitleAZ)
            PlaylistSongsOrderMode.Sorted(option)
        }
    }

    /**
     * Delete multiple playlists in batch
     */
    fun deletePlaylistsInBatch(playlistIds: List<String>) {
        viewModelScope.launch {
            playlistIds.forEach { playlistId ->
                if (!isFolderPlaylistId(playlistId)) {
                    playlistPreferencesRepository.deletePlaylist(playlistId)
                }
            }
        }
    }

    /**
     * Merge selected playlists into a new playlist
     * Collects all songs from all selected playlists (removing duplicates)
     */
    fun mergeSelectedPlaylists(playlistIds: List<String>, newPlaylistName: String) {
        if (newPlaylistName.isBlank()) return

        viewModelScope.launch {
            try {
                val selectedPlaylists = _uiState.value.playlists.filter { it.id in playlistIds }
                val mergedSongIds = selectedPlaylists
                    .flatMap { it.songIds }
                    .distinct()
                    .toList()

                if (mergedSongIds.isNotEmpty()) {
                    playlistPreferencesRepository.createPlaylist(newPlaylistName, mergedSongIds)
                    _playlistCreationEvent.emit(true)
                }
            } catch (e: Exception) {
                Timber.tag("PlaylistViewModel").e(e, "Error merging playlists")
            }
        }
    }

    /**
     * Get all playlists with their song data for bulk operations
     */
    suspend fun getPlaylistsWithSongs(playlistIds: List<String>): List<Pair<Playlist, List<Song>>> {
        return try {
            val selectedPlaylists = _uiState.value.playlists.filter { it.id in playlistIds }
            selectedPlaylists.map { playlist ->
                val songs = musicRepository.getSongsByIds(playlist.songIds).first()
                playlist to songs
            }
        } catch (e: Exception) {
            Timber.tag("PlaylistViewModel").e(e, "Error getting playlists with songs")
            emptyList()
        }
    }

    /** Tracks of a single playlist, for callers that only want to start playback. */
    suspend fun songsOf(playlistId: String): List<Song> =
            getPlaylistsWithSongs(listOf(playlistId)).firstOrNull()?.second.orEmpty()

    /**
     * Share all selected playlists as M3U files in a ZIP
     */
    fun shareSelectedPlaylistsAsZip(playlistIds: List<String>, activity: android.app.Activity?) {
        if (activity == null) {
            Timber.tag("PlaylistViewModel").w("Activity is null, cannot share")
            return
        }

        viewModelScope.launch {
            try {
                Timber.tag("PlaylistViewModel").d("Starting share of ${playlistIds.size} playlists")
                val playlistsWithSongs = getPlaylistsWithSongs(playlistIds)

                if (playlistsWithSongs.isEmpty()) {
                    Timber.tag("PlaylistViewModel").w("No playlists found to share")
                    Toast.makeText(context, context.getString(R.string.playlist_none_to_share), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val shareFile: File
                val shareFileName: String
                val shareMimeType: String

                if (playlistsWithSongs.size == 1) {
                    val (playlist, songs) = playlistsWithSongs.first()
                    val m3uContent = m3uManager.generateM3u(playlist, songs)
                    val sanitizedName = sanitizeFileName(playlist.name)
                    shareFileName = "$sanitizedName.m3u"
                    shareFile = File(context.cacheDir, shareFileName)
                    shareFile.writeText(m3uContent)
                    shareMimeType = "audio/mpegurl"
                    Timber.tag("PlaylistViewModel").d("Created M3U file: ${shareFile.absolutePath}, size: ${shareFile.length()} bytes")
                } else {
                    val firstPlaylistName = sanitizeFileName(playlistsWithSongs.first().first.name)
                    val zipFileName = "Playlists_${firstPlaylistName}_and_${playlistsWithSongs.size - 1}_more.zip"
                    shareFile = File(context.cacheDir, zipFileName)
                    val outputStream = FileOutputStream(shareFile)

                    java.util.zip.ZipOutputStream(outputStream).use { zipOut ->
                        val usedNames = mutableSetOf<String>()
                        playlistsWithSongs.forEach { (playlist, songs) ->
                            val m3uContent = m3uManager.generateM3u(playlist, songs)
                            val baseName = sanitizeFileName(playlist.name)
                            var entryName = "$baseName.m3u"
                            var counter = 1
                            while (usedNames.contains(entryName)) {
                                entryName = "${baseName}_$counter.m3u"
                                counter++
                            }
                            usedNames.add(entryName)

                            val entry = java.util.zip.ZipEntry(entryName)
                            zipOut.putNextEntry(entry)
                            zipOut.write(m3uContent.toByteArray())
                            zipOut.closeEntry()
                        }
                    }

                    shareFileName = zipFileName
                    shareMimeType = "application/zip"
                    Timber.tag("PlaylistViewModel").d("Created ZIP file: ${shareFile.absolutePath}, size: ${shareFile.length()} bytes")
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    shareFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = shareMimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                Timber.tag("PlaylistViewModel").d("Launching share intent for: $shareFileName")
                activity.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.playlist_share_chooser_title)))
                val n = playlistsWithSongs.size
                val sharingMsg = context.resources.getQuantityString(R.plurals.sharing_playlists_message, n, n)
                Toast.makeText(context, sharingMsg, Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Timber.tag("PlaylistViewModel").e(e, "Error sharing playlists")
                Toast.makeText(context, context.getString(R.string.playlist_share_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Merge multiple playlists into one new playlist
     * @param playlistIds List of playlist IDs to merge
     * @param newPlaylistName Name for the merged playlist
     */
    fun mergePlaylistsIntoOne(playlistIds: List<String>, newPlaylistName: String) {
        if (playlistIds.isEmpty() || newPlaylistName.isEmpty()) return

        viewModelScope.launch {
            try {
                val currentPlaylists = _uiState.value.playlists

                val allSongs = mutableSetOf<String>()
                playlistIds.forEach { playlistId ->
                    val playlist = currentPlaylists.find { it.id == playlistId }
                    if (playlist != null) {
                        allSongs.addAll(playlist.songIds)
                    }
                }

                val newPlaylist = Playlist(
                    id = UUID.randomUUID().toString(),
                    name = newPlaylistName,
                    songIds = allSongs.toList(),
                    createdAt = System.currentTimeMillis(),
                    lastModified = System.currentTimeMillis(),
                    isQueueGenerated = false
                )

                playlistPreferencesRepository.createPlaylist(
                    name = newPlaylistName,
                    songIds = allSongs.toList(),
                    isQueueGenerated = false
                )

                Timber.tag("PlaylistViewModel").d("Successfully merged ${playlistIds.size} playlists into '$newPlaylistName' with ${allSongs.size} total unique songs")

            } catch (e: Exception) {
                Timber.tag("PlaylistViewModel").e(e, "Error merging playlists")
            }
        }
    }

    /**
     * Export selected playlists as M3U files to device storage
     */
    fun exportPlaylistsAsM3u(playlistIds: List<String>) {
        if (playlistIds.isEmpty()) return

        viewModelScope.launch {
            try {
                Timber.tag("PlaylistViewModel").d("Starting export of ${playlistIds.size} playlists")
                val musicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                if (!musicDir.exists()) {
                    musicDir.mkdirs()
                }

                val exportDir = File(musicDir, "PiscesPlayer Exports")
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }

                val playlistsWithSongs = getPlaylistsWithSongs(playlistIds)
                if (playlistsWithSongs.isEmpty()) {
                    Timber.tag("PlaylistViewModel").w("No playlists found to export")
                    Toast.makeText(context, context.getString(R.string.playlist_none_to_export), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                playlistsWithSongs.forEach { (playlist, songs) ->
                    val m3uContent = m3uManager.generateM3u(playlist, songs)
                    val baseName = sanitizeFileName(playlist.name)
                    var file = File(exportDir, "$baseName.m3u")
                    var counter = 1
                    while (file.exists()) {
                        file = File(exportDir, "${baseName}_$counter.m3u")
                        counter++
                    }
                    file.writeText(m3uContent)
                    Timber.tag("PlaylistViewModel").d("Exported playlist '${playlist.name}' to ${file.absolutePath}")
                }

                Timber.tag("PlaylistViewModel").d("Successfully exported ${playlistIds.size} playlists to $exportDir")
                val count = playlistsWithSongs.size
                val folderLabel = context.getString(R.string.playlist_export_folder_display)
                val exportedMsg = context.resources.getQuantityString(R.plurals.exported_playlists_message, count, count, folderLabel)
                Toast.makeText(context, exportedMsg, Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Timber.tag("PlaylistViewModel").e(e, "Error exporting playlists")
                Toast.makeText(context, context.getString(R.string.playlist_export_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
