/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (neon rhythm playfield: glowing notes and judgement line,
 * hit particles, star field and beat pulse — one Canvas layer on the frame clock).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Expanding double-ring lifetime; the outer ring trails the inner one. */
private const val RING_MS = 230f
private const val RING2_DELAY_MS = 55f

/** Particle burst lifetime, per the visual spec (~300 ms). */
private const val PARTICLE_LIFE_MS = 300f

/** Short red lane flash after a miss; also the lifetime the game screen ages misses with. */
internal const val RHYTHM_MISS_EFFECT_MS = 200L

/** Width of the strength-scaled note body at neutral strength. */
private const val NOTE_HEIGHT_DP = 14f

/**
 * Fixed-slot particle pool for hit bursts: 8–12 sparks per hit, hard cap 60 resident, drawn
 * position is a pure function of age so no per-frame state integration or allocation happens.
 * Slots are handed out round-robin, so a burst over the cap recycles the oldest sparks.
 */
internal class RhythmNeonParticleField(val capacity: Int = 60) {
    val active = BooleanArray(capacity)
    val bornNanos = LongArray(capacity)
    val lane = IntArray(capacity)
    /** Burst direction, radians; 0 = right. */
    val angle = FloatArray(capacity)
    /** Launch speed in lane-widths per second. */
    val speed = FloatArray(capacity)
    /** Spark radius as a fraction of lane width. */
    val size = FloatArray(capacity)
    val judgement = arrayOfNulls<RhythmJudgement>(capacity)

    private var cursor = 0
    private var lastSpawnedId = Long.MIN_VALUE
    private var rngState = 0x2545F4914F6CDD1DL

    private fun nextInt(bound: Int): Int {
        rngState = rngState * 6364136223846793005L + 1442695040888963407L
        val bits = (rngState ushr 33).toInt() and 0x7fffffff
        return if (bound <= 0) 0 else bits % bound
    }

    /**
     * Spawn bursts for hit effects not yet seen. Idempotent: the game screen calls this from
     * composition while the effect list lives, and the id watermark makes repeats no-ops.
     */
    fun onFrame(effects: List<RhythmHitEffect>, nowNanos: Long) {
        var newest = lastSpawnedId
        for (effect in effects) {
            if (effect.id <= lastSpawnedId) continue
            spawn(effect, nowNanos)
            if (effect.id > newest) newest = effect.id
        }
        lastSpawnedId = newest
    }

    private fun spawn(effect: RhythmHitEffect, nowNanos: Long) {
        val count = 8 + (effect.id % 5).toInt() // 8..12 sparks per hit
        rngState = rngState xor (effect.id * 0x2545F4914F6CDD1DL)
        repeat(count) {
            val index = cursor
            cursor = (cursor + 1) % capacity
            active[index] = true
            bornNanos[index] = nowNanos
            lane[index] = effect.lane
            // Spread the burst over a full circle, slightly biased upward is unnecessary —
            // a symmetric burst with a small per-spark speed spread reads best.
            angle[index] = (nextInt(3600) / 1800f) * Math.PI.toFloat()
            speed[index] = 1.1f + nextInt(160) / 100f // 1.1 .. 2.7 lane-widths/s
            size[index] = 0.018f + nextInt(14) / 1000f
            judgement[index] = effect.judgement
        }
    }
}

/**
 * Size-keyed cache for the lane key-area gradients: the draw pass is not composable, so the
 * brushes are rebuilt only when the canvas is measured at a new size, not every frame.
 */
private class KeyBrushCache {
    private var lastWidth = -1f
    private var lastHeight = -1f
    private var brushes: List<Brush> = emptyList()

    fun brushesFor(widthPx: Float, heightPx: Float, hitLineY: Float): List<Brush> {
        if (widthPx != lastWidth || heightPx != lastHeight || brushes.isEmpty()) {
            lastWidth = widthPx
            lastHeight = heightPx
            brushes = List(RHYTHM_LANE_COUNT) { lane ->
                Brush.verticalGradient(
                    colors = listOf(
                        RhythmNeonPalette.laneTop[lane].copy(alpha = 0.85f),
                        RhythmNeonPalette.laneTop[lane].copy(alpha = 0.42f),
                        RhythmNeonPalette.laneBottom[lane].copy(alpha = 0.62f),
                    ),
                    startY = hitLineY,
                    endY = heightPx,
                )
            }
        }
        return brushes
    }
}

