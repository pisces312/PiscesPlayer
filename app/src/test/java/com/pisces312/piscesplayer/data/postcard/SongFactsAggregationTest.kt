package com.pisces312.piscesplayer.data.postcard.generate

import com.pisces312.piscesplayer.data.rhythm.RhythmNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongFactsAggregationTest {

    private fun note(time: Int, band: Int, rms: Float = 0f, strength: Float = 0f) =
        RhythmNote(id = time, timeMs = time, lane = 0, band = band, rms = rms, strength = strength)

    @Test
    fun rmsPreferredOverStrength() {
        // rms where present wins; the strength-only note still contributes strength.
        val features = SongFactsAggregation.dspFeatures(
            listOf(
                note(0, band = -1, rms = 0.2f, strength = 0.9f),
                note(1, band = -1, rms = 0.6f, strength = 0.1f),
                note(2, band = -1, strength = 0.4f),
            ),
        )
        // usable = [0.2, 0.6, 0.4] -> sorted [0.2, 0.4, 0.6]; P50=0.4, P85=0.6
        // energy = 0.6*0.4 + 0.4*0.6 = 0.48
        assertEquals(0.48f, features.energy!!, 1e-6f)
        assertNull(features.lowRatio)
    }

    @Test
    fun energyUsesP50AndP85NearestRank() {
        val features = SongFactsAggregation.dspFeatures(
            listOf(
                note(0, band = -1, rms = 0.2f),
                note(1, band = -1, rms = 0.4f),
                note(2, band = -1, rms = 0.6f),
                note(3, band = -1, rms = 0.8f),
            ),
        )
        // P50 = sorted[ceil(2)-1] = 0.4; P85 = sorted[ceil(3.4)-1] = 0.8
        // energy = 0.6*0.4 + 0.4*0.8 = 0.56
        assertEquals(0.56f, features.energy!!, 1e-6f)
    }

    @Test
    fun energyIsClampedToUnit() {
        val features = SongFactsAggregation.dspFeatures(
            listOf(note(0, band = -1, rms = 5f), note(1, band = -1, rms = 9f)),
        )
        assertEquals(1.0f, features.energy!!, 1e-6f)
    }

    @Test
    fun allZeroStrengthsYieldNullEnergy() {
        val features = SongFactsAggregation.dspFeatures(
            listOf(note(0, band = 0), note(1, band = 3)),
        )
        assertNull(features.energy)
        // Bands are still valid, so the ratios exist, weighted at the 0.01 floor.
        assertEquals(0.5f, features.lowRatio!!, 1e-6f)
        assertEquals(0.5f, features.midRatio!!, 1e-6f)
    }

    @Test
    fun noValidBandsYieldNullRatios() {
        val features = SongFactsAggregation.dspFeatures(
            listOf(note(0, band = -1, rms = 0.5f)),
        )
        assertEquals(0.5f, features.energy!!, 1e-6f)
        assertNull(features.lowRatio)
        assertNull(features.midRatio)
        assertNull(features.highRatio)
    }

    @Test
    fun bandRatiosWeightByEffectiveStrength() {
        val features = SongFactsAggregation.dspFeatures(
            listOf(
                note(0, band = 0, rms = 0.5f),
                note(1, band = 3, rms = 0.25f),
                note(2, band = 6, strength = 0.25f),
            ),
        )
        assertEquals(0.5f, features.lowRatio!!, 1e-6f)
        assertEquals(0.25f, features.midRatio!!, 1e-6f)
        assertEquals(0.25f, features.highRatio!!, 1e-6f)
    }

    @Test
    fun bandBoundariesAre0to2Low3to5Mid6to7High() {
        val features = SongFactsAggregation.dspFeatures(
            listOf(
                note(0, band = 0, rms = 1f),
                note(1, band = 2, rms = 1f),
                note(2, band = 3, rms = 1f),
                note(3, band = 5, rms = 1f),
                note(4, band = 6, rms = 1f),
                note(5, band = 7, rms = 1f),
            ),
        )
        // 2/6 quantizes to 0.333 at the persisted 1/1000 precision.
        assertEquals(0.333f, features.lowRatio!!, 1e-9f)
        assertEquals(0.333f, features.midRatio!!, 1e-9f)
        assertEquals(0.333f, features.highRatio!!, 1e-9f)
    }

    @Test
    fun floatsAreQuantizedToThousandths() {
        // 3/7 = 0.4285714... -> 0.429
        val features = SongFactsAggregation.dspFeatures(
            listOf(
                note(0, band = 0, rms = 1f),
                note(1, band = 0, rms = 1f),
                note(2, band = 0, rms = 1f),
                note(3, band = 6, rms = 1f),
                note(4, band = 6, rms = 1f),
                note(5, band = 6, rms = 1f),
                note(6, band = 6, rms = 1f),
            ),
        )
        assertEquals(0.429f, features.lowRatio!!, 1e-9f)
        assertEquals(0.571f, features.highRatio!!, 1e-9f)
    }

    @Test
    fun genreAliasesNormalize() {
        assertEquals("folk", SongFactsAggregation.normalizeGenre("民谣"))
        assertEquals("folk", SongFactsAggregation.normalizeGenre("  Folk "))
        assertEquals("classical", SongFactsAggregation.normalizeGenre("CLASSICAL"))
        assertEquals("jazz", SongFactsAggregation.normalizeGenre("Bossa Nova"))
        assertEquals("electronic", SongFactsAggregation.normalizeGenre("Deep House"))
        assertEquals("city_pop", SongFactsAggregation.normalizeGenre("CityPop"))
        assertEquals("rock", SongFactsAggregation.normalizeGenre("Post-Rock"))
        assertEquals("metal", SongFactsAggregation.normalizeGenre("金属"))
    }

    @Test
    fun unknownOrEmptyGenreIsNull() {
        assertNull(SongFactsAggregation.normalizeGenre(null))
        assertNull(SongFactsAggregation.normalizeGenre(""))
        assertNull(SongFactsAggregation.normalizeGenre("  "))
        assertNull(SongFactsAggregation.normalizeGenre("Something Obscure"))
    }
}
