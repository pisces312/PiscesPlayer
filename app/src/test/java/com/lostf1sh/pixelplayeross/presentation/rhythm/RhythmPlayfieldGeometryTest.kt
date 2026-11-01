/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype.
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RhythmPlayfieldGeometryTest {
    private val geometry = RhythmPlayfieldGeometry(widthPx = 400f, heightPx = 800f)

    @Test
    fun hitLineSitsAtEightyPercentAndIsTheTopOfTheInputArea() {
        assertEquals(640f, geometry.hitLineY)
        assertEquals(geometry.hitLineY, geometry.inputTopY)
    }

    @Test
    fun anythingBelowTheHitLineIsTappableAndEveryLaneGapIsCovered() {
        // Above the line: no input, so a stray tap on the falling notes does nothing.
        assertNull(geometry.laneAt(50f, 639f))
        assertEquals(0, geometry.laneAt(0f, 640f))
        assertEquals(0, geometry.laneAt(99.9f, 700f))
        assertEquals(1, geometry.laneAt(100f, 700f))
        assertEquals(1, geometry.laneAt(199.9f, 799f))
        assertEquals(2, geometry.laneAt(200f, 700f))
        assertEquals(3, geometry.laneAt(399.9f, 800f))
    }

    @Test
    fun positionsOutsideThePlayfieldDoNotHit() {
        assertNull(geometry.laneAt(-1f, 700f))
        assertNull(geometry.laneAt(400f, 700f))
        assertNull(geometry.laneAt(50f, 801f))
    }

    @Test
    fun aNoteReachesTheHitLineAtItsChartTimeAndEntersAtTheTop() {
        assertEquals(
            640f,
            geometry.noteCenterY(noteTimeMs = 1000, nowMs = 1000.0, approachMs = 1500.0),
        )
        // 1500 ms of fall time = the full distance from the top of the playfield to the line.
        assertEquals(
            0f,
            geometry.noteCenterY(noteTimeMs = 1000, nowMs = -500.0, approachMs = 1500.0),
        )
    }

    @Test
    fun degenerateSizesDoNotDivideByZero() {
        val empty = RhythmPlayfieldGeometry(widthPx = 0f, heightPx = 0f)
        assertNull(empty.laneAt(0f, 0f))
        // A zero approach time must not produce an infinite slope.
        assertEquals(640f, geometry.noteCenterY(1000, 1000.0, approachMs = 0.0))
    }
}
