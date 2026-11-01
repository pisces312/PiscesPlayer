/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (four-lane canvas, multi-touch, injected audio clock).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.offline.CloudOfflineRepository
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmAudioClock
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmPcmDecoder
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val RHYTHM_LANE_COUNT = 4

private val LaneBackground = Color(0xFF20202C)
private val NoteColor = Color(0xFF78C8FF)
private val NoteActiveColor = Color(0xFFFFDC78)
private val JudgeLineColor = Color.White
private val GameTextColor = Color(0xFFE6E6E6)
private val GameBackground = Color(0xFF121218)

/**
 * Four-lane gameplay screen. The shared player is paused on entry and resumed on
 * dispose only if it was playing; gameplay uses its own AudioTrack clock.
 */
@Composable
fun RhythmGameScreen(
    songId: String,
    chartId: String,
    playerViewModel: com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    // Application context: the repository outlives the Activity scope.
    val repository = remember { RhythmRepository(context.applicationContext) }

    var session by remember { mutableStateOf<RhythmPlaySession?>(null) }
    var audioClock by remember { mutableStateOf<RhythmAudioClock?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<RhythmSessionResult?>(null) }
    var paused by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(3) }
    var frameTick by remember { mutableLongStateOf(0L) }
    var runId by remember { mutableIntStateOf(0) }
    var shouldResumePlayer by remember { mutableStateOf(false) }

    val cloudUnavailableMessage = stringResource(R.string.rhythm_cloud_not_supported)
    val chartMissingMessage = stringResource(R.string.rhythm_chart_missing)
    val songMissingMessage = stringResource(R.string.rhythm_song_missing)

    // Pause normal playback for the whole gameplay screen, resume it on leave.
    LaunchedEffect(Unit) {
        val wasPlaying = playerViewModel.stablePlayerState.value.isPlaying
        shouldResumePlayer = wasPlaying
        if (wasPlaying) {
            playerViewModel.playPause()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (shouldResumePlayer) {
                playerViewModel.playPause()
            }
        }
    }

    // Load chart, decode audio, and build the injected-clock session.
    LaunchedEffect(songId, chartId) {
        withContext(Dispatchers.IO) {
            try {
                val chart = repository.loadChart(chartId)
                    ?: error(chartMissingMessage)
                val song = playerViewModel.observeSong(songId).first()
                    ?: error(songMissingMessage)
                if (CloudOfflineRepository.isCloudSong(song)) {
                    error(cloudUnavailableMessage)
                }
                val playbackUri = song.contentUriString.takeIf { it.isNotBlank() }?.let(Uri::parse)
                    ?: Uri.fromFile(File(song.path))
                val pcm = RhythmPcmDecoder.decode(context, playbackUri)
                val clock = RhythmAudioClock(pcm)
                audioClock = clock
                session = RhythmPlaySession(chart = chart, clock = clock)
            } catch (e: Exception) {
                errorMessage = e.message ?: e.javaClass.simpleName
            }
        }
    }

    // Portrait-only, keep the screen awake, release audio on any dispose path.
    val activity = context as? Activity
    val view = LocalView.current
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        view.keepScreenOn = true
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            view.keepScreenOn = false
            audioClock?.stop()
            audioClock?.release()
        }
    }

    // Background pause, including system back during gameplay.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                session?.pause()
                paused = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = result == null && session != null) {
        session?.pause()
        paused = true
    }

    // Game loop: countdown, then frame-driven advance. runId restarts the loop.
    LaunchedEffect(session, runId) {
        val currentSession = session ?: return@LaunchedEffect
        currentSession.clock.stop()
        countdown = 3
        repeat(3) {
            delay(1000)
            countdown -= 1
        }
        currentSession.restart()
        // A pause pressed during the countdown must keep the audio silent.
        if (paused) currentSession.pause()
        while (isActive) {
            withFrameNanos { }
            if (!paused) {
                val now = currentSession.clock.nowMs()
                currentSession.tick(now)
                frameTick += 1
                if (currentSession.isFinished(now)) {
                    val sessionResult = currentSession.settle()
                    result = sessionResult
                    withContext(Dispatchers.IO) {
                        repository.recordScore(chartId, sessionResult.score)
                    }
                    break
                }
            }
        }
    }

    val currentSession = session
    when {
        errorMessage != null -> RhythmMessageScreen(
            message = stringResource(R.string.rhythm_load_failed, errorMessage ?: ""),
            onExit = onExit,
        )
        currentSession == null -> RhythmMessageScreen(
            message = stringResource(R.string.rhythm_loading),
            onExit = onExit,
        )
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GameBackground),
            ) {
                RhythmHud(
                    session = currentSession,
                    frameTick = frameTick,
                    onTogglePause = {
                        if (paused) currentSession.resume() else currentSession.pause()
                        paused = !paused
                    },
                    onRestart = {
                        result = null
                        paused = false
                        runId += 1
                    },
                    onExit = {
                        currentSession.clock.stop()
                        audioClock?.release()
                        onExit()
                    },
                )
                RhythmGameCanvas(
                    session = currentSession,
                    frameTick = frameTick,
                    onLaneDown = { lane ->
                        if (!paused && result == null && countdown == 0) {
                            currentSession.keyDown(lane, currentSession.clock.nowMs())
                            frameTick += 1
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (countdown > 0 && result == null) {
                RhythmCenterOverlay {
                    Text(
                        text = countdown.toString(),
                        color = GameTextColor,
                        fontSize = 56.sp,
                    )
                }
            }

            if (paused && result == null) {
                RhythmPauseOverlay(
                    onResume = {
                        currentSession.resume()
                        paused = false
                    },
                    onRestart = {
                        paused = false
                        result = null
                        runId += 1
                    },
                    onExit = onExit,
                )
            }
        }
    }

    result?.let { sessionResult ->
        RhythmResultOverlay(
            result = sessionResult,
            onRetry = {
                result = null
                paused = false
                runId += 1
            },
            onExit = onExit,
        )
    }
}

@Composable
private fun RhythmHud(
    session: RhythmPlaySession,
    frameTick: Long,
    onTogglePause: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") frameTick
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${session.engine.score}  x${session.engine.combo}",
                color = GameTextColor,
                fontSize = 15.sp,
            )
            Text(
                text = stringResource(
                    R.string.rhythm_accuracy,
                    String.format(java.util.Locale.US, "%.2f", session.engine.accuracy * 100),
                ),
                color = GameTextColor,
                fontSize = 15.sp,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            TextButton(onClick = onTogglePause) {
                Text(stringResource(R.string.rhythm_pause), color = GameTextColor)
            }
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.rhythm_restart), color = GameTextColor)
            }
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.rhythm_exit), color = GameTextColor)
            }
        }
    }
}

