/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (RhythmChartLab docs/design/rhythm-latency-calibration-ui-design.md).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.rhythm.CalibrationStats
import com.pisces312.piscesplayer.data.rhythm.CalibrationTickSample
import com.pisces312.piscesplayer.data.rhythm.RhythmAudioClock
import com.pisces312.piscesplayer.data.rhythm.RhythmCalibrationTrack
import com.pisces312.piscesplayer.data.rhythm.RhythmLatencyStore
import com.pisces312.piscesplayer.data.rhythm.RhythmUiPreferences
import com.pisces312.piscesplayer.data.rhythm.analyzeCalibration
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val CAL_BACKGROUND = Color(0xFF0D0F17)
private val CAL_SURFACE = Color(0xFF1B202C)
private val CAL_TEXT = Color(0xFFE9EDF7)
private val CAL_MUTED = Color(0xFF9DA6BA)
private val CAL_ACCENT = Color(0xFF78C8FF)
private val CAL_ON_ACCENT = Color(0xFF062333)
private val CAL_GOLD = Color(0xFFFFD24B)
private val CAL_WARN = Color(0xFFFF8A9B)

private enum class Phase { READY, LEAD_IN, MEASURE, RESULT, ERROR }

/** Tail after the last tick's wall crossing before the run is analysed. */
private const val END_GRACE_NS = 1_200_000_000L

/**
 * A gap between the event's own time and the handler time larger than this means the two clock
 * bases disagree; the handler time is then the safer reference.
 */
private const val MAX_CLOCK_SKEW_NS = 60_000_000L

/** Taps that start inside the top bar (the back arrow) are never beats. */
private val TOP_BAR_GUARD = 100.dp

private const val PREVIEW_MS = 2_700L

/**
 * Latency calibration, shaped like a rhythm test rather than a measurement rig: listen to four
 * beats, tap along with eight, read the result.
 *
 * Why it is built this way (design doc §3):
 * - the metronome plays through the CURRENT audio route, so a Bluetooth link contributes its
 *   full, otherwise unobservable latency;
 * - the four lead-in beats are audible but never measured: they warm the link and give the
 *   player time to lock the tempo (the old "do not tap the first 2 ticks" rule is gone);
 * - during the measured beats there is deliberately NO beat-synced visual and NO live
 *   good/bad rating: a visual pulse would lead the audio by exactly the latency being measured,
 *   and a live rating would turn the run into a feedback loop that pins the median at whatever
 *   the first few taps guessed;
 * - taps are timestamped at the DOWN event's own time, so finger dwell time (40–90 ms, always in
 *   the same direction) is not counted as device latency.
 */
