/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §6.1, §6.1.1, §6.1.2, §10.2).
 */
package com.pisces312.piscesplayer.presentation.postcard

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.pisces312.piscesplayer.data.postcard.model.PostcardSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which face of the card to draw. */
enum class PostcardSide { FRONT, BACK }

/**
 * The single drawing entry of the feature (design §10.2 "渲染隔离"): it receives a spec, the
 * side and the canvas it should fill, and nothing else. It knows no Composable state, no
 * repository and no network, so the same code renders the list thumbnail, the detail view and
 * (later) the exported PNG.
 *
 * M0 draws the layered composition with code-only primitives (sky gradient, far/mid/near
 * silhouettes, figure, stamp, caption). Pre-made vector/WebP components (design §6.1.1) replace
 * the silhouette constants in M1 without changing this contract: `spec.far/mid/near` already
 * carry the component ids the art set will be keyed by.
 *
 * Logical canvas is fixed at 1536 × 1024 (3:2, design §6.1) and all geometry below is expressed
 * in that space, so every output size is a pure scale. Every placement is a fixed lattice or a
 * pure function of the spec — no Random() (design §10.2).
 */
object PostcardRenderer {

    const val LOGICAL_WIDTH = 1536f
    const val LOGICAL_HEIGHT = 1024f

