/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §4.4, §10).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Storage compatibility and the variant synthesis a legacy master relies on. */
class RhythmChartTimingVariantsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun master(
        timing: List<RhythmTimingPoint>,
        variants: List<RhythmTimingVariant> = emptyList(),
        thresholds: List<RhythmRmsThreshold> = emptyList(),
        rms: Float = 0f,
    ): RhythmChart = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = "DSP Raw",
        timing = timing,
        timingVariants = variants,
        rmsThresholds = thresholds,
        notes = listOf(RhythmNote(id = 0, timeMs = 1_500, lane = 1, strength = 0.5f, band = 2, rms = rms)),
        durationMs = 10_000,
    )

    @Test
    fun storedVariantsAreReturnedAsIs() {
        val tracked = listOf(RhythmTimingPoint(0, 500.0), RhythmTimingPoint(4_000, 400.0))
        val classic = listOf(RhythmTimingPoint(0, 480.0))
        val chart = master(
            timing = tracked,
            variants = listOf(
                RhythmTimingVariant(RhythmTimingVariantKey.TRACKED, tracked),
                RhythmTimingVariant(RhythmTimingVariantKey.CLASSIC, classic),
            ),
        )

        val choices = chart.timingVariantsFor()

        assertEquals(
            listOf(RhythmTimingVariantKey.TRACKED, RhythmTimingVariantKey.CLASSIC),
            choices.map { it.key },
        )
        assertEquals(tracked, choices[0].timing)
        assertEquals(classic, choices[1].timing)
        assertFalse(choices[0].approximate)
        assertFalse(choices[1].approximate)
    }

    @Test
    fun singlePointLegacyMasterIsItsOwnClassicGrid() {
        val timing = listOf(RhythmTimingPoint(120, 480.0))

        val choices = master(timing).timingVariantsFor()

        assertEquals(2, choices.size)
        assertEquals(RhythmTimingVariantKey.TRACKED, choices[0].key)
        assertEquals(RhythmTimingVariantKey.CLASSIC, choices[1].key)
        assertEquals(timing, choices[0].timing)
        assertEquals(timing, choices[1].timing)
        assertFalse(choices[1].approximate)
    }

    @Test
    fun piecewiseLegacyMasterGetsAnApproximateClassicGrid() {
        // Median beat of 500 / 400 / 600 is 500; the anchor is the earliest point.
        val timing = listOf(
            RhythmTimingPoint(0, 500.0),
            RhythmTimingPoint(4_000, 400.0),
            RhythmTimingPoint(8_000, 600.0),
        )

        val choices = master(timing).timingVariantsFor()

        assertEquals(timing, choices[0].timing)
        assertFalse(choices[0].approximate)
        assertEquals(listOf(RhythmTimingPoint(0, 500.0)), choices[1].timing)
        assertTrue(choices[1].approximate)
    }

    @Test
    fun emptyTimingHasNoChoices() {
        val chart = RhythmChart(
            source = RhythmChart.SOURCE_DSP,
            difficultyName = "DSP Raw",
            notes = listOf(RhythmNote(id = 0, timeMs = 1_500, lane = 0)),
            durationMs = 10_000,
        )

        assertTrue(chart.timingVariantsFor().isEmpty())
    }

    @Test
    fun rmsThresholdLookupIsByPercentile() {
        val chart = master(
            timing = listOf(RhythmTimingPoint(0, 500.0)),
            thresholds = listOf(
                RhythmRmsThreshold(25, 0.02f),
                RhythmRmsThreshold(40, 0.05f),
            ),
        )

        assertEquals(0.02f, chart.rmsThresholdFor(25)!!, 0f)
        assertEquals(0.05f, chart.rmsThresholdFor(40)!!, 0f)
        assertNull(chart.rmsThresholdFor(10))
    }

    @Test
    fun newFieldsRoundTripWhileDefaultsStayOutOfTheJson() {
        val root = temp.newFolder("rhythm")
        val store = RhythmLibraryStore(root)
        val tracked = listOf(RhythmTimingPoint(0, 500.0), RhythmTimingPoint(4_000, 400.0))
        val chart = master(
            timing = tracked,
            variants = listOf(
                RhythmTimingVariant(RhythmTimingVariantKey.TRACKED, tracked),
                RhythmTimingVariant(RhythmTimingVariantKey.CLASSIC, listOf(RhythmTimingPoint(0, 500.0))),
            ),
            thresholds = listOf(RhythmRmsThreshold(25, 0.02f)),
            rms = 0.25f,
        )

        val meta = store.saveMaster(songId = "song-a", chart = chart)

        assertEquals(chart, store.readChart(meta.chartId))
        val raw = File(File(root, "charts"), "${meta.chartId}.json").readText()
        assertTrue(raw.contains("\"timingVariants\""))
        assertTrue(raw.contains("\"rmsThresholds\""))
        assertTrue(raw.contains("\"rms\""))

        val plain = master(timing = listOf(RhythmTimingPoint(0, 500.0)))
        val plainMeta = store.saveMaster(songId = "song-b", chart = plain)
        val plainRaw = File(File(root, "charts"), "${plainMeta.chartId}.json").readText()
        assertFalse(plainRaw.contains("\"timingVariants\""))
        assertFalse(plainRaw.contains("\"rmsThresholds\""))
        assertFalse(plainRaw.contains("\"rms\""))
    }

    @Test
    fun legacyJsonWithoutNewFieldsDecodesWithDefaults() {
        val root = temp.newFolder("rhythm")
        val charts = File(root, "charts").apply { mkdirs() }
        File(charts, "legacy.json").writeText(
            """
            {
              "schemaVersion": 1,
              "source": "dsp",
              "difficultyName": "Old Master",
              "timing": [{"timeMs": 0, "beatMs": 500.0}],
              "notes": [{"id": 0, "timeMs": 1500, "lane": 2, "strength": 0.5, "band": 3}],
              "durationMs": 5000
            }
            """.trimIndent(),
        )

        val chart = RhythmLibraryStore(root).readChart("legacy")

        requireNotNull(chart)
        assertTrue(chart.timingVariants.isEmpty())
        assertTrue(chart.rmsThresholds.isEmpty())
        assertEquals(0f, chart.notes.single().rms, 0f)
        assertNull(chart.rmsThresholdFor(25))
        assertEquals(2, chart.timingVariantsFor().size)
        assertEquals(chart.timing, chart.timingVariantsFor()[1].timing)
    }
}
