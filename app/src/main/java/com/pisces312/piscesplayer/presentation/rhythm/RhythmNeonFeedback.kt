/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (neon rhythm feedback: combo tiers, judgement bounce and
 * the full-screen combo brightness pulse).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisces312.piscesplayer.R
import kotlin.math.roundToInt

/** Full-screen brightness pulse at every 50 combo: 420 ms, alpha only (no shake, no offset). */
private const val COMBO_PULSE_MS = 420f
private const val COMBO_PULSE_ALPHA = 0.15f

/**
 * Neon variant of [RhythmFeedbackOverlay]: the combo counter recolours through the 30/50/100
 * tiers with a glow, the judgement word pops with a scale bounce and fades out, and every 50
 * combo fires a full-screen brightness pulse — brightness only, never displacement, so it can
 * not be mistaken for a touch or move the reading position of the notes.
 *
 * Timing is elapsed-nanos driven like [RhythmTimedJudgement]: one-shot events aged by the game
 * loop's frame clock, no infinite animations. [lowMotion] keeps the text and combo but drops
 * the brightness pulse (per the low-motion contract: particles, stars, pulses off).
 */
@Composable
internal fun RhythmNeonFeedbackOverlay(
    combo: Int,
    feedback: RhythmTimedJudgement?,
    nowNanos: Long,
    lowMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    // Combo milestone pulse: fire when the counter crosses a 50-hit boundary. Crossing-based so
    // a chord (two hits in one frame) cannot skip its milestone.
    var previousCombo by remember { mutableIntStateOf(combo) }
    var pulseStartedAtNanos by remember { mutableLongStateOf(Long.MIN_VALUE) }
    LaunchedEffect(combo) {
        if (!lowMotion && rhythmComboPulseCrossed(previousCombo, combo)) {
            pulseStartedAtNanos = nowNanos
        }
        previousCombo = combo
    }

    Box(modifier) {
        if (combo > 1) {
            val tier = rhythmComboTier(combo)
            Text(
                text = stringResource(R.string.rhythm_combo, combo),
                modifier = Modifier.align(Alignment.Center).offset(y = (-58).dp),
                color = rhythmComboTierColor(tier),
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = rhythmComboTierColor(tier).copy(alpha = 0.55f),
                        offset = Offset.Zero,
                        blurRadius = 14f,
                    ),
                ),
            )
        }

        feedback?.let { timed ->
            val elapsedMs = ((nowNanos - timed.startedAtNanos).coerceAtLeast(0L) / 1_000_000L)
                .toFloat()
            val alpha = when {
                elapsedMs < 55f -> elapsedMs / 55f
                elapsedMs < 270f -> 1f
                else -> 1f - (elapsedMs - 270f) / 170f
            }.coerceIn(0f, 1f)
            // Scale bounce: up to 1.18, settle back to 1 — a pop, not just a fade.
            val scale = when {
                elapsedMs < 70f -> 0.72f + 0.46f * (elapsedMs / 70f)
                elapsedMs < 150f -> 1.18f - 0.21f * ((elapsedMs - 70f) / 80f)
                elapsedMs < 220f -> 0.97f + 0.03f * ((elapsedMs - 150f) / 70f)
                else -> 1f
            }
            val event = timed.event
            val glow = rhythmJudgementColor(event.judgement)
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = 4.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = event.judgement.name,
                    color = glow,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    style = TextStyle(
                        shadow = Shadow(
                            color = glow.copy(alpha = 0.6f),
                            offset = Offset.Zero,
                            blurRadius = 16f,
                        ),
                    ),
                )
                event.errorMs?.let { error ->
                    val direction = when {
                        error < -0.5 -> stringResource(R.string.rhythm_timing_early)
                        error > 0.5 -> stringResource(R.string.rhythm_timing_late)
                        else -> stringResource(R.string.rhythm_timing_on_time)
                    }
                    val signed = if (error > 0.5) "+${error.roundToInt()}" else "${error.roundToInt()}"
                    Text(
                        text = stringResource(R.string.rhythm_feedback_timing, signed, direction),
                        color = Color(0xFFD8DCE8),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 50-combo brightness pulse: alpha over the whole screen, nothing else moves.
        if (!lowMotion && pulseStartedAtNanos != Long.MIN_VALUE) {
            val pulseElapsedMs =
                ((nowNanos - pulseStartedAtNanos).coerceAtLeast(0L) / 1_000_000L).toFloat()
            if (pulseElapsedMs <= COMBO_PULSE_MS) {
                val t = (pulseElapsedMs / COMBO_PULSE_MS).coerceIn(0f, 1f)
                val rise = (pulseElapsedMs / 55f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.White.copy(alpha = COMBO_PULSE_ALPHA * rise * (1f - t))),
                )
            }
        }
    }
}
