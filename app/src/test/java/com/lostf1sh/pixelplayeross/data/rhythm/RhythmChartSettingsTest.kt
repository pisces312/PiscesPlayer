/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §14).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RhythmChartSettingsTest {

    @Test
    fun delayIsNotClampedInEitherDirection() {
        assertEquals(12_345, RhythmChartSettings(delayMs = 12_345).clamped().delayMs)
        assertEquals(-98_765, RhythmChartSettings(delayMs = -98_765).clamped().delayMs)
    }

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
    fun jsonRoundTripKeepsAnyDelay() {
        val settings = RhythmChartSettings(delayMs = -98_765, approachMs = 1_500)

        val decoded = Json.decodeFromString(
            RhythmChartSettings.serializer(),
            Json.encodeToString(RhythmChartSettings.serializer(), settings),
        )

        assertEquals(settings, decoded)
    }
}
