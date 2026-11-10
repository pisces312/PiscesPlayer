package com.pisces312.piscesplayer.data.ai.serendipity

import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns a [SerendipityContext] into the sentence handed to the playlist generator.
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
        context.weather?.let { parts += weatherClause(it, context.city, index) }
        context.stepsToday?.let { parts += stepsClause(it, index) }
        parts += CLOSINGS[index]
        return parts.joinToString(" ")
    }

    private fun timeClause(context: SerendipityContext, index: Int): String {
        val weekday = context.weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val timeOfDay = timeOfDayWord(context.timeOfDay)
        return when (index) {
            0 -> "$weekday ${context.clockTime}, $timeOfDay."
            1 -> "It is $weekday $timeOfDay, ${context.clockTime}."
            2 -> "Right now it is $weekday, ${context.clockTime} — $timeOfDay."
            3 -> "$weekday, ${context.clockTime} in the $timeOfDay."
            else -> "$weekday $timeOfDay, around ${context.clockTime}."
        }
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

    private fun timeOfDayWord(timeOfDay: SerendipityTimeOfDay): String =
            when (timeOfDay) {
                SerendipityTimeOfDay.MORNING -> "morning"
                SerendipityTimeOfDay.AFTERNOON -> "afternoon"
                SerendipityTimeOfDay.EVENING -> "evening"
                SerendipityTimeOfDay.LATE_NIGHT -> "late night"
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
}