@Composable
private fun RhythmGameCanvas(
    session: RhythmPlaySession,
    frameTick: Long,
    onLaneDown: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.pointerInput(session) {
            val laneWidthPx = size.width.toFloat() / RHYTHM_LANE_COUNT
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { change ->
                        if (change.pressed && !change.previousPressed) {
                            val lane = (change.position.x / laneWidthPx).toInt()
                                .coerceIn(0, RHYTHM_LANE_COUNT - 1)
                            onLaneDown(lane)
                        }
                    }
                }
            }
        },
    ) {
        @Suppress("UNUSED_EXPRESSION") frameTick
        val now = session.clock.nowMs()
        val laneWidth = size.width / RHYTHM_LANE_COUNT
        val judgeY = size.height * 0.82f
        val pxPerMs = (judgeY - 40f) / session.approachMs.toFloat()
        val goodWindow = session.config.goodMs

        for (lane in 0 until RHYTHM_LANE_COUNT) {
            drawRect(
                color = LaneBackground,
                topLeft = Offset(lane * laneWidth, 0f),
                size = Size(laneWidth, size.height),
            )
        }
        for (note in session.chart.notes) {
            if (!session.engine.isNotePending(note.id)) continue
            val delta = (note.timeMs - now).toFloat()
            if (delta < -goodWindow || delta > session.approachMs) continue
            val y = judgeY - delta * pxPerMs
            val color = if (kotlin.math.abs(delta) <= goodWindow) NoteActiveColor else NoteColor
            drawRect(
                color = color,
                topLeft = Offset(note.lane * laneWidth + 10f, y - 11f),
                size = Size(laneWidth - 20f, 22f),
            )
        }
        drawRect(
            color = JudgeLineColor,
            topLeft = Offset(0f, judgeY - 2f),
            size = Size(size.width, 4f),
        )
    }
}

@Composable
private fun RhythmCenterOverlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88121218)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun RhythmPauseOverlay(
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC121218)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_paused_title),
                color = GameTextColor,
                fontSize = 22.sp,
            )
            Button(onClick = onResume) {
                Text(stringResource(R.string.rhythm_resume))
            }
            OutlinedButton(onClick = onRestart) {
                Text(stringResource(R.string.rhythm_restart))
            }
            OutlinedButton(onClick = onExit) {
                Text(stringResource(R.string.rhythm_exit))
            }
        }
    }
}

@Composable
private fun RhythmResultOverlay(
    result: RhythmSessionResult,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC121218)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_result_title, result.difficultyName),
                color = GameTextColor,
                fontSize = 22.sp,
            )
            Text(
                text = stringResource(
                    R.string.rhythm_result_summary,
                    result.score,
                    String.format(java.util.Locale.US, "%.2f", result.accuracy * 100),
                ),
                color = GameTextColor,
            )
            Text(
                text = stringResource(R.string.rhythm_result_combo, result.maxCombo, result.noteCount),
                color = GameTextColor,
            )
            Text(
                text = stringResource(
                    R.string.rhythm_result_judgements,
                    result.counts[RhythmJudgement.PERFECT] ?: 0,
                    result.counts[RhythmJudgement.GREAT] ?: 0,
                    result.counts[RhythmJudgement.GOOD] ?: 0,
                    result.counts[RhythmJudgement.MISS] ?: 0,
                ),
                color = GameTextColor,
                fontSize = 13.sp,
            )
            Text(
                text = stringResource(
                    R.string.rhythm_result_timing,
                    String.format(java.util.Locale.US, "%.1f", result.meanErrorMs),
                    result.earlyCount,
                    result.lateCount,
                ),
                color = GameTextColor,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.rhythm_retry))
                }
                OutlinedButton(onClick = onExit) {
                    Text(stringResource(R.string.rhythm_exit))
                }
            }
        }
    }
}

@Composable
private fun RhythmMessageScreen(message: String, onExit: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GameBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, color = GameTextColor, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onExit) {
                Text(stringResource(R.string.rhythm_exit))
            }
        }
    }
}
