/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §7.6).
 */
package com.pisces312.piscesplayer.data.noise

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization round-trips and preset invariants: whatever the user saves must
 * come back byte-identical through DataStore's JSON string, and the built-in
 * presets must always be present and non-deletable in the merged list.
 */
class SoundscapePresetTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun mixRoundTripsThroughJson() {
        val mix = SoundscapeMix(
            tracks = listOf(
                SoundscapeTrackConfig("brown", volume = 0.55f, toneHz = 7_500f, motionDb = 2f, width = 0.6f),
                SoundscapeTrackConfig("pink", volume = 0.2f),
            ),
            masterVolume = 0.6f,
            durationMinutes = 45,
        )
        assertEquals(mix, json.decodeFromString<SoundscapeMix>(json.encodeToString(mix)))
    }

    @Test
    fun emptyMixRoundTrips() {
        val mix = SoundscapeMix()
        assertEquals(mix, json.decodeFromString<SoundscapeMix>(json.encodeToString(mix)))
    }

    @Test
    fun presetRoundTripsThroughJson() {
        val preset = SoundscapePreset(
            id = 17L,
            name = "雨夜",
            tracks = listOf(SoundscapeTrackConfig("white", volume = 0.3f)),
            masterVolume = 0.4f,
            durationMinutes = SoundscapeMix.DURATION_FOREVER,
        )
        assertEquals(preset, json.decodeFromString<SoundscapePreset>(json.encodeToString(preset)))
    }

    @Test
    fun builtInsAreStableAndNonDeletable() {
        val builtIns = SoundscapePreset.builtIns()
        assertEquals(2, builtIns.size)
        assertTrue(builtIns.all { it.isBuiltIn })
        assertTrue(builtIns.any { it.builtInId == SoundscapePreset.BUILT_IN_BROWN_ONLY })
        assertTrue(builtIns.any { it.builtInId == SoundscapePreset.BUILT_IN_WHITE_MASK })

        val brownOnly = builtIns.first { it.builtInId == SoundscapePreset.BUILT_IN_BROWN_ONLY }
        assertEquals(60, brownOnly.durationMinutes)
        assertEquals(1, brownOnly.tracks.size)
        assertEquals(SoundscapeSound.BROWN.id, brownOnly.tracks[0].soundId)

        val whiteMask = builtIns.first { it.builtInId == SoundscapePreset.BUILT_IN_WHITE_MASK }
        assertEquals(SoundscapeMix.DURATION_FOREVER, whiteMask.durationMinutes)
        assertEquals(0f, whiteMask.tracks[0].motionDb)
    }

    @Test
    fun trackConfigMapsToEngineParams() {
        val config = SoundscapeTrackConfig("deep_brown", volume = 0.8f, toneHz = 5_000f, motionDb = 3f, width = 1f)
        val params = config.toParams()
        assertEquals(NoiseColor.DEEP_BROWN, params.color)
        assertEquals(5_000f, params.toneHz)
        assertEquals(3f, params.motionDb)
        assertEquals(1f, params.width)
        // 0.8 -> -8 dB of track gain.
        assertTrue(Math.abs(params.trackGain() - 0.398f) < 0.01f)
    }

    @Test
    fun unknownSoundIdFallsBackToBrownNotCrash() {
        val params = SoundscapeTrackConfig("future_rain").toParams()
        assertEquals(NoiseColor.BROWN, params.color)
    }

    @Test
    fun presetToMixCarriesAllFields() {
        val preset = SoundscapePreset(
            id = -1L,
            builtInId = SoundscapePreset.BUILT_IN_BROWN_ONLY,
            tracks = SoundscapePreset.builtIns().first().tracks,
            masterVolume = 0.5f,
            durationMinutes = 60,
        )
        val mix = preset.toMix()
        assertEquals(preset.tracks, mix.tracks)
        assertEquals(preset.masterVolume, mix.masterVolume, 0f)
        assertEquals(preset.durationMinutes, mix.durationMinutes)
        assertNull(preset.name)
        assertFalse(preset.id >= 0L)
    }
}
