/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §5.1, §5.3, §6.1.2).
 */
package com.pisces312.piscesplayer.data.postcard.model

import kotlinx.serialization.Serializable

/** Time-of-day bucket derived from the local clock at natural completion (design §5.3). */
@Serializable
enum class PostcardTimeOfDay(val key: String) {
    MORNING("morning"),   // 05:00..15:59
    EVENING("evening"),   // 16:00..19:59
    NIGHT("night");       // 20:00..04:59

    companion object {
        fun fromKey(key: String): PostcardTimeOfDay = entries.first { it.key == key }
    }
}

/** Music-derived fantasy weather — never the real weather (design §5.3). */
@Serializable
enum class PostcardWeather(val key: String) {
    SUNNY("sunny"),
    RAINY("rainy"),
    SNOWY("snowy");

    companion object {
        fun fromKey(key: String): PostcardWeather = entries.first { it.key == key }
    }
}

/**
 * The condition axis of the collection grid: 3 time slots x 3 weather = 9 variants per spot
 * (design §8.4). The key is the persisted string; it participates in the card unique key
 * `(stableSongKey, spotId, conditionKey)`.
 */
@Serializable
data class PostcardCondition(
    val timeOfDay: PostcardTimeOfDay,
    val weather: PostcardWeather,
) {
    val key: String get() = "${timeOfDay.key}_${weather.key}"

    companion object {
        fun fromKey(key: String): PostcardCondition {
            val parts = key.split("_")
            require(parts.size == 2) { "malformed conditionKey $key" }
            return PostcardCondition(
                timeOfDay = PostcardTimeOfDay.fromKey(parts[0]),
                weather = PostcardWeather.fromKey(parts[1]),
            )
        }
    }
}

/** Display-only season axis (design §5.3): decorates plants/sky, never adds collection cells. */
@Serializable
enum class PostcardSeason(val key: String) {
    SPRING("spring"),   // Mar..May
    SUMMER("summer"),   // Jun..Aug
    AUTUMN("autumn"),   // Sep..Nov
    WINTER("winter");   // Dec..Feb

    companion object {
        fun fromKey(key: String): PostcardSeason = entries.first { it.key == key }
    }
}

/** Sky parameters rendered from the condition plus variant-level detail (design §6.1.2). */
@Serializable
data class PostcardSky(
    /** Gradient asset id such as `sky_morning_sunny`. */
    val gradientId: String,
    /** 0..2 cloud density. */
    val cloudLevel: Int,
    /** Moon phase 0..7; null unless the condition is night. */
    val moonPhase: Int? = null,
)

/** Traveller silhouette pose, decided by weather and BPM (design §6.1.2 figure). */
@Serializable
data class PostcardFigure(
    val poseId: String,
)

/** Postmark printed on the card front (design §6.1.2 stamp). */
@Serializable
data class PostcardStamp(
    /** `yyyy-MM-dd` in the first-visit zone; never a localized date string. */
    val date: String,
    val spotName: String,
    /** 1..9999, derived from cardKey — deliberately not a database row id. */
    val serial: Int,
)

/**
 * The complete deterministic recipe of one postcard face pair, persisted as JSON
 * (~1-4 KB). Versioning is three independent knobs (design §6.1.2):
 * [specVersion] JSON structure, [generatorVersion] selection rules,
 * [rendererContentVersion] component content.
 *
 * Serialization rules: fixed enum keys and RGB ints only — no Compose `Color`, no `Bitmap`,
 * no cover URI, no resource references, no localized sentences (the rendered snapshot text
 * is [messageTextSnapshot]).
 */
@Serializable
data class PostcardSpec(
    val specVersion: Int,
    val generatorVersion: Int,
    val rendererContentVersion: Int,
    /** Human-opaque unique key: `stableSongKey \u001F spotId \u001F conditionKey`. */
    val cardKey: String,
    val songSeedHash: String,
    val spotId: String,
    val regionId: String,
    val conditionKey: String,
    val seasonKey: String,
    val sky: PostcardSky,
    val far: String,
    val mid: String,
    val near: String,
    val figure: PostcardFigure,
    val componentIds: List<String>,
    val layoutVariant: Int,
    val paletteSource: String,
    val paletteArgb: List<Int>,
    val stamp: PostcardStamp,
    val distanceKm: Float,
    val messageTemplateId: String,
    val messageArgs: Map<String, String>,
    val messageTextSnapshot: String,
    val songTitleSnapshot: String,
    val artistSnapshot: String,
    val firstCreatedAtEpochMs: Long,
    val firstCreatedAtZoneId: String,
    val stampSerial: Int,
)

/** Where the card palette came from (design §5.4). */
object PostcardPaletteSource {
    const val ARTWORK = "ARTWORK"
    const val FALLBACK = "FALLBACK"
}

/** Version knobs shared by the M0 pipeline. */
object PostcardVersions {
    const val SPEC = 1
    const val GENERATOR = 1
    const val RENDERER_CONTENT = 1
}

/**
 * Result of [com.pisces312.piscesplayer.data.postcard.generate.JourneyPlanner]: everything the
 * composer needs that is *independent* of weather/condition (design §5.2). The route is frozen
 * per song; condition inputs are evaluated at completion time.
 */
data class TripPlan(
    val songSeedHex: String,
    val regionId: String,
    /** 1..3 spot ids in fixed route order (M0: no camera, so never 4). */
    val routeSpotIds: List<String>,
    /** 0 suburb, 1 neighbouring town, 2 far (design §5.2 distanceTier). */
    val distanceTier: Int,
    /** Displayed km with one decimal; fantasy value, not real distance. */
    val baseKm: Float,
    val timeOfDay: PostcardTimeOfDay,
    val season: PostcardSeason,
)
