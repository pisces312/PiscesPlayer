package com.pisces312.piscesplayer.data.ai.serendipity

import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns a [SerendipityContext] into the sentence handed to the playlist generator.
 *
 * One clause per signal, in a fixed order: time (with its zone), date, device language, weather
 * (or the city alone when the weather could not be read), steps, closing. A clause whose signal is
 * `null` is left out entirely rather than replaced by a placeholder, so the prompt only ever states
 * what was actually read — see [SerendipityContext] for which signals are guaranteed.
 *
 * Pure and deterministic: `(context, variant)` always produces the same text, which is what makes
 * "reshuffle" verifiable in unit tests. [variant] is the only source of variation, so the caller
 * owns the randomness and the tests can pin it.
 *
 * The sentence is always English. The playlist pipeline already speaks English end to end (system
 * prompt, `'Title - Artist'` output format), and keeping the prompt in one language avoids mixing
 * scripts inside a single request. The sheet shows localized chips next to it instead.
 */
object SerendipityPromptComposer {

    /** How many sentence shapes "reshuffle" walks through before it repeats itself. */
    const val VARIANT_COUNT = 5

    fun compose(context: SerendipityContext, variant: Int = 0): String {
        val index = Math.floorMod(variant, VARIANT_COUNT)
        val parts = mutableListOf<String>()
        parts += timeClause(context, index)
        parts += dateClause(context, index)
        context.deviceLanguage?.let { parts += languageClause(it, index) }
        val weather = context.weather
        if (weather != null) {
            parts += weatherClause(weather, context.city, index)
        } else {
            // Without weather the city used to be dropped too, which threw away the only regional
            // signal an offline device still had.
            context.city?.let { parts += cityClause(it, index) }
        }
        context.stepsToday?.let { parts += stepsClause(it, index) }
        parts += closing(context, index)
        return parts.joinToString(" ")
    }

    /**
     * Day, clock, part of day and zone.
     *
     * The zone rides along on every variant instead of getting its own sentence: it reads as one
     * fact ("Friday 19:20, evening, China Standard Time") and it is the only geographic anchor a
     * prompt gets when nothing was granted, so folding it in avoids a stub sentence like
     * "Time zone: ...".
     */
    private fun timeClause(context: SerendipityContext, index: Int): String {
        val weekday = context.weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val timeOfDay = timeOfDayWord(context.timeOfDay)
        val inTimeOfDay = timeOfDayPhrase(context.timeOfDay)
        val zone = context.timeZone
        return when (index) {
            0 -> "$weekday ${context.clockTime}, $timeOfDay, $zone."
            1 -> "It is $weekday $timeOfDay, ${context.clockTime}, $zone."
            2 -> "Right now it is $weekday, ${context.clockTime} — $timeOfDay, $zone."
            3 -> "$weekday, ${context.clockTime} $inTimeOfDay, $zone."
            else -> "$weekday $timeOfDay, around ${context.clockTime}, $zone."
        }
    }

    /**
     * Where the calendar sits, at a granularity that reads as a season rather than a timestamp:
     * "early November", "mid-November", "late November".
     *
     * No hemisphere correction is applied. The date is a fact; "it is winter" would be an
     * inference, and from the month alone it is wrong for every southern-hemisphere listener. The
     * zone clause names the region, so a model that cares about the season can place it itself.
     * One variant states the exact day, which is what makes holidays usable.
     */
    private fun dateClause(context: SerendipityContext, index: Int): String {
        val month = context.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val phrase =
                when (context.dayOfMonth) {
                    in 1..10 -> "early $month"
                    in 11..20 -> "mid-$month"
                    else -> "late $month"
                }
        return when (index % 3) {
            0 -> "It is $phrase."
            1 -> "The date is $month ${context.dayOfMonth}."
            else -> "${phrase.replaceFirstChar { it.titlecase(Locale.ENGLISH) }}."
        }
    }

    /**
     * The language the phone runs in.
     *
     * Stated as a fact and nothing more: it says which market the listener lives in, not which
     * language their music is in, so the wording must not turn into "pick Chinese songs".
     */
    private fun languageClause(language: String, index: Int): String =
            when (index % 3) {
                0 -> "The phone is set to $language."
                1 -> "Their device runs in $language."
                else -> "$language is the system language here."
            }

