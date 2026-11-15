/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §0.3, §5.3, §5.4, §6.1.2, §6.2).
 */
package com.pisces312.piscesplayer.data.postcard.generate

import com.pisces312.piscesplayer.data.postcard.model.PostcardCondition
import com.pisces312.piscesplayer.data.postcard.model.PostcardContent
import com.pisces312.piscesplayer.data.postcard.model.PostcardFigure
import com.pisces312.piscesplayer.data.postcard.model.PostcardPaletteSource
import com.pisces312.piscesplayer.data.postcard.model.PostcardSky
import com.pisces312.piscesplayer.data.postcard.model.PostcardSpec
import com.pisces312.piscesplayer.data.postcard.model.PostcardStamp
import com.pisces312.piscesplayer.data.postcard.model.PostcardTimeOfDay
import com.pisces312.piscesplayer.data.postcard.model.PostcardVersions
import com.pisces312.piscesplayer.data.postcard.model.PostcardWeather
import com.pisces312.piscesplayer.data.postcard.model.TripPlan
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure function from a [TripPlan] plus the song facts to 1..3 [PostcardSpec]s (M0 has no
 * camera, so never 4, design §6.3). Everything a card face shows is decided here and frozen
 * into the spec; rendering only draws it.
 *
 * Seed discipline (design §0.3):
 * - `weatherSeed = SHA-256(songSeed, spotId, "weather")` — only the stable fallback of the
 *   base weather, so weather never depends on the not-yet-computed conditionKey;
 * - `variantSeed = SHA-256(songSeed, spotId, conditionKey, rendererContentVersion)` — sky
 *   detail, component variants and message choice. Luggage ids never enter card seeds.
 */
