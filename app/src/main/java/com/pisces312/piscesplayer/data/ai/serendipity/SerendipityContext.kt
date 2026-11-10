package com.pisces312.piscesplayer.data.ai.serendipity

import java.time.DayOfWeek

/**
 * Everything Serendipity knows about "right now".
 *
 * Every optional signal is nullable on purpose: a signal that could not be read is dropped from
 * the prompt instead of being guessed or replaced by a placeholder, so the user can see in the
 * sheet exactly what the mix was based on.
 *
 * Coordinates never appear here — the location is only ever turned into a city name.
 */
data class SerendipityContext(
    val weekday: DayOfWeek,
    val clockTime: String,
    val timeOfDay: SerendipityTimeOfDay,
    /**
     * Place name for the prompt, which is English end to end; a district carries its city with it
     * ("Huangpu, Shanghai").
     */
    val city: String? = null,
    /** Place name as the user should see it: "黄浦区 · 上海" rather than "Huangpu, Shanghai". */
    val cityLabel: String? = null,
    val weather: SerendipityWeather? = null,
    val stepsToday: Int? = null
)

/** Weather at city granularity; the raw coordinates stay inside the collector. */
data class SerendipityWeather(val group: SerendipityWeatherGroup, val temperatureC: Int)

enum class SerendipityTimeOfDay {
    MORNING,
    AFTERNOON,
    EVENING,
    LATE_NIGHT;

    companion object {
        fun at(hour: Int): SerendipityTimeOfDay =
                when (hour) {
                    in 5..11 -> MORNING
                    in 12..16 -> AFTERNOON
                    in 17..21 -> EVENING
                    else -> LATE_NIGHT
                }
    }
}

/**
 * Coarse buckets of the WMO weather codes Open-Meteo returns.
 *
 * Deliberately coarser than the code list: "drizzle" and "rain showers" would not change which
 * songs fit, and every extra bucket costs two more translated labels.
 */
enum class SerendipityWeatherGroup {
    CLEAR,
    CLOUDY,
    FOG,
    RAIN,
    SNOW,
    THUNDERSTORM;

    companion object {
        fun fromWmoCode(code: Int): SerendipityWeatherGroup =
                when (code) {
                    0, 1 -> CLEAR
                    2, 3 -> CLOUDY
                    45, 48 -> FOG
                    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
                    71, 73, 75, 77, 85, 86 -> SNOW
                    95, 96, 99 -> THUNDERSTORM
                    else -> CLOUDY
                }
    }
}

/** How active the day has been, derived from the step count. */
enum class SerendipityActivity {
    QUIET,
    MODERATE,
    ACTIVE;

    companion object {
        fun fromSteps(steps: Int): SerendipityActivity =
                when {
                    steps < QUIET_BELOW -> QUIET
                    steps < ACTIVE_FROM -> MODERATE
                    else -> ACTIVE
                }

        private const val QUIET_BELOW = 2_000
        private const val ACTIVE_FROM = 8_000
    }
}