/**
 * Slowly drifting star field for the neon background. Deterministic (fixed seed), positions are
 * a pure function of song time so the field freezes with the pause like the notes do.
 */
internal class RhythmNeonStarField(count: Int = 44) {
    private val x = FloatArray(count)
    private val y0 = FloatArray(count)
    /** Fraction of the field height per second. */
    private val speed = FloatArray(count)
    private val radiusDp = FloatArray(count)
    private val alpha = FloatArray(count)
    val size: Int = count

    init {
        var state = 0x1BADB002L
        fun next(): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toInt() and 0x7fffffff)
        }
        for (i in 0 until count) {
            x[i] = (next() % 1000) / 1000f
            y0[i] = (next() % 1000) / 1000f
            speed[i] = 0.012f + (next() % 28) / 1000f // ~1–4 % of the field per second
            radiusDp[i] = 0.6f + (next() % 12) / 10f
            alpha[i] = 0.16f + (next() % 30) / 100f
        }
    }

    fun xAt(i: Int, widthPx: Float): Float = x[i] * widthPx

    /** Wrapped vertical position inside `fieldHeightPx` at song time `nowMs`. */
    fun yAt(i: Int, nowMs: Double, fieldHeightPx: Float): Float {
        val travel = (y0[i] + speed[i] * nowMs / 1000.0).toFloat()
        return (travel - kotlin.math.floor(travel)) * fieldHeightPx
    }

    fun radiusPxAt(i: Int, density: Float): Float = radiusDp[i] * density

    fun alphaAt(i: Int): Float = alpha[i]
}

/**
 * The neon playfield: judgement line glow, strength-scaled glowing notes, hit particles,
 * double rings, miss flash, star field and beat pulse — all in one Canvas on the game's
 * [frameTick] clock.
 *
 * Input handling is identical to [RhythmPlayfield] (same [RhythmLaneTouchTracker] lifecycle and
 * [RhythmPlayfieldGeometry.laneAt] gating), so judgement and timing are untouched; only the
 * drawing is different. Everything animated can be switched off with [lowMotion], which keeps
 * the glowing judgement line and the strength-scaled notes.
 */
