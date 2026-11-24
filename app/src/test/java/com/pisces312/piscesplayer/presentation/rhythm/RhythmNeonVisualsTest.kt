/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (neon rhythm visuals: grade mapping, beat grid and combo tiers).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import com.pisces312.piscesplayer.data.rhythm.RhythmTimingPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-function tests for the neon presentation logic: nothing here touches Compose or
 * Android, so the thresholds and the tempo-map math are pinned on the JVM.
 */
class RhythmNeonVisualsTest {

    // ---- Grade mapping: ≥95 S / ≥90 A / ≥80 B / rest C ----

    @Test
    fun gradeThresholds() {
        assertEquals(RhythmGrade.S, rhythmGradeFor(1.0))
        assertEquals(RhythmGrade.S, rhythmGradeFor(0.95))
        assertEquals(RhythmGrade.A, rhythmGradeFor(0.9499))
        assertEquals(RhythmGrade.A, rhythmGradeFor(0.90))
        assertEquals(RhythmGrade.B, rhythmGradeFor(0.8999))
        assertEquals(RhythmGrade.B, rhythmGradeFor(0.80))
        assertEquals(RhythmGrade.C, rhythmGradeFor(0.7999))
        assertEquals(RhythmGrade.C, rhythmGradeFor(0.0))
    }

    // ---- Combo tiers: 30 / 50 / 100 ----

    @Test
    fun comboTierBoundaries() {
        assertEquals(RhythmComboTier.BASE, rhythmComboTier(0))
        assertEquals(RhythmComboTier.BASE, rhythmComboTier(29))
        assertEquals(RhythmComboTier.TIER_30, rhythmComboTier(30))
        assertEquals(RhythmComboTier.TIER_30, rhythmComboTier(49))
        assertEquals(RhythmComboTier.TIER_50, rhythmComboTier(50))
        assertEquals(RhythmComboTier.TIER_50, rhythmComboTier(99))
        assertEquals(RhythmComboTier.TIER_100, rhythmComboTier(100))
        assertEquals(RhythmComboTier.TIER_100, rhythmComboTier(250))
    }

    @Test
    fun comboPulseFiresOnFiftyMilestoneCrossings() {
        assertTrue(rhythmComboPulseCrossed(0, 50))
        assertTrue(rhythmComboPulseCrossed(49, 50))
        // A chord can land two hits in one frame: the milestone must not be skipped.
        assertTrue(rhythmComboPulseCrossed(99, 101))
        assertTrue(rhythmComboPulseCrossed(0, 120))
        assertFalse(rhythmComboPulseCrossed(50, 51))
        assertFalse(rhythmComboPulseCrossed(0, 49))
        // A miss resets the combo — never a pulse.
        assertFalse(rhythmComboPulseCrossed(87, 0))
        assertFalse(rhythmComboPulseCrossed(3, 3))
    }

    // ---- Beat grid from the piecewise tempo map ----

    @Test
    fun emptyTimingYieldsNoBeats() {
        assertTrue(rhythmBeatTimes(emptyList(), 0.0, 2000.0).isEmpty())
    }

    @Test
    fun singleSegmentBeatsFillTheWindowInclusively() {
        val timing = listOf(RhythmTimingPoint(timeMs = 0, beatMs = 500.0))
        assertEquals(
            listOf(0.0, 500.0, 1000.0, 1500.0, 2000.0),
            rhythmBeatTimes(timing, 0.0, 2000.0),
        )
    }

    @Test
    fun windowIsRespectedAndHalfOpenOnSegmentBoundaries() {
        val timing = listOf(RhythmTimingPoint(timeMs = 0, beatMs = 500.0))
        // First beat at or after fromMs, last beat at or before toMs.
        assertEquals(listOf(500.0, 1000.0), rhythmBeatTimes(timing, 250.0, 1250.0))
        // The 1000 boundary beat is emitted exactly once — by the second segment's first beat.
        val twoSegments = listOf(
            RhythmTimingPoint(timeMs = 0, beatMs = 500.0),
            RhythmTimingPoint(timeMs = 1000, beatMs = 250.0),
        )
        assertEquals(
            listOf(0.0, 500.0, 1000.0, 1250.0, 1500.0, 1750.0, 2000.0),
            rhythmBeatTimes(twoSegments, 0.0, 2000.0),
        )
    }

    @Test
    fun tempoChangeSegmentBeatsFollowTheNewSpacing() {
        val timing = listOf(
            RhythmTimingPoint(timeMs = 1000, beatMs = 400.0),
            RhythmTimingPoint(timeMs = 2000, beatMs = 200.0),
        )
        assertEquals(
            listOf(1000.0, 1400.0, 1800.0, 2000.0, 2200.0, 2400.0),
            rhythmBeatTimes(timing, 0.0, 2400.0),
        )
    }

    @Test
    fun degenerateSegmentsAndWindowsProduceNothing() {
        assertEquals(
            emptyList<Double>(),
            rhythmBeatTimes(listOf(RhythmTimingPoint(timeMs = 0, beatMs = 0.0)), 0.0, 100.0),
        )
        assertEquals(
            emptyList<Double>(),
            rhythmBeatTimes(listOf(RhythmTimingPoint(timeMs = 0, beatMs = 500.0)), 2000.0, 100.0),
        )
    }

    @Test
    fun beatPhaseAndPulsePeakOnBeats() {
        val timing = listOf(RhythmTimingPoint(timeMs = 0, beatMs = 500.0))
        assertEquals(0.0, rhythmBeatPhase(timing, 1000.0), 1e-9)
        assertEquals(0.5, rhythmBeatPhase(timing, 250.0), 1e-9)
        assertEquals(1f, rhythmBeatPulse(timing, 0.0), 1e-6f)
        assertEquals(0.25f, rhythmBeatPulse(timing, 250.0), 1e-6f)
        // No usable timing: no pulse at all.
        assertEquals(-1.0, rhythmBeatPhase(emptyList(), 100.0), 1e-9)
        assertEquals(0f, rhythmBeatPulse(emptyList(), 100.0), 1e-6f)
        assertEquals(-1.0, rhythmBeatPhase(timing, -100.0), 1e-9)
    }

    // ---- Note strength → brightness and thickness ----

    @Test
    fun strengthScalesBrightnessAndThickness() {
        // Unknown strength (0, e.g. osu imports) reads as neutral, not as the dimmest note.
        assertEquals(rhythmNoteBrightness(0.5f), rhythmNoteBrightness(0f), 1e-6f)
        assertEquals(rhythmNoteThickness(0.5f), rhythmNoteThickness(0f), 1e-6f)
        assertTrue(rhythmNoteBrightness(0.2f) < rhythmNoteBrightness(0.8f))
        assertTrue(rhythmNoteThickness(0.2f) < rhythmNoteThickness(0.8f))
        assertEquals(1.0f, rhythmNoteBrightness(1f), 1e-6f)
        assertEquals(1.25f, rhythmNoteThickness(1f), 1e-6f)
        // Out-of-range values clamp instead of over-brightening.
        assertEquals(1.0f, rhythmNoteBrightness(2f), 1e-6f)
        // Negative is "unknown" (neutral), not clamped to the floor.
        assertEquals(rhythmNoteBrightness(0f), rhythmNoteBrightness(-3f), 1e-6f)
    }
}
