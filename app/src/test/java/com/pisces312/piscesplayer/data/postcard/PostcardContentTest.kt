package com.pisces312.piscesplayer.data.postcard.generate

import com.pisces312.piscesplayer.data.postcard.model.PostcardContent
import com.pisces312.piscesplayer.data.postcard.model.PostcardWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostcardContentTest {

    @Test
    fun sixRegionsWithUniqueIdsAndNames() {
        assertEquals(6, PostcardContent.REGIONS.size)
        assertEquals(6, PostcardContent.REGIONS.map { it.id }.toSet().size)
        assertEquals(6, PostcardContent.REGIONS.map { it.nameZh }.toSet().size)
    }

    @Test
    fun m0ShipsTwoSpotsPerRegionWithRegionPrefixedIds() {
        for (region in PostcardContent.REGIONS) {
            assertEquals("M0 region ${region.id} must ship 2 spots", 2, region.spots.size)
            for (spot in region.spots) {
                assertTrue(
                    "spot id ${spot.id} must start with region prefix",
                    spot.id.startsWith("${region.id}_"),
                )
            }
        }
    }

    @Test
    fun allSpotIdsUniqueAcrossRegions() {
        val all = PostcardContent.REGIONS.flatMap { it.spots.map { s -> s.id } }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun spotIdsMatchTwelveSpotZeroPaddedFormat() {
        for (region in PostcardContent.REGIONS) {
            for (spot in region.spots) {
                val ordinal = spot.id.removePrefix("${region.id}_")
                assertTrue(
                    "spot ordinal $ordinal must be zero-padded 01..12",
                    ordinal.length == 2 && ordinal.toInt() in 1..12,
                )
            }
        }
    }

    @Test
    fun everyRegionHasAFallbackPaletteOfAtLeastFourColors() {
        for (region in PostcardContent.REGIONS) {
            assertTrue(
                "region ${region.id} fallback palette needs >= 4 colors",
                region.fallbackPaletteArgb.size >= 4,
            )
        }
    }

    @Test
    fun messageTemplatesCoverEveryRegionWeatherPairPlusGeneric() {
        val pool = PostcardContent.MESSAGE_TEMPLATES
        for (region in PostcardContent.REGIONS) {
            for (weather in PostcardWeather.entries) {
                assertTrue(
                    "missing template for ${region.id}/${weather.key}",
                    pool.any { it.regionId == region.id && it.weather == weather },
                )
            }
        }
        assertTrue(pool.count { it.regionId == null } >= 2)
        // Every template must reference the distance placeholder.
        for (template in pool) {
            assertTrue("template ${template.id} lacks {distance}", "{distance}" in template.text)
        }
    }

    @Test
    fun routeLengthBoundaries() {
        assertEquals(1, PostcardContent.routeLengthFor(119_999L))
        assertEquals(2, PostcardContent.routeLengthFor(120_000L))
        assertEquals(2, PostcardContent.routeLengthFor(239_999L))
        assertEquals(3, PostcardContent.routeLengthFor(240_000L))
    }
}