@Composable
internal fun RhythmPlayfieldNeon(
    session: RhythmPlaySession,
    songMs: Double,
    frameTick: Long,
    touchTracker: RhythmLaneTouchTracker,
    hitEffects: List<RhythmHitEffect>,
    missEffects: List<RhythmHitEffect>,
    effectNowNanos: Long,
    inputEnabled: Boolean,
    lowMotion: Boolean,
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
            label = "rhythm-neon-lane-$lane-press",
        )
    }
    val particleField = remember { RhythmNeonParticleField() }
    val starField = remember { RhythmNeonStarField() }
    val keyBrushCache = remember { KeyBrushCache() }
    // Idempotent per effect id, so re-running composition between frames cannot double-spawn.
    if (!lowMotion) particleField.onFrame(hitEffects, effectNowNanos)

    val density = LocalDensity.current
    val densityPx = density.density
    val noteHeightPx = with(density) { NOTE_HEIGHT_DP.dp.toPx() }
    val notePaddingPx = with(density) { 7.dp.toPx() }
    val noteCornerPx = with(density) { 4.dp.toPx() }
    val glowPadPx = with(density) { 5.dp.toPx() }
    val missBandPx = with(density) { 26.dp.toPx() }

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
        val now = songMs
        val goodWindow = session.config.goodMs

        // Lane key-area gradients depend only on the canvas size — cached, not rebuilt per frame.
        val keyBrushes = keyBrushCache.brushesFor(size.width, size.height, geometry.hitLineY)

        drawRect(RhythmNeonPalette.background)

        for (lane in 0 until RHYTHM_LANE_COUNT) {
            val x = lane * laneWidth
            drawRect(
                color = if (lane % 2 == 0) RhythmNeonPalette.laneEven else RhythmNeonPalette.laneOdd,
                topLeft = Offset(x, 0f),
                size = Size(laneWidth, size.height),
            )
            if (lane > 0) {
                drawLine(
                    color = RhythmNeonPalette.separator.copy(alpha = 0.55f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
            drawRect(
                brush = keyBrushes[lane],
                topLeft = Offset(x, geometry.hitLineY),
                size = Size(laneWidth, keyHeight),
            )
            val press = pressAnimations[lane].value
            if (press > 0f) {
                drawRect(
                    color = Color.White.copy(alpha = 0.20f * press),
                    topLeft = Offset(x, geometry.hitLineY),
                    size = Size(laneWidth, keyHeight),
                )
                drawRect(
                    color = RhythmNeonPalette.laneTop[lane].copy(alpha = 0.16f * press),
                    topLeft = Offset(x, geometry.hitLineY),
                    size = Size(laneWidth, keyHeight),
                )
            }
        }

        // Star field drifts behind the notes, wrapped inside the note area.
        if (!lowMotion) {
            for (i in 0 until starField.size) {
                drawCircle(
                    color = RhythmNeonPalette.star.copy(alpha = starField.alphaAt(i)),
                    radius = starField.radiusPxAt(i, densityPx),
                    center = Offset(starField.xAt(i, size.width), starField.yAt(i, now, geometry.hitLineY)),
                )
            }
        }

        // Beat pulse: a gentle brightness breath over the field and the keys on every beat,
        // computed from the chart's own tempo map (no audio analysis).
        if (!lowMotion) {
            val pulse = rhythmBeatPulse(session.chart.timing, now)
            if (pulse > 0.004f) {
                drawRect(
                    color = Color.White.copy(alpha = 0.020f * pulse),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, geometry.hitLineY),
                )
                drawRect(
                    color = Color.White.copy(alpha = 0.045f * pulse),
                    topLeft = Offset(0f, geometry.hitLineY),
                    size = Size(size.width, keyHeight),
                )
            }
        }

        for (note in session.chart.notes) {
            if (!session.engine.isNotePending(note.id)) continue
            val delta = (note.timeMs - now).toFloat()
            if (delta < -goodWindow || delta > session.approachMs) continue
            val brightness = rhythmNoteBrightness(note.strength)
            val thickness = rhythmNoteThickness(note.strength)
            val noteH = noteHeightPx * thickness
            val noteWidth = laneWidth - notePaddingPx * 2f
            val centerY = geometry.noteCenterY(note.timeMs, now, session.approachMs)
            val topLeft = Offset(note.lane * laneWidth + notePaddingPx, centerY - noteH / 2f)

            // Fade in over the top fifth of the fall, so a note materialises instead of
            // popping in at the top edge.
            val fadeSpan = session.approachMs * 0.2
            val fadeIn = ((session.approachMs - delta) / fadeSpan).coerceIn(0.0, 1.0).toFloat()
            val appear = 0.22f + 0.78f * fadeIn
            val near = abs(delta) <= goodWindow * 1.5f
            val baseColor =
                if (near) RhythmNeonPalette.noteActive else RhythmNeonPalette.laneNote[note.lane]
            val alpha = appear * brightness

            // Outer glow, mid glow, core: three rounded rects approximate a bloom without a
            // per-frame shader or blur pass.
            drawRoundRect(
                color = baseColor.copy(alpha = 0.09f * alpha),
                topLeft = Offset(topLeft.x - glowPadPx, topLeft.y - glowPadPx),
                size = Size(noteWidth + glowPadPx * 2f, noteH + glowPadPx * 2f),
                cornerRadius = CornerRadius(noteCornerPx + glowPadPx),
            )
            drawRoundRect(
                color = baseColor.copy(alpha = 0.20f * alpha),
                topLeft = Offset(topLeft.x - glowPadPx / 2f, topLeft.y - glowPadPx / 2f),
                size = Size(noteWidth + glowPadPx, noteH + glowPadPx),
                cornerRadius = CornerRadius(noteCornerPx + glowPadPx / 2f),
            )
            drawRoundRect(
                color = baseColor.copy(alpha = 0.92f * alpha),
                topLeft = topLeft,
                size = Size(noteWidth, noteH),
                cornerRadius = CornerRadius(noteCornerPx),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.26f * alpha),
                topLeft = topLeft,
                size = Size(noteWidth, noteH * 0.28f),
                cornerRadius = CornerRadius(noteCornerPx),
            )
            if (near) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.22f * appear),
                    topLeft = topLeft,
                    size = Size(noteWidth, noteH),
                    cornerRadius = CornerRadius(noteCornerPx),
                )
            }
        }

        // MISS: a short red flash on the missed track.
        for (effect in missEffects) {
            val elapsedMs = (effectNowNanos - effect.startedAtNanos).coerceAtLeast(0L) / 1_000_000f
            if (elapsedMs > RHYTHM_MISS_EFFECT_MS) continue
            val t = (elapsedMs / RHYTHM_MISS_EFFECT_MS).coerceIn(0f, 1f)
            val x = effect.lane * laneWidth
            drawRect(
                color = RhythmNeonPalette.missFlash.copy(alpha = 0.20f * (1f - t)),
                topLeft = Offset(x, 0f),
                size = Size(laneWidth, size.height),
            )
            drawRect(
                color = RhythmNeonPalette.missFlash.copy(alpha = 0.30f * (1f - t)),
                topLeft = Offset(x, geometry.hitLineY - missBandPx),
                size = Size(laneWidth, missBandPx * 2f + keyHeight * 0.12f),
            )
        }

        // Double expanding ring per hit: the trailing ring reads as a shockwave echo.
        for (effect in hitEffects) {
            val elapsedMs = (effectNowNanos - effect.startedAtNanos).coerceAtLeast(0L) / 1_000_000f
            if (elapsedMs > RING_MS) continue
            val center = Offset((effect.lane + 0.5f) * laneWidth, geometry.hitLineY)
            val glowColor = rhythmJudgementColor(effect.judgement)
            val t1 = (elapsedMs / RING_MS).coerceIn(0f, 1f)
            drawGlowRing(center, laneWidth * (0.16f + 0.34f * t1), glowColor, 1f - t1)
            if (elapsedMs > RING2_DELAY_MS) {
                val t2 = ((elapsedMs - RING2_DELAY_MS) / RING_MS).coerceIn(0f, 1f)
                drawGlowRing(center, laneWidth * (0.12f + 0.30f * t2), glowColor, 0.7f * (1f - t2))
            }
        }

        // Hit sparks from the fixed pool.
        if (!lowMotion) {
            val field = particleField
            for (i in 0 until field.capacity) {
                if (!field.active[i]) continue
                val ageMs = (effectNowNanos - field.bornNanos[i]) / 1_000_000f
                if (ageMs >= PARTICLE_LIFE_MS) {
                    field.active[i] = false
                    continue
                }
                if (ageMs < 0f) continue
                val t = ageMs / PARTICLE_LIFE_MS
                val travel = field.speed[i] * laneWidth * ageMs / 1000f
                val angle = field.angle[i]
                val cx = (field.lane[i] + 0.5f) * laneWidth + kotlin.math.cos(angle) * travel
                val cy = geometry.hitLineY + kotlin.math.sin(angle) * travel * 0.8f
                val sparkColor = field.judgement[i]
                    ?.let { rhythmJudgementColor(it) }
                    ?: RhythmNeonPalette.laneNote[field.lane[i]]
                drawCircle(
                    color = sparkColor.copy(alpha = 0.85f * (1f - t) * (1f - t)),
                    radius = field.size[i] * laneWidth * (1f - 0.5f * t),
                    center = Offset(cx, cy),
                )
            }
        }

        // The judgement line: layered strokes around a white-hot core.
        drawLine(
            color = RhythmNeonPalette.judgementLine.copy(alpha = 0.05f),
            start = Offset(0f, geometry.hitLineY),
            end = Offset(size.width, geometry.hitLineY),
            strokeWidth = with(density) { 18.dp.toPx() },
        )
        drawLine(
            color = RhythmNeonPalette.judgementLine.copy(alpha = 0.12f),
            start = Offset(0f, geometry.hitLineY),
            end = Offset(size.width, geometry.hitLineY),
            strokeWidth = with(density) { 9.dp.toPx() },
        )
        drawLine(
            color = RhythmNeonPalette.judgementLine.copy(alpha = 0.42f),
            start = Offset(0f, geometry.hitLineY),
            end = Offset(size.width, geometry.hitLineY),
            strokeWidth = with(density) { 3.dp.toPx() },
        )
        drawLine(
            color = Color.White.copy(alpha = 0.95f),
            start = Offset(0f, geometry.hitLineY),
            end = Offset(size.width, geometry.hitLineY),
            strokeWidth = 2f,
        )
    }
}

/** One ring: a wide soft stroke plus a thin bright stroke, fading out as it expands. */
private fun DrawScope.drawGlowRing(
    center: Offset,
    radius: Float,
    color: Color,
    lifeAlpha: Float,
) {
    if (radius <= 0f || lifeAlpha <= 0f) return
    drawCircle(
        color = color.copy(alpha = 0.16f * lifeAlpha),
        radius = radius,
        center = center,
        style = Stroke(width = 8f),
    )
    drawCircle(
        color = color.copy(alpha = 0.75f * lifeAlpha),
        radius = radius,
        center = center,
        style = Stroke(width = 2.5f),
    )
}
