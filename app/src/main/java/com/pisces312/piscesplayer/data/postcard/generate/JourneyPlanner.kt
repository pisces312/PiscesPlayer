/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §5.2, §5.3).
 */
package com.pisces312.piscesplayer.data.postcard.generate

import com.pisces312.piscesplayer.data.postcard.model.PostcardContent
import com.pisces312.piscesplayer.data.postcard.model.PostcardRegionConfig
import com.pisces312.piscesplayer.data.postcard.model.PostcardSeason
import com.pisces312.piscesplayer.data.postcard.model.PostcardTimeOfDay
import com.pisces312.piscesplayer.data.postcard.model.TripPlan
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Pure function from song facts + completion time to a [TripPlan] (design §5.2).
 *
 * One region score — genre and terrain never each pick a region on their own:
 * ```
 * regionScore = genrePrior(region)          // matched genre: 0.65, others 0.05
 *             + terrainPrior(region, facts)  // DSP present: 0..0.30; absent: 0
 *             + stableJitter(region)         // from songSeed: 0..0.05, breaks ties
 * ```
 * Highest score wins; exact ties resolve in [PostcardContent.REGIONS] order so the result is
 * still deterministic. The route is a fixed per-song ordering of the region's spots by
 * `SHA-256(songSeed, regionId, spotIndex)`; it never changes with weather or re-listens.
 */
