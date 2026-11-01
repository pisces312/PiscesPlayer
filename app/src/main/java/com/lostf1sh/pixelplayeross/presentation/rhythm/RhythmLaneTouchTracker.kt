/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (multi-touch lane tracking).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

/**
 * Touch lifecycle and lane mapping, deliberately free of judgement logic.
 *
 * A pointer locks to one lane on down and only releases itself on up/cancel, so two fingers on
 * two lanes produce two independent presses — required now that charts can contain double and
 * triple notes. A second finger landing on an already pressed lane does not produce a second
 * judgement.
 */
internal class RhythmLaneTouchTracker(private val laneCount: Int = RHYTHM_LANE_COUNT) {
    private val pointerToLane = mutableMapOf<Long, Int>()
    private val pointersByLane = Array(laneCount) { mutableSetOf<Long>() }

    /** True when the pointer newly locked a lane; a repeated down returns false. */
    fun press(pointerId: Long, lane: Int): Boolean {
        require(lane in 0 until laneCount) { "lane $lane out of range" }
        if (pointerToLane.containsKey(pointerId)) return false
        pointerToLane[pointerId] = lane
        pointersByLane[lane] += pointerId
        return true
    }

    /** Lane released by this pointer, or null when the pointer was unknown. */
    fun release(pointerId: Long): Int? {
        val lane = pointerToLane.remove(pointerId) ?: return null
        pointersByLane[lane] -= pointerId
        return lane
    }

    fun isLanePressed(lane: Int): Boolean = pointersByLane[lane].isNotEmpty()

    fun pressedLanes(): BooleanArray = BooleanArray(laneCount) { isLanePressed(it) }

    val activePointerCount: Int get() = pointerToLane.size

    fun clear() {
        pointerToLane.clear()
        pointersByLane.forEach { it.clear() }
    }
}
