/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: unit tests for the Serendipity prompt composer.
 */
package com.pisces312.piscesplayer.data.ai.serendipity

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import org.junit.Test

class SerendipityPromptComposerTest {

    /** The zero-permission baseline: time, zone and language, nothing else. */
    private val bareMoment =
            SerendipityContext(
                    weekday = DayOfWeek.FRIDAY,
                    clockTime = "19:20",
                    timeOfDay = SerendipityTimeOfDay.EVENING,
                    timeZone = "China Standard Time",
                    deviceLanguage = "Chinese"
            )

    /** An English device: the collector drops the language, so no language clause appears. */
    private val timeOnly = bareMoment.copy(deviceLanguage = null)

    private val fullSignals =
            bareMoment.copy(
                    city = "Shanghai",
                    weather = SerendipityWeather(SerendipityWeatherGroup.RAIN, 14),
                    stepsToday = 8_412
            )

    @Test
    fun `every available signal ends up in the sentence`() {
        val prompt = SerendipityPromptComposer.compose(fullSignals, variant = 0)

        assertThat(prompt).isEqualTo(
                "Friday 19:20, evening, China Standard Time. " +
                        "The phone is set to Chinese. " +
                        "Rain, 14°C, in Shanghai. " +
                        "About 8,400 steps today, an active day. " +
                        "Pick songs that fit this exact moment."
        )
    }

    @Test
    fun `missing weather and steps remove their sentence instead of adding a placeholder`() {
        val prompt = SerendipityPromptComposer.compose(timeOnly, variant = 0)

        assertThat(prompt).isEqualTo(
                "Friday 19:20, evening, China Standard Time. " +
                        "Nothing else about this day is known — pick music that suits the moment."
        )
        assertThat(prompt).doesNotContain("°C")
        assertThat(prompt).doesNotContain("steps")
        assertThat(prompt).doesNotContain("%s")
    }

    @Test
    fun `the zone is present even when nothing else could be read`() {
        val prompt = SerendipityPromptComposer.compose(timeOnly, variant = 2)

        assertThat(prompt).contains("China Standard Time")
    }

    @Test
    fun `an english device drops the language clause`() {
        val prompt = SerendipityPromptComposer.compose(timeOnly, variant = 0)

        assertThat(prompt).doesNotContain("The phone is set to")
        assertThat(prompt).doesNotContain("system language")
    }

    @Test
    fun `a read signal switches the closing away from the cold start set`() {
        val withCityOnly = bareMoment.copy(city = "Shanghai")

        assertThat(SerendipityPromptComposer.compose(withCityOnly, variant = 0))
                .endsWith("Pick songs that fit this exact moment.")
    }

    @Test
    fun `city is omitted when the weather is known but the city is not`() {
        val prompt = SerendipityPromptComposer.compose(
                bareMoment.copy(weather = SerendipityWeather(SerendipityWeatherGroup.CLEAR, 3)),
                variant = 0
        )

        assertThat(prompt).isEqualTo(
                "Friday 19:20, evening, China Standard Time. " +
                        "The phone is set to Chinese. " +
                        "Clear skies, 3°C. " +
                        "Pick songs that fit this exact moment."
        )
    }

    @Test
    fun `late night reads as english rather than a templated preposition`() {
        val prompt = SerendipityPromptComposer.compose(
                timeOnly.copy(clockTime = "02:10", timeOfDay = SerendipityTimeOfDay.LATE_NIGHT),
                variant = 3
        )

        assertThat(prompt).contains("Friday, 02:10 late at night, China Standard Time.")
        assertThat(prompt).doesNotContain("in the late night")
    }

    @Test
    fun `the same context and variant always compose the same text`() {
        val first = SerendipityPromptComposer.compose(fullSignals, variant = 3)
        val second = SerendipityPromptComposer.compose(fullSignals, variant = 3)

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `each variant reshuffles the wording`() {
        val variants =
                (0 until SerendipityPromptComposer.VARIANT_COUNT).map {
                    SerendipityPromptComposer.compose(fullSignals, it)
                }

        assertThat(variants.toSet()).hasSize(SerendipityPromptComposer.VARIANT_COUNT)
    }

    @Test
    fun `the cold start closings differ across variants`() {
        val variants =
                (0 until SerendipityPromptComposer.VARIANT_COUNT).map {
                    SerendipityPromptComposer.compose(timeOnly, it)
                }

        assertThat(variants.toSet()).hasSize(SerendipityPromptComposer.VARIANT_COUNT)
    }

    @Test
    fun `an out of range variant wraps around instead of crashing`() {
        val wrapped = SerendipityPromptComposer.compose(fullSignals, variant = 7)

        assertThat(wrapped)
                .isEqualTo(
                        SerendipityPromptComposer.compose(
                                fullSignals,
                                variant = 7 % SerendipityPromptComposer.VARIANT_COUNT
                        )
                )
    }

    @Test
    fun `steps are floored to the hundred so the prompt is not precise`() {
        val prompt = SerendipityPromptComposer.compose(
                bareMoment.copy(stepsToday = 8_499),
                variant = 0
        )

        assertThat(prompt).contains("8,400 steps")
        assertThat(prompt).doesNotContain("8,499")
    }

    @Test
    fun `wmo weather codes map onto the coarse buckets`() {
        assertThat(SerendipityWeatherGroup.fromWmoCode(0))
                .isEqualTo(SerendipityWeatherGroup.CLEAR)
        assertThat(SerendipityWeatherGroup.fromWmoCode(3))
                .isEqualTo(SerendipityWeatherGroup.CLOUDY)
        assertThat(SerendipityWeatherGroup.fromWmoCode(48))
                .isEqualTo(SerendipityWeatherGroup.FOG)
        assertThat(SerendipityWeatherGroup.fromWmoCode(61))
                .isEqualTo(SerendipityWeatherGroup.RAIN)
        assertThat(SerendipityWeatherGroup.fromWmoCode(86))
                .isEqualTo(SerendipityWeatherGroup.SNOW)
        assertThat(SerendipityWeatherGroup.fromWmoCode(96))
                .isEqualTo(SerendipityWeatherGroup.THUNDERSTORM)
    }

    @Test
    fun `step counts bucket into an activity level`() {
        assertThat(SerendipityActivity.fromSteps(0)).isEqualTo(SerendipityActivity.QUIET)
        assertThat(SerendipityActivity.fromSteps(1_999)).isEqualTo(SerendipityActivity.QUIET)
        assertThat(SerendipityActivity.fromSteps(2_000)).isEqualTo(SerendipityActivity.MODERATE)
        assertThat(SerendipityActivity.fromSteps(7_999)).isEqualTo(SerendipityActivity.MODERATE)
        assertThat(SerendipityActivity.fromSteps(8_000)).isEqualTo(SerendipityActivity.ACTIVE)
    }

    @Test
    fun `time of day follows the clock`() {
        assertThat(SerendipityTimeOfDay.at(6)).isEqualTo(SerendipityTimeOfDay.MORNING)
        assertThat(SerendipityTimeOfDay.at(14)).isEqualTo(SerendipityTimeOfDay.AFTERNOON)
        assertThat(SerendipityTimeOfDay.at(19)).isEqualTo(SerendipityTimeOfDay.EVENING)
        assertThat(SerendipityTimeOfDay.at(2)).isEqualTo(SerendipityTimeOfDay.LATE_NIGHT)
    }
}
