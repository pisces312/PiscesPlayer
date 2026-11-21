/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (RhythmChartLab docs/experiments/2026-11-21-game-start-note-tremble-analysis.md §14).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.rhythm.RhythmAudioClock
import com.pisces312.piscesplayer.data.rhythm.RhythmCalibrationTrack
import com.pisces312.piscesplayer.data.rhythm.RhythmLatencyStore
import com.pisces312.piscesplayer.data.rhythm.analyzeTapOffsets
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val CALIBRATION_BACKGROUND = Color(0xFF0D0F17)
private val CALIBRATION_TEXT = Color(0xFFE9EDF7)
private val CALIBRATION_SECONDARY = Color(0xFF9DA6BA)
private val CALIBRATION_ACCENT = Color(0xFF78C8FF)

private enum class Phase { READY, COUNTING, LISTENING, RESULT }

private const val END_GRACE_NS = 1_200_000_000L

/**
 * Latency calibration: play generated metronome ticks through the CURRENT audio route (so a
 * Bluetooth link contributes its full, otherwise unobservable latency), tap the screen on the
 * heard beats, and save the mean tap delay as the offset for this route. Replaces the old
 * per-chart ±10 ms guessing: one 10-second run measures the route directly.
 *
 * The first [RhythmCalibrationTrack.SKIP_TICKS] ticks warm the Bluetooth link (codec session
 * and jitter buffer) and must not be tapped; on a cold link they arrive later than the
 * steady-state route latency, which is exactly what a calibration must not learn.
 */
