/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (four-lane gameplay screen: HUD, judgement feedback, pause and result overlays).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.offline.CloudOfflineRepository
import com.pisces312.piscesplayer.data.rhythm.RhythmAudioClock
import com.pisces312.piscesplayer.data.rhythm.RhythmChartSettings
import com.pisces312.piscesplayer.data.rhythm.RhythmDriftKind
import com.pisces312.piscesplayer.data.rhythm.RhythmDriftRecorder
import com.pisces312.piscesplayer.data.rhythm.RhythmDriftReport
import com.pisces312.piscesplayer.data.rhythm.RhythmLatencyStore
import com.pisces312.piscesplayer.data.rhythm.RhythmPcmAudio
import com.pisces312.piscesplayer.data.rhythm.RhythmPcmDecoder
import com.pisces312.piscesplayer.data.rhythm.RhythmRepository
import com.pisces312.piscesplayer.data.rhythm.delayAfterTap
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

/** m:ss.d at 100ms granularity, monospace-width so the HUD time does not wiggle. */
private fun formatHudClock(ms: Double): String {
    val totalSec = (ms / 1000.0).coerceAtLeast(0.0)
    val minutes = (totalSec / 60).toInt()
    return String.format(Locale.US, "%d:%04.1f", minutes, totalSec - minutes * 60)
}

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
    playerViewModel: com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    // Application context: the repository outlives the Activity scope.
    val repository = remember { RhythmRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<RhythmPlaySession?>(null) }
    var audioClock by remember { mutableStateOf<RhythmAudioClock?>(null) }
    // Held so leaving the screen can cancel a still-running MIDI render instead of burning
    // CPU synthesizing a song nobody is listening to any more.
    var pcmStream by remember { mutableStateOf<RhythmPcmAudio?>(null) }
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
    // Non-null while the pause-screen log viewer dialog is open; holds the full diagnostics text.
    var diagnosticsLogText by remember { mutableStateOf<String?>(null) }

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

    /**
     * Offset tweak from the pause screen: applied live and persisted as this chart's override
     * (a chart override beats the route calibration; mid-run tweaks are chart-scoped).
     */
    fun adjustDelay(deltaMs: Int) {
        val currentSession = session ?: return
        val next = delayAfterTap(currentSession.clock.offsetMs.roundToInt(), deltaMs)
        currentSession.clock.offsetMs = next.toDouble()
        val updated = chartSettings.copy(
            delayOverrideMs = next,
            updatedAtMs = System.currentTimeMillis(),
        )
        chartSettings = updated
        scope.launch { repository.saveSettings(chartId, updated) }
        // The paused game loop does not sample the clock; refresh the frame-stable render time
        // explicitly so the playfield reflects the new offset before playback resumes.
        songMs = currentSession.clock.nowMs()
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

    // Load chart, decode audio, and build the injected-clock session. Charts always play as
    // stored: the play-time A/B re-bake was retired after the timing-estimator experiments
    // concluded (see RhythmChartLab docs/design/rhythm-play-logic-retirement.md).
    //
    // decodeStreaming is what keeps the entry fast for MIDI: the synthesis runs on a
    // background thread and this waits only for the play runway (seconds of PCM), while the
    // clock keeps draining the buffer as the render fills it. Without it the screen blocked
    // on a full-song software render before the first note could even fall.
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
                val pcm = RhythmPcmDecoder.decodeStreaming(context, playbackUri, song.mimeType)
                pcmStream = pcm
                // Per-chart playback tweaks: fall time and lead-in on the session; the latency
                // offset is this chart's override when set, otherwise the current route's
                // calibrated value (both must be read before the clock starts so the first
                // note is right).
                val settings = repository.loadSettings(chartId)
                chartSettings = settings
                val routeOffsetMs = RhythmLatencyStore.offsetMs(context)
                val effectiveOffset = settings.delayOverrideMs ?: routeOffsetMs
                val clock = RhythmAudioClock(pcm, offsetMs = effectiveOffset.toDouble())
                audioClock = clock
                session = RhythmPlaySession(
                    chart = chart,
                    clock = clock,
                    approachMs = settings.approachMs.toDouble(),
                    leadInMs = settings.leadInMs.toDouble(),
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
            pcmStream?.fill?.cancel()
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
        // Baseline the render time at the stopped clock (including the calibrated offset) so
        // the countdown freeze and the first post-restart frame agree; otherwise notes jump by
        // offsetMs when the loop starts sampling after the countdown.
        songMs = currentSession.clock.nowMs()
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
                // The clock runs continuously from the play anchor and its output is monotonic
                // (see RhythmFrameEstimator), so every sampled frame is directly usable — there
                // is no startup sync state to gate on.
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

    /**
     * This run's full diagnostics text (startup trace + classification + trace + crossings +
     * taps). Shared by the export (writes the same text to a file) and the in-app log viewer.
     */
    fun buildDiagnosticsText(): String {
        val currentSession = session ?: return ""
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
            // What really happened at the start: park release, anchor sources, grace expiry,
            // per-frame clock reads. Lets a real-device run be checked against the intended
            // startup contract (see RhythmStartupTrace).
            append(audioClock?.startupTraceText().orEmpty())
        }
        return driftRecorder.exportText(meta, notes)
    }

    /** Write this run's clock trace + classification to cache and open the share sheet. */
    fun exportDiagnostics() {
        val text = buildDiagnosticsText()
        if (text.isEmpty()) return
        val notes = session?.chart?.notes ?: return
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "rhythm_diagnostics").apply { mkdirs() }
                    File(dir, "rhythm-drift-${System.currentTimeMillis()}.txt")
                        .apply { writeText(text) }
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
                    songMs = songMs,
                    leadInMs = chartSettings.leadInMs,
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
                                val tapTimeMs = currentSession.clock.nowMs()
                                val event = currentSession.keyDown(lane, tapTimeMs)
                                presentJudgement(event)
                                // Same wall base as the trace, so a tap row lines up with the
                                // 50ms samples; one list append per tap, no per-frame cost.
                                driftRecorder.recordTap(lane, tapTimeMs, event.noteId, event.errorMs, System.nanoTime())
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
                onViewDiagnostics = {
                    scope.launch {
                        val text = withContext(Dispatchers.Default) { buildDiagnosticsText() }
                        if (text.isNotEmpty()) diagnosticsLogText = text
                    }
                },
            )
        }

        diagnosticsLogText?.let { logText ->
            RhythmDiagnosticsLogDialog(
                text = logText,
                onDismiss = { diagnosticsLogText = null },
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

/**
 * Score, difficulty, accuracy and a thin progress bar. No transport controls: pausing is it.
 * Below the score row the HUD always shows the two play-time settings that decide whether the
 * notes meet the music: the effective latency offset (chart override or route calibration,
 * live-tweakable on the pause screen) and the lead-in mask.
 */
@Composable
private fun RhythmHud(
    session: RhythmPlaySession,
    songMs: Double,
    leadInMs: Int,
    paused: Boolean,
    frameTick: Long,
    onTogglePause: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") frameTick
    val engine = session.engine
    val progress = if (session.clock.durationMs > 0.0) {
        (songMs / session.clock.durationMs).toFloat().coerceIn(0f, 1f)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.rhythm_hud_status,
                    session.clock.offsetMs.roundToInt(),
                    leadInMs,
                ),
                color = HUD_SECONDARY,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            // Current song time at 100ms granularity: pinpoints a felt latency to a spot in
            // the exported trace without waiting for the result screen.
            Text(
                text = formatHudClock(songMs) + " / " + formatHudClock(session.clock.durationMs),
                color = HUD_TEXT,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
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
    onViewDiagnostics: () -> Unit,
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onViewDiagnostics) {
                        Text(stringResource(R.string.rhythm_view_diagnostics), color = HUD_SECONDARY)
                    }
                    TextButton(onClick = onExportDiagnostics) {
                        Text(stringResource(R.string.rhythm_export_diagnostics), color = HUD_SECONDARY)
                    }
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

/**
 * In-app diagnostics viewer: the same text the export writes, scrollable and selectable.
 * Long-press selects a passage (with copy in the selection toolbar); "Copy all" puts the
 * whole log on the clipboard. LazyColumn keeps multi-thousand-line logs from laying out
 * in one frame; selection works within the composed (visible) region.
 */
@Composable
private fun RhythmDiagnosticsLogDialog(
    text: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.rhythm_copied)
    val dialogScope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
            shape = RoundedCornerShape(20.dp),
            color = CARD_BACKGROUND,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.rhythm_diagnostics_log_title),
                        color = HUD_TEXT,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        dialogScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("rhythm diagnostics", text)))
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text(stringResource(R.string.rhythm_copy_all), color = HUD_ACCENT)
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.rhythm_close), color = HUD_SECONDARY)
                    }
                }
                SelectionContainer {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(text.lines()) { line ->
                            Text(
                                text = line,
                                color = HUD_TEXT,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
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
