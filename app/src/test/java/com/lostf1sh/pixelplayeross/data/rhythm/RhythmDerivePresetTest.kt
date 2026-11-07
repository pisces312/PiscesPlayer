/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §7.2, §10).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmDerivePresetTest {

    @Test
    fun classicPresetWritesTheFrozenFieldTable() {
        val settings = RhythmDerivePreset.CLASSIC_043.applyTo(RhythmDeriveSettings())

        assertEquals(RhythmTimingVariantKey.CLASSIC, settings.timingVariant)
        assertEquals(setOf(RhythmDeriveMode.CLASSIC), settings.modes)
        assertEquals(OnsetSelection.BALANCED, settings.selection)
        assertEquals(LaneStrategy.BAND_ORDER, settings.laneStrategy)
        assertEquals(2, settings.maxChord)
        assertNull(settings.minRmsPercentile)
        assertNull(settings.maxLocalNps)
        assertEquals(DspDifficulty.entries.toSet(), settings.difficulties)
        assertEquals(RhythmDerivePreset.CLASSIC_043, RhythmDerivePreset.match(settings))
    }

    @Test
    fun recommendedPresetWritesTheMeasuredBestCombination() {
        val settings = RhythmDerivePreset.RECOMMENDED.applyTo(RhythmDeriveSettings())

        assertEquals(RhythmTimingVariantKey.TRACKED, settings.timingVariant)
        assertEquals(setOf(RhythmDeriveMode.ONSET), settings.modes)
        assertEquals(OnsetSelection.MELODY_FIRST, settings.selection)
        assertEquals(LaneStrategy.BAND_ORDER, settings.laneStrategy)
        assertEquals(2, settings.maxChord)
        assertNull(settings.minRmsPercentile)
        assertNull(settings.maxLocalNps)
        assertEquals(RhythmDerivePreset.RECOMMENDED, RhythmDerivePreset.match(settings))
    }

    @Test
    fun anyEditFallsBackToCustomAndRevertingRestoresThePreset() {
        val applied = RhythmDerivePreset.RECOMMENDED.applyTo(RhythmDeriveSettings())

        val edited = applied.copy(maxChord = 3)
        assertNull(RhythmDerivePreset.match(edited))
        assertEquals(RhythmDerivePreset.RECOMMENDED, RhythmDerivePreset.match(edited.copy(maxChord = 2)))

        // Difficulties are part of the preset too: half a ladder is not the preset.
        assertNull(RhythmDerivePreset.match(applied.copy(difficulties = setOf(DspDifficulty.EASY))))
    }

    @Test
    fun defaultsAreCustomUntilAPresetIsChosen() {
        assertNull(RhythmDerivePreset.match(RhythmDeriveSettings()))
        assertTrue(RhythmDerivePreset.entries.isNotEmpty())
    }
}
