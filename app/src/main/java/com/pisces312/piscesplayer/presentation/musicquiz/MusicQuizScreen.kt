package com.pisces312.piscesplayer.presentation.musicquiz

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.musicquiz.MUSIC_QUIZ_CLIP_DURATION_MS
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizAnswerRecord
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizMode
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizOption
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizQuestionType
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizTrack
import com.pisces312.piscesplayer.presentation.components.PlaylistBottomSheet
import com.pisces312.piscesplayer.presentation.components.SmartImage
import com.pisces312.piscesplayer.presentation.components.SmartImageListTargetSize
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import com.pisces312.piscesplayer.data.classical.ClassicalDisplay
import com.pisces312.piscesplayer.data.classical.ClassicalLibrary
import com.pisces312.piscesplayer.data.classical.ClassicalStyle
import com.pisces312.piscesplayer.data.classical.ClassicalWorkCatalog
import com.pisces312.piscesplayer.presentation.viewmodel.ClassicalLibraryViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.PlaylistViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.collections.immutable.toImmutableList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicQuizScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    quizViewModel: MusicQuizViewModel = hiltViewModel(),
) {
    val allSongs by playerViewModel.allSongsFlow.collectAsStateWithLifecycle()
    // 内置曲目只归「内置古典」模式（独立池，见下方 setBuiltInClassicalTracks）；
    // 其余模式的题目/干扰项一律来自用户曲库（Song.isBuiltIn 排除整个内置 MIDI 包，
    // 与听歌统计/AI 选歌的内置排除规则一致）。
    val quizSongs = remember(allSongs) { allSongs.filterNot { it.isBuiltIn } }
    val uiState by quizViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var shouldResumeMainPlayer by rememberSaveable { mutableStateOf(false) }
    var mainPlayerCaptured by rememberSaveable { mutableStateOf(false) }
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    var showSongSelectSheet by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var playlistSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var pendingPlaylistName by remember { mutableStateOf("") }

    LaunchedEffect(quizSongs) {
        quizViewModel.updateLibrary(quizSongs)
    }
    // Feed the built-in classical pool once the pack index is ready.
    val classicalViewModel: ClassicalLibraryViewModel = hiltViewModel()
    val classicalSongs by classicalViewModel.classicalSongs.collectAsStateWithLifecycle()
    val translationEnabled by classicalViewModel.translationEnabled.collectAsStateWithLifecycle()
    val workCatalog = classicalViewModel.workCatalog
    LaunchedEffect(classicalSongs, translationEnabled) {
        val tracks = classicalSongs.mapNotNull { song ->
            val id = song.id.toLongOrNull() ?: return@mapNotNull null
            val names = classicalViewModel.nameCatalog.songName(id)
            val display = names?.let { ClassicalDisplay.resolve(it, translate = translationEnabled) }
            MusicQuizTrack(
                id = song.id,
                title = display?.primary ?: song.title,
                artist = song.artist,
                albumArtUriString = song.albumArtUriString,
                durationMs = song.duration,
                playbackUri = song.contentUriString,
                year = 0,
                genre = workCatalog.styleOf(id),
                album = song.album,
                isClassical = true,
                isKidsSong = false,
            )
        }
        quizViewModel.setBuiltInClassicalTracks(tracks)
    }
    LaunchedEffect(Unit) {
        if (!mainPlayerCaptured) {
            mainPlayerCaptured = true
            val stableState = playerViewModel.stablePlayerState.value
            shouldResumeMainPlayer = stableState.isPlaying || stableState.playWhenReady
            if (shouldResumeMainPlayer) playerViewModel.playPause()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // The route ViewModel survives configuration changes; keep the run but freeze audio/time.
            // Do NOT restore the main player here — config change would incorrectly resume it.
            quizViewModel.pauseGame()
        }
    }
    LaunchedEffect(quizViewModel) {
        quizViewModel.events.collect { event ->
            when (event) {
                MusicQuizEvent.PreviewPlayFailed ->
                    playerViewModel.sendToast(context.getString(R.string.music_quiz_preview_play_failed))
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> quizViewModel.pauseGame()
                Lifecycle.Event.ON_RESUME -> quizViewModel.onScreenResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(view, uiState.phase) {
        view.keepScreenOn = uiState.phase == MusicQuizPhase.PREPARING ||
            uiState.phase == MusicQuizPhase.PLAYING
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(context) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    fun restoreMainPlayer() {
        if (shouldResumeMainPlayer) {
            val currentState = playerViewModel.stablePlayerState.value
            if (!currentState.isPlaying && !currentState.playWhenReady) {
                playerViewModel.playPause()
            }
        }
    }

    fun handleBack() {
        when (uiState.phase) {
            MusicQuizPhase.IDLE,
            MusicQuizPhase.FINISHED,
            MusicQuizPhase.ERROR,
            -> {
                quizViewModel.stopGame()
                restoreMainPlayer()
                onBack()
            }
            else -> quizViewModel.requestExit()
        }
    }

    fun openPlaylistFlow() {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        pendingPlaylistName = context.getString(
            R.string.music_quiz_playlist_default_name,
            timestamp,
        )
        showSongSelectSheet = true
    }

    BackHandler { handleBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.phase == MusicQuizPhase.PLAYING ||
                            uiState.phase == MusicQuizPhase.REVEALING ||
                            uiState.phase == MusicQuizPhase.PREPARING
                        ) {
                            if (uiState.isEndless) {
                                stringResource(
                                    R.string.music_quiz_round_endless,
                                    uiState.roundIndex + 1,
                                )
                            } else {
                                stringResource(
                                    R.string.music_quiz_round,
                                    uiState.roundIndex + 1,
                                    uiState.totalRounds,
                                )
                            }
                        } else {
                            stringResource(R.string.music_games_quiz_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::handleBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back),
                        )
                    }
                },
                actions = {
                    when (uiState.phase) {
                        MusicQuizPhase.PREPARING,
                        MusicQuizPhase.PLAYING,
                        -> IconButton(onClick = { quizViewModel.pauseGame() }) {
                            Icon(
                                imageVector = Icons.Rounded.Pause,
                                contentDescription = stringResource(R.string.music_quiz_pause),
                            )
                        }
                        else -> Unit
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (uiState.phase) {
                MusicQuizPhase.IDLE -> MusicQuizIdleContent(
                    state = uiState,
                    onSelectMode = quizViewModel::setMode,
                    onStart = quizViewModel::startGame,
                    onRefreshLibrary = {
                        quizViewModel.refreshLibrary(quizSongs)
                        playerViewModel.sendToast(
                            context.getString(R.string.music_quiz_library_refreshed),
                        )
                    },
                )
                MusicQuizPhase.PREPARING -> MusicQuizPreparingContent()
                MusicQuizPhase.PLAYING,
                MusicQuizPhase.REVEALING,
                -> MusicQuizRoundContent(
                    state = uiState,
                    onAnswer = quizViewModel::answer,
                    onNext = quizViewModel::nextRound,
                )
                MusicQuizPhase.PAUSED -> MusicQuizPausedContent(
                    isEndless = uiState.isEndless,
                    onResume = quizViewModel::resumeGame,
                    onExit = quizViewModel::requestExit,
                )
                MusicQuizPhase.FINISHED -> MusicQuizResultContent(
                    state = uiState,
                    onSelectMode = quizViewModel::setMode,
                    onReplay = quizViewModel::startGame,
                    onBack = ::handleBack,
                    onTogglePreview = quizViewModel::togglePreview,
                    onAddToPlaylist = ::openPlaylistFlow,
                )
                MusicQuizPhase.ERROR -> MusicQuizErrorContent(
                    mode = uiState.mode,
                    onSelectMode = quizViewModel::setMode,
                    onRetry = quizViewModel::startGame,
                    onBack = ::handleBack,
                )
            }
        }
    }

    val summary = uiState.summary
    if (showSongSelectSheet && summary != null) {
        MusicQuizSongSelectSheet(
            tracks = summary.records.map { it.question.track },
            onConfirm = { selected ->
                showSongSelectSheet = false
                playlistSongs = selected.map { it.toPlaylistSong() }
                showPlaylistSheet = true
            },
            onDismiss = { showSongSelectSheet = false },
        )
    }

    if (showPlaylistSheet) {
        PlaylistBottomSheet(
            playlistUiState = playlistUiState,
            songs = playlistSongs.toImmutableList(),
            onDismiss = { showPlaylistSheet = false },
            bottomBarHeight = 0.dp,
            playerViewModel = playerViewModel,
            playlistViewModel = playlistViewModel,
            createDialogInitialName = pendingPlaylistName,
        )
    }

    if (uiState.showExitConfirmation) {
        AlertDialog(
            onDismissRequest = quizViewModel::dismissExitConfirmation,
            title = {
                Text(
                    stringResource(
                        if (uiState.isEndless) {
                            R.string.music_quiz_end_title
                        } else {
                            R.string.music_quiz_exit_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (uiState.isEndless) {
                            R.string.music_quiz_end_message
                        } else {
                            R.string.music_quiz_exit_message
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (uiState.isEndless) {
                            // Settle: keep the answered questions and show the results.
                            quizViewModel.endRunAndSettle()
                        } else {
                            quizViewModel.stopGame()
                            restoreMainPlayer()
                            onBack()
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (uiState.isEndless) {
                                R.string.music_quiz_end_run
                            } else {
                                R.string.music_quiz_exit
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        quizViewModel.dismissExitConfirmation()
                        quizViewModel.resumeGame()
                    },
                ) {
                    Text(stringResource(R.string.music_quiz_keep_playing))
                }
            },
        )
    }
}

@Composable
private fun MusicQuizIdleContent(
    state: MusicQuizUiState,
    onSelectMode: (MusicQuizMode) -> Unit,
    onStart: () -> Unit,
    onRefreshLibrary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = Icons.Rounded.Quiz,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.music_games_quiz_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                when (state.mode) {
                    MusicQuizMode.STANDARD -> R.string.music_quiz_rule_summary
                    MusicQuizMode.KIDS -> R.string.music_quiz_rule_summary_kids
                    MusicQuizMode.CLASSICAL -> R.string.music_quiz_rule_summary_classical
                    MusicQuizMode.ENDLESS -> R.string.music_quiz_rule_summary_endless
                    MusicQuizMode.BUILTIN_CLASSICAL -> R.string.music_quiz_rule_summary_builtin_classical
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MusicQuizStatCard(
                text = stringResource(R.string.music_quiz_best_score, state.stats.bestScore),
                modifier = Modifier.weight(1f),
            )
            MusicQuizStatCard(
                text = stringResource(R.string.music_quiz_best_streak, state.stats.bestStreak),
                modifier = Modifier.weight(1f),
            )
        }
        if (state.isEndless) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.music_quiz_best_rounds, state.bestRounds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        when {
            !state.hasLibrarySnapshot -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.music_quiz_loading_library))
            }
            state.isClassifying -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.music_quiz_classifying))
            }
            state.libraryIssue != null -> {
                Text(
                    text = when (state.libraryIssue) {
                        MusicQuizLibraryIssue.NOT_ENOUGH_SONGS -> stringResource(
                            R.string.music_quiz_not_enough_songs,
                            state.eligibleTrackCount,
                        )
                        MusicQuizLibraryIssue.NOT_ENOUGH_TITLES ->
                            stringResource(R.string.music_quiz_not_enough_titles)
                        MusicQuizLibraryIssue.NOT_ENOUGH_KIDS_SONGS ->
                            stringResource(R.string.music_quiz_not_enough_kids_songs)
                        MusicQuizLibraryIssue.NOT_ENOUGH_CLASSICAL_SONGS ->
                            stringResource(R.string.music_quiz_not_enough_classical_songs)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> {
                Text(
                    text = if (state.mode == MusicQuizMode.KIDS) {
                        stringResource(R.string.music_quiz_pool_count_kids, state.kidsTrackCount)
                    } else {
                        stringResource(R.string.music_quiz_pool_count, state.eligibleTrackCount)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onRefreshLibrary,
            enabled = !state.isClassifying,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.music_quiz_refresh_library))
        }
        Spacer(Modifier.height(16.dp))
        MusicQuizModeSelector(
            current = state.mode,
            onSelect = onSelectMode,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onStart,
            enabled = state.canStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.music_quiz_start))
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MusicQuizStatCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Full-width mode picker shown above the primary action; only reachable outside an active run. */
@Composable
private fun MusicQuizModeSelector(
    current: MusicQuizMode,
    onSelect: (MusicQuizMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    R.string.music_quiz_mode_current,
                    stringResource(musicQuizModeLabel(current)),
                ),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = stringResource(R.string.music_quiz_mode_cd),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MusicQuizMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == current, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(musicQuizModeLabel(mode)))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                )
            }
        }
    }
}