    /**
     * The city alone, when the weather could not be read (offline, or the endpoint failed).
     *
     * Deliberately silent about where the name came from: it may be the city picked in settings
     * rather than the device's position, so "last known location" would be a lie.
     */
    private fun cityClause(city: String, index: Int): String =
            when (index % 3) {
                0 -> "Somewhere in $city."
                1 -> "The phone is somewhere in $city."
                else -> "In or around $city."
            }

    private fun weatherClause(
        weather: SerendipityWeather,
        city: String?,
        index: Int
    ): String {
        val label = weatherWord(weather.group)
        val temperature = "${weather.temperatureC}°C"
        return when (index % 3) {
            0 -> if (city != null) "$label, $temperature, in $city." else "$label, $temperature."
            1 -> if (city != null) "$label and $temperature in $city." else "$label and $temperature."
            // Phrased so it still reads with a district, whose name already contains a comma
            // ("Huangpu, Shanghai"): "$city is ..." would not.
            else -> if (city != null) "$label, $temperature — $city." else "$label, $temperature."
        }
    }

    private fun stepsClause(steps: Int, index: Int): String {
        val rounded = String.format(Locale.US, "%,d", steps / 100 * 100)
        val activity =
                when (SerendipityActivity.fromSteps(steps)) {
                    SerendipityActivity.QUIET -> "a quiet day"
                    SerendipityActivity.MODERATE -> "a steady day"
                    SerendipityActivity.ACTIVE -> "an active day"
                }
        return when (index % 3) {
            0 -> "About $rounded steps today, $activity."
            1 -> "Around $rounded steps so far: $activity."
            else -> "$rounded steps today — $activity."
        }
    }

    /**
     * The request itself.
     *
     * Two sets, because the closing carries the whole burden when nothing else is known: a bare
     * prompt should admit that rather than assert "this exact moment" over a single clock reading.
     */
    private fun closing(context: SerendipityContext, index: Int): String {
        val hasOptionalSignal =
                context.city != null || context.weather != null || context.stepsToday != null
        return if (hasOptionalSignal) CLOSINGS[index] else COLD_CLOSINGS[index % COLD_CLOSINGS.size]
    }

    private fun timeOfDayWord(timeOfDay: SerendipityTimeOfDay): String =
            when (timeOfDay) {
                SerendipityTimeOfDay.MORNING -> "morning"
                SerendipityTimeOfDay.AFTERNOON -> "afternoon"
                SerendipityTimeOfDay.EVENING -> "evening"
                SerendipityTimeOfDay.LATE_NIGHT -> "late night"
            }

    /**
     * The same part of day wherever the phrase needs a preposition.
     *
     * "in the late night" is not English, so the late-night slot gets its own phrase instead of
     * being templated like the other three.
     */
    private fun timeOfDayPhrase(timeOfDay: SerendipityTimeOfDay): String =
            when (timeOfDay) {
                SerendipityTimeOfDay.MORNING -> "in the morning"
                SerendipityTimeOfDay.AFTERNOON -> "in the afternoon"
                SerendipityTimeOfDay.EVENING -> "in the evening"
                SerendipityTimeOfDay.LATE_NIGHT -> "late at night"
            }

    private fun weatherWord(group: SerendipityWeatherGroup): String =
            when (group) {
                SerendipityWeatherGroup.CLEAR -> "Clear skies"
                SerendipityWeatherGroup.CLOUDY -> "Cloudy"
                SerendipityWeatherGroup.FOG -> "Fog"
                SerendipityWeatherGroup.RAIN -> "Rain"
                SerendipityWeatherGroup.SNOW -> "Snow"
                SerendipityWeatherGroup.THUNDERSTORM -> "Thunderstorms"
            }

    private val CLOSINGS =
            listOf(
                    "Pick songs that fit this exact moment.",
                    "Choose tracks that belong to right now.",
                    "Find songs that match this moment.",
                    "Suggest music for right now, exactly as it is.",
                    "Pick music that sounds like this moment."
            )

    /** Used when no optional signal was read — the clock, the zone and the calendar are it. */
    private val COLD_CLOSINGS =
            listOf(
                    "Nothing else about this day is known — pick music that suits the moment.",
                    "That is all there is to go on; choose songs that fit it.",
                    "Fill in the rest yourself, and match what this moment sounds like."
            )
}
