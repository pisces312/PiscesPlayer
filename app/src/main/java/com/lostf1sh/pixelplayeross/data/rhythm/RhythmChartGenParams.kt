/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-dsp-chart-generation-plan.md §5.2).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

/**
 * Difficulty ladder for the DSP generator. The quantization grid is the difficulty:
 * a coarser grid can only place one note per cell, so it directly caps the note density.
 * Measured on the reference song (261.9 s / 90.84 BPM): 1/1 -> 1.55 NPS (F1 89.9 vs model
 * easy), 1/2 -> 3.03 NPS (F1 93.0 vs model normal), 1/4 -> 5.54 NPS (F1 70.5 vs model hard).
 */
enum class DspDifficulty(val gridDiv: Int, val label: String) {
    EASY(1, "Easy"),
    NORMAL(2, "Normal"),
    HARD(4, "Hard"),
    ;

    /** Beat subdivisions per note slot; 1 = one note per beat. */
    val slotName: String
        get() = when (this) {
            EASY -> "1/1"
            NORMAL -> "1/2"
            HARD -> "1/4"
        }
}

/**
 * Lane assignment strategy.
 *
 * [BAND_ORDER] is the reference Python behaviour (`lane = band * lanes / bands`), kept as
 * the default so the Kotlin output and the PC reference stay directly comparable.
 *
 * [LOW_OUTSIDE] maps low frequencies to the outer lanes. The measurement that motivates it:
 * bass-led onsets are the ones the model picks least often (55.7%), so putting them on the
 * outer lanes keeps the busy centre lanes for content that actually reads as rhythm.
 */
enum class LaneStrategy { BAND_ORDER, LOW_OUTSIDE }

/**
 * Everything the generator lets the user tune. Defaults are the *simplest* path on purpose:
 * every optional constraint measured worse than plain grid quantization, so they ship off.
 */
data class RhythmChartGenParams(
    val difficulty: DspDifficulty = DspDifficulty.EASY,
    val laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
    /**
     * Drop onsets whose local RMS (40 ms window) sits below this percentile of the whole
     * song. `null` = off. Measured: enabling it lowers match quality, so it is only a
     * user-facing escape hatch for very noisy material.
     */
    val minRmsPercentile: Int? = null,
    /**
     * Cap notes inside any 1 s window. `null` = off. This is a playability guard, not a
     * quality filter: at 5 NPS the recall against a reference chart drops to 35.8%.
     */
    val maxLocalNps: Double? = null,
    /** Skip automatic BPM detection (the octave ambiguity is unsolvable for some songs). */
    val bpmOverride: Double? = null,
    /** Shifts every note in time; user calibration knob, not an analysis parameter. */
    val extraOffsetMs: Int = 0,
)

/** Analysis facts worth showing after a run (and worth logging when quality looks off). */
data class DspDiagnostics(
    val detectedBpm: Double,
    val usedBpm: Double,
    val octaveCorrection: Double,
    val beatOffsetMs: Double,
    val onsetCount: Int,
    val noteCount: Int,
    val analysisMs: Long,
) {
    val beatMs: Double get() = 60_000.0 / usedBpm
}

/** A generated chart plus the facts needed to explain it. */
data class GeneratedChart(
    val chart: RhythmChart,
    val diagnostics: DspDiagnostics,
)
