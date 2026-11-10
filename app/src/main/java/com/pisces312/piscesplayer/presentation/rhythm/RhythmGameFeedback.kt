/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (judgement text, combo counter and hit ring).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisces312.piscesplayer.R
import kotlin.math.roundToInt

/** How long a PERFECT/GREAT/GOOD/MISS word stays on screen, and how long the ring expands. */
internal const val RHYTHM_JUDGEMENT_FEEDBACK_MS = 440L
internal const val RHYTHM_HIT_EFFECT_MS = 210L

/** A judgement waiting to be animated out; [startedAtNanos] comes from the frame clock. */
internal data class RhythmTimedJudgement(
    val id: Long,
    val event: RhythmJudgementEvent,
    val startedAtNanos: Long,
)

/** Expanding ring drawn at the judgement line on a hit. */
internal data class RhythmHitEffect(
    val id: Long,
    val lane: Int,
    val judgement: RhythmJudgement,
    val startedAtNanos: Long,
)

internal fun rhythmJudgementColor(judgement: RhythmJudgement): Color = when (judgement) {
    RhythmJudgement.PERFECT -> Color(0xFF8BE9FD)
    RhythmJudgement.GREAT -> Color(0xFF9DF2B6)
    RhythmJudgement.GOOD -> Color(0xFFFFD166)
    RhythmJudgement.MISS -> Color(0xFFFF6B81)
    RhythmJudgement.EMPTY -> Color(0xFFB8BCC9)
}

/**
 * Combo counter plus the judgement word. Both are driven by wall-clock nanoseconds rather than
 * by an animation spec: a judgement is a one-shot event, and the frame loop that feeds the
 * playfield already runs at display refresh, so scaling/alpha are computed from elapsed time.
 */
@Composable
internal fun RhythmFeedbackOverlay(
    combo: Int,
    feedback: RhythmTimedJudgement?,
    nowNanos: Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (combo > 1) {
            Text(
                text = stringResource(R.string.rhythm_combo, combo),
                modifier = Modifier.align(Alignment.Center).offset(y = (-58).dp),
                color = Color(0xFFF2F4FA),
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
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
            val scale = when {
                elapsedMs < 85f -> 0.84f + 0.22f * (elapsedMs / 85f)
                elapsedMs < 180f -> 1.06f - 0.06f * ((elapsedMs - 85f) / 95f)
                else -> 1f
            }
            val event = timed.event
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
                    color = rhythmJudgementColor(event.judgement),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
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
    }
}