class PostcardComposer(
    private val generatorVersion: Int,
    private val rendererContentVersion: Int,
) {

    /** One spec per route spot, in route order. */
    fun compose(
        facts: SongFacts,
        plan: TripPlan,
        firstCreatedAtEpochMs: Long,
        zoneId: ZoneId,
    ): List<PostcardSpec> {
        val region = requireNotNull(PostcardContent.REGION_BY_ID[plan.regionId]) {
            "unknown region ${plan.regionId}"
        }
        val songSeed = StableHash.songSeed(generatorVersion, facts.stableSongKey)
        val distanceText = "%.1f".format(Locale.ROOT, plan.baseKm)

        return plan.routeSpotIds.map { spotId ->
            val spot = requireNotNull(region.spotById(spotId)) {
                "spot $spotId not configured in region ${region.id}"
            }
            val weatherSeed = StableHash.deriveSeed(songSeed, spotId, "weather")
            val weather = baseWeather(facts, plan.timeOfDay, weatherSeed)
            val condition = PostcardCondition(timeOfDay = plan.timeOfDay, weather = weather)
            val variantSeed = StableHash.deriveSeed(
                songSeed, spotId, condition.key, rendererContentVersion.toString(),
            )
            val cardKey = cardKeyOf(facts.stableSongKey, spotId, condition.key)

            val message = pickMessage(variantSeed, region.id, weather)
            val stampSerial = stampSerialOf(cardKey)
            val figure = figureOf(weather, facts.bpm, weatherSeed)

            PostcardSpec(
                specVersion = PostcardVersions.SPEC,
                generatorVersion = generatorVersion,
                rendererContentVersion = rendererContentVersion,
                cardKey = cardKey,
                songSeedHash = StableHash.hex(songSeed),
                spotId = spotId,
                regionId = region.id,
                conditionKey = condition.key,
                seasonKey = plan.season.key,
                sky = skyOf(variantSeed, condition),
                far = region.farComponentId,
                mid = spot.midComponentId,
                near = spot.nearComponentId,
                figure = figure,
                componentIds = listOf(
                    region.farComponentId,
                    spot.midComponentId,
                    spot.nearComponentId,
                    skyGradientId(condition),
                    figure.poseId,
                ),
                layoutVariant = StableHash.deriveLongMod(variantSeed, 4, "layout").toInt(),
                paletteSource = paletteSourceOf(facts),
                paletteArgb = paletteOf(facts, region),
                stamp = PostcardStamp(
                    date = STAMP_DATE_FORMAT
                        .format(java.time.Instant.ofEpochMilli(firstCreatedAtEpochMs).atZone(zoneId)),
                    spotName = "${region.nameZh}·${spot.nameZh}",
                    serial = stampSerial,
                ),
                distanceKm = plan.baseKm,
                messageTemplateId = message.id,
                messageArgs = linkedMapOf("distance" to distanceText),
                messageTextSnapshot = message.text.replace("{distance}", distanceText),
                songTitleSnapshot = facts.title,
                artistSnapshot = facts.artist,
                firstCreatedAtEpochMs = firstCreatedAtEpochMs,
                firstCreatedAtZoneId = zoneId.id,
                stampSerial = stampSerial,
            )
        }
    }

    // ------------------------------------------------------------------ weather (design §5.3)

    /**
     * Music-derived fantasy weather, evaluated per spot. Branch order is fixed:
     * 1. `energy < 0.35` and night -> snow;
     * 2. `energy < 0.25` and `hash(weatherSeed, "snow") % 4 == 0` -> snow;
     * 3. `highRatio >= 0.45` -> sunny;
     * 4. `lowRatio >= 0.45` -> rainy;
     * 5. otherwise the weatherSeed picks stably between sun and rain (a 1/3 bias toward rain) —
     *    this is also the entire path for songs without DSP data, which therefore never roll
     *    snow (umbrella luggage in M1 is the explicit way to force rain).
     */
    internal fun baseWeather(
        facts: SongFacts,
        timeOfDay: PostcardTimeOfDay,
        weatherSeed: ByteArray,
    ): PostcardWeather {
        val energy = facts.energy
        if (energy != null) {
            if (energy < 0.35f && timeOfDay == PostcardTimeOfDay.NIGHT) return PostcardWeather.SNOWY
            if (energy < 0.25f && StableHash.deriveLongMod(weatherSeed, 4, "snow") == 0L) {
                return PostcardWeather.SNOWY
            }
        }
        if (facts.highRatio != null && facts.highRatio >= 0.45f) return PostcardWeather.SUNNY
        if (facts.lowRatio != null && facts.lowRatio >= 0.45f) return PostcardWeather.RAINY
        return if (StableHash.deriveLongMod(weatherSeed, 3, "fallback") == 0L) {
            PostcardWeather.RAINY
        } else {
            PostcardWeather.SUNNY
        }
    }

    // ------------------------------------------------------------------ sky & figure

    private fun skyOf(variantSeed: ByteArray, condition: PostcardCondition): PostcardSky {
        val cloudLevel = StableHash.deriveLongMod(variantSeed, 3, "cloud").toInt()
        val moonPhase = if (condition.timeOfDay == PostcardTimeOfDay.NIGHT) {
            StableHash.deriveLongMod(variantSeed, 8, "moon").toInt()
        } else {
            null
        }
        return PostcardSky(
            gradientId = skyGradientId(condition),
            cloudLevel = cloudLevel,
            moonPhase = moonPhase,
        )
    }

    private fun skyGradientId(condition: PostcardCondition): String =
        "sky_${condition.timeOfDay.key}_${condition.weather.key}"

    /** 4 poses (design §6.1.2): umbrella in rain, snow stance in snow, then BPM-driven. */
    private fun figureOf(weather: PostcardWeather, bpm: Float?, weatherSeed: ByteArray): PostcardFigure {
        val poseId = when (weather) {
            PostcardWeather.RAINY -> "figure_umbrella"
            PostcardWeather.SNOWY -> "figure_snow"
            PostcardWeather.SUNNY -> when {
                bpm != null && bpm >= 110f -> "figure_walk"
                bpm != null -> "figure_rest"
                else -> if (StableHash.deriveLongMod(weatherSeed, 2, "figure") == 0L) {
                    "figure_walk"
                } else {
                    "figure_rest"
                }
            }
        }
        return PostcardFigure(poseId = poseId)
    }

    // ------------------------------------------------------------------ palette (design §5.4)

    /**
     * M0: artwork quantization is not implemented yet (M1), so live inputs always fall back to
     * the region palette; the ARTWORK branch stays for the frozen-profile format and tests.
     * Below 4 colors the fixed neutral ramp fills up — deterministic, never a new hue.
     */
    private fun paletteSourceOf(facts: SongFacts): String =
        if (facts.artworkPaletteArgb.isEmpty()) PostcardPaletteSource.FALLBACK
        else PostcardPaletteSource.ARTWORK

    private fun paletteOf(facts: SongFacts, region: com.pisces312.piscesplayer.data.postcard.model.PostcardRegionConfig): List<Int> {
        if (facts.artworkPaletteArgb.isEmpty()) {
            return region.fallbackPaletteArgb
        }
        val artwork = facts.artworkPaletteArgb.take(5)
        val filled = artwork + NEUTRAL_FILL.filterNot { candidate -> artwork.contains(candidate) }
        return filled.take(4.coerceAtLeast(artwork.size))
    }

    /** Neutral ramp appended when an artwork palette has fewer than 4 entries. */
    private val NEUTRAL_FILL = listOf(0xFF707070.toInt(), 0xFFA0A0A0.toInt(), 0xFFD0D0D0.toInt())

    // ------------------------------------------------------------------ message (design §6.2)

    /** Deterministic template pick: exact region x weather matches plus the generic pool. */
    private fun pickMessage(variantSeed: ByteArray, regionId: String, weather: PostcardWeather) =
        PostcardContent.MESSAGE_TEMPLATES
            .filter { template ->
                (template.regionId == regionId && template.weather == weather) ||
                    (template.regionId == null && template.weather == null)
            }
            .sortedBy { it.id }
            .let { candidates ->
                candidates[StableHash.deriveLongMod(variantSeed, candidates.size.toLong(), "message").toInt()]
            }

    // ------------------------------------------------------------------ keys & serial

    private fun cardKeyOf(stableSongKey: String, spotId: String, conditionKey: String): String =
        "$stableSongKey\u001F$spotId\u001F$conditionKey"

    /** 1..9999 derived from the cardKey — deliberately not a database id (design §6.1.2). */
    private fun stampSerialOf(cardKey: String): Int =
        StableHash.deriveLongMod(StableHash.sha256(cardKey), 9999, "serial").toInt() + 1

    private companion object {
        val STAMP_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    }
}