@Composable
internal fun RhythmCalibrationOverlay(
    currentOffsetMs: Int,
    currentSigmaMs: Double?,
    playerViewModel: PlayerViewModel,
    onApplied: (appliedMs: Int, sigmaMs: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val topBarGuardPx = with(LocalDensity.current) { TOP_BAR_GUARD.toPx() }
    val deviceKey = remember { RhythmLatencyStore.deviceKey(context) }
    val deviceLabel = stringResource(RhythmLatencyStore.deviceLabelRes(deviceKey))
    val lowMotion = remember { RhythmUiPreferences(context).lowMotion }
    val calibratedAtMs = remember { RhythmLatencyStore.calibratedAtMs(context, deviceKey) }

    // The shared player must be silent: mixing music into the ticks would corrupt the taps.
    LaunchedEffect(Unit) {
        if (playerViewModel.stablePlayerState.value.isPlaying) playerViewModel.playPause()
    }
    // A 15 s run with no input must not trip the system screen timeout.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val clockResult = remember {
        runCatching { RhythmAudioClock(RhythmCalibrationTrack.build(), offsetMs = 0.0) }
    }
    val clock = clockResult.getOrNull()
    DisposableEffect(clock) {
        onDispose {
            clock?.stop()
            clock?.release()
        }
    }

    var phase by remember { mutableStateOf(if (clock == null) Phase.ERROR else Phase.READY) }
    var runId by remember { mutableIntStateOf(0) }
    var previewId by remember { mutableIntStateOf(0) }
    var previewing by remember { mutableStateOf(false) }
    var crossedTick by remember { mutableIntStateOf(-1) }
    var measuredDone by remember { mutableIntStateOf(0) }
    var stats by remember { mutableStateOf<CalibrationStats?>(null) }

    // Stable holders: the frame loop writes, the tap handler reads, and recomposition must not
    // split them into two different instances.
    val tapTimes = remember { mutableListOf<Long>() }
    val tickWalls = remember { arrayOfNulls<Long>(RhythmCalibrationTrack.TICK_COUNT) }
    val clockSkewNs = remember { LongArray(1) }

    val pulse = remember { Animatable(1f) }
    val ripple = remember { Animatable(1f) }

    fun resetRun() {
        runId = 0
        clock?.stop()
        tapTimes.clear()
        tickWalls.fill(null)
        crossedTick = -1
        measuredDone = 0
        stats = null

        phase = if (clock == null) Phase.ERROR else Phase.READY
    }

    BackHandler {
        when (phase) {
            Phase.LEAD_IN, Phase.MEASURE -> resetRun()
            else -> onDismiss()
        }
    }

    fun onPointerDown(eventUptimeMs: Long) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val mapped = eventUptimeMs * 1_000_000L + clockSkewNs[0]
        val tapNs = if (abs(mapped - nowNs) > MAX_CLOCK_SKEW_NS) nowNs else mapped
        if (phase == Phase.MEASURE) {
            tapTimes.add(tapNs)
            }
        if (!lowMotion) {
            scope.launch {
                ripple.snapTo(0f)
                ripple.animateTo(1f, tween(durationMillis = 200))
            }
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    LaunchedEffect(previewId) {
        if (previewId == 0) return@LaunchedEffect
        val running = clock ?: return@LaunchedEffect
        previewing = true
        try {
            running.start()
            delay(PREVIEW_MS)
        } finally {
            running.stop()
            previewing = false
        }
    }

    // One run: lead-in, measured beats, analyse. The frame loop interpolates the wall time at
    // which each tick's song position crossed the presented line; taps are matched afterwards.
    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        val running = clock ?: return@LaunchedEffect
        tapTimes.clear()
        tickWalls.fill(null)
        crossedTick = -1
        measuredDone = 0
        stats = null

        clockSkewNs[0] =
            SystemClock.elapsedRealtimeNanos() - SystemClock.uptimeMillis() * 1_000_000L
        phase = Phase.LEAD_IN
        running.start()
        var prevSongMs = running.nowMs()
        var prevBootNs = SystemClock.elapsedRealtimeNanos()
        try {
            while (isActive) {
                withFrameNanos { }
                val bootNs = SystemClock.elapsedRealtimeNanos()
                val songMs = running.nowMs()
                clockSkewNs[0] = bootNs - SystemClock.uptimeMillis() * 1_000_000L

                var latest = crossedTick
                for (index in (crossedTick + 1) until RhythmCalibrationTrack.TICK_COUNT) {
                    val tickMs = RhythmCalibrationTrack.tickSongMs(index)
                    if (songMs < tickMs) break // ticks are ordered: later ones cannot have crossed
                    if (songMs > prevSongMs) {
                        val fraction = ((tickMs - prevSongMs) / (songMs - prevSongMs))
                            .coerceIn(0.0, 1.0)
                        tickWalls[index] = (prevBootNs + fraction * (bootNs - prevBootNs)).toLong()
                    }
                    latest = index
                }
                if (latest != crossedTick) {
                    crossedTick = latest
                    if (latest >= 0 &&
                        latest < RhythmCalibrationTrack.LEAD_IN_TICKS &&
                        !lowMotion
                    ) {
                        scope.launch {
                            pulse.snapTo(0f)
                            pulse.animateTo(1f, tween(durationMillis = 240))
                        }
                    }
                    // Switch to the measured section one beat EARLY: a player tapping "with" the
                    // beat is often slightly ahead of it, and a tap that lands before the first
                    // measured tick would otherwise be dropped. The pad is up a beat before the
                    // first counted tick, so nothing that counts is ever discarded.
                    if (latest >= RhythmCalibrationTrack.LEAD_IN_TICKS - 1) {
                        measuredDone = (latest - RhythmCalibrationTrack.LEAD_IN_TICKS + 1)
                            .coerceIn(0, RhythmCalibrationTrack.MEASURED_TICKS)
                        phase = Phase.MEASURE
                    }
                }
                prevSongMs = songMs
                prevBootNs = bootNs

                val lastWall = tickWalls[RhythmCalibrationTrack.TICK_COUNT - 1]
                if (lastWall != null && bootNs - lastWall > END_GRACE_NS) {
                    val result = analyzeCalibration(tapTimes.toList(), tickWalls.toList())
                    stats = result
                    phase = Phase.RESULT
                    break
                }
            }
        } finally {
            running.stop()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CAL_BACKGROUND)
            .safeDrawingPadding()
            .pointerInput(phase) {
                if (phase == Phase.LEAD_IN || phase == Phase.MEASURE) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.y > topBarGuardPx) onPointerDown(down.uptimeMillis)
                        waitForUpOrCancellation()
                    }
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (phase == Phase.LEAD_IN || phase == Phase.MEASURE) resetRun() else onDismiss()
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.rhythm_latency_close),
                        tint = CAL_MUTED,
                    )
                }
                Text(
                    text = stringResource(R.string.rhythm_latency_title),
                    color = CAL_TEXT,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = deviceLabel,
                    color = CAL_ACCENT,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .border(1.dp, CAL_ACCENT.copy(alpha = 0.28f), CircleShape)
                        .padding(horizontal = 11.dp, vertical = 4.dp),
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(targetState = phase, label = "calibrationPhase") { current ->
                    when (current) {
                        Phase.READY -> ReadyContent(
                            offsetMs = currentOffsetMs,
                            sigmaMs = currentSigmaMs,
                            calibratedAtMs = calibratedAtMs,
                        )

                        Phase.LEAD_IN -> LeadInContent(
                            beat = (crossedTick + 1)
                                .coerceIn(1, RhythmCalibrationTrack.LEAD_IN_TICKS),
                            pulse = if (lowMotion) 1f else pulse.value,
                        )

                        Phase.MEASURE -> MeasureContent(
                            done = measuredDone,
                            ripple = if (lowMotion) 1f else ripple.value,
                        )

                        Phase.RESULT -> ResultContent(stats = stats)

                        Phase.ERROR -> Text(
                            text = stringResource(R.string.rhythm_latency_error),
                            color = CAL_TEXT,
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                when (phase) {
                    Phase.READY -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.rhythm_latency_start),
                            enabled = !previewing,
                            onClick = { runId += 1 },
                        )
                        TextButton(onClick = { previewId += 1 }, enabled = !previewing) {
                            Text(
                                text = stringResource(R.string.rhythm_latency_preview),
                                color = CAL_MUTED,
                                fontSize = 15.sp,
                            )
                        }
                    }

                    Phase.LEAD_IN, Phase.MEASURE -> FooterHint(
                        text = if (phase == Phase.LEAD_IN) {
                            stringResource(R.string.rhythm_latency_anytime_back)
                        } else {
                            stringResource(
                                R.string.rhythm_latency_measure_footer,
                                RhythmCalibrationTrack.MEASURED_TICKS,
                            )
                        },
                    )

                    Phase.RESULT -> {
                        val result = stats
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (result == null || result.isLowConfidence) {
                                PrimaryButton(
                                    text = stringResource(R.string.rhythm_latency_retry),
                                    onClick = { runId += 1 },
                                )
                                if (result == null) {
                                    TextButton(onClick = onDismiss) {
                                        Text(
                                            text = stringResource(R.string.rhythm_latency_cancel),
                                            color = CAL_MUTED,
                                            fontSize = 15.sp,
                                        )
                                    }
                                } else {
                                    TextButton(
                                        onClick = {
                                            RhythmLatencyStore.setOffsetMs(
                                                context,
                                                result.appliedOffsetMs,
                                                result.sigmaMs,
                                                deviceKey,
                                            )
                                            onApplied(result.appliedOffsetMs, result.sigmaMs)
                                        },
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.rhythm_latency_apply_anyway,
                                                formatOffset(result.appliedOffsetMs),
                                            ),
                                            color = CAL_WARN,
                                            fontSize = 15.sp,
                                        )
                                    }
                                }
                            } else {
                                PrimaryButton(
                                    text = stringResource(R.string.rhythm_latency_apply),
                                    onClick = {
                                        RhythmLatencyStore.setOffsetMs(
                                            context,
                                            result.appliedOffsetMs,
                                            result.sigmaMs,
                                            deviceKey,
                                        )
                                        onApplied(result.appliedOffsetMs, result.sigmaMs)
                                    },
                                )
                                Row(horizontalArrangement = Arrangement.Center) {
                                    TextButton(onClick = { runId += 1 }) {
                                        Text(
                                            text = stringResource(R.string.rhythm_latency_retry),
                                            color = CAL_MUTED,
                                            fontSize = 15.sp,
                                        )
                                    }
                                    TextButton(onClick = onDismiss) {
                                        Text(
                                            text = stringResource(R.string.rhythm_latency_cancel),
                                            color = CAL_MUTED,
                                            fontSize = 15.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Phase.ERROR -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // The AudioTrack could not even be created; reopening the overlay builds
                        // a fresh one, so there is nothing to retry inside this instance.
                        PrimaryButton(
                            text = stringResource(R.string.rhythm_latency_cancel),
                            onClick = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CAL_ACCENT,
            contentColor = CAL_ON_ACCENT,
            disabledContainerColor = CAL_ACCENT.copy(alpha = 0.4f),
            disabledContentColor = CAL_ON_ACCENT.copy(alpha = 0.7f),
        ),
    ) {
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FooterHint(text: String) {
    Text(
        text = text,
        color = CAL_MUTED.copy(alpha = 0.75f),
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 24.dp),
    )
}

@Composable
private fun StepDots(active: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 18.dp),
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (index == active) 18.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (index <= active) CAL_ACCENT else CAL_TEXT.copy(alpha = 0.22f)),
            )
        }
    }
}

