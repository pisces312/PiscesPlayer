/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §7;
 * RhythmChartLab/docs/experiments/2026-11-20-handtap-config-fit.md).
 */
package com.pisces312.piscesplayer.data.rhythm

/** How a derivation treats the master beyond picking a difficulty and a mode. */
data class RhythmDeriveOptions(
    /** Timing variant key ([RhythmTimingVariantKey]); null uses the master's primary timing. */
    val timingVariant: String? = null,
    /** Percentile of the master's stored loudness cutoffs; null = loudness filter off. */
    val minRmsPercentile: Int? = null,
    /** Notes allowed inside any centred 1 s window; null = density cap off. */
    val maxLocalNps: Double? = null,
    /** BEAT_FIRST's gate / rest / cap knobs; only the [RhythmDeriveMode.BEAT_FIRST] mode reads them. */
    val beatParams: RhythmBeatFirstParams = RhythmBeatFirstParams(),
)

/**
 * Every control of the derive sheet in one value.
 *
 * Keeping this a plain data class is what makes preset matching a pure comparison: a preset is
 * selected exactly while every *configuration* field still equals the values it wrote. The
 * difficulty axis ([difficulties], [customDifficulty]) is deliberately not part of that: a preset
 * is "how to pick and place notes", the ladder is "how dense", and the two are chosen separately.
 */
data class RhythmDeriveSettings(
    val timingVariant: String = RhythmTimingVariantKey.TRACKED,
    val modes: Set<RhythmDeriveMode> = RhythmDeriveMode.entries.toSet(),
    val selection: OnsetSelection = OnsetSelection.MELODY_FIRST,
    val laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
    val maxChord: Int = 2,
    val minRmsPercentile: Int? = null,
    val maxLocalNps: Double? = null,
    val difficulties: Set<DspDifficultySpec> = DspDifficultySpec.BUILT_IN.toSet(),
    val beatDrumGate: BeatDrumGate = BeatDrumGate.BAND,
    val beatRestThreshold: Double = 0.10,
    val beatDensityCap: Double? = null,
    val beatLowRatioMin: Double = 0.45,
    /** The user-defined difficulty; null = only the built-in ladder is derived. */
    val customDifficulty: RhythmCustomDifficulty? = null,
) {
    fun options(): RhythmDeriveOptions = RhythmDeriveOptions(
        timingVariant = timingVariant,
        minRmsPercentile = minRmsPercentile,
        maxLocalNps = maxLocalNps,
        beatParams = RhythmBeatFirstParams(
            drumGate = beatDrumGate,
            restThreshold = beatRestThreshold,
            densityCap = beatDensityCap,
            lowRatioMin = beatLowRatioMin,
        ),
    )

    /**
     * The difficulty slots to derive, built-in rungs easiest first and the custom one last.
     * [customLabel] names the custom difficulty when its own name field is blank.
     */
    fun difficultySpecs(customLabel: String): List<DspDifficultySpec> =
        difficulties.sortedBy { it.slotsPerBeat } +
            listOfNotNull(customDifficulty?.spec(customLabel))
}

/**
 * Built-in derive presets: the four configurations measured against the user's hand-tapped chart
 * (v1-v4 of `RhythmChartLab/docs/experiments/2026-11-20-handtap-config-fit.md`), plus the two
 * historical ones. All are fixed field tables (not "whatever the defaults are today"), so a future
 * default change can not silently move what a preset reproduces. The PC lab's
 * `scripts/pps_matrix.py` carries the same tables, so phone output can be diffed against PC output.
 *
 * A preset fixes the *configuration* (grid choice, mode, gate, sparsification); the difficulty
 * ladder (1/1, 1/2, 1/4 per rung) is chosen separately and applies inside every preset. The
 * beat-gate knobs are inert unless the BEAT_FIRST mode is on, and are written anyway so that
 * switching modes after picking a preset still reproduces the PC variant exactly.
 */
