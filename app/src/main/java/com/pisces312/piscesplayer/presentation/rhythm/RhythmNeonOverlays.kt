/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (neon rhythm overlays: glowing HUD, neon pause card and the
 * grade + rolling-number result screen).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.rhythm.RhythmDriftKind
import com.pisces312.piscesplayer.data.rhythm.RhythmDriftReport
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive

private val NEON_HUD_TEXT = Color(0xFFE9EDF7)
private val NEON_HUD_SECONDARY = Color(0xFF9DA6BA)
private val NEON_HUD_ACCENT = Color(0xFF54E8FF)
private val NEON_CARD_BACKGROUND = Color(0xFF121826)
private val NEON_DANGER = Color(0xFFFF6B81)
private val NEON_TRACK = Color(0xFF303748)

/** How long the result numbers roll from zero up to their final value. */
private const val ROLL_MS = 900.0

/**
 * Frame-clock driven one-shot rollout, 0 → 1 with an ease-out. Same nanos-driven approach as
 * [RhythmTimedJudgement] — a finite animation that stops when it lands, no infinite transition.
 */
@Composable
private fun rememberRollout(): Float {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (isActive) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            val t = (elapsedMs / ROLL_MS).coerceIn(0.0, 1.0)
            progress = (1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t)).toFloat()
            if (t >= 1.0) break
            withFrameNanos { }
        }
    }
    return progress
}

/**
 * Neon HUD: the same information as [RhythmHud] (score, difficulty, accuracy, pause, offset
 * status, clock) with a glowing progress bar.
 */
@Composable
internal fun RhythmNeonHud(
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
                    color = NEON_HUD_TEXT,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = session.chart.difficultyName.uppercase(),
                    color = NEON_HUD_SECONDARY,
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                )
            }
            Text(
                text = stringResource(
                    R.string.rhythm_accuracy,
                    String.format(Locale.US, "%.2f", engine.accuracy * 100),
                ),
                color = NEON_HUD_TEXT,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onTogglePause) {
                Text(
                    text = stringResource(
                        if (paused) R.string.rhythm_resume else R.string.rhythm_pause,
                    ),
                    color = NEON_HUD_ACCENT,
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
                color = NEON_HUD_SECONDARY,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatHudClock(songMs) + " / " + formatHudClock(session.clock.durationMs),
                color = NEON_HUD_TEXT,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        // Glowing progress bar: soft bloom band, bright fill, hot tip.
        Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            val centerY = size.height / 2f
            val trackHalf = 1.5.dp.toPx()
            drawRect(
                color = NEON_TRACK,
                topLeft = Offset(0f, centerY - trackHalf),
                size = Size(size.width, trackHalf * 2f),
            )
            val filled = size.width * progress
            drawRect(
                color = NEON_HUD_ACCENT.copy(alpha = 0.16f),
                topLeft = Offset(0f, centerY - 3.5.dp.toPx()),
                size = Size(filled, 7.dp.toPx()),
            )
            drawRect(
                color = NEON_HUD_ACCENT.copy(alpha = 0.85f),
                topLeft = Offset(0f, centerY - trackHalf),
                size = Size(filled, trackHalf * 2f),
            )
            if (progress > 0f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 2.dp.toPx(),
                    center = Offset(filled, centerY),
                )
            }
        }
    }
}

/**
 * Neon pause card: same controls as [RhythmPauseOverlay] (resume, ±10 ms offset, diagnostics,
 * restart, exit) in a glowing frame.
 */
@Composable
internal fun RhythmNeonPauseOverlay(
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
            .background(Color(0xE6070A12)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            shape = RoundedCornerShape(20.dp),
            color = NEON_CARD_BACKGROUND,
            border = BorderStroke(1.dp, NEON_HUD_ACCENT.copy(alpha = 0.45f)),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.rhythm_paused_title),
                    color = NEON_HUD_ACCENT,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(
                        shadow = Shadow(
                            color = NEON_HUD_ACCENT.copy(alpha = 0.5f),
                            offset = Offset.Zero,
                            blurRadius = 14f,
                        ),
                    ),
                )
                Text(
                    text = stringResource(R.string.rhythm_pause_hint),
                    color = NEON_HUD_SECONDARY,
                    fontSize = 13.sp,
                )
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rhythm_resume))
                }
                Text(
                    text = stringResource(R.string.rhythm_offset_label, offsetMs.roundToInt()),
                    color = NEON_HUD_TEXT,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onOffset(-10) }) { Text("-10 ms") }
                    OutlinedButton(onClick = { onOffset(10) }) { Text("+10 ms") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onViewDiagnostics) {
                        Text(stringResource(R.string.rhythm_view_diagnostics), color = NEON_HUD_SECONDARY)
                    }
                    TextButton(onClick = onExportDiagnostics) {
                        Text(stringResource(R.string.rhythm_export_diagnostics), color = NEON_HUD_SECONDARY)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onRestart) {
                        Text(stringResource(R.string.rhythm_restart), color = NEON_HUD_TEXT)
                    }
                    TextButton(onClick = onExit) {
                        Text(stringResource(R.string.rhythm_exit), color = NEON_DANGER)
                    }
                }
            }
        }
    }
}

