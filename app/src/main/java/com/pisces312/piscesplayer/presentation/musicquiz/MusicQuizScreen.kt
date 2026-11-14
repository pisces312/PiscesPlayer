package com.pisces312.piscesplayer.presentation.musicquiz

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizAnswerRecord
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizOption
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizShareText
import com.pisces312.piscesplayer.data.musicquiz.MusicQuizTrack
import com.pisces312.piscesplayer.presentation.components.PlaylistBottomSheet
import com.pisces312.piscesplayer.presentation.components.SmartImage
import com.pisces312.piscesplayer.presentation.components.SmartImageListTargetSize
import com.pisces312.piscesplayer.presentation.components.rememberModalSheetState
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.PlaylistViewModel
import java.util.Date
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicQuizScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    quizViewModel: MusicQuizViewModel = hiltViewModel(),
) {
    val allSongs by playerViewModel.allSongsFlow.collectAsStateWithLifecycle()
    val uiState by quizViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var shouldResumeMainPlayer by rememberSaveable { mutableStateOf(false) }
    var mainPlayerCaptured by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shareGraphicsLayer = rememberGraphicsLayer()
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    var showShareSheet by remember { mutableStateOf(false) }
    var showSongSelectSheet by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    var playlistSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var pendingPlaylistName by remember { mutableStateOf("") }

    LaunchedEffect(allSongs) {
        quizViewModel.updateLibrary(allSongs)
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

    fun shareResultImage() {
        val summary = uiState.summary ?: return
        scope.launch {
            val text = MusicQuizShare.buildText(context, summary, uiState.isNewBestScore)
            val png = MusicQuizShare.capturePng(context, shareGraphicsLayer)
            if (png == null) {
                playerViewModel.sendToast(context.getString(R.string.music_quiz_share_failed))
            } else {
                MusicQuizShare.shareImage(context, png, text)
            }
        }
    }

    fun copyResultText() {
        val summary = uiState.summary ?: return
        MusicQuizShare.copyText(
            context,
            MusicQuizShare.buildText(context, summary, uiState.isNewBestScore),
        )
        playerViewModel.sendToast(context.getString(R.string.music_quiz_copied))
    }

    fun openPlaylistFlow() {
        pendingPlaylistName = context.getString(
            R.string.music_quiz_playlist_default_name,
            MusicQuizShareText.timestampSuffix(Date()),
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
                            stringResource(
                                R.string.music_quiz_round,
                                uiState.roundIndex + 1,
                                uiState.totalRounds,
                            )
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
                    if (uiState.phase == MusicQuizPhase.PREPARING ||
                        uiState.phase == MusicQuizPhase.PLAYING
                    ) {
                        IconButton(onClick = { quizViewModel.pauseGame() }) {
                            Icon(
                                imageVector = Icons.Rounded.Pause,
                                contentDescription = stringResource(R.string.music_quiz_pause),
                            )
                        }
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
                    onStart = quizViewModel::startGame,
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
                    onResume = quizViewModel::resumeGame,
                    onExit = quizViewModel::requestExit,
                )
                MusicQuizPhase.FINISHED -> MusicQuizResultContent(
                    state = uiState,
                    onReplay = quizViewModel::startGame,
                    onBack = ::handleBack,
                    onTogglePreview = quizViewModel::togglePreview,
                    onAddToPlaylist = ::openPlaylistFlow,
                    onShare = { showShareSheet = true },
                )
                MusicQuizPhase.ERROR -> MusicQuizErrorContent(
                    onRetry = quizViewModel::startGame,
                    onBack = ::handleBack,
                )
            }

            if (uiState.phase == MusicQuizPhase.FINISHED) {
                uiState.summary?.let { summary ->
                    // Composed and drawn with alpha 0: the share card stays recordable for
                    // screenshot sharing without ever being visible on screen.
                    Box(modifier = Modifier.graphicsLayer { alpha = 0f }) {
                        MusicQuizShareCard(
                            summary = summary,
                            isNewBestScore = uiState.isNewBestScore,
                            modifier = Modifier.drawWithContent {
                                shareGraphicsLayer.record(
                                    density = this,
                                    layoutDirection = layoutDirection,
                                    size = IntSize(size.width.toInt(), size.height.toInt()),
                                ) {
                                    this@drawWithContent.drawContent()
                                }
                                drawContent()
                            },
                        )
                    }
                }
            }
        }
    }

    if (showShareSheet) {
        MusicQuizShareSheet(
            onShareImage = {
                showShareSheet = false
                shareResultImage()
            },
            onCopyText = {
                showShareSheet = false
                copyResultText()
            },
            onDismiss = { showShareSheet = false },
        )
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
            title = { Text(stringResource(R.string.music_quiz_exit_title)) },
            text = { Text(stringResource(R.string.music_quiz_exit_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        quizViewModel.stopGame()
                        restoreMainPlayer()
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.music_quiz_exit))
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
    onStart: () -> Unit,
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
            text = stringResource(R.string.music_quiz_rule_summary),
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
        Spacer(Modifier.height(24.dp))
        when {
            !state.hasLibrarySnapshot -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.music_quiz_loading_library))
            }
            state.libraryIssue != null -> {
                Text(
                    text = if (state.libraryIssue == MusicQuizLibraryIssue.NOT_ENOUGH_SONGS) {
                        stringResource(
                            R.string.music_quiz_not_enough_songs,
                            state.eligibleTrackCount,
                        )
                    } else {
                        stringResource(R.string.music_quiz_not_enough_titles)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
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
        }
        if (!revealing) {
            val progress = (state.remainingMs / 10_000f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (state.remainingMs <= 3_000L) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(
                        R.string.music_quiz_time_remaining,
                        (state.remainingMs + 999L) / 1_000L,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (revealing) {
                val record = resolution.record
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SmartImage(
                        model = question.track.albumArtUriString,
                        contentDescription = null,
                        modifier = Modifier.size(88.dp),
                        shape = RoundedCornerShape(20.dp),
                        targetSize = SmartImageListTargetSize,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            when {
                                record.timedOut -> R.string.music_quiz_timeout
                                record.isCorrect -> R.string.music_quiz_correct
                                else -> R.string.music_quiz_wrong
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (record.isCorrect) CORRECT_COLOR else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = question.track.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = question.track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (record.isCorrect) {
                            stringResource(R.string.music_quiz_points, record.scoreAwarded)
                        } else {
                            stringResource(R.string.music_quiz_no_points)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.music_quiz_listen),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.music_quiz_clip_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        question.options.forEach { option ->
            MusicQuizAnswerButton(
                option = option,
                correctOptionId = question.track.id,
                selectedOptionId = resolution?.record?.selectedOptionId,
                revealing = revealing,
                onClick = { onAnswer(option.songId) },
            )
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
                            if (state.roundIndex == state.totalRounds - 1) {
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
    Button(
        onClick = onClick,
        enabled = !revealing,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor,
        ),
    ) {
        Text(
            text = option.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
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
            Text(stringResource(R.string.music_quiz_exit))
        }
    }
}

@Composable
private fun MusicQuizResultContent(
    state: MusicQuizUiState,
    onReplay: () -> Unit,
    onBack: () -> Unit,
    onTogglePreview: (String) -> Unit,
    onAddToPlaylist: () -> Unit,
    onShare: () -> Unit,
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
                text = stringResource(R.string.music_quiz_result_title),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onAddToPlaylist, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.music_quiz_add_to_playlist))
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.music_quiz_share))
                }
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

/** Minimal [Song] stand-in: playlist operations only consume the library id and labels. */
private fun MusicQuizTrack.toPlaylistSong(): Song =
    Song.emptySong().copy(id = id, title = title, artist = artist)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicQuizShareSheet(
    onShareImage: () -> Unit,
    onCopyText: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { true },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { BottomSheetDefaults.modalWindowInsets },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.music_quiz_share_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            MusicQuizShareOption(
                icon = Icons.Rounded.Share,
                title = stringResource(R.string.music_quiz_share_image),
                description = stringResource(R.string.music_quiz_share_image_desc),
                onClick = onShareImage,
            )
            MusicQuizShareOption(
                icon = Icons.Rounded.ContentCopy,
                title = stringResource(R.string.music_quiz_copy_text),
                description = stringResource(R.string.music_quiz_copy_text_desc),
                onClick = onCopyText,
            )
        }
    }
}

@Composable
private fun MusicQuizShareOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