private fun musicQuizModeLabel(mode: MusicQuizMode): Int = when (mode) {
    MusicQuizMode.STANDARD -> R.string.music_quiz_mode_standard
    MusicQuizMode.KIDS -> R.string.music_quiz_mode_kids
    MusicQuizMode.CLASSICAL -> R.string.music_quiz_mode_classical
    MusicQuizMode.ENDLESS -> R.string.music_quiz_mode_endless
    MusicQuizMode.BUILTIN_CLASSICAL -> R.string.music_quiz_mode_builtin_classical
}

@Composable
private fun MusicQuizPreparingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.music_quiz_preparing),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MusicQuizRoundContent(
    state: MusicQuizUiState,
    onAnswer: (String) -> Unit,
    onNext: () -> Unit,
) {
    val question = state.currentQuestion ?: return
    val resolution = state.resolution
    val revealing = state.phase == MusicQuizPhase.REVEALING && resolution != null
    val scrollState = rememberScrollState()
    // Option titles may wrap to several lines; a round that was scrolled must start at the top.
    LaunchedEffect(question.track.id) { scrollState.scrollTo(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Score and streak on the left, the clip countdown back in the top-right corner. Hiding
        // the countdown on reveal only changes the row's width, so its height - and everything
        // below it - stays put.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.music_quiz_score, state.score),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.music_quiz_streak, state.currentStreak),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Fixed width and faded out on reveal instead of removed: taking it out of the row
            // would slide the streak label sideways the instant the answer appears.
            Box(
                modifier = Modifier
                    .width(MUSIC_QUIZ_TIMER_SLOT_WIDTH)
                    .alpha(if (revealing) 0f else 1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            R.string.music_quiz_time_remaining,
                            (state.remainingMs + 999L) / 1_000L,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Stays in the layout on reveal (remaining time is 0, so the track is simply empty): a
        // collapsing bar here would move the band and the candidate list by its own height.
        LinearProgressIndicator(
            progress = { (state.remainingMs.toFloat() / MUSIC_QUIZ_CLIP_DURATION_MS).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = if (state.remainingMs <= 3_000L) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        // The hero band keeps a fixed height in both states - the note while listening, the cover
        // plus the answer afterwards - so the candidate list always starts at the same y.
        MusicQuizRoundHero(
            title = if (question.questionType == MusicQuizQuestionType.COMPOSER) {
                question.track.artist
            } else {
                question.track.title
            },
            artist = if (question.questionType == MusicQuizQuestionType.COMPOSER) {
                ""
            } else {
                question.track.artist
            },
            coverUri = question.track.albumArtUriString,
            // Only a reveal sets a resolution, so a non-null record is the same signal as
            // `revealing` while also carrying the verdict.
            record = resolution?.record,
        )
        // Only the options take the remaining height and only they scroll, so the countdown stays
        // on screen even when a long title pushes the list. 8 dp between cards buys the margin
        // that keeps five three-line titles inside the viewport.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                question.options.forEach { option ->
                    MusicQuizAnswerButton(
                        option = option,
                        correctOptionId = question.track.id,
                        selectedOptionId = resolution?.record?.selectedOptionId,
                        revealing = revealing,
                        onClick = { onAnswer(option.songId) },
                    )
                }
            }
        }
        // The slot is reserved up front so revealing never shifts the options above it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MUSIC_QUIZ_NEXT_BUTTON_SLOT_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = revealing,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (!state.isEndless && state.roundIndex == state.totalRounds - 1) {
                                R.string.music_quiz_finish
                            } else {
                                R.string.music_quiz_next
                            },
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The round's hero band. While listening it is the placeholder note above the "which song is
 * this?" prompt; on reveal the same slot shows the cover, the verdict and the answer. Both states
 * occupy exactly [MUSIC_QUIZ_HERO_BLOCK_HEIGHT], which is what keeps the candidate list at a
 * constant offset. The countdown itself lives in the header row, not in here.
 */
@Composable
private fun MusicQuizRoundHero(
    title: String,
    artist: String,
    coverUri: String?,
    record: MusicQuizAnswerRecord?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MUSIC_QUIZ_HERO_BLOCK_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        if (record != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmartImage(
                    model = coverUri,
                    contentDescription = null,
                    modifier = Modifier.size(MUSIC_QUIZ_HERO_ART_SIZE),
                    shape = RoundedCornerShape(16.dp),
                    targetSize = SmartImageListTargetSize,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            when {
                                record.timedOut -> R.string.music_quiz_timeout
                                record.isCorrect -> R.string.music_quiz_correct
                                else -> R.string.music_quiz_wrong
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (record.isCorrect) {
                            CORRECT_COLOR
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    // A miss shows the verdict alone: the old "no points" line cost a row and
                    // told the player nothing they could not read off the score.
                    if (record.isCorrect) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.music_quiz_points, record.scoreAwarded),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                MusicQuizAnswerTitle(title)
                if (artist.isNotBlank()) {
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(MUSIC_QUIZ_HERO_ART_SIZE),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.music_quiz_listen),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Title of the revealed answer. It is always exactly one line tall — a long classical title scrolls
 * sideways instead of wrapping — so showing the answer cannot change the height of the hero block
 * and the candidate list below keeps its position. Titles that fit are simply centred.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicQuizAnswerTitle(text: String) {
    var overflowing by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        // The plain measuring pass is what decides whether a marquee is needed; inside the marquee
        // the text is measured with an unbounded width and would always report no overflow, so the
        // flag must not be rewritten once it has flipped.
        onTextLayout = { layout -> if (!overflowing) overflowing = layout.hasVisualOverflow },
        modifier = if (overflowing) {
            Modifier
                .fillMaxWidth()
                .basicMarquee(
                    initialDelayMillis = 600,
                    repeatDelayMillis = 1500,
                    velocity = 40.dp,
                )
        } else {
            Modifier
        },
    )
}

@Composable
private fun MusicQuizAnswerButton(
    option: MusicQuizOption,
    correctOptionId: String,
    selectedOptionId: String?,
    revealing: Boolean,
    onClick: () -> Unit,
) {
    val isCorrect = revealing && option.songId == correctOptionId
    val isWrongSelection = revealing && option.songId == selectedOptionId && !isCorrect
    val containerColor = when {
        isCorrect -> CORRECT_COLOR
        isWrongSelection -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        isCorrect -> Color.White
        isWrongSelection -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    // "Artist · year" subtitle; unknown parts (blank artist / year 0) are omitted,
    // and the whole line disappears when neither is known.
    val subtitle = buildString {
        if (option.artist.isNotBlank()) append(option.artist)
        if (option.year > 0) {
            if (isNotEmpty()) append(" · ")
            append(option.year)
        }
    }
    Button(
        onClick = onClick,
        enabled = !revealing,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
        shape = RoundedCornerShape(16.dp),
        // 8 dp vertical: a three-line option costs 98 dp instead of 102, which is what keeps five
        // of them on one screen below the hero band.
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor,
        ),
    ) {
        SmartImage(
            model = option.albumArtUriString,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(8.dp),
            targetSize = SmartImageListTargetSize,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // No line cap: classical titles (work, opus, movement) are routinely longer than one
            // line, and truncation can make two candidates look identical. Line height is tight
            // so a two-line option still fits the reveal layout. The body scrolls if needed.
            Text(
                text = option.title,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                textAlign = TextAlign.Start,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    // Follows the button's content color so the reveal states stay legible.
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
        }
        if (isCorrect || isWrongSelection) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (isCorrect) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun MusicQuizPausedContent(
    isEndless: Boolean,
    onResume: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Pause,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.music_quiz_paused_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.music_quiz_paused_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.music_quiz_resume))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (isEndless) R.string.music_quiz_end_run else R.string.music_quiz_exit,
                ),
            )
        }
    }
}

@Composable
private fun MusicQuizResultContent(
    state: MusicQuizUiState,
    onSelectMode: (MusicQuizMode) -> Unit,
    onReplay: () -> Unit,
    onBack: () -> Unit,
    onTogglePreview: (String) -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val summary = state.summary ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Icon(
                imageVector = Icons.Rounded.EmojiEvents,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    if (state.isEndless) {
                        R.string.music_quiz_result_title_endless
                    } else {
                        R.string.music_quiz_result_title
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (state.isNewBestScore) {
                Text(
                    text = stringResource(R.string.music_quiz_new_record),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (state.isNewBestStreak) {
                Text(
                    text = stringResource(R.string.music_quiz_new_streak_record),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(R.string.music_quiz_result_score, summary.score),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(
                    R.string.music_quiz_accuracy,
                    summary.correctCount,
                    summary.totalRounds,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.music_quiz_best_streak, summary.maxStreak),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (summary.correctCount > 0) {
                    stringResource(
                        R.string.music_quiz_average_time,
                        summary.averageCorrectAnswerMs / 1_000f,
                    )
                } else {
                    stringResource(R.string.music_quiz_average_time_empty)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.music_quiz_review),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(summary.records, key = { it.roundIndex }) { record ->
            MusicQuizReviewRow(
                record = record,
                isPreviewing = state.previewTrackId == record.question.track.id,
                previewPaused = state.previewPaused,
                onClick = { onTogglePreview(record.question.track.id) },
            )
        }
        item {
            MusicQuizModeSelector(
                current = state.mode,
                onSelect = onSelectMode,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onReplay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.music_quiz_replay))
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Home, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.music_quiz_back_to_hub))
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onAddToPlaylist, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.music_quiz_add_to_playlist))
            }
        }
    }
}

@Composable
private fun MusicQuizReviewRow(
    record: MusicQuizAnswerRecord,
    isPreviewing: Boolean,
    previewPaused: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${record.roundIndex + 1}",
                modifier = Modifier.width(28.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(contentAlignment = Alignment.Center) {
                SmartImage(
                    model = record.question.track.albumArtUriString,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    targetSize = SmartImageListTargetSize,
                )
                if (isPreviewing) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (previewPaused) {
                                Icons.Rounded.PlayArrow
                            } else {
                                Icons.Rounded.Pause
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.question.track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = record.question.track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    record.timedOut -> stringResource(R.string.music_quiz_timeout)
                    record.isCorrect -> stringResource(
                        R.string.music_quiz_review_correct,
                        record.scoreAwarded,
                        record.activeElapsedMs / 1_000f,
                    )
                    else -> stringResource(R.string.music_quiz_wrong)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (record.isCorrect) CORRECT_COLOR else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MusicQuizErrorContent(
    mode: MusicQuizMode,
    onSelectMode: (MusicQuizMode) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.music_quiz_playback_error),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        MusicQuizModeSelector(
            current = mode,
            onSelect = onSelectMode,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.music_quiz_retry))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.music_quiz_back_to_hub))
        }
    }
}

private val CORRECT_COLOR = Color(0xFF2E7D32)

/** Height of the reserved reveal-button slot on the round screen; keeps reveal from shifting options. */
private val MUSIC_QUIZ_NEXT_BUTTON_SLOT_HEIGHT = 48.dp

/** Width reserved for the countdown in the header row; wide enough for "10 s" in any locale. */
private val MUSIC_QUIZ_TIMER_SLOT_WIDTH = 64.dp

/**
 * Height of the round's hero band: the placeholder note with its prompt, or the revealed cover
 * plus its answer lines. Fixed on purpose so nothing above the candidate list ever changes height.
 * The taller state sets it: 64 art + 6 + 20 verdict + 28 title + 16 artist = 134 dp.
 */
private val MUSIC_QUIZ_HERO_BLOCK_HEIGHT = 136.dp

/**
 * Edge of the hero artwork — the placeholder note while listening, the album cover on reveal.
 * Sharing one size is what makes the two states read as the same slot.
 */
private val MUSIC_QUIZ_HERO_ART_SIZE = 64.dp

/** Minimal [Song] stand-in: playlist operations only consume the library id and labels. */
private fun MusicQuizTrack.toPlaylistSong(): Song =
    Song.emptySong().copy(id = id, title = title, artist = artist)
