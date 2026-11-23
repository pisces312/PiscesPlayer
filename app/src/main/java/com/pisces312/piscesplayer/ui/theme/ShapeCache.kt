package com.pisces312.piscesplayer.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * OPT #6 — Cached instances of frequently-used AbsoluteSmoothCornerShape.
 *
 * AbsoluteSmoothCornerShape is significantly more expensive than RoundedCornerShape because it
 * computes cubic Bézier curves analytically. In LazyColumn items (song cards, album cards, etc.)
 * each item pays this cost on first composition. By reusing singleton instances we avoid
 * repeated Path construction for the most common radii.
 *
 * Usage:
 *   Modifier.clip(ShapeCache.smooth12)
 *   Modifier.background(color, ShapeCache.smooth16)
 */
object ShapeCache {
    /** 8dp smooth corners — compact chips, small surfaces */
    val smooth8 = AbsoluteSmoothCornerShape(cornerRadius = 8.dp, smoothnessAsPercent = 60)

    /** 10dp smooth corners */
    val smooth10 = AbsoluteSmoothCornerShape(cornerRadius = 10.dp, smoothnessAsPercent = 60)

    /** 12dp smooth corners — song list items, small cards */
    val smooth12 = AbsoluteSmoothCornerShape(cornerRadius = 12.dp, smoothnessAsPercent = 60)

    /** 14dp smooth corners */
    val smooth14 = AbsoluteSmoothCornerShape(cornerRadius = 14.dp, smoothnessAsPercent = 60)

    /** 16dp smooth corners — album cards, playlist items */
    val smooth16 = AbsoluteSmoothCornerShape(cornerRadius = 16.dp, smoothnessAsPercent = 60)

    /** 20dp smooth corners — larger cards */
    val smooth20 = AbsoluteSmoothCornerShape(cornerRadius = 20.dp, smoothnessAsPercent = 60)

    /** 24dp smooth corners — dialog surfaces */
    val smooth24 = AbsoluteSmoothCornerShape(cornerRadius = 24.dp, smoothnessAsPercent = 60)

    /** 28dp smooth corners */
    val smooth28 = AbsoluteSmoothCornerShape(cornerRadius = 28.dp, smoothnessAsPercent = 60)

    /** 32dp smooth corners — bottom sheets, floating panels */
    val smooth32 = AbsoluteSmoothCornerShape(cornerRadius = 32.dp, smoothnessAsPercent = 60)

    /** Fully smooth (pill) — 50dp, used for buttons and chips */
    val smoothPill = AbsoluteSmoothCornerShape(cornerRadius = 50.dp, smoothnessAsPercent = 60)

    /** M3 Expressive cookie — artist/people avatars and placeholders */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    val expressiveAvatar: Shape = RoundedPolygonShape(MaterialShapes.Cookie9Sided)

    /** M3 Expressive clover — folder/collection icon containers */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    val expressiveClover: Shape = RoundedPolygonShape(MaterialShapes.Clover8Leaf)

    /** M3 Expressive soft burst — empty-state hero icon containers */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    val expressiveHero: Shape = RoundedPolygonShape(MaterialShapes.SoftBurst)

    /**
     * The Serendipity (不期而遇) button is a live jelly: [SERENDIPITY_PHASES] plump blobs on
     * the same 8-vertex topology morph consecutively (the last wraps back to the first)
     * across one loop, addressed by a single 0..1 [serendipityBlob] progress (v1.3.7).
     *
     * The blobs are authored directly in the unit box of the pill's wide rectangle as
     * superellipses — fuller than a circle. The first attempt (v1.3.6) stretched a
     * near-circle to the pill's ~3:1 aspect, which pulled the left/right vertices into
     * pointed "eye" ends. A superellipse base plus heavy smoothing keeps every phase plump
     * at any aspect, while one bulge travels 45° around the outline per phase so the jelly
     * sloshes instead of pulsing in place.
     */
    private const val SERENDIPITY_PHASES = 8

    private val serendipityPhases: Array<RoundedPolygon> by lazy {
        Array(SERENDIPITY_PHASES) { serendipityBlobPolygon(it) }
    }

    /** Consecutive-phase morphs (including the wrap-around pair); Morph needs matching
     *  polygon structure, and all phases share the same 8-vertex convex topology. */
    private val serendipityMorphs: Array<Morph> by lazy {
        Array(SERENDIPITY_PHASES) { p ->
            Morph(serendipityPhases[p], serendipityPhases[(p + 1) % SERENDIPITY_PHASES])
        }
    }

    /** Serendipity button outline at loop [progress] (0..1 = one full jelly revolution). */
    fun serendipityBlob(progress: Float): Shape {
        val scaled = progress.coerceIn(0f, 1f) * SERENDIPITY_PHASES
        val segment = minOf(scaled.toInt(), SERENDIPITY_PHASES - 1)
        // Linear between phases: a continuous slosh, no stop-and-go at each vertex.
        return MorphShape(serendipityMorphs[segment], scaled - segment)
    }

    /**
     * One organic blob in unit-box coordinates (0..1): 8 vertices on a superellipse around
     * the center (power 2/3 — fuller than a circle, softer than a rectangle), one vertex
     * bulging (1.0x, touching the box edge), the opposite one dipping (0.78x), the rest
     * tucked at 0.88x, every corner heavily smoothed. The pattern rotates one vertex (45°)
     * per phase, so the bulge travels around the outline across the loop. All factors stay
     * ≤ 1 so the outline never exceeds the button's bounds.
     */
    private fun serendipityBlobPolygon(phase: Int): RoundedPolygon {
        val n = SERENDIPITY_PHASES
        val power = 2f / 3f
        // graphics-shapes takes the outline as a flat [x0, y0, x1, y1, ...] array.
        val vertices = FloatArray(n * 2) { i ->
            val vertex = i / 2
            val angle = 2f * PI.toFloat() * vertex / n
            val wobble =
                    when ((vertex - phase).mod(n)) {
                        0 -> 1.0f
                        n / 2 -> 0.78f
                        else -> 0.88f
                    }
            val c = cos(angle)
            val s = sin(angle)
            val x = 0.5f + 0.5f * wobble * sign(c) * abs(c).pow(power)
            val y = 0.5f + 0.5f * wobble * sign(s) * abs(s).pow(power)
            if (i % 2 == 0) x else y
        }
        return RoundedPolygon(
                vertices = vertices,
                perVertexRounding = List(n) { CornerRounding(0.35f, smoothing = 1f) },
                centerX = 0.5f,
                centerY = 0.5f
        )
    }
}

/**
 * Singleton-friendly alternative to material3's @Composable RoundedPolygon.toShape():
 * the normalized (0..1 bounds) polygon path is computed once and scaled to the outline size,
 * so the shape can be cached here like the other ShapeCache entries.
 */
private class RoundedPolygonShape(polygon: RoundedPolygon) : Shape {
    private val basePath = polygon.toPath().asComposePath()

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val matrix = Matrix()
        matrix.scale(size.width, size.height)
        val path = Path()
        path.addPath(basePath)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

/**
 * Progress-driven variant of [RoundedPolygonShape] for [androidx.graphics.shapes.Morph]:
 * the interpolated path is regenerated whenever the breathing pulse produces a new
 * [progress], then scaled to the outline size like the static variant.
 */
private class MorphShape(
        private val morph: Morph,
        private val progress: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val matrix = Matrix()
        matrix.scale(size.width, size.height)
        val path = Path()
        path.addPath(morph.toPath(progress).asComposePath())
        path.transform(matrix)
        return Outline.Generic(path)
    }
}