@Composable
private fun ReadyContent(
    offsetMs: Int,
    sigmaMs: Double?,
    calibratedAtMs: Long,
) {
    val neverCalibrated = calibratedAtMs == 0L
    val meta = if (neverCalibrated) {
        stringResource(R.string.rhythm_latency_meta_duration)
    } else {
        val age = measuredAgoLabel(calibratedAtMs)
        val jitter = sigmaMs?.let { stringResource(R.string.rhythm_latency_sigma, it.roundToInt()) }
        if (jitter == null) age else "$jitter · $age"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepDots(active = 0)
        Text(
            text = stringResource(R.string.rhythm_latency_current_label),
            color = CAL_MUTED,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        if (neverCalibrated) {
            Text(
                text = stringResource(R.string.rhythm_latency_uncalibrated),
                color = CAL_ACCENT,
                fontSize = 34.sp,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatOffset(offsetMs),
                    color = CAL_TEXT,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "ms",
                    color = CAL_MUTED,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(text = meta, color = CAL_MUTED, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.rhythm_latency_ready_hint),
            color = CAL_MUTED,
            fontSize = 15.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(290.dp),
        )
    }
}

@Composable
private fun LeadInContent(beat: Int, pulse: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepDots(active = 1)
        Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                drawCircle(
                    color = CAL_ACCENT.copy(alpha = 0.28f),
                    radius = radius - 1.dp.toPx() / 2,
                    style = Stroke(1.dp.toPx()),
                )
                drawCircle(
                    color = CAL_ACCENT.copy(alpha = 0.16f),
                    radius = radius - 24.dp.toPx(),
                    style = Stroke(1.dp.toPx()),
                )
                if (pulse < 1f) {
                    drawCircle(
                        color = CAL_ACCENT.copy(alpha = 0.30f * (1f - pulse)),
                        radius = radius * (0.86f + 0.22f * pulse),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
            Text(
                text = beat.toString(),
                color = CAL_ACCENT,
                fontSize = 88.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.rhythm_latency_listen_hint),
            color = CAL_TEXT,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.rhythm_latency_listen_micro),
            color = CAL_MUTED.copy(alpha = 0.8f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun MeasureContent(done: Int, ripple: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        StepDots(active = 2)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 26.dp),
        ) {
            repeat(RhythmCalibrationTrack.MEASURED_TICKS) { index ->
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < done) CAL_ACCENT else CAL_TEXT.copy(alpha = 0.18f),
                        ),
                )
            }
        }
        Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2
                drawCircle(
                    color = CAL_ACCENT.copy(alpha = 0.22f),
                    radius = radius - 1.dp.toPx() / 2,
                    style = Stroke(1.dp.toPx()),
                )
                drawCircle(color = CAL_ACCENT.copy(alpha = 0.08f), radius = 66.dp.toPx())
                if (ripple < 1f) {
                    drawCircle(
                        color = CAL_ACCENT.copy(alpha = 0.35f * (1f - ripple)),
                        radius = (66f + 62f * ripple).dp.toPx(),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
            Text(
                text = stringResource(R.string.rhythm_latency_tap_pad),
                color = CAL_MUTED,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.rhythm_latency_measure_micro),
            color = CAL_MUTED.copy(alpha = 0.8f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultContent(stats: CalibrationStats?) {
    if (stats == null) {
        Text(
            text = stringResource(R.string.rhythm_latency_insufficient),
            color = CAL_TEXT,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
        )
        return
    }
    val stars = when {
        stats.sigmaMs <= 15.0 -> 3
        stats.sigmaMs <= 30.0 -> 2
        else -> 1
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.rhythm_latency_result_label),
            color = CAL_MUTED,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatOffset(stats.appliedOffsetMs),
                color = CAL_TEXT,
                fontSize = 44.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "ms",
                color = CAL_MUTED,
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { index ->
                Text(
                    text = "★",
                    color = if (index < stars) CAL_GOLD else CAL_TEXT.copy(alpha = 0.16f),
                    fontSize = 24.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val warn = stats.isLowConfidence
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.rhythm_latency_sigma, stats.sigmaMs.roundToInt()),
                color = if (warn) CAL_WARN else CAL_ACCENT,
                fontSize = 12.sp,
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = (if (warn) CAL_WARN else CAL_ACCENT).copy(alpha = 0.35f),
                        shape = CircleShape,
                    )
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Text(
                text = stringResource(
                    R.string.rhythm_latency_result_meta,
                    stats.matchedTicks,
                    stats.measuredTicks,
                ),
                color = CAL_MUTED,
                fontSize = 13.sp,
            )
            if (stats.rejectedTicks > 0) {
                Text(
                    text = stringResource(
                        R.string.rhythm_latency_result_rejected,
                        stats.rejectedTicks,
                    ),
                    color = CAL_MUTED.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        ScatterStrip(samples = stats.samples)
        if (stats.isLowConfidence) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.rhythm_latency_result_low),
                color = CAL_WARN,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Per-tick residuals around the applied median: the run is judged from this, not from prose. */
@Composable
private fun ScatterStrip(samples: List<CalibrationTickSample>) {
    val maxAbsMs = (samples.maxOfOrNull { abs(it.offsetMs) } ?: 0.0).coerceAtLeast(60.0)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CAL_SURFACE),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val midY = size.height / 2
            drawLine(
                color = CAL_MUTED.copy(alpha = 0.25f),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = CAL_ACCENT.copy(alpha = 0.55f),
                start = Offset(size.width / 2, 10.dp.toPx()),
                end = Offset(size.width / 2, size.height - 10.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            samples.forEachIndexed { position, sample ->
                val x = size.width / 2 +
                    (sample.offsetMs / maxAbsMs * 0.42 * size.width).toFloat()
                val y = midY + if (position % 2 == 0) -6.dp.toPx() else 6.dp.toPx()
                if (sample.rejected) {
                    drawCircle(
                        color = CAL_MUTED.copy(alpha = 0.7f),
                        radius = 3.5.dp.toPx(),
                        center = Offset(x, y),
                        style = Stroke(1.5.dp.toPx()),
                    )
                } else {
                    drawCircle(
                        color = CAL_ACCENT,
                        radius = 3.5.dp.toPx(),
                        center = Offset(x, y),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.rhythm_latency_early),
            color = CAL_MUTED.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.rhythm_latency_late),
            color = CAL_MUTED.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp),
        )
    }
}

/** Signed offset with a true minus sign, so −420 is not read as a dash. */
internal fun formatOffset(ms: Int): String = if (ms < 0) "−${-ms}" else ms.toString()

/** "今天测量" / "N 天前测量" for a calibration timestamp; shared with the entry card. */
@Composable
internal fun measuredAgoLabel(calibratedAtMs: Long): String {
    val days = ((System.currentTimeMillis() - calibratedAtMs) / 86_400_000L).toInt()
    return if (days <= 0) {
        stringResource(R.string.rhythm_latency_measured_today)
    } else {
        stringResource(R.string.rhythm_latency_measured_days, days)
    }
}
