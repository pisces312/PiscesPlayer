package com.pisces312.piscesplayer.data.postcard.generate

import com.pisces312.piscesplayer.data.postcard.model.PostcardContent
import com.pisces312.piscesplayer.data.postcard.model.PostcardSeason
import com.pisces312.piscesplayer.data.postcard.model.PostcardTimeOfDay
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyPlannerTest {

    private val planner = JourneyPlanner(generatorVersion = 1)
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun facts(
        key: String = "local:42",
        durationMs: Long = 200_000L,
        bpm: Float? = null,
        genreKey: String? = null,
        energy: Float? = null,
        lowRatio: Float? = null,
        highRatio: Float? = null,
    ) = SongFacts(
        stableSongKey = key,
        librarySongId = 42L,
        title = "Title",
        artist = "Artist",
        durationMs = durationMs,
        bpm = bpm,
        genreKey = genreKey,
        energy = energy,
        lowRatio = lowRatio,
        midRatio = null,
        highRatio = highRatio,
        artworkPaletteArgb = emptyList(),
        artworkBrightness = null,
        masterGeneratedAtMs = null,
    )

    // Completion at 2026-11-14 12:00 (noon, autumn) in Asia/Shanghai.
    private val noon = Instant.parse("2026-11-14T04:00:00Z").toEpochMilli()

    @Test
    fun planIsDeterministicForSameInput() {
        val a = planner.plan(facts(genreKey = "folk"), noon, zone)
        val b = planner.plan(facts(genreKey = "folk"), noon, zone)
        assertEquals(a, b)
    }

    @Test
    fun folkJazzClassicalLeanToOldTown() {
        for (genre in listOf("folk", "classical", "jazz")) {
            assertEquals(
                "genre $genre should target old_town",
                "old_town",
                planner.plan(facts(genreKey = genre), noon, zone).regionId,
            )
        }
    }

    @Test
    fun electronicAndCityPopLeanToLampHarbor() {
        for (genre in listOf("electronic", "city_pop")) {
            assertEquals(
                "lamp_harbor",
                planner.plan(facts(genreKey = genre), noon, zone).regionId,
            )
        }
    }

    @Test
    fun rockAndMetalLeanToWildRoad() {
        for (genre in listOf("rock", "metal")) {
            assertEquals("wild_road", planner.plan(facts(genreKey = genre), noon, zone).regionId)
        }
    }

    @Test
    fun noGenreNoDspStillDeterministic() {
        val a = planner.plan(facts(), noon, zone)
        val b = planner.plan(facts(), noon, zone)
        assertEquals(a, b)
        assertTrue(a.regionId in PostcardContent.REGIONS.map { it.id })
    }

    @Test
    fun brightBriskHighsLeanToEastGorge() {
        val plan = planner.plan(
            facts(energy = 0.9f, highRatio = 0.8f, lowRatio = 0.05f),
            noon,
            zone,
        )
        assertEquals("east_gorge", plan.regionId)
    }

    @Test
    fun coldCalmHighsLeanToWhiteField() {
        val plan = planner.plan(
            facts(energy = 0.1f, highRatio = 0.8f, lowRatio = 0.05f),
            noon,
            zone,
        )
        assertEquals("white_field", plan.regionId)
    }

    @Test
    fun heavySlowLowsLeanToMistValley() {
        val plan = planner.plan(
            facts(energy = 0.1f, highRatio = 0.05f, lowRatio = 0.8f),
            noon,
            zone,
        )
        assertEquals("mist_valley", plan.regionId)
    }

    @Test
    fun genrePriorBeatsTerrain() {
        // Folk plus DSP that would otherwise say east_gorge: genre wins (0.65 vs 0.05+0.30+0.05).
        val plan = planner.plan(
            facts(genreKey = "folk", energy = 0.9f, highRatio = 0.8f),
            noon,
            zone,
        )
        assertEquals("old_town", plan.regionId)
    }

    @Test
    fun routeLengthFollowsDurationBoundaries() {
        // M0 caps every region at 2 spots, so a >=240 s song materializes 2 cards even though
        // routeLengthFor asks for 3 (verified directly in PostcardContentTest). M1's 12 spots
        // restore the 3 without any migration — the take() truncation is the only seam.
        assertEquals(1, planner.plan(facts(durationMs = 119_999L), noon, zone).routeSpotIds.size)
        assertEquals(2, planner.plan(facts(durationMs = 120_000L), noon, zone).routeSpotIds.size)
        assertEquals(2, planner.plan(facts(durationMs = 239_999L), noon, zone).routeSpotIds.size)
        assertEquals(2, planner.plan(facts(durationMs = 240_000L), noon, zone).routeSpotIds.size)
    }

    @Test
    fun routeIsStableAndWithinRegion() {
        val plan = planner.plan(facts(durationMs = 240_000L, genreKey = "folk"), noon, zone)
        val spots = PostcardContent.REGION_BY_ID.getValue("old_town").spots.map { it.id }.toSet()
        assertEquals(2, plan.routeSpotIds.size)
        assertEquals(2, plan.routeSpotIds.toSet().size)
        assertTrue(plan.routeSpotIds.all { it in spots })
        assertEquals(plan.routeSpotIds, planner.plan(facts(genreKey = "folk", durationMs = 240_000L), noon, zone).routeSpotIds)
    }

    @Test
    fun distanceTierBoundaries() {
        assertEquals(0, planner.plan(facts(bpm = 79.999f), noon, zone).distanceTier)
        assertEquals(1, planner.plan(facts(bpm = 80f), noon, zone).distanceTier)
        assertEquals(1, planner.plan(facts(bpm = 119.999f), noon, zone).distanceTier)
        assertEquals(2, planner.plan(facts(bpm = 120f), noon, zone).distanceTier)
    }

    @Test
    fun missingBpmResolvesStablyFromSeed() {
        val a = planner.plan(facts(bpm = null), noon, zone).distanceTier
        val b = planner.plan(facts(bpm = null), noon, zone).distanceTier
        assertEquals(a, b)
        assertTrue(a in 0..2)
    }

    @Test
    fun baseKmFollowsTierAndOneDecimal() {
        // 200 s = 3.333 min; tier 0 -> 3.3, tier 1 -> 4.2, tier 2 -> 5.0.
        assertEquals(3.3f, planner.plan(facts(durationMs = 200_000L, bpm = 60f), noon, zone).baseKm, 1e-6f)
        assertEquals(4.2f, planner.plan(facts(durationMs = 200_000L, bpm = 90f), noon, zone).baseKm, 1e-6f)
        assertEquals(5.0f, planner.plan(facts(durationMs = 200_000L, bpm = 140f), noon, zone).baseKm, 1e-6f)
    }

    @Test
    fun timeOfDayBoundaries() {
        assertEquals(PostcardTimeOfDay.NIGHT, planner.timeOfDayAt(4))
        assertEquals(PostcardTimeOfDay.MORNING, planner.timeOfDayAt(5))
        assertEquals(PostcardTimeOfDay.MORNING, planner.timeOfDayAt(15))
        assertEquals(PostcardTimeOfDay.EVENING, planner.timeOfDayAt(16))
        assertEquals(PostcardTimeOfDay.EVENING, planner.timeOfDayAt(19))
        assertEquals(PostcardTimeOfDay.NIGHT, planner.timeOfDayAt(20))
        assertEquals(PostcardTimeOfDay.NIGHT, planner.timeOfDayAt(0))
    }

    @Test
    fun seasonBoundaries() {
        assertEquals(PostcardSeason.WINTER, planner.seasonAt(12))
        assertEquals(PostcardSeason.WINTER, planner.seasonAt(2))
        assertEquals(PostcardSeason.SPRING, planner.seasonAt(3))
        assertEquals(PostcardSeason.SPRING, planner.seasonAt(5))
        assertEquals(PostcardSeason.SUMMER, planner.seasonAt(6))
        assertEquals(PostcardSeason.SUMMER, planner.seasonAt(8))
        assertEquals(PostcardSeason.AUTUMN, planner.seasonAt(9))
        assertEquals(PostcardSeason.AUTUMN, planner.seasonAt(11))
    }

    @Test
    fun completionTimeDrivesTimeOfDayAndSeason() {
        // 18:30 local on Nov 14 -> evening, autumn.
        val evening = Instant.parse("2026-11-14T10:30:00Z").toEpochMilli()
        val plan = planner.plan(facts(genreKey = "folk"), evening, zone)
        assertEquals(PostcardTimeOfDay.EVENING, plan.timeOfDay)
        assertEquals(PostcardSeason.AUTUMN, plan.season)
    }
}
