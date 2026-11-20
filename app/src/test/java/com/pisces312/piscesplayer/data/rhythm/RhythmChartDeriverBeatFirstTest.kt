/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (RhythmChartLab/docs/experiments/2026-11-20-handtap-config-fit.md).
 */
package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BEAT_FIRST derivation pinned against the PC lab's `pps_derive.derive_beat_first`.
 *
 * The expected lists below are the verbatim output of the PC implementation over this exact
 * master (six knob combinations), generated with `D:\Temp\beat_first_golden.py`. The phone chart
 * must diff clean against `scripts/pps_matrix.py` output, so this is a parity contract, not a
 * plausibility check — including the quirks: a chord extra keeps its own lane even when the
 * lane-avoidance moved the primary, and a note with unknown lowRatio (0) falls back to the band
 * gate under the share gate.
 */
class RhythmChartDeriverBeatFirstTest {

    private fun master(): RhythmChart = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = "DSP Raw",
        timing = listOf(RhythmTimingPoint(0, 500.0)),
        notes = listOf(
            note(0, 1_000, 1.00f, 0, 0.80f),
            note(1, 1_200, 0.90f, 5, 0.20f),
            note(2, 1_520, 0.80f, 2, 0.70f),
            note(3, 1_560, 0.60f, 6, 0.30f),
            note(4, 2_010, 0.50f, 7, 0.05f),
            note(5, 2_480, 0.15f, 1, 0.90f),
            note(6, 3_010, 1.00f, 0, 0.80f),
            note(7, 3_510, 1.00f, 0, 0.80f),
            note(8, 4_010, 0.90f, 0, 0.80f),
            note(9, 4_030, 0.50f, 3, 0.80f),
            note(10, 5_010, 1.00f, 0, 0.80f),
            note(11, 5_510, 0.80f, 0, 0.80f),
            note(12, 6_010, 0.60f, 0, 0.80f),
            note(13, 7_010, 1.00f, 2, 0.00f),
            note(14, 7_510, 1.00f, 5, 0.00f),
        ),
        durationMs = 20_000,
    )

    private fun note(id: Int, timeMs: Int, strength: Float, band: Int, lowRatio: Float): RhythmNote =
        RhythmNote(id = id, timeMs = timeMs, lane = 0, strength = strength, band = band, lowRatio = lowRatio)

    private fun derive(params: RhythmBeatFirstParams, maxChord: Int = 2): List<String> =
        RhythmChartDeriver.deriveBeatFirst(
            master = master(),
            difficulty = DspDifficulty.EASY.spec,
            maxChord = maxChord,
            params = params,
        ).notes.map { "${it.timeMs},${it.lane},${it.strength},${it.band},${it.lowRatio}" }

    @Test
    fun gateOffKeepsEveryCell() {
        assertEquals(
            listOf(
                "1000,0,1.0,0,0.8",
                "1500,1,0.8,2,0.7",
                "1500,3,0.6,6,0.3",
                "2000,3,0.5,7,0.05",
                "2500,0,0.15,1,0.9",
                "3000,1,1.0,0,0.8",
                "3500,0,1.0,0,0.8",
                "4000,1,0.9,0,0.8",
                "4000,1,0.5,3,0.8",
                "5000,0,1.0,0,0.8",
                "5500,1,0.8,0,0.8",
                "6000,0,0.6,0,0.8",
                "7000,1,1.0,2,0.0",
                "7500,2,1.0,5,0.0",
            ),
            derive(RhythmBeatFirstParams(drumGate = BeatDrumGate.OFF)),
        )
    }

    @Test
    fun bandGateDropsTheCymbalLedCellButKeepsUnknownBands() {
        assertEquals(
            listOf(
                "1000,0,1.0,0,0.8",
                "1500,1,0.8,2,0.7",
                "1500,3,0.6,6,0.3",
                "2500,0,0.15,1,0.9",
                "3000,1,1.0,0,0.8",
                "3500,0,1.0,0,0.8",
                "4000,1,0.9,0,0.8",
                "4000,1,0.5,3,0.8",
                "5000,0,1.0,0,0.8",
                "5500,1,0.8,0,0.8",
                "6000,0,0.6,0,0.8",
                "7000,1,1.0,2,0.0",
            ),
            derive(RhythmBeatFirstParams(drumGate = BeatDrumGate.BAND)),
        )
    }

    @Test
    fun shareGateKeepsLowBandsAndFallsBackToTheBandGateWhenLowRatioIsUnknown() {
        assertEquals(
            listOf(
                "1000,0,1.0,0,0.8",
                "1500,1,0.8,2,0.7",
                "1500,3,0.6,6,0.3",
                "2500,0,0.15,1,0.9",
                "3000,1,1.0,0,0.8",
                "3500,0,1.0,0,0.8",
                "4000,1,0.9,0,0.8",
                "4000,1,0.5,3,0.8",
                "5000,0,1.0,0,0.8",
                "5500,1,0.8,0,0.8",
                "6000,0,0.6,0,0.8",
                // band 2 with unknown lowRatio passes through the band fallback;
                // band 5 with unknown lowRatio is rejected by it.
                "7000,1,1.0,2,0.0",
            ),
            derive(
                RhythmBeatFirstParams(
                    drumGate = BeatDrumGate.LOW_RATIO,
                    lowRatioMin = 0.45,
                ),
            ),
        )
    }

    @Test
    fun restThresholdAgreesWithTheReferenceOnTheQuietCell() {
        // The quiet cell at 2500 (score 0.19) still clears 10% of the local p90 (~1.25),
        // so rest 0.10 removes nothing here — pinned so a change of either side is visible.
        assertEquals(
            derive(RhythmBeatFirstParams(drumGate = BeatDrumGate.BAND)),
            derive(
                RhythmBeatFirstParams(
                    drumGate = BeatDrumGate.BAND,
                    restThreshold = 0.10,
                ),
            ),
        )
    }

    @Test
    fun densityCapDropsTheWeakestHitInsideTheWindow() {
        assertEquals(
            listOf(
                "1000,0,1.0,0,0.8",
                "2000,3,0.5,7,0.05",
                "3000,0,1.0,0,0.8",
                "4000,1,0.9,0,0.8",
                "4000,1,0.5,3,0.8",
                "5000,0,1.0,0,0.8",
                "6000,1,0.6,0,0.8",
                "7000,2,1.0,2,0.0",
            ),
            derive(
                RhythmBeatFirstParams(
                    drumGate = BeatDrumGate.OFF,
                    densityCap = 1.0,
                ),
            ),
        )
    }

    @Test
    fun maxChordOneDropsTheChordExtras() {
        assertEquals(
            listOf(
                "1000,0,1.0,0,0.8",
                "1500,1,0.8,2,0.7",
                "2500,0,0.15,1,0.9",
                "3000,1,1.0,0,0.8",
                "3500,0,1.0,0,0.8",
                "4000,1,0.9,0,0.8",
                "5000,0,1.0,0,0.8",
                "5500,1,0.8,0,0.8",
                "6000,0,0.6,0,0.8",
                "7000,1,1.0,2,0.0",
            ),
            derive(RhythmBeatFirstParams(drumGate = BeatDrumGate.BAND), maxChord = 1),
        )
    }
}