enum class RhythmDerivePreset(
    val timingVariant: String,
    val modes: Set<RhythmDeriveMode>,
    val selection: OnsetSelection,
    val laneStrategy: LaneStrategy,
    val maxChord: Int,
    val minRmsPercentile: Int?,
    val maxLocalNps: Double?,
    val beatDrumGate: BeatDrumGate,
    val beatRestThreshold: Double,
    val beatDensityCap: Double?,
    val beatLowRatioMin: Double,
) {
    /**
     * v1-grid-tracked — GRID mode over the tracked piecewise grid, one note per beat cell, no
     * selection gate. The default preset: measured closest to the user's hand-tapped chart
     * (F1 0.913, tap-to-note p50 16 ms on the reference song) and confirmed by real-device
     * A/B play (the classic phase needs added per-chart delay to match the music, this one
     * does not). Its tracked phase can not reproduce the classic-phase charts like the
     * 544-note golden (566 notes, half-a-grid phase, best-shift F1 0.887) — use [CLASSIC_043]
     * for that; feel was chosen over reproducibility for the default (2026-11-20).
     */
    V1_GRID_TRACKED(
        timingVariant = RhythmTimingVariantKey.TRACKED,
        modes = setOf(RhythmDeriveMode.GRID),
        selection = OnsetSelection.BALANCED,
        laneStrategy = LaneStrategy.BAND_ORDER,
        maxChord = 2,
        minRmsPercentile = null,
        maxLocalNps = null,
        beatDrumGate = BeatDrumGate.BAND,
        beatRestThreshold = 0.10,
        beatDensityCap = null,
        beatLowRatioMin = 0.45,
    ),

    /**
     * v2-beat-band-rest0 — BEAT_FIRST with the drum-band gate: only beats whose strongest onset
     * sits in the drum bands get a note, the others stay empty (265 notes on the reference song).
     */
    V2_BEAT_BAND_REST0(
        timingVariant = RhythmTimingVariantKey.TRACKED,
        modes = setOf(RhythmDeriveMode.BEAT_FIRST),
        selection = OnsetSelection.BALANCED,
        laneStrategy = LaneStrategy.BAND_ORDER,
        maxChord = 2,
        minRmsPercentile = null,
        maxLocalNps = null,
        beatDrumGate = BeatDrumGate.BAND,
        beatRestThreshold = 0.0,
        beatDensityCap = null,
        beatLowRatioMin = 0.45,
    ),

    /**
     * v3-beat-band-rest10 — v2 plus quiet-passage skipping: a beat whose drum score is below
     * 10% of its local p90 is left empty (long rests stay rests).
     */
    V3_BEAT_BAND_REST10(
        timingVariant = RhythmTimingVariantKey.TRACKED,
        modes = setOf(RhythmDeriveMode.BEAT_FIRST),
        selection = OnsetSelection.BALANCED,
        laneStrategy = LaneStrategy.BAND_ORDER,
        maxChord = 2,
        minRmsPercentile = null,
        maxLocalNps = null,
        beatDrumGate = BeatDrumGate.BAND,
        beatRestThreshold = 0.10,
        beatDensityCap = null,
        beatLowRatioMin = 0.45,
    ),

    /**
     * v4-beat-low45-rest10 — v3 with the gate swapped to low-frequency share >= 0.45. Equivalent
     * to v1 on instrumental material (5 notes apart on the reference song) and the variant to use
     * when the material is vocal-heavy and the band gate keeps too many sung beats.
     */
    V4_BEAT_LOW45_REST10(
        timingVariant = RhythmTimingVariantKey.TRACKED,
        modes = setOf(RhythmDeriveMode.BEAT_FIRST),
        selection = OnsetSelection.BALANCED,
        laneStrategy = LaneStrategy.BAND_ORDER,
        maxChord = 2,
        minRmsPercentile = null,
        maxLocalNps = null,
        beatDrumGate = BeatDrumGate.LOW_RATIO,
        beatRestThreshold = 0.10,
        beatDensityCap = null,
        beatLowRatioMin = 0.45,
    ),

    /**
     * 0.4.3-pisces.1 output: classic grid, frozen quantization rules, old lane/chord defaults.
     * The compatibility preset: it is the only configuration that reproduces the accepted
     * classic-phase charts — the 544-note golden comes out of this path note for note — which
     * none of the tracked-phase variants (v1-v4) can do. Kept on the side, not the default:
     * real-device play showed the classic phase needs added delay to match the music.
     */
    CLASSIC_043(
        timingVariant = RhythmTimingVariantKey.CLASSIC,
        modes = setOf(RhythmDeriveMode.CLASSIC),
        selection = OnsetSelection.BALANCED,
        laneStrategy = LaneStrategy.BAND_ORDER,
        maxChord = 2,
        minRmsPercentile = null,
        maxLocalNps = null,
        beatDrumGate = BeatDrumGate.BAND,
        beatRestThreshold = 0.10,
        beatDensityCap = null,
        beatLowRatioMin = 0.45,
    ),

    /** Earlier measured-best combination: tracked grid + onset selection with melody priority. */
    RECOMMENDED(
        timingVariant = RhythmTimingVariantKey.TRACKED,
        modes = setOf(RhythmDeriveMode.ONSET),
        selection = OnsetSelection.MELODY_FIRST,
        laneStrategy = LaneStrategy.BAND_ORDER,
        maxChord = 2,
        minRmsPercentile = null,
        maxLocalNps = null,
        beatDrumGate = BeatDrumGate.BAND,
        beatRestThreshold = 0.10,
        beatDensityCap = null,
        beatLowRatioMin = 0.45,
    ),
    ;

    /** Write this preset's configuration into [settings]; the difficulty ladder is left alone. */
    fun applyTo(settings: RhythmDeriveSettings): RhythmDeriveSettings = settings.copy(
        timingVariant = timingVariant,
        modes = modes,
        selection = selection,
        laneStrategy = laneStrategy,
        maxChord = maxChord,
        minRmsPercentile = minRmsPercentile,
        maxLocalNps = maxLocalNps,
        beatDrumGate = beatDrumGate,
        beatRestThreshold = beatRestThreshold,
        beatDensityCap = beatDensityCap,
        beatLowRatioMin = beatLowRatioMin,
    )

    fun matches(settings: RhythmDeriveSettings): Boolean =
        settings.timingVariant == timingVariant &&
            settings.modes == modes &&
            settings.selection == selection &&
            settings.laneStrategy == laneStrategy &&
            settings.maxChord == maxChord &&
            settings.minRmsPercentile == minRmsPercentile &&
            settings.maxLocalNps == maxLocalNps &&
            settings.beatDrumGate == beatDrumGate &&
            settings.beatRestThreshold == beatRestThreshold &&
            settings.beatDensityCap == beatDensityCap &&
            settings.beatLowRatioMin == beatLowRatioMin

    companion object {
        /**
         * The default the derive sheet opens on: [V1_GRID_TRACKED], the configuration closest
         * to the hand-tapped feel and the one that plays on-beat without per-chart delay.
         * [CLASSIC_043] stays one tap away for reproducing classic-phase charts like the
         * 544-note golden.
         */
        fun defaultSettings(): RhythmDeriveSettings =
            V1_GRID_TRACKED.applyTo(RhythmDeriveSettings())

        /** The preset [settings] currently equals, or null when it is custom. */
        fun match(settings: RhythmDeriveSettings): RhythmDerivePreset? =
            entries.firstOrNull { it.matches(settings) }
    }
}
