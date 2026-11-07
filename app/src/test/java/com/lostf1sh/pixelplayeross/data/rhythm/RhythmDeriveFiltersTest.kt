/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §6, §10).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Test

/** Semantics of the filters that moved from generation time to derivation time. */
class RhythmDeriveFiltersTest {

    private fun note(id: Int, timeMs: Int, strength: Float, rms: Float): RhythmNote =
        RhythmNote(id = id, timeMs = timeMs, lane = 0, strength = strength, band = 0, rms = rms)

    @Test
    fun loudnessKeepsUnknownRmsAndTheBoundary() {
        val notes = listOf(
            note(0, 1_000, strength = 0.5f, rms = 0.1f),
            note(1, 1_100, strength = 0.5f, rms = 0.5f),
            note(2, 1_200, strength = 0.5f, rms = 0f),
        )

        val kept = RhythmDeriveFilters.applyLoudness(notes, threshold = 0.5f)

        assertEquals(listOf(1, 2), kept.map { it.id })
    }

    @Test
    fun densityKeepsTheStrongestFirstInsideCentredWindows() {
        val notes = listOf(
            note(0, 1_000, strength = 0.9f, rms = 0f),
            note(1, 1_100, strength = 0.5f, rms = 0f),
            note(2, 1_600, strength = 0.8f, rms = 0f),
        )

        val kept = RhythmDeriveFilters.applyDensityCap(notes, limit = 1.0)

        // 1000 wins its window; 1600 is exactly 500 ms away, so it wins the next one; 1100 then
        // has two kept neighbours and is dropped. A sliding-window implementation would keep
        // 1600 out instead, which is why this exact set is pinned.
        assertEquals(listOf(1_000, 1_600), kept.map { it.timeMs })
    }

    @Test
    fun loudnessRunsBeforeDensity() {
        val notes = listOf(
            note(0, 0, strength = 10f, rms = 0.1f),
            note(1, 100, strength = 5f, rms = 0.8f),
            note(2, 600, strength = 4f, rms = 0.8f),
        )

        val kept = RhythmDeriveFilters.apply(notes, minRms = 0.5f, maxLocalNps = 1.0)

        // Loudness-first drops the strong but quiet note, so 100 is kept and 600 (exactly 500 ms
        // from it) is dropped. Density-first would keep 0 and 600 instead, then loudness would
        // leave 600 — the two orders are observably different.
        assertEquals(listOf(100), kept.map { it.timeMs })
    }

    @Test
    fun nullFiltersAreOff() {
        val notes = listOf(
            note(0, 1_000, strength = 0.1f, rms = 0f),
            note(1, 1_010, strength = 0.2f, rms = 0f),
        )

        assertEquals(notes, RhythmDeriveFilters.apply(notes, minRms = null, maxLocalNps = null))
    }
}
