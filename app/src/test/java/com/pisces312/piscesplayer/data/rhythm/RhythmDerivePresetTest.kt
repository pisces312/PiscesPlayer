/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §7.2, §10;
 * RhythmChartLab/docs/experiments/2026-11-20-handtap-config-fit.md).
 */
package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmDerivePresetTest {

    @Test
    fun v1IsTheDefaultBecauseItMatchesTheFeel() {
        val settings = RhythmDerivePreset.defaultSettings()

        // Feel beats reproducibility for the default (2026-11-20, real-device A/B): v1 hugs the
        // hand-taps and plays on-beat with delay 0, while the classic phase needs added delay.
        // Reproducing the 544-note golden stays CLASSIC_043's job (the legacy fixture test pins
        // it note for note), one tap away but not the default.
        assertEquals(RhythmDerivePreset.V1_GRID_TRACKED, RhythmDerivePreset.match(settings))
        assertEquals(RhythmTimingVariantKey.TRACKED, settings.timingVariant)
        assertEquals(setOf(RhythmDeriveMode.GRID), settings.modes)
    }

    @Test
    fun v1GridTrackedWritesTheHandTapFieldTable() {
        val settings = RhythmDerivePreset.V1_GRID_TRACKED.applyTo(RhythmDeriveSettings())

        assertEquals(RhythmDerivePreset.V1_GRID_TRACKED, RhythmDerivePreset.match(settings))
        assertEquals(RhythmTimingVariantKey.TRACKED, settings.timingVariant)
        assertEquals(setOf(RhythmDeriveMode.GRID), settings.modes)
        assertEquals(OnsetSelection.BALANCED, settings.selection)
        assertEquals(LaneStrategy.BAND_ORDER, settings.laneStrategy)
        assertEquals(2, settings.maxChord)
        assertNull(settings.minRmsPercentile)
        assertNull(settings.maxLocalNps)
    }

    @Test
    fun theBeatFirstVariantsWriteTheirMeasuredFieldTables() {
        val v2 = RhythmDerivePreset.V2_BEAT_BAND_REST0.applyTo(RhythmDeriveSettings())
        assertEquals(setOf(RhythmDeriveMode.BEAT_FIRST), v2.modes)
        assertEquals(BeatDrumGate.BAND, v2.beatDrumGate)
        assertEquals(0.0, v2.beatRestThreshold, 0.0)
        assertNull(v2.beatDensityCap)

        val v3 = RhythmDerivePreset.V3_BEAT_BAND_REST10.applyTo(RhythmDeriveSettings())
        assertEquals(BeatDrumGate.BAND, v3.beatDrumGate)
        assertEquals(0.10, v3.beatRestThreshold, 0.0)

        val v4 = RhythmDerivePreset.V4_BEAT_LOW45_REST10.applyTo(RhythmDeriveSettings())
        assertEquals(BeatDrumGate.LOW_RATIO, v4.beatDrumGate)
        assertEquals(0.10, v4.beatRestThreshold, 0.0)
        assertEquals(0.45, v4.beatLowRatioMin, 0.0)

        // Every variant reproduces its PC matrix row exactly (scripts/pps_matrix.py).
        assertEquals(RhythmDerivePreset.V2_BEAT_BAND_REST0, RhythmDerivePreset.match(v2))
        assertEquals(RhythmDerivePreset.V3_BEAT_BAND_REST10, RhythmDerivePreset.match(v3))
        assertEquals(RhythmDerivePreset.V4_BEAT_LOW45_REST10, RhythmDerivePreset.match(v4))
    }

    @Test
    fun legacyPresetsKeepTheirFrozenFieldTables() {
        val classic = RhythmDerivePreset.CLASSIC_043.applyTo(RhythmDeriveSettings())
        assertEquals(RhythmTimingVariantKey.CLASSIC, classic.timingVariant)
        assertEquals(setOf(RhythmDeriveMode.CLASSIC), classic.modes)
        assertEquals(RhythmDerivePreset.CLASSIC_043, RhythmDerivePreset.match(classic))

        val recommended = RhythmDerivePreset.RECOMMENDED.applyTo(RhythmDeriveSettings())
        assertEquals(setOf(RhythmDeriveMode.ONSET), recommended.modes)
        assertEquals(OnsetSelection.MELODY_FIRST, recommended.selection)
        assertEquals(RhythmDerivePreset.RECOMMENDED, RhythmDerivePreset.match(recommended))
    }

    @Test
    fun anyConfigEditFallsBackToCustomAndRevertingRestoresThePreset() {
        val applied = RhythmDerivePreset.V3_BEAT_BAND_REST10.applyTo(RhythmDeriveSettings())

        val edited = applied.copy(beatRestThreshold = 0.30)
        assertNull(RhythmDerivePreset.match(edited))
        assertEquals(
            RhythmDerivePreset.V3_BEAT_BAND_REST10,
            RhythmDerivePreset.match(edited.copy(beatRestThreshold = 0.10)),
        )

        assertNull(RhythmDerivePreset.match(applied.copy(maxChord = 3)))
        assertNull(RhythmDerivePreset.match(applied.copy(beatDrumGate = BeatDrumGate.OFF)))
    }

    @Test
    fun theDifficultyAxisIsIndependentFromThePreset() {
        val applied = RhythmDerivePreset.V1_GRID_TRACKED.applyTo(RhythmDeriveSettings())

        // A preset is "how to pick and place notes"; the ladder and the custom difficulty are
        // chosen separately and must not knock the preset chip back to custom.
        val halfLadder = applied.copy(difficulties = setOf(DspDifficultySpec.EASY))
        assertEquals(RhythmDerivePreset.V1_GRID_TRACKED, RhythmDerivePreset.match(halfLadder))
        // applyTo must not wipe the difficulty selection either.
        assertEquals(
            setOf(DspDifficultySpec.EASY),
            RhythmDerivePreset.V2_BEAT_BAND_REST0.applyTo(halfLadder).difficulties,
        )

        val custom = applied.copy(customDifficulty = RhythmCustomDifficulty(gridDiv = 8))
        assertEquals(RhythmDerivePreset.V1_GRID_TRACKED, RhythmDerivePreset.match(custom))
    }

    @Test
    fun defaultsAreCustomUntilAPresetIsChosen() {
        assertNull(RhythmDerivePreset.match(RhythmDeriveSettings()))
        assertTrue(RhythmDerivePreset.entries.size >= 6)
    }

    @Test
    fun difficultySpecsCarryBuiltInsThenTheCustomOne() {
        val settings = RhythmDeriveSettings(
            difficulties = setOf(DspDifficultySpec.HARD, DspDifficultySpec.EASY),
            customDifficulty = RhythmCustomDifficulty(name = "", gridDiv = 8),
        )

        val specs = settings.difficultySpecs("Custom")

        assertEquals(
            listOf("Easy", "Hard", "Custom"),
            specs.map { it.label },
        )
        assertEquals(
            listOf("easy", "hard", DspDifficultySpec.KEY_CUSTOM),
            specs.map { it.key },
        )
    }

    @Test
    fun theBuiltInLadderIsTwoRungsEasyAtTwoBeatSlots() {
        // 2026-11-20: three auto levels were too hard to control. Hard is the old Easy density
        // (one slot per beat), Easy is the new sparser rung (one slot per two beats).
        assertEquals(listOf("easy", "hard"), DspDifficultySpec.BUILT_IN.map { it.key })
        assertEquals(2, DspDifficultySpec.EASY.slotBeats)
        assertEquals(1, DspDifficultySpec.HARD.slotBeats)
        assertTrue(DspDifficultySpec.EASY.slotsPerBeat < DspDifficultySpec.HARD.slotsPerBeat)
    }
}