class JourneyPlanner(
    private val generatorVersion: Int,
) {

    fun plan(facts: SongFacts, completedAtEpochMs: Long, zone: ZoneId): TripPlan {
        val songSeed = StableHash.songSeed(generatorVersion, facts.stableSongKey)
        val region = pickRegion(songSeed, facts)
        val route = orderedRoute(songSeed, region, PostcardContent.routeLengthFor(facts.durationMs))
        val tier = distanceTier(songSeed, facts.bpm)
        val local = Instant.ofEpochMilli(completedAtEpochMs).atZone(zone)

        return TripPlan(
            songSeedHex = StableHash.hex(songSeed),
            regionId = region.id,
            routeSpotIds = route,
            distanceTier = tier,
            baseKm = baseKm(facts.durationMs, tier),
            timeOfDay = timeOfDayAt(local.hour),
            season = seasonAt(local.monthValue),
        )
    }

    /**
     * Re-plan a song whose travel identity is already frozen in `postcard_song_profile`
     * (design §5.1: the region and the full ordered route never move afterwards).
     *
     * Only the per-listen axes are recomputed here — time-of-day and season come from the new
     * completion time, distance from the stored duration and BPM — and [regionId] / [route] are
     * taken verbatim from the profile. A generator upgrade therefore never relocates a familiar
     * song; it only affects songs that have no profile yet.
     */
    fun planWithFrozenRoute(
        facts: SongFacts,
        regionId: String,
        route: List<String>,
        completedAtEpochMs: Long,
        zone: ZoneId,
    ): TripPlan {
        val songSeed = StableHash.songSeed(generatorVersion, facts.stableSongKey)
        val tier = distanceTier(songSeed, facts.bpm)
        val local = Instant.ofEpochMilli(completedAtEpochMs).atZone(zone)
        return TripPlan(
            songSeedHex = StableHash.hex(songSeed),
            regionId = regionId,
            routeSpotIds = route.take(PostcardContent.routeLengthFor(facts.durationMs)),
            distanceTier = tier,
            baseKm = baseKm(facts.durationMs, tier),
            timeOfDay = timeOfDayAt(local.hour),
            season = seasonAt(local.monthValue),
        )
    }

    // ------------------------------------------------------------------ region scoring

    private fun pickRegion(songSeed: ByteArray, facts: SongFacts): PostcardRegionConfig =
        PostcardContent.REGIONS
            .map { region ->
                region to genrePrior(region, facts.genreKey) +
                    terrainPrior(region, facts) +
                    stableJitter(songSeed, region)
            }
            .maxBy { (region, score) -> score } // maxBy keeps the FIRST maximum -> stable tie-break
            .first

    private fun genrePrior(region: PostcardRegionConfig, genreKey: String?): Double {
        if (genreKey == null) return 0.0
        return if (genreKey in region.genreKeys) 0.65 else 0.05
    }

    /**
     * DSP terrain prior (0..0.30, design §5.2). The fixed formulas:
     * - east_gorge:  high ratio * energy          (bright, brisk highs)
     * - white_field: high ratio * (1 - energy)    (cold, calm highs)
     * - mist_valley: low ratio * (1 - energy)     (heavy, slow lows)
     * - the three genre regions score 0 (their genre prior carries them)
     * Any missing DSP input makes the term 0, never a guess.
     */
    private fun terrainPrior(region: PostcardRegionConfig, facts: SongFacts): Double {
        val energy = facts.energy ?: return 0.0
        val high = facts.highRatio ?: return 0.0
        val low = facts.lowRatio ?: return 0.0
        return when (region.id) {
            "east_gorge" -> 0.30 * high * energy
            "white_field" -> 0.30 * high * (1.0 - energy)
            "mist_valley" -> 0.30 * low * (1.0 - energy)
            else -> 0.0
        }
    }

    /** 0..0.05 inclusive, derived only from the song seed (design §5.2 stableJitter). */
    private fun stableJitter(songSeed: ByteArray, region: PostcardRegionConfig): Double =
        StableHash.deriveLongMod(songSeed, 51, "region", region.id) / 1000.0

    // ------------------------------------------------------------------ route & distance

    /**
     * The region's spots ordered by `SHA-256(songSeed, regionId, spotIndex)` (design §5.2),
     * truncated to [length]. spotIndex is the spot's ordinal within the region config, so the
     * ordering is stable per configuration version.
     */
    private fun orderedRoute(
        songSeed: ByteArray,
        region: PostcardRegionConfig,
        length: Int,
    ): List<String> =
        region.spots
            .mapIndexed { index, spot ->
                val order = StableHash.deriveLong(songSeed, "route", region.id, index.toString())
                order to spot.id
            }
            .sortedBy { (order, _) -> order }
            .take(length)
            .map { (_, spotId) -> spotId }

    /** 0 suburb / 1 neighbouring town / 2 far; missing BPM resolves stably from the seed. */
    private fun distanceTier(songSeed: ByteArray, bpm: Float?): Int {
        if (bpm == null) return StableHash.deriveLongMod(songSeed, 3, "tier").toInt()
        return when {
            bpm < 80f -> 0
            bpm < 120f -> 1
            else -> 2
        }
    }

    /** `durationMinutes * (1 + 0.25 * tier)`, one decimal — a fantasy number, never real km. */
    private fun baseKm(durationMs: Long, tier: Int): Float {
        val minutes = durationMs / 60_000.0
        val km = minutes * (1.0 + 0.25 * tier)
        return (km * 10).roundToInt() / 10f
    }

    // ------------------------------------------------------------------ time & season

    /** 05:00..15:59 morning, 16:00..19:59 evening, 20:00..04:59 night (design §5.3). */
    internal fun timeOfDayAt(hour: Int): PostcardTimeOfDay = when (hour) {
        in 5..15 -> PostcardTimeOfDay.MORNING
        in 16..19 -> PostcardTimeOfDay.EVENING
        else -> PostcardTimeOfDay.NIGHT
    }

    /** Mar..May spring, Jun..Aug summer, Sep..Nov autumn, Dec..Feb winter (design §5.3). */
    internal fun seasonAt(month: Int): PostcardSeason = when (month) {
        in 3..5 -> PostcardSeason.SPRING
        in 6..8 -> PostcardSeason.SUMMER
        in 9..11 -> PostcardSeason.AUTUMN
        else -> PostcardSeason.WINTER
    }
}
