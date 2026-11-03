/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (four-lane gameplay screen: HUD, judgement feedback, pause and result overlays).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.offline.CloudOfflineRepository
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmAudioClock
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChartSettings
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmDriftKind
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmDriftRecorder
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmDriftReport
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmPcmDecoder
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val GAME_BACKGROUND = Color(0xFF0D0F17)
private val HUD_TEXT = Color(0xFFE9EDF7)
private val HUD_SECONDARY = Color(0xFF9DA6BA)
private val HUD_ACCENT = Color(0xFF78C8FF)
private val CARD_BACKGROUND = Color(0xFF1B202C)
private val DANGER_TEXT = Color(0xFFFF8A9B)

/**
 * Four-lane gameplay screen. The shared player is paused on entry and resumed on
 * dispose only if it was playing; gameplay uses its own AudioTrack clock.
 *
 * Judgements are produced by [RhythmPlaySession] (pure logic, injected clock) and rendered by
 * [RhythmPlayfield]; this file only wires them together and owns the run lifecycle.
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
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<RhythmPlaySession?>(null) }
    var audioClock by remember { mutableStateOf<RhythmAudioClock?>(null) }
    var chartSettings by remember { mutableStateOf(RhythmChartSettings.DEFAULT) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<RhythmSessionResult?>(null) }
    var paused by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(3) }
    var frameTick by remember { mutableLongStateOf(0L) }
    // Song time sampled once per frame by the loop; the playfield renders from this so notes,
    // judgement and feedback all agree on a single instant instead of each re-reading the clock.
    var songMs by remember { mutableDoubleStateOf(0.0) }
    var runId by remember { mutableIntStateOf(0) }
    var shouldResumePlayer by remember { mutableStateOf(false) }

    // Judgement feedback is one-shot: an event is shown, then aged out by the frame loop.
    var judgementFeedback by remember { mutableStateOf<RhythmTimedJudgement?>(null) }
    var hitEffects by remember { mutableStateOf<List<RhythmHitEffect>>(emptyList()) }
    var feedbackId by remember { mutableLongStateOf(0L) }
    var effectNowNanos by remember { mutableLongStateOf(System.nanoTime()) }
    val touchTracker = remember(session, runId) { RhythmLaneTouchTracker() }
    // Per-run clock diagnostics: the loop feeds it one sample per frame, the result screen shows
    // the auto-classified drift, and both pause and result screens can export the raw trace.
    val driftRecorder = remember(session, runId) { RhythmDriftRecorder() }
    var driftReport by remember { mutableStateOf<RhythmDriftReport?>(null) }

    val cloudUnavailableMessage = stringResource(R.string.rhythm_cloud_not_supported)
    val chartMissingMessage = stringResource(R.string.rhythm_chart_missing)
    val songMissingMessage = stringResource(R.string.rhythm_song_missing)

    fun presentJudgement(event: RhythmJudgementEvent?) {
        if (event == null || event.judgement == RhythmJudgement.EMPTY) return
        val nowNanos = System.nanoTime()
        feedbackId += 1
        judgementFeedback = RhythmTimedJudgement(feedbackId, event, nowNanos)
        if (event.judgement != RhythmJudgement.MISS) {
            hitEffects = hitEffects + RhythmHitEffect(feedbackId, event.lane, event.judgement, nowNanos)
        }
    }

    /** Offset tweak from the pause screen: applied live and persisted to the chart sidecar. */
    fun adjustDelay(deltaMs: Int) {
        val currentSession = session ?: return
        val next = (currentSession.clock.offsetMs + deltaMs)
            .coerceIn(
                RhythmChartSettings.MIN_DELAY_MS.toDouble(),
                RhythmChartSettings.MAX_DELAY_MS.toDouble(),
            )
        currentSession.clock.offsetMs = next
        val updated = chartSettings.copy(
            delayMs = next.roundToInt(),
            updatedAtMs = System.currentTimeMillis(),
        )
        chartSettings = updated
        scope.launch { repository.saveSettings(chartId, updated) }
        frameTick += 1
    }

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
                // Per-chart playback tweaks: latency compensation on the clock, fall time on the
                // session. Both must be read before the clock starts so the first note is right.
                val settings = repository.loadSettings(chartId)
                chartSettings = settings
                val clock = RhythmAudioClock(pcm, offsetMs = settings.delayMs.toDouble())
                audioClock = clock
                session = RhythmPlaySession(
                    chart = chart,
                    clock = clock,
                    approachMs = settings.approachMs.toDouble(),
                )
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
                touchTracker.clear()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = result == null && session != null) {
        session?.pause()
        paused = true
        touchTracker.clear()
    }

    // Game loop: countdown, then frame-driven advance. runId restarts the loop.
    LaunchedEffect(session, runId) {
        val currentSession = session ?: return@LaunchedEffect
        judgementFeedback = null
        hitEffects = emptyList()
        touchTracker.clear()
        driftReport = null
        currentSession.clock.stop()
        countdown = 3
        repeat(3) {
            delay(1000)
            countdown -= 1
        }
        currentSession.restart()
        // A pause pressed during the countdown must keep the audio silent.
        if (paused) currentSession.pause()
        driftRecorder.start(System.nanoTime())
        songMs = currentSession.clock.nowMs()
        while (isActive) {
            withFrameNanos { }
            val animationNow = System.nanoTime()
            effectNowNanos = animationNow
            judgementFeedback?.let {
                if (animationNow - it.startedAtNanos > RHYTHM_JUDGEMENT_FEEDBACK_MS * 1_000_000L) {
                    judgementFeedback = null
                }
            }
            if (hitEffects.isNotEmpty()) {
                hitEffects = hitEffects.filter {
                    animationNow - it.startedAtNanos <= RHYTHM_HIT_EFFECT_MS * 1_000_000L
                }
            }
            if (!paused) {
                val now = currentSession.clock.nowMs()
                songMs = now
                audioClock?.let { driftRecorder.recordFrame(it.diagnosticsSample(), animationNow) }
                currentSession.tick(now).forEach(::presentJudgement)
                frameTick += 1
                if (currentSession.isFinished(now)) {
                    val sessionResult = currentSession.settle()
                    result = sessionResult
                    driftReport = driftRecorder.analyze(currentSession.chart.notes)
                    withContext(Dispatchers.IO) {
                        repository.recordScore(chartId, sessionResult.score)
                    }
                    break
                }
            }
        }
    }

    fun exitGame() {
        session?.clock?.stop()
        audioClock?.release()
        onExit()
    }

    /** Write this run's clock trace + classification to cache and open the share sheet. */
    fun exportDiagnostics() {
        val currentSession = session ?: return
        val notes = currentSession.chart.notes
        if (driftReport == null) driftReport = driftRecorder.analyze(notes)
        val meta = buildString {
            appendLine("capturedAt=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("songId=$songId")
            appendLine("chartId=$chartId")
            appendLine("difficulty=${currentSession.chart.difficultyName}")
            appendLine("durationMs=${currentSession.clock.durationMs}")
            appendLine("offsetMs=${currentSession.clock.offsetMs}")
            appendLine("approachMs=${currentSession.approachMs}")
        }
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "rhythm_diagnostics").apply { mkdirs() }
                    File(dir, "rhythm-drift-${System.currentTimeMillis()}.txt")
                        .apply { writeText(driftRecorder.exportText(meta, notes)) }
                }.getOrNull()
            }
            if (file == null) {
                Toast.makeText(context, R.string.rhythm_diagnostics_export_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Rhythm clock diagnostics")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, null)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }

    val currentSession = session
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GAME_BACKGROUND)
            .safeDrawingPadding(),
    ) {
        when {
            errorMessage != null -> RhythmMessageScreen(
                message = stringResource(R.string.rhythm_load_failed, errorMessage ?: ""),
                onExit = ::exitGame,
            )

            currentSession == null -> RhythmMessageScreen(
                message = stringResource(R.string.rhythm_loading),
                onExit = ::exitGame,
            )

            else -> Column(modifier = Modifier.fillMaxSize()) {
                RhythmHud(
                    session = currentSession,
                    paused = paused,
                    frameTick = frameTick,
                    onTogglePause = {
                        if (paused) {
                            currentSession.resume()
                        } else {
                            currentSession.pause()
                        }
                        paused = !paused
                        touchTracker.clear()
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    RhythmPlayfield(
                        session = currentSession,
                        songMs = songMs,
                        frameTick = frameTick,
                        touchTracker = touchTracker,
                        hitEffects = hitEffects,
                        effectNowNanos = effectNowNanos,
                        inputEnabled = !paused && result == null && countdown == 0,
                        onLaneDown = { lane ->
                            if (!paused && result == null && countdown == 0) {
                                presentJudgement(
                                    currentSession.keyDown(lane, currentSession.clock.nowMs()),
                                )
                                frameTick += 1
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    RhythmFeedbackOverlay(
                        combo = currentSession.engine.combo,
                        feedback = judgementFeedback,
                        nowNanos = effectNowNanos,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (currentSession != null && countdown > 0 && result == null && !paused) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = countdown.toString(),
                    color = HUD_TEXT,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        if (currentSession != null && paused && result == null) {
            RhythmPauseOverlay(
                offsetMs = currentSession.clock.offsetMs,
                onResume = {
                    currentSession.resume()
                    paused = false
                    touchTracker.clear()
                },
                onOffset = ::adjustDelay,
                onRestart = {
                    result = null
                    paused = false
                    runId += 1
                },
                onExit = ::exitGame,
                onExportDiagnostics = ::exportDiagnostics,
            )
        }

        result?.let { sessionResult ->
            RhythmResultOverlay(
                result = sessionResult,
                driftReport = driftReport,
                onRetry = {
                    result = null
                    paused = false
                    runId += 1
                },
                onExit = ::exitGame,
                onExportDiagnostics = ::exportDiagnostics,
            )
        }
    }
}

/** Score, difficulty, accuracy and a thin progress bar. No transport controls: pausing is it. */
@Composable
private fun RhythmHud(
    session: RhythmPlaySession,
    paused: Boolean,
    frameTick: Long,
    onTogglePause: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") frameTick
    val engine = session.engine
    val progress = if (session.clock.durationMs > 0.0) {
        (session.clock.nowMs() / session.clock.durationMs).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = String.format(Locale.US, "%,d", engine.score),
                    color = HUD_TEXT,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = session.chart.difficultyName.uppercase(),
                    color = HUD_SECONDARY,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                )
            }
            Text(
                text = stringResource(
                    R.string.rhythm_accuracy,
                    String.format(Locale.US, "%.2f", engine.accuracy * 100),
                ),
                color = HUD_TEXT,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onTogglePause) {
                Text(
                    text = stringResource(
                        if (paused) R.string.rhythm_resume else R.string.rhythm_pause,
                    ),
                    color = HUD_ACCENT,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0xFF303748)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(HUD_ACCENT),
            )
        }
    }
}

/**
 * Pause screen. The device offset is adjustable here on purpose: it is the one setting a player
 * notices mid-song, and stopping to open the chart settings sheet would lose the run.
 */
@Composable
private fun RhythmPauseOverlay(
    offsetMs: Double,
    onResume: () -> Unit,
    onOffset: (Int) -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE60D0F17)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            shape = RoundedCornerShape(20.dp),
            color = CARD_BACKGROUND,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.rhythm_paused_title),
                    color = HUD_TEXT,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.rhythm_pause_hint),
                    color = HUD_SECONDARY,
                    fontSize = 13.sp,
                )
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rhythm_resume))
                }
                Text(
                    text = stringResource(R.string.rhythm_offset_label, offsetMs.roundToInt()),
                    color = HUD_TEXT,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onOffset(-10) }) { Text("-10 ms") }
                    OutlinedButton(onClick = { onOffset(10) }) { Text("+10 ms") }
                }
                TextButton(onClick = onExportDiagnostics) {
                    Text(stringResource(R.string.rhythm_export_diagnostics), color = HUD_SECONDARY)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onRestart) {
                        Text(stringResource(R.string.rhythm_restart), color = HUD_TEXT)
                    }
                    TextButton(onClick = onExit) {
                        Text(stringResource(R.string.rhythm_exit), color = DANGER_TEXT)
                    }
                }
            }
        }
    }
}

