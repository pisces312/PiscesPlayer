/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (tools/rhythm-lab/docs/2026-11-03-1354-dual-derive-modes-and-segmented-beat-grid.md §8).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmTimingRefinerTest {

    private val beatMs = 500.0

    @Test
    fun followsGradualTempoDrift() {
        // 240 beats whose length shrinks by 0.03 ms each beat: ~1.4% over four minutes, i.e. a
        // realistic BPM estimation error that a global grid can not follow.
        val offset = 100
        val onsets = ArrayList<RhythmTimingRefiner.Onset>(240)
        var time = offset.toDouble()
        var length = beatMs
        repeat(240) {
            onsets += RhythmTimingRefiner.Onset(time.roundToInt(), 1f)
            time += length
            length -= 0.03
        }
        val duration = time.roundToInt() + 2_000

        val refined = RhythmTimingRefiner.refine(
            onsets = onsets,
            global = RhythmTimingPoint(offset, beatMs),
            durationMs = duration,
        )

        assertTrue("drift should produce several segments", refined.size > 1)
        assertTrue("timing must stay sorted", refined.zipWithNext().all { it.first.timeMs < it.second.timeMs })
        assertTrue(
            "local tempo must stay near the detected range",
            refined.all { it.beatMs in beatMs * 0.95..beatMs * 1.005 },
        )

        // Compare the last quarter of the song: the global grid has drifted away, the refined
        // one should still be close to the actual onsets.
        val lateOnsets = onsets.drop(180)
        val globalWorst = lateOnsets.maxOf { gridErrorMs(it.timeMs, listOf(RhythmTimingPoint(offset, beatMs))) }
        val refinedWorst = lateOnsets.maxOf { gridErrorMs(it.timeMs, refined) }
        assertTrue("global grid should have drifted (was $globalWorst ms)", globalWorst > 200.0)
        assertTrue("refined grid should track the drift (was $refinedWorst ms)", refinedWorst < 20.0)
    }

    @Test
    fun perfectGlobalGridStaysUntouched() {
        val onsets = (0 until 100).map {
            RhythmTimingRefiner.Onset(20 + (it * beatMs).roundToInt(), 1f)
        }
        val global = RhythmTimingPoint(20, beatMs)

        val refined = RhythmTimingRefiner.refine(onsets, global, durationMs = 55_000)

        assertEquals(listOf(global), refined)
    }

    @Test
    fun sparseMaterialFallsBackToTheGlobalGrid() {
        val onsets = listOf(
            RhythmTimingRefiner.Onset(1_000, 0.4f),
            RhythmTimingRefiner.Onset(3_000, 0.6f),
        )
        val global = RhythmTimingPoint(0, beatMs)

        assertEquals(listOf(global), RhythmTimingRefiner.refine(onsets, global, durationMs = 20_000))
    }

    @Test
    fun invalidBeatLengthFallsBackToTheGlobalGrid() {
        val global = RhythmTimingPoint(0, 0.0)
        val onsets = (0 until 20).map { RhythmTimingRefiner.Onset(it * 500, 1f) }

        assertEquals(listOf(global), RhythmTimingRefiner.refine(onsets, global, durationMs = 20_000))
    }

    private fun gridErrorMs(timeMs: Int, timing: List<RhythmTimingPoint>): Double {
        val point = timing.lastOrNull { it.timeMs <= timeMs } ?: timing.first()
        val beats = (timeMs - point.timeMs) / point.beatMs
        return abs((beats - Math.round(beats)) * point.beatMs)
    }
}
