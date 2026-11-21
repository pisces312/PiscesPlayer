/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §14).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RhythmChartSettingsTest {

    @Test
    fun approachIsStillClamped() {
        assertEquals(700, RhythmChartSettings(approachMs = 10).clamped().approachMs)
        assertEquals(3_000, RhythmChartSettings(approachMs = 99_999).clamped().approachMs)
    }

    @Test
    fun tapsArePlainTenMillisecondSteps() {
        assertEquals(10, delayAfterTap(0, 10))
        assertEquals(-10, delayAfterTap(0, -10))
        assertEquals(12_350, delayAfterTap(12_340, 10))
        assertEquals(-98_775, delayAfterTap(-98_765, -10))
    }

    @Test
    fun jsonRoundTrip() {
        val settings = RhythmChartSettings(
            delayOverrideMs = -320,
            leadInMs = 1_000,
            approachMs = 1_200,
        )

        val decoded = Json.decodeFromString(
            RhythmChartSettings.serializer(),
            Json.encodeToString(RhythmChartSettings.serializer(), settings),
        )

        assertEquals(settings, decoded)
    }

    @Test
    fun delayOverrideDefaultsToNullAndKeepsRoutePriority() {
        // No override: the route calibration applies (null = follow the route store).
        assertEquals(null, RhythmChartSettings.DEFAULT.delayOverrideMs)
        // A sidecar written before overrides existed must parse with none.
        val legacy = Json { ignoreUnknownKeys = true }.decodeFromString(
            RhythmChartSettings.serializer(),
            """{"schemaVersion":1,"leadInMs":0,"approachMs":1500}""",
        )
        assertEquals(null, legacy.delayOverrideMs)
    }

    @Test
    fun leadInRoundsToFiveHundredMsSteps() {
        assertEquals(500, RhythmChartSettings.DEFAULT.leadInMs)
        assertEquals(0, RhythmChartSettings(leadInMs = 0).clamped().leadInMs)
        assertEquals(500, RhythmChartSettings(leadInMs = 250).clamped().leadInMs)
        assertEquals(500, RhythmChartSettings(leadInMs = 749).clamped().leadInMs)
        assertEquals(1_000, RhythmChartSettings(leadInMs = 751).clamped().leadInMs)
    }

    @Test
    fun legacySidecarWithDelayKeyStillParses() {
        // delayMs lived in the chart sidecar before the offset moved to the route-keyed
        // RhythmLatencyStore; with the store's ignoreUnknownKeys the old key must be ignored,
        // not fatal.
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(
            RhythmChartSettings.serializer(),
            """{"schemaVersion":1,"delayMs":-165,"approachMs":1500}""",
        )

        assertEquals(RhythmChartSettings(approachMs = 1_500), decoded)
    }
}