@Composable
private fun driftSummary(report: RhythmDriftReport): String = when (report.kind) {
    RhythmDriftKind.STABLE -> stringResource(R.string.rhythm_drift_stable)
    RhythmDriftKind.PROGRESSIVE_OVER_RUN ->
        stringResource(R.string.rhythm_drift_over, abs(report.endResidualMs), abs(report.driftPpm))
    RhythmDriftKind.PROGRESSIVE_UNDER_RUN ->
        stringResource(R.string.rhythm_drift_under, abs(report.endResidualMs), abs(report.driftPpm))
    RhythmDriftKind.STEP -> stringResource(R.string.rhythm_drift_step, report.largestStepMs)
    RhythmDriftKind.SAWTOOTH -> stringResource(R.string.rhythm_drift_sawtooth, report.maxAbsResidualMs)
    RhythmDriftKind.INSUFFICIENT_DATA -> stringResource(R.string.rhythm_drift_insufficient)
}

@Composable
private fun RhythmResultOverlay(
    result: RhythmSessionResult,
    driftReport: RhythmDriftReport?,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF20D0F17)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            color = CARD_BACKGROUND,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = stringResource(R.string.rhythm_result_title, result.difficultyName),
                    color = HUD_ACCENT,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = String.format(Locale.US, "%,d", result.score),
                    color = HUD_TEXT,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = stringResource(
                        R.string.rhythm_accuracy,
                        String.format(Locale.US, "%.2f", result.accuracy * 100),
                    ),
                    color = HUD_TEXT,
                    fontSize = 20.sp,
                )
                Text(
                    text = stringResource(R.string.rhythm_result_combo, result.maxCombo, result.noteCount),
                    color = HUD_SECONDARY,
                )
                Text(
                    text = stringResource(
                        R.string.rhythm_result_judgements,
                        result.counts[RhythmJudgement.PERFECT] ?: 0,
                        result.counts[RhythmJudgement.GREAT] ?: 0,
                        result.counts[RhythmJudgement.GOOD] ?: 0,
                        result.counts[RhythmJudgement.MISS] ?: 0,
                    ),
                    color = HUD_TEXT,
                    fontSize = 13.sp,
                )
                Text(
                    text = stringResource(
                        R.string.rhythm_result_timing,
                        String.format(Locale.US, "%.1f", result.meanErrorMs),
                        result.earlyCount,
                        result.lateCount,
                    ),
                    color = HUD_SECONDARY,
                    fontSize = 13.sp,
                )
                driftReport?.let { report ->
                    Text(
                        text = driftSummary(report),
                        color = if (report.kind == RhythmDriftKind.STABLE) HUD_SECONDARY else DANGER_TEXT,
                        fontSize = 13.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.rhythm_retry))
                    }
                    OutlinedButton(onClick = onExit) {
                        Text(stringResource(R.string.rhythm_exit))
                    }
                }
                TextButton(onClick = onExportDiagnostics) {
                    Text(stringResource(R.string.rhythm_export_diagnostics), color = HUD_SECONDARY)
                }
            }
        }
    }
}

@Composable
private fun RhythmMessageScreen(message: String, onExit: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(GAME_BACKGROUND),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, color = HUD_TEXT, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onExit) {
                Text(stringResource(R.string.rhythm_exit))
            }
        }
    }
}
