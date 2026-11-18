/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §3.3).
 */
package com.pisces312.piscesplayer.data.noise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundscapeSoundsTest {

    @Test
    fun synthEntriesHaveAColorAndNoAsset() {
        val synth = SoundscapeSound.entries.filter { it.isSynth }
        assertEquals(4, synth.size)
        assertTrue(synth.all { it.noiseColor != null })
        assertTrue(synth.all { it.rawRes == null })
    }

    @Test
    fun natureEntriesHaveAnAssetAndNoColor() {
        val nature = SoundscapeSound.entries.filter { !it.isSynth }
        assertEquals(6, nature.size)
        assertTrue(nature.all { it.noiseColor == null })
        assertTrue(nature.all { it.rawRes != null })
        // Each natural sound loops its own recording.
        assertEquals(nature.size, nature.map { it.rawRes }.toSet().size)
    }

    @Test
    fun everyEntryRoundTripsThroughFromIdWithAUniqueId() {
        val ids = SoundscapeSound.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (sound in SoundscapeSound.entries) {
            assertEquals(sound, SoundscapeSound.fromId(sound.id))
        }
        assertNull(SoundscapeSound.fromId("not_a_sound"))
    }

    @Test
    fun expectedNatureIdsExist() {
        for (id in listOf("rain", "wind", "thunder", "fire", "ocean", "crickets")) {
            assertNotNull(id, SoundscapeSound.fromId(id))
        }
    }
}