/**
 * Neon result screen: big grade letter (S/A/B/C from accuracy), rolling score and accuracy
 * numbers, the four-colour judgement distribution bar, and the usual run statistics.
 */
@Composable
internal fun RhythmNeonResultOverlay(
    result: RhythmSessionResult,
    driftReport: RhythmDriftReport?,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val rollout = rememberRollout()
    val grade = rhythmGradeFor(result.accuracy)
    val gradeColor = rhythmGradeColor(grade)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2070A12)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            color = NEON_CARD_BACKGROUND,
            border = BorderStroke(1.dp, gradeColor.copy(alpha = 0.45f)),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = stringResource(R.string.rhythm_result_title, result.difficultyName),
                    color = NEON_HUD_ACCENT,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                // Grade letter pops in with the rollout and glows in its tier colour.
                Text(
                    text = grade.name,
                    color = gradeColor,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = TextStyle(
                        shadow = Shadow(
                            color = gradeColor.copy(alpha = 0.6f),
                            offset = Offset.Zero,
                            blurRadius = 22f,
                        ),
                    ),
                    modifier = Modifier.graphicsLayer {
                        scaleX = 0.6f + 0.4f * rollout
                        scaleY = 0.6f + 0.4f * rollout
                    },
                )
                Text(
                    text = String.format(Locale.US, "%,d", (result.score * rollout).toInt()),
                    color = NEON_HUD_TEXT,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = stringResource(
                        R.string.rhythm_accuracy,
                        String.format(Locale.US, "%.2f", result.accuracy * 100 * rollout),
                    ),
                    color = NEON_HUD_TEXT,
                    fontSize = 20.sp,
                )
                Text(
                    text = stringResource(R.string.rhythm_result_combo, result.maxCombo, result.noteCount),
                    color = NEON_HUD_SECONDARY,
                )
                JudgementDistributionBar(
                    counts = result.counts,
                    rollout = rollout,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.rhythm_result_timing,
                        String.format(Locale.US, "%.1f", result.meanErrorMs),
                        result.earlyCount,
                        result.lateCount,
                    ),
                    color = NEON_HUD_SECONDARY,
                    fontSize = 13.sp,
                )
                driftReport?.let { report ->
                    Text(
                        text = driftSummary(report),
                        color = if (report.kind == RhythmDriftKind.STABLE) NEON_HUD_SECONDARY else NEON_DANGER,
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
                    Text(stringResource(R.string.rhythm_export_diagnostics), color = NEON_HUD_SECONDARY)
                }
            }
        }
    }
}

/**
 * PERFECT/GREAT/GOOD/MISS stacked distribution bar with a count legend; the bar fills in with
 * the result rollout.
 */
@Composable
private fun JudgementDistributionBar(
    counts: Map<RhythmJudgement, Int>,
    rollout: Float,
    modifier: Modifier = Modifier,
) {
    val total = RhythmJudgement.SCORING.sumOf { counts[it] ?: 0 }.coerceAtLeast(1)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
        ) {
            var x = 0f
            for (judgement in RhythmJudgement.SCORING) {
                val share = (counts[judgement] ?: 0).toFloat() / total
                val width = size.width * share * rollout
                if (width > 0f) {
                    drawRect(
                        color = rhythmJudgementColor(judgement),
                        topLeft = Offset(x, 0f),
                        size = Size(width, size.height),
                    )
                }
                x += size.width * share
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (judgement in RhythmJudgement.SCORING) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                rhythmJudgementColor(judgement),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${judgement.name} ${counts[judgement] ?: 0}",
                        color = NEON_HUD_SECONDARY,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