    /** Draws [spec]'s [side] filling [scope]'s size, scaled from the logical canvas. */
    fun draw(scope: DrawScope, spec: PostcardSpec, side: PostcardSide) {
        val scale = min(
            scope.size.width / LOGICAL_WIDTH,
            scope.size.height / LOGICAL_HEIGHT,
        )
        val offsetX = (scope.size.width - LOGICAL_WIDTH * scale) / 2f
        val offsetY = (scope.size.height - LOGICAL_HEIGHT * scale) / 2f

        scope.withTransform({
            translate(offsetX, offsetY)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            // Paper base first, so any transparent primitive composites onto card stock.
            drawPaperBase()
            when (side) {
                PostcardSide.FRONT -> drawFront(spec)
                PostcardSide.BACK -> drawBack(spec)
            }
        }
    }

    // ------------------------------------------------------------------ paper

    /** Card stock: warm paper, a soft corner vignette and a thin inner frame line. */
    private fun DrawScope.drawPaperBase() {
        drawRect(color = PAPER, size = Size(LOGICAL_WIDTH, LOGICAL_HEIGHT))
        // Vignette: transparent center fading to a warm shadow at the corners.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color(0xFF4A3F33).copy(alpha = 0.10f)),
                center = Offset(LOGICAL_WIDTH / 2f, LOGICAL_HEIGHT / 2f),
                radius = LOGICAL_WIDTH * 0.72f,
            ),
            size = Size(LOGICAL_WIDTH, LOGICAL_HEIGHT),
        )
        // Hairline inner frame, like the printed border of real card stock.
        drawRoundRect(
            color = Color(0xFF6B5B4B).copy(alpha = 0.16f),
            topLeft = Offset(26f, 26f),
            size = Size(LOGICAL_WIDTH - 52f, LOGICAL_HEIGHT - 52f),
            cornerRadius = CornerRadius(20f, 20f),
            style = Stroke(width = 2.5f),
        )
    }

    // ------------------------------------------------------------------ front

    private fun DrawScope.drawFront(spec: PostcardSpec) {
        val palette = spec.paletteArgb.map(::Color)
        val sky = palette.getOrElse(3) { DEFAULT_SKY }
        val far = palette.getOrElse(1) { DEFAULT_FAR }
        val mid = palette.getOrElse(0) { DEFAULT_MID }
        val near = palette.getOrElse(2) { DEFAULT_NEAR }

        drawSky(spec, skyTop = sky)
        drawFar(spec, far)
        drawMid(spec, mid)
        drawNear(spec, near)
        drawFigure(spec)
        drawStamp(spec)
        drawCaption(spec)
    }

    /** Sky gradient, sun or moon, stars at night, cloud puffs and weather particles. */
    private fun DrawScope.drawSky(spec: PostcardSpec, skyTop: Color) {
        val horizon = LOGICAL_HEIGHT * 0.62f
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    skyTop,
                    lerp(skyTop, PAPER, 0.45f),
                    lerp(skyTop, PAPER, 0.75f),
                    PAPER,
                ),
                startY = 0f,
                endY = horizon,
            ),
            size = Size(LOGICAL_WIDTH, horizon),
        )

        val night = spec.conditionKey.startsWith("night")
        if (night) {
            drawStars(horizon)
        } else if (spec.conditionKey.endsWith("sunny")) {
            drawSun(horizon)
        }

        // Cloud puffs: `cloudLevel + 1` clusters, each three overlapping ellipses.
        val clusters = spec.sky.cloudLevel.coerceIn(0, 2) + 1
        for (index in 0 until clusters) {
            val alpha = 0.14f + 0.05f * index
            val baseY = horizon * (0.16f + 0.20f * index)
            val centerX = LOGICAL_WIDTH * (0.16f + 0.14f * index)
            val height = horizon * 0.05f
            drawOval(
                color = Color.White.copy(alpha = alpha),
                topLeft = Offset(centerX - LOGICAL_WIDTH * 0.16f, baseY),
                size = Size(LOGICAL_WIDTH * 0.32f, height),
            )
            drawOval(
                color = Color.White.copy(alpha = alpha),
                topLeft = Offset(centerX - LOGICAL_WIDTH * 0.05f, baseY - height * 0.35f),
                size = Size(LOGICAL_WIDTH * 0.26f, height * 1.3f),
            )
            drawOval(
                color = Color.White.copy(alpha = alpha),
                topLeft = Offset(centerX + LOGICAL_WIDTH * 0.06f, baseY + height * 0.15f),
                size = Size(LOGICAL_WIDTH * 0.22f, height * 0.8f),
            )
        }

        // Night moon: a disc plus a shadow disc for the phase.
        val moonPhase = spec.sky.moonPhase
        if (moonPhase != null) {
            val center = Offset(LOGICAL_WIDTH * 0.78f, horizon * 0.3f)
            val radius = LOGICAL_WIDTH * 0.035f
            // Soft halo so the moon reads as light, not a cut-out.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFF3F0DC).copy(alpha = 0.35f), Color.Transparent),
                    center = center,
                    radius = radius * 3.2f,
                ),
                radius = radius * 3.2f,
                center = center,
            )
            drawCircle(color = Color(0xFFF3F0DC).copy(alpha = 0.95f), radius = radius, center = center)
            val shift = radius * 2f * (moonPhase / 8f)
            drawCircle(
                color = skyTop.copy(alpha = 0.95f),
                radius = radius,
                center = Offset(center.x + shift, center.y),
            )
        }

        // Weather particles: rain streaks above the ground, snow dots everywhere.
        when {
            spec.conditionKey.endsWith("_rainy") -> drawRain()
            spec.conditionKey.endsWith("_snowy") -> drawSnow()
        }
    }

    /** Daytime sun: a warm radial glow with a crisp disc. */
    private fun DrawScope.drawSun(horizon: Float) {
        val center = Offset(LOGICAL_WIDTH * 0.22f, horizon * 0.26f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFFFF2D0).copy(alpha = 0.55f), Color.Transparent),
                center = center,
                radius = LOGICAL_WIDTH * 0.16f,
            ),
            radius = LOGICAL_WIDTH * 0.16f,
            center = center,
        )
        drawCircle(
            color = Color(0xFFFFF6DE).copy(alpha = 0.95f),
            radius = LOGICAL_WIDTH * 0.028f,
            center = center,
        )
    }

    /** Night stars: a fixed lattice, denser toward the top of the sky. */
    private fun DrawScope.drawStars(horizon: Float) {
        val count = 46
        for (i in 0 until count) {
            val x = LOGICAL_WIDTH * (0.04f + 0.92f * ((i * 37 % 43) / 43f))
            val y = horizon * (0.04f + 0.82f * ((i * 53 % 41) / 41f))
            drawCircle(
                color = Color.White.copy(alpha = 0.30f + 0.38f * ((i % 5) / 5f)),
                radius = 1.8f + (i % 3),
                center = Offset(x, y),
            )
        }
    }

    private fun DrawScope.drawRain() {
        // Deterministic placement: a fixed lattice, no Random() (design §10.2).
        val columns = 26
        for (i in 0 until columns) {
            val x = LOGICAL_WIDTH * (i + 0.5f) / columns
            for (row in 0 until 5) {
                val y = LOGICAL_HEIGHT * 0.66f * ((row + i % 3) / 6f) + (i % 4) * 12f
                drawLine(
                    color = Color.White.copy(alpha = 0.22f),
                    start = Offset(x, y),
                    end = Offset(x - 10f, y + 34f),
                    strokeWidth = 3f,
                )
            }
        }
    }

    private fun DrawScope.drawSnow() {
        val columns = 30
        for (i in 0 until columns) {
            val x = LOGICAL_WIDTH * (i + 0.5f) / columns + ((i % 5) - 2) * 9f
            for (row in 0 until 4) {
                val y = LOGICAL_HEIGHT * 0.66f * ((row + (i % 4) * 0.5f) / 5f)
                drawCircle(
                    color = Color.White.copy(alpha = 0.55f),
                    radius = 4.5f + (i % 3),
                    center = Offset(x, y),
                )
            }
        }
    }

    /** Far layer: two mountain ridges hugging the horizon for aerial perspective. */
    private fun DrawScope.drawFar(spec: PostcardSpec, color: Color) {
        // Back ridge: lighter and lower, so the front ridge reads against it.
        drawRidge(
            peaks = regionPeaks(spec.regionId).asReversed().map { it * 0.55f },
            horizon = LOGICAL_HEIGHT * 0.66f + 8f,
            color = lerp(color, PAPER, 0.30f),
            alpha = 0.35f,
        )
        drawRidge(
            peaks = regionPeaks(spec.regionId),
            horizon = LOGICAL_HEIGHT * 0.66f,
            color = color,
            alpha = 0.55f,
        )
    }

    /** One silhouette ridge across the full width; [peaks] is a fixed per-region lattice. */
    private fun DrawScope.drawRidge(
        peaks: List<Float>,
        horizon: Float,
        color: Color,
        alpha: Float,
    ) {
        val path = Path().apply {
            moveTo(0f, horizon)
            for ((index, height) in peaks.withIndex()) {
                val x = LOGICAL_WIDTH * index / (peaks.size - 1)
                lineTo(x, horizon - height)
            }
            lineTo(LOGICAL_WIDTH, horizon)
            close()
        }
        drawPath(path, color.copy(alpha = alpha))
    }

    /** Mid layer: the region's landmark block shape; M1 swaps in the art-set vector. */
    private fun DrawScope.drawMid(spec: PostcardSpec, color: Color) {
        val base = LOGICAL_HEIGHT * 0.72f
        when (spec.regionId) {
            "east_gorge" -> {
                // Lighthouse: a tapered tower plus a gallery.
                val cx = LOGICAL_WIDTH * 0.34f
                val tower = Path().apply {
                    moveTo(cx - 46f, base)
                    lineTo(cx - 26f, base - 300f)
                    lineTo(cx + 26f, base - 300f)
                    lineTo(cx + 46f, base)
                    close()
                }
                drawPath(tower, color.copy(alpha = 0.9f))
                drawRect(
                    color = color.copy(alpha = 0.75f),
                    topLeft = Offset(cx - 44f, base - 348f),
                    size = Size(88f, 52f),
                )
                // A warm lamp glow at the gallery: the lighthouse is lit.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFFE9B0).copy(alpha = 0.8f), Color.Transparent),
                        center = Offset(cx, base - 322f),
                        radius = 60f,
                    ),
                    radius = 60f,
                    center = Offset(cx, base - 322f),
                )
            }
            "white_field" -> {
                // Lone slope marker with an aurora curtain behind it.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF9BE8D8).copy(alpha = 0.35f), Color.Transparent),
                        startY = base - 420f,
                        endY = base,
                    ),
                    topLeft = Offset(LOGICAL_WIDTH * 0.5f, base - 420f),
                    size = Size(LOGICAL_WIDTH * 0.42f, 420f),
                )
                drawTriangleMarker(base, LOGICAL_WIDTH * 0.3f, color)
            }
            "mist_valley" -> {
                // Valley bridge: an arc between two piers.
                val left = LOGICAL_WIDTH * 0.24f
                val right = LOGICAL_WIDTH * 0.56f
                val arc = Path().apply {
                    moveTo(left, base)
                    quadraticTo((left + right) / 2f, base - 170f, right, base)
                }
                drawPath(arc, color.copy(alpha = 0.9f), style = Stroke(width = 16f))
                drawRect(color.copy(alpha = 0.8f), Offset(left - 14f, base - 60f), Size(28f, 60f))
                drawRect(color.copy(alpha = 0.8f), Offset(right - 14f, base - 60f), Size(28f, 60f))
            }
            "old_town" -> {
                // Clock tower plus two roof triangles.
                drawRect(color.copy(alpha = 0.9f), Offset(LOGICAL_WIDTH * 0.3f, base - 300f), Size(90f, 300f))
                drawTriangleMarker(base - 300f, LOGICAL_WIDTH * 0.3f + 45f, color, width = 120f, height = 60f)
                drawRect(color.copy(alpha = 0.7f), Offset(LOGICAL_WIDTH * 0.46f, base - 190f), Size(150f, 190f))
                drawTriangleMarker(base - 190f, LOGICAL_WIDTH * 0.46f + 75f, color, width = 170f, height = 70f)
                // A lit clock face.
                drawCircle(
                    color = Color(0xFFF6E7C1).copy(alpha = 0.85f),
                    radius = 22f,
                    center = Offset(LOGICAL_WIDTH * 0.3f + 45f, base - 250f),
                )
            }
            "lamp_harbor" -> {
                // Container stacks plus a crane mast.
                for (i in 0 until 3) {
                    drawRect(
                        color.copy(alpha = 0.85f - i * 0.1f),
                        Offset(LOGICAL_WIDTH * (0.24f + i * 0.13f), base - 90f - i * 60f),
                        Size(LogicalSize.containerWidth, 90f + i * 60f),
                    )
                }
                drawRect(color.copy(alpha = 0.8f), Offset(LOGICAL_WIDTH * 0.62f, base - 380f), Size(20f, 380f))
                drawRect(color.copy(alpha = 0.8f), Offset(LOGICAL_WIDTH * 0.62f, base - 380f), Size(160f, 18f))
                // String lights along the crane arm: small warm dots.
                for (i in 0 until 5) {
                    drawCircle(
                        color = Color(0xFFFFD9A0).copy(alpha = 0.8f),
                        radius = 6f,
                        center = Offset(LOGICAL_WIDTH * 0.62f + 28f + i * 28f, base - 368f),
                    )
                }
            }
            else -> {
                // Wild road / unknown: a mesa plus a dead tree.
                drawRect(color.copy(alpha = 0.85f), Offset(LOGICAL_WIDTH * 0.26f, base - 210f), Size(260f, 210f))
                drawRect(color.copy(alpha = 0.7f), Offset(LOGICAL_WIDTH * 0.52f, base - 150f), Size(18f, 150f))
                drawLine(
                    color.copy(alpha = 0.7f),
                    Offset(LOGICAL_WIDTH * 0.53f, base - 140f),
                    Offset(LOGICAL_WIDTH * 0.57f, base - 180f),
                    strokeWidth = 12f,
                )
            }
        }
    }

    /** Near layer: the ground the figure stands on, plus season-driven foliage. */
    private fun DrawScope.drawNear(spec: PostcardSpec, color: Color) {
        val groundTop = LOGICAL_HEIGHT * 0.78f
        val ground = Path().apply {
            moveTo(0f, groundTop + 26f)
            quadraticTo(LOGICAL_WIDTH * 0.3f, groundTop - 18f, LOGICAL_WIDTH * 0.62f, groundTop + 10f)
            quadraticTo(LOGICAL_WIDTH * 0.86f, groundTop + 30f, LOGICAL_WIDTH, groundTop - 6f)
            lineTo(LOGICAL_WIDTH, LOGICAL_HEIGHT)
            lineTo(0f, LOGICAL_HEIGHT)
            close()
        }
        drawPath(ground, color.copy(alpha = 0.95f))

        // Season decoration: a few trees/grass glyphs whose tint follows the season.
        val foliage = seasonFoliage(spec.seasonKey)
        val treeCount = 3
        for (i in 0 until treeCount) {
            val x = LOGICAL_WIDTH * (0.72f + 0.09f * i)
            val trunkHeight = 84f - i * 10f
            drawRect(
                color = Color(0xFF3C3A36).copy(alpha = 0.8f),
                topLeft = Offset(x, groundTop - trunkHeight),
                size = Size(9f, trunkHeight),
            )
            drawCircle(
                color = foliage.copy(alpha = 0.85f),
                radius = 30f - i * 4f,
                center = Offset(x + 4f, groundTop - trunkHeight - 6f),
            )
        }
    }

    private fun DrawScope.drawFigure(spec: PostcardSpec) {
        val groundTop = LOGICAL_HEIGHT * 0.78f
        val cx = LOGICAL_WIDTH * 0.45f
        val footY = groundTop + 8f
        val bodyTop = footY - 132f
        val shadow = Color.Black.copy(alpha = 0.22f)
        // Ground shadow ellipse.
        drawOval(
            color = shadow,
            topLeft = Offset(cx - 34f, footY - 10f),
            size = Size(68f, 20f),
        )
        val ink = Color(0xFF23262B)
        // Body: a rounded coat silhouette.
        drawRoundRect(
            color = ink,
            topLeft = Offset(cx - 22f, bodyTop + 34f),
            size = Size(44f, 74f),
            cornerRadius = CornerRadius(16f, 16f),
        )
        // Head.
        drawCircle(color = ink, radius = 19f, center = Offset(cx, bodyTop + 18f))
        // Legs.
        drawLine(ink, Offset(cx - 10f, bodyTop + 108f), Offset(cx - 14f, footY), strokeWidth = 10f)
        drawLine(ink, Offset(cx + 10f, bodyTop + 108f), Offset(cx + 14f, footY), strokeWidth = 10f)

        when (spec.figure.poseId) {
            "figure_umbrella" -> {
                // Canopy arc + shaft, held on the figure's left.
                val tip = Offset(cx - 54f, bodyTop + 30f)
                val canopy = Path().apply {
                    moveTo(tip.x - 62f, tip.y)
                    quadraticTo(tip.x, tip.y - 58f, tip.x + 62f, tip.y)
                    close()
                }
                drawPath(canopy, Color(0xFF4A5A66).copy(alpha = 0.92f))
                drawLine(Color(0xFF2E3A44), tip, Offset(cx - 30f, footY - 6f), strokeWidth = 6f)
            }
            "figure_snow" -> {
                // Scarf: a warm band across the chest.
                drawRect(
                    color = Color(0xFFB4533C).copy(alpha = 0.9f),
                    topLeft = Offset(cx - 24f, bodyTop + 40f),
                    size = Size(48f, 16f),
                )
            }
            "figure_rest" -> {
                // Hands in pockets: a slight arm line.
                drawLine(ink, Offset(cx - 22f, bodyTop + 56f), Offset(cx - 26f, bodyTop + 84f), strokeWidth = 8f)
                drawLine(ink, Offset(cx + 22f, bodyTop + 56f), Offset(cx + 26f, bodyTop + 84f), strokeWidth = 8f)
            }
            else -> {
                // Walking: one leg forward, one arm swinging.
                drawLine(ink, Offset(cx + 22f, bodyTop + 58f), Offset(cx + 40f, bodyTop + 92f), strokeWidth = 8f)
            }
        }
    }

    /** Postmark box in the upper-right: date + spot name + serial (design §6.1.2). */
    private fun DrawScope.drawStamp(spec: PostcardSpec) {
        val boxWidth = LOGICAL_WIDTH * 0.27f
        val boxHeight = LOGICAL_HEIGHT * 0.15f
        val left = LOGICAL_WIDTH - boxWidth - LOGICAL_WIDTH * 0.05f
        val top = LOGICAL_HEIGHT * 0.055f
        // The whole stamp sits at a slight angle, like a hand-applied postmark.
        withTransform({
            rotate(degrees = -2.2f, pivot = Offset(left + boxWidth / 2f, top + boxHeight / 2f))
        }) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.78f),
                topLeft = Offset(left, top),
                size = Size(boxWidth, boxHeight),
                cornerRadius = CornerRadius(10f, 10f),
            )
            // Perforation: a dashed border reads as the punched edge of a stamp.
            drawRoundRect(
                color = Color(0xFF6B5B4B).copy(alpha = 0.55f),
                topLeft = Offset(left, top),
                size = Size(boxWidth, boxHeight),
                cornerRadius = CornerRadius(10f, 10f),
                style = Stroke(
                    width = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                ),
            )
            // Wavy cancellation lines: the "postmark" gesture, fixed lattice.
            for (i in 0 until 3) {
                val y = top + boxHeight * (0.62f + 0.09f * i)
                drawLine(
                    color = Color(0xFF6B5B4B).copy(alpha = 0.45f),
                    start = Offset(left + 18f, y),
                    end = Offset(left + boxWidth - 18f, y),
                    strokeWidth = 2f,
                )
            }
            drawTextBlock(
                lines = listOf(spec.stamp.date, spec.stamp.spotName, "#${spec.stamp.serial}"),
                left = left + 16f,
                top = top + 12f,
                maxWidth = boxWidth - 32f,
                color = Color(0xFF3A332C),
            )
            // Circular date-stamp overlapping the lower-left corner, half off the stamp.
            drawCircle(
                color = Color(0xFF6B5B4B).copy(alpha = 0.35f),
                radius = boxHeight * 0.42f,
                center = Offset(left + 6f, top + boxHeight - 4f),
                style = Stroke(width = 3f),
            )
        }
    }

    /** Caption block at the bottom: divider, title, artist, distance. */
    private fun DrawScope.drawCaption(spec: PostcardSpec) {
        val left = LOGICAL_WIDTH * 0.055f
        val top = LOGICAL_HEIGHT * 0.855f
        drawLine(
            color = Color(0xFF2B2723).copy(alpha = 0.22f),
            start = Offset(left, top - 16f),
            end = Offset(left + LOGICAL_WIDTH * 0.5f, top - 16f),
            strokeWidth = 2f,
        )
        drawTextBlock(
            lines = listOf(spec.songTitleSnapshot),
            left = left,
            top = top,
            maxWidth = LOGICAL_WIDTH * 0.55f,
            color = Color(0xFF2B2723),
            sizePx = 40f,
            bold = true,
        )
        drawTextBlock(
            lines = listOf(spec.artistSnapshot),
            left = left,
            top = top + 52f,
            maxWidth = LOGICAL_WIDTH * 0.55f,
            color = Color(0xFF5A524A),
            sizePx = 28f,
        )
        drawTextBlock(
            lines = listOf(formatDistance(spec.distanceKm)),
            left = left,
            top = top + 94f,
            maxWidth = LOGICAL_WIDTH * 0.4f,
            color = Color(0xFF5A524A),
            sizePx = 26f,
        )
    }

    // ------------------------------------------------------------------ back

    private fun DrawScope.drawBack(spec: PostcardSpec) {
        val ink = Color(0xFF2B2723)
        // Classic postcard back: message on the left, address side on the right.
        val dividerX = LOGICAL_WIDTH * 0.62f
        drawLine(
            color = Color(0xFF9A9186).copy(alpha = 0.45f),
            start = Offset(dividerX, LOGICAL_HEIGHT * 0.09f),
            end = Offset(dividerX, LOGICAL_HEIGHT * 0.91f),
            strokeWidth = 2.5f,
        )
        // Dashed stamp box on the address side, upper-right.
        drawRect(
            color = Color(0xFF6B5B4B).copy(alpha = 0.45f),
            topLeft = Offset(LOGICAL_WIDTH * 0.68f, LOGICAL_HEIGHT * 0.085f),
            size = Size(LOGICAL_WIDTH * 0.24f, LOGICAL_HEIGHT * 0.14f),
            style = Stroke(
                width = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
            ),
        )
        // Ruled lines on the left (message) side only.
        val lineCount = 7
        val lineTop = LOGICAL_HEIGHT * 0.34f
        val lineGap = LOGICAL_HEIGHT * 0.072f
        for (i in 0 until lineCount) {
            drawLine(
                color = Color(0xFF9A9186).copy(alpha = 0.4f),
                start = Offset(LOGICAL_WIDTH * 0.09f, lineTop + i * lineGap),
                end = Offset(LOGICAL_WIDTH * 0.57f, lineTop + i * lineGap),
                strokeWidth = 2f,
            )
        }
        drawTextBlock(
            lines = listOf(spec.stamp.spotName, spec.stamp.date),
            left = LOGICAL_WIDTH * 0.09f,
            top = LOGICAL_HEIGHT * 0.13f,
            maxWidth = LOGICAL_WIDTH * 0.5f,
            color = ink,
        )
        drawMessageBlock(spec)
    }

    /** Word-wraps the message snapshot over the ruled area. CJK-aware: breaks on any char. */
    private fun DrawScope.drawMessageBlock(spec: PostcardSpec) {
        val maxWidth = LOGICAL_WIDTH * 0.46f
        val charWidth = 26f
        val charsPerLine = max(1, (maxWidth / charWidth).roundToInt())
        val lines = mutableListOf<String>()
        var buffer = StringBuilder()
        for (ch in spec.messageTextSnapshot) {
            buffer.append(ch)
            if (buffer.length >= charsPerLine) {
                lines += buffer.toString()
                buffer = StringBuilder()
            }
        }
        if (buffer.isNotEmpty()) lines += buffer.toString()

        val left = LOGICAL_WIDTH * 0.09f
        val top = LOGICAL_HEIGHT * 0.30f
        val gap = LOGICAL_HEIGHT * 0.072f
        val color = Color(0xFF2B2723)
        for ((index, line) in lines.take(6).withIndex()) {
            drawTextBlock(
                lines = listOf(line),
                left = left,
                top = top + index * gap,
                maxWidth = maxWidth,
                color = color,
            )
        }
        drawTextBlock(
            lines = listOf("— ${spec.songTitleSnapshot} · ${spec.artistSnapshot}"),
            left = left,
            top = top + 6 * gap + 18f,
            maxWidth = maxWidth,
            color = Color(0xFF5A524A),
        )
    }

    // ------------------------------------------------------------------ primitives

    /**
     * Draws text with the platform paint at a size proportional to the logical canvas.
     * Deliberately one block per call so wrapping stays explicit and deterministic.
     *
     * Compose has no text-drawing primitive inside `DrawScope`, so this reaches the native
     * canvas. That is the only platform dependency of the renderer and it is local to this
     * function; exporting PNG (M3) reuses the same call on a bitmap-backed canvas.
     */
    private fun DrawScope.drawTextBlock(
        lines: List<String>,
        left: Float,
        top: Float,
        maxWidth: Float,
        color: Color,
        sizePx: Float = 34f,
        lineGap: Float = 40f,
        bold: Boolean = false,
    ) {
        val androidPaint = android.graphics.Paint().apply {
            this.color = color.toArgb()
            isAntiAlias = true
            textSize = sizePx
            typeface = if (bold) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
        }
        for ((index, line) in lines.withIndex()) {
            // Truncate rather than shrink: the art set fixes the type scale in M1.
            var text: String = line
            while (text.isNotEmpty() && androidPaint.measureText(text) > maxWidth) {
                text = text.dropLast(1)
            }
            if (text.length < line.length && text.isNotEmpty()) text += "…"
            drawContext.canvas.nativeCanvas.drawText(
                text,
                left,
                top + index * lineGap + sizePx,
                androidPaint,
            )
        }
    }

    private fun DrawScope.drawTriangleMarker(
        baseY: Float,
        cx: Float,
        color: Color,
        width: Float = 70f,
        height: Float = 90f,
    ) {
        val path = Path().apply {
            moveTo(cx - width / 2f, baseY)
            lineTo(cx, baseY - height)
            lineTo(cx + width / 2f, baseY)
            close()
        }
        drawPath(path, color.copy(alpha = 0.9f))
    }

    private fun formatDistance(km: Float): String = "${"%.1f".format(java.util.Locale.ROOT, km)} km"

    private fun lerp(start: Color, stop: Color, fraction: Float): Color = Color(
        red = start.red + (stop.red - start.red) * fraction,
        green = start.green + (stop.green - start.green) * fraction,
        blue = start.blue + (stop.blue - start.blue) * fraction,
        alpha = start.alpha + (stop.alpha - start.alpha) * fraction,
    )

    /** Fixed peak lattices per region — different regions read as different skylines. */
    private fun regionPeaks(regionId: String): List<Float> = when (regionId) {
        "east_gorge" -> listOf(40f, 96f, 140f, 88f, 190f, 120f, 60f, 30f)
        "white_field" -> listOf(20f, 44f, 30f, 62f, 40f, 26f, 18f, 12f)
        "mist_valley" -> listOf(180f, 220f, 160f, 240f, 200f, 150f, 120f, 90f)
        "old_town" -> listOf(30f, 52f, 40f, 64f, 44f, 30f, 22f, 18f)
        "lamp_harbor" -> listOf(14f, 20f, 16f, 26f, 18f, 22f, 12f, 10f)
        else -> listOf(70f, 130f, 100f, 150f, 110f, 80f, 50f, 26f)
    }

    private fun seasonFoliage(seasonKey: String): Color = when (seasonKey) {
        "spring" -> Color(0xFF7FA55B)
        "summer" -> Color(0xFF4F7F45)
        "autumn" -> Color(0xFFA9713B)
        else -> Color(0xFF6F7A76)
    }

    private object LogicalSize {
        const val containerWidth = 118f
    }

    private val PAPER = Color(0xFFF6F1E7)
    private val DEFAULT_SKY = Color(0xFFCBDDE8)
    private val DEFAULT_FAR = Color(0xFF8FA9B8)
    private val DEFAULT_MID = Color(0xFF6B7E8C)
    private val DEFAULT_NEAR = Color(0xFF48545C)
}
