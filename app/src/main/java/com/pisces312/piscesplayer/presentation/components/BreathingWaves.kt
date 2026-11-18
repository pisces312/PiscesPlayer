/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §3.5).
 */
package com.pisces312.piscesplayer.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * The soundscape's breathing visualization: one wave group per active track,
 * drawn in a tertiary gradient. Purely parameter-driven (never a spectrum
 * analyzer): the amplitude follows the track volume times master volume and a
 * slow 8 s breath whose visibility scales with the "motion" setting; pausing
 * collapses the waves toward flat, low-saturation lines.
 *
 * The animation is only composed while the page is visible — nothing runs when
 * the composable leaves the composition.
 */
@Composable
fun BreathingWaves(
    trackAmplitudes: List<Float>,
    trackMotions: List<Float>,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val activeColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiary
    val fadedColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer

    val transition = rememberInfiniteTransition(label = "soundscape-waves")
    // Slow breath: one cycle per 8 s, mirrored so the amplitude never jumps.
    val breath by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    // Slow phase drift only while playing: lines flow, they never race.
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
        ),
        label = "drift",
    )

    val strokeWidth = with(LocalDensity.current) { 1.5.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f
        if (trackAmplitudes.isEmpty()) {
            // Idle: a single faint line so the area reads as calm, not broken.
            drawLine(
                color = fadedColor.copy(alpha = 0.4f),
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            return@Canvas
        }

        val groupCount = trackAmplitudes.size
        val groupSpacing = w / (groupCount + 1)
        val maxAmplitude = h * 0.36f

        for ((index, amplitude) in trackAmplitudes.withIndex()) {
            val motion = trackMotions.getOrElse(index) { 0f }
            // Waving amplitude: volume x master breath; motion deepens the swell,
            // and pausing collapses toward a flat line (with residual 15%).
            val liveliness = if (playing) breath * (0.35f + 0.65f * motion) else 0.15f
            val amp = maxAmplitude * amplitude * liveliness
            // Groups spread by index so several tracks read as separate waves.
            val basePhase = drift + index * 1.7f

            for (line in 0 until 3) {
                val lineAlpha = 1f - line * 0.3f
                val lineColor = lerp(activeColor, fadedColor, line / 2f).copy(alpha = lineAlpha)
                val frequency = (2.0 * Math.PI / (w * 0.9)).toFloat() * (1f + line * 0.22f)
                val phase = basePhase + line * 0.55f

                val stepPx = 8f
                var previous: Offset? = null
                var x = 0f
                while (x <= w) {
                    val y = centerY + amp * sin(frequency * x + phase)
                    val current = Offset(x, y)
                    previous?.let { from ->
                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(lineColor, lineColor.copy(alpha = lineAlpha * 0.6f)),
                                start = from,
                                end = current,
                            ),
                            start = from,
                            end = current,
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                    previous = current
                    x += stepPx
                }
            }
        }
    }
}

private fun lerp(a: Color, b: Color, fraction: Float): Color = Color(
    red = a.red + (b.red - a.red) * fraction,
    green = a.green + (b.green - a.green) * fraction,
    blue = a.blue + (b.blue - a.blue) * fraction,
    alpha = a.alpha + (b.alpha - a.alpha) * fraction,
)