@Composable
internal fun RhythmCalibrationOverlay(
    currentOffsetMs: Int,
    playerViewModel: PlayerViewModel,
    onApplied: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val deviceKey = remember { RhythmLatencyStore.deviceKey(context) }
    val deviceLabel = stringResource(RhythmLatencyStore.deviceLabelRes(deviceKey))

    // The shared player must be silent: mixing music into the ticks would corrupt the taps.
    LaunchedEffect(Unit) {
        if (playerViewModel.stablePlayerState.value.isPlaying) playerViewModel.playPause()
    }

    val clock = remember { RhythmAudioClock(RhythmCalibrationTrack.build(), offsetMs = 0.0) }
    DisposableEffect(Unit) {
        onDispose {
            clock.stop()
            clock.release()
        }
    }

    var phase by remember { mutableStateOf(Phase.READY) }
    var countdown by remember { mutableIntStateOf(3) }
    var taps by remember { mutableStateOf(listOf<Long>()) }
    var tickWalls by remember { mutableStateOf(List<Long?>(RhythmCalibrationTrack.TICK_COUNT) { null }) }
    var lastTickIndex by remember { mutableIntStateOf(-1) }
    var measuredMs by remember { mutableDoubleStateOf(Double.NaN) }

    fun resetRun() {
        clock.stop()
        taps = emptyList()
        tickWalls = List(RhythmCalibrationTrack.TICK_COUNT) { null }
        lastTickIndex = -1
        measuredMs = Double.NaN
        phase = Phase.READY
    }

    BackHandler {
        when (phase) {
            Phase.COUNTING, Phase.LISTENING -> resetRun()
            else -> onDismiss()
        }
    }

    // Countdown then start: the metronome's own clock parks at the play anchor and releases on
    // the first frame-progress sample, exactly like a game run (RhythmFrameEstimator).
    LaunchedEffect(phase) {
        if (phase != Phase.COUNTING) return@LaunchedEffect
        countdown = 3
        repeat(3) {
            delay(1000)
            countdown -= 1
        }
        clock.start()
        phase = Phase.LISTENING
    }

    // Frame loop while the ticks play: interpolate the wall time at which each tick's song
    // position crossed the presented line; taps are matched against those crossings later.
    LaunchedEffect(phase) {
        if (phase != Phase.LISTENING) return@LaunchedEffect
        var prevSongMs = clock.nowMs()
        var prevBootNs = SystemClock.elapsedRealtimeNanos()
        while (isActive) {
            withFrameNanos { }
            val bootNs = SystemClock.elapsedRealtimeNanos()
            val songMs = clock.nowMs()
            var crossed = lastTickIndex
            val walls = tickWalls.toMutableList()
            var changed = false
            for (index in (lastTickIndex + 1) until RhythmCalibrationTrack.TICK_COUNT) {
                val tickMs = RhythmCalibrationTrack.tickSongMs(index)
                if (songMs < tickMs) break // ticks are ordered: later ones cannot have crossed
                if (songMs > prevSongMs) {
                    val fraction = ((tickMs - prevSongMs) / (songMs - prevSongMs))
                        .coerceIn(0.0, 1.0)
                    walls[index] = (prevBootNs + fraction * (bootNs - prevBootNs)).toLong()
                    changed = true
                }
                crossed = index
            }
            if (changed) tickWalls = walls
            if (crossed != lastTickIndex) lastTickIndex = crossed
            prevSongMs = songMs
            prevBootNs = bootNs

            val lastWall = walls[RhythmCalibrationTrack.TICK_COUNT - 1]
            if (lastWall != null && bootNs - lastWall > END_GRACE_NS) {
                measuredMs = analyzeTapOffsets(taps, tickWalls) ?: Double.NaN
                clock.stop()
                phase = Phase.RESULT
                break
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CALIBRATION_BACKGROUND)
            .safeDrawingPadding()
            .pointerInput(phase) {
                if (phase == Phase.LISTENING) {
                    detectTapGestures {
                        taps = taps + SystemClock.elapsedRealtimeNanos()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            Phase.READY -> Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.rhythm_latency_title),
                    color = CALIBRATION_TEXT,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.rhythm_latency_ready_current,
                        deviceLabel,
                        currentOffsetMs,
                    ),
                    color = CALIBRATION_ACCENT,
                    fontSize = 16.sp,
                )
                Text(
                    text = stringResource(R.string.rhythm_latency_hint),
                    color = CALIBRATION_SECONDARY,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { phase = Phase.COUNTING }) {
                    Text(stringResource(R.string.rhythm_latency_start))
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.rhythm_latency_cancel),
                        color = CALIBRATION_SECONDARY,
                    )
                }
            }

            Phase.COUNTING -> Text(
                text = countdown.toString(),
                color = CALIBRATION_TEXT,
                fontSize = 64.sp,
                fontWeight = FontWeight.ExtraBold,
            )

            Phase.LISTENING -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.rhythm_latency_listening),
                    color = CALIBRATION_TEXT,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.rhythm_latency_progress,
                        (lastTickIndex + 1).coerceAtLeast(0),
                        RhythmCalibrationTrack.TICK_COUNT,
                    ),
                    color = CALIBRATION_ACCENT,
                    fontSize = 17.sp,
                )
                Text(
                    text = stringResource(R.string.rhythm_latency_taps, taps.size),
                    color = CALIBRATION_SECONDARY,
                    fontSize = 14.sp,
                )
            }

            Phase.RESULT -> Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (measuredMs.isNaN()) {
                    Text(
                        text = stringResource(R.string.rhythm_latency_insufficient),
                        color = CALIBRATION_TEXT,
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { resetRun(); phase = Phase.COUNTING }) {
                            Text(stringResource(R.string.rhythm_latency_retry))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(
                                stringResource(R.string.rhythm_latency_cancel),
                                color = CALIBRATION_SECONDARY,
                            )
                        }
                    }
                } else {
                    val applied = -measuredMs.roundToInt()
                    Text(
                        text = stringResource(R.string.rhythm_latency_result, measuredMs.roundToInt()),
                        color = CALIBRATION_TEXT,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.rhythm_latency_result_hint, deviceLabel, applied),
                        color = CALIBRATION_ACCENT,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = {
                        RhythmLatencyStore.setOffsetMs(context, applied)
                        onApplied(applied)
                    }) {
                        Text(stringResource(R.string.rhythm_latency_apply))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { resetRun(); phase = Phase.COUNTING }) {
                            Text(stringResource(R.string.rhythm_latency_retry))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(
                                stringResource(R.string.rhythm_latency_cancel),
                                color = CALIBRATION_SECONDARY,
                            )
                        }
                    }
                }
            }
        }
    }
}
