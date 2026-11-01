/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (four-lane canvas with a coloured key area and press feedback).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val FIELD_BACKGROUND = Color(0xFF0D0F17)
private val LANE_EVEN = Color(0xFF171A25)
private val LANE_ODD = Color(0xFF1C202D)
private val SEPARATOR = Color(0xFF3A4154)
private val NOTE_ACTIVE = Color(0xFFB5E6FF)

/** Key-area gradient per lane (top = judgement line, bottom = screen edge). */
private data class LaneKeyColors(val top: Color, val bottom: Color, val note: Color)

private val LANE_KEYS = listOf(
    LaneKeyColors(top = Color(0xFF54E8FF), bottom = Color(0xFF0E5FC8), note = Color(0xFF6FE9FF)),
    LaneKeyColors(top = Color(0xFFFF7ADF), bottom = Color(0xFF9C22C4), note = Color(0xFFFF92E4)),
    LaneKeyColors(top = Color(0xFF63FFB0), bottom = Color(0xFF0D9B62), note = Color(0xFF7FFFC2)),
    LaneKeyColors(top = Color(0xFFFFC466), bottom = Color(0xFFD0521C), note = Color(0xFFFFD28A)),
)

/**
 * The playfield: lanes, falling notes, judgement line and hit rings.
 *
 * Input is admitted only below the judgement line ([RhythmPlayfieldGeometry.laneAt]), so a tap
 * on a falling note does not cheat it into existence. Pointer lifecycle lives in
 * [RhythmLaneTouchTracker]: one judgement per finger, chords across lanes, and a release only
 * clears the finger that lifted.
 */
@Composable
internal fun RhythmPlayfield(
    session: RhythmPlaySession,
    frameTick: Long,
    touchTracker: RhythmLaneTouchTracker,
    hitEffects: List<RhythmHitEffect>,
    effectNowNanos: Long,
    inputEnabled: Boolean,
    onLaneDown: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_EXPRESSION") frameTick // the clock advancing is what redraws the canvas

    LaunchedEffect(inputEnabled, touchTracker) {
        if (!inputEnabled) touchTracker.clear()
    }

    val pressedLanes = touchTracker.pressedLanes()
    val pressAnimations = pressedLanes.mapIndexed { lane, pressed ->
        animateFloatAsState(
            targetValue = if (pressed) 1f else 0f,
            animationSpec = tween(durationMillis = if (pressed) 70 else 150),
            label = "rhythm-lane-$lane-press",
        )
    }
    val density = LocalDensity.current
    val noteHeightPx = with(density) { 14.dp.toPx() }
    val notePaddingPx = with(density) { 7.dp.toPx() }
    val noteCornerPx = with(density) { 4.dp.toPx() }
    val effectStrokePx = with(density) { 3.dp.toPx() }

    Canvas(
        modifier = modifier.pointerInput(session, inputEnabled, touchTracker) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val geometry = RhythmPlayfieldGeometry(size.width.toFloat(), size.height.toFloat())
                    event.changes.forEach { change ->
                        val pointerId = change.id.value
                        if (change.pressed && !change.previousPressed) {
                            if (inputEnabled) {
                                val lane = geometry.laneAt(change.position.x, change.position.y)
                                if (lane != null && touchTracker.press(pointerId, lane)) {
                                    onLaneDown(lane)
                                }
                            }
                        } else if (!change.pressed) {
                            // Up and cancel both release; a second finger on the same lane keeps
                            // the lane pressed.
                            touchTracker.release(pointerId)
                        }
                    }
                }
            }
        },
    ) {
        val geometry = RhythmPlayfieldGeometry(size.width, size.height)
        val laneWidth = geometry.laneWidthPx
        val keyHeight = size.height - geometry.hitLineY
        val now = session.clock.nowMs()
        val goodWindow = session.config.goodMs

        drawRect(FIELD_BACKGROUND)
        for (lane in 0 until RHYTHM_LANE_COUNT) {
            val x = lane * laneWidth
            val press = pressAnimations[lane].value
            val colors = LANE_KEYS[lane]
            drawRect(
                color = if (lane % 2 == 0) LANE_EVEN else LANE_ODD,
                topLeft = Offset(x, 0f),
                size = Size(laneWidth, size.height),
            )
            if (lane > 0) {
                drawLine(
                    color = SEPARATOR.copy(alpha = 0.65f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }

            // Everything below the judgement line is the key: a coloured gradient block.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        colors.top.copy(alpha = 0.92f),
                        colors.top.copy(alpha = 0.55f),
                        colors.bottom.copy(alpha = 0.72f),
                    ),
                    startY = geometry.hitLineY,
                    endY = size.height,
                ),
                topLeft = Offset(x, geometry.hitLineY),
                size = Size(laneWidth, keyHeight),
            )
            if (press > 0f) {
                // Brighter near the line, so the finger's own lane lights up.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.34f * press),
                            Color.White.copy(alpha = 0.10f * press),
                            colors.top.copy(alpha = 0.18f * press),
                        ),
                        startY = geometry.hitLineY,
                        endY = size.height,
                    ),
                    topLeft = Offset(x, geometry.hitLineY),
                    size = Size(laneWidth, keyHeight),
                )
            }
        }

        for (note in session.chart.notes) {
            if (!session.engine.isNotePending(note.id)) continue
            val delta = (note.timeMs - now).toFloat()
            if (delta < -goodWindow || delta > session.approachMs) continue
            val noteWidth = laneWidth - notePaddingPx * 2f
            val color = if (abs(delta) <= goodWindow) NOTE_ACTIVE else LANE_KEYS[note.lane].note
            val centerY = geometry.noteCenterY(note.timeMs, now, session.approachMs)
            val topLeft = Offset(note.lane * laneWidth + notePaddingPx, centerY - noteHeightPx / 2f)
            drawRoundRect(
                color = color,
                topLeft = topLeft,
                size = Size(noteWidth, noteHeightPx),
                cornerRadius = CornerRadius(noteCornerPx),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = topLeft,
                size = Size(noteWidth, noteHeightPx * 0.28f),
                cornerRadius = CornerRadius(noteCornerPx),
            )
        }

        for (effect in hitEffects) {
            val elapsedMs = (effectNowNanos - effect.startedAtNanos).coerceAtLeast(0L) / 1_000_000f
            if (elapsedMs > RHYTHM_HIT_EFFECT_MS) continue
            val progress = (elapsedMs / RHYTHM_HIT_EFFECT_MS).coerceIn(0f, 1f)
            drawCircle(
                color = rhythmJudgementColor(effect.judgement).copy(alpha = 0.85f * (1f - progress)),
                radius = laneWidth * (0.18f + 0.30f * progress),
                center = Offset((effect.lane + 0.5f) * laneWidth, geometry.hitLineY),
                style = Stroke(width = effectStrokePx * (1f - progress * 0.35f)),
            )
        }

        drawLine(
            color = Color.White.copy(alpha = 0.82f),
            start = Offset(0f, geometry.hitLineY),
            end = Offset(size.width, geometry.hitLineY),
            strokeWidth = 2f,
        )
    }
}
