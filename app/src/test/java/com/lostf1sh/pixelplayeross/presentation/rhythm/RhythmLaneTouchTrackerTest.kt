/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype.
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmLaneTouchTrackerTest {
    @Test
    fun aDuplicateDownFromTheSamePointerDoesNotRetriggerTheLane() {
        val tracker = RhythmLaneTouchTracker()

        assertTrue(tracker.press(10L, 0))
        assertFalse(tracker.press(10L, 1))
        assertEquals(1, tracker.activePointerCount)
        assertTrue(tracker.isLanePressed(0))
        assertFalse(tracker.isLanePressed(1))
    }

    @Test
    fun fourPointersPressIndependently() {
        val tracker = RhythmLaneTouchTracker()

        for (lane in 0 until RHYTHM_LANE_COUNT) {
            assertTrue(tracker.press(lane.toLong(), lane))
        }

        assertArrayEquals(BooleanArray(RHYTHM_LANE_COUNT) { true }, tracker.pressedLanes())
        assertEquals(4, tracker.activePointerCount)
    }

    @Test
    fun releasingOneOfTwoPointersKeepsTheLanePressed() {
        val tracker = RhythmLaneTouchTracker()
        tracker.press(10L, 2)
        tracker.press(11L, 2)

        assertEquals(2, tracker.release(10L))
        assertTrue(tracker.isLanePressed(2))
        assertEquals(1, tracker.activePointerCount)

        assertEquals(2, tracker.release(11L))
        assertFalse(tracker.isLanePressed(2))
        assertNull(tracker.release(11L))
    }

    @Test
    fun clearReleasesEveryLane() {
        val tracker = RhythmLaneTouchTracker()
        tracker.press(10L, 0)
        tracker.press(11L, 3)

        tracker.clear()

        assertEquals(0, tracker.activePointerCount)
        assertArrayEquals(BooleanArray(RHYTHM_LANE_COUNT), tracker.pressedLanes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aLaneOutsideThePlayfieldIsRejected() {
        RhythmLaneTouchTracker().press(10L, RHYTHM_LANE_COUNT)
    }
}
