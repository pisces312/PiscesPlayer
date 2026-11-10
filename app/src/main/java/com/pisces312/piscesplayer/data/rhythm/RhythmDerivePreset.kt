/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §7).
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
)

/**
 * Every control of the derive sheet in one value.
 *
 * Keeping this a plain data class is what makes preset matching a pure comparison: a preset is
 * selected exactly while every field still equals the values it wrote.
 */
data class RhythmDeriveSettings(
    val timingVariant: String = RhythmTimingVariantKey.TRACKED,
    val modes: Set<RhythmDeriveMode> = RhythmDeriveMode.entries.toSet(),
    val selection: OnsetSelection = OnsetSelection.MELODY_FIRST,
    val laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
    val maxChord: Int = 2,
    val minRmsPercentile: Int? = null,
    val maxLocalNps: Double? = null,
    val difficulties: Set<DspDifficulty> = DspDifficulty.entries.toSet(),
) {
    fun options(): RhythmDeriveOptions = RhythmDeriveOptions(
        timingVariant = timingVariant,
        minRmsPercentile = minRmsPercentile,
        maxLocalNps = maxLocalNps,
    )
}

/**
 * Built-in derive presets. Both are fixed field tables (not "whatever the defaults are today"),
 * so a future default change can not silently move what a preset reproduces.
 */
enum class RhythmDerivePreset(
    val timingVariant: String,
    val modes: Set<RhythmDeriveMode>,
    val selection: OnsetSelection,
    val laneStrategy: LaneStrategy,
    val maxChord: Int,
    val minRmsPercentile: Int?,
    val maxLocalNps: Double?,
) {
    /** 0.4.3-pisces.1 output: classic grid, frozen quantization rules, old lane/chord defaults. */
    CLASSIC_043(
        timingVariant = RhythmTimingVariantKey.CLASSIC,
        modes = setOf(RhythmDeriveMode.CLASSIC),
        selection = OnsetSelection.BALANCED,
        laneStrategy = LaneStrategy.BAND_ORDER,
        maxChord = 2,
        minRmsPercentile = null,
        maxLocalNps = null,
    ),

    /** Current measured-best combination: tracked grid + onset selection with melody priority. */
    RECOMMENDED(
        timingVariant = RhythmTimingVariantKey.TRACKED,
        modes = setOf(RhythmDeriveMode.ONSET),
        selection = OnsetSelection.MELODY_FIRST,
        laneStrategy = LaneStrategy.BAND_ORDER,
        maxChord = 2,
        minRmsPercentile = null,
        maxLocalNps = null,
    ),
    ;

    /** Write this preset's fields into [settings], leaving nothing behind. */
    fun applyTo(settings: RhythmDeriveSettings): RhythmDeriveSettings = settings.copy(
        timingVariant = timingVariant,
        modes = modes,
        selection = selection,
        laneStrategy = laneStrategy,
        maxChord = maxChord,
        minRmsPercentile = minRmsPercentile,
        maxLocalNps = maxLocalNps,
        difficulties = DspDifficulty.entries.toSet(),
    )

    fun matches(settings: RhythmDeriveSettings): Boolean =
        settings.timingVariant == timingVariant &&
            settings.modes == modes &&
            settings.selection == selection &&
            settings.laneStrategy == laneStrategy &&
            settings.maxChord == maxChord &&
            settings.minRmsPercentile == minRmsPercentile &&
            settings.maxLocalNps == maxLocalNps &&
            settings.difficulties == DspDifficulty.entries.toSet()

    companion object {
        /** The preset [settings] currently equals, or null when it is custom. */
        fun match(settings: RhythmDeriveSettings): RhythmDerivePreset? =
            entries.firstOrNull { it.matches(settings) }
    }
}
