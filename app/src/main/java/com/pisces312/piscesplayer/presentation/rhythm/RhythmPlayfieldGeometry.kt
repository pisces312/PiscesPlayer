/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (shared four-lane playfield geometry).
 */
package com.pisces312.piscesplayer.presentation.rhythm

/** The chart format is four-key; lanes and bands map onto this count. */
internal const val RHYTHM_LANE_COUNT = 4

/**
 * Geometry shared by hit testing, the notes and the receptors, so all three agree on where
 * the judgement line is. Pure data: no Compose, no Android, testable on the JVM.
 *
 * [hitLineY] sits at 80% of the playfield height, leaving the bottom fifth as the key area.
 * Everything below the line is tappable — the same forgiving input the PC prototype uses,
 * rather than requiring a hit on a thin strip.
 */
internal data class RhythmPlayfieldGeometry(
    val widthPx: Float,
    val heightPx: Float,
) {
    val laneWidthPx: Float = widthPx / RHYTHM_LANE_COUNT

    val hitLineY: Float = heightPx * 0.80f

    /** Top of the tappable area; the whole lane below the judgement line responds. */
    val inputTopY: Float = hitLineY

    /** Lane under a pointer, or null when the pointer is above the line or outside. */
    fun laneAt(xPx: Float, yPx: Float): Int? {
        if (widthPx <= 0f || heightPx <= 0f) return null
        if (xPx < 0f || xPx >= widthPx || yPx < inputTopY || yPx > heightPx) return null
        return (xPx / laneWidthPx).toInt().coerceIn(0, RHYTHM_LANE_COUNT - 1)
    }

    /**
     * Y of a note centre at song time [nowMs]. A note is at the judgement line exactly at its
     * chart time, and enters at the top of the playfield [approachMs] earlier.
     */
    fun noteCenterY(noteTimeMs: Int, nowMs: Double, approachMs: Double): Float {
        val pxPerMs = hitLineY / approachMs.toFloat().coerceAtLeast(1f)
        return hitLineY - (noteTimeMs - nowMs).toFloat() * pxPerMs
    }
}
