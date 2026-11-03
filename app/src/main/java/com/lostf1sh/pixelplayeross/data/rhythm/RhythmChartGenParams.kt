/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-dsp-chart-generation-plan.md §5.2).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlin.math.min

/**
 * Selection budget for v3 derivation (`RhythmChartDeriver.deriveOnsetNative`).
 *
 * A difficulty is a density, not a grid: the deriver only picks which master onsets survive and
 * never rewrites their timestamps (`docs/rhythm-chart-ladder-plan.md` §17). Both limits apply at
 * once and the stricter one wins — the 1 s cap alone still lets four notes bunch into 100 ms and
 * "pay it back" with a silent second, which [minGapMs] forbids.
 */
data class DspDeriveProfile(
    /** Notes allowed inside any 1 s window; a chord costs one slot per note. */
    val maxNotesPerSecond: Double,
    /** Minimum gap from the last note of one hit to the first note of the next. */
    val minGapMs: Double,
    /** Optional whole-song ceiling, for very long tracks. `null` = no absolute cap. */
    val maxNotes: Int? = null,
    /** MELODY-FIRST slot split per second; unused by [OnsetSelection.BALANCED]. */
    val melodySlots: Int = 1,
    val percSlots: Int = 1,
) {
    init {
        require(maxNotesPerSecond > 0.0) { "maxNotesPerSecond must be > 0" }
        require(minGapMs >= 0.0) { "minGapMs must be >= 0" }
        require(maxNotes == null || maxNotes > 0) { "maxNotes must be > 0 when set" }
        require(melodySlots > 0 && percSlots > 0) { "melodySlots/percSlots must be > 0" }
    }
}

/**
 * Difficulty ladder for the DSP generator.
 *
 * [deriveProfile] is the v3 definition: a difficulty is a note-density budget with no grid.
 * [gridDiv] and [slotName] are the v2 quantization grid, kept only for the legacy
 * `RhythmChartDeriver.derive`/`RhythmChartGenerator.render` path (osu-style grids and the golden
 * fixtures). Measured v2 densities on the reference song (261.9 s / 90.84 BPM): 1/1 -> 1.55 NPS,
 * 1/2 -> 3.03 NPS, 1/4 -> 5.54 NPS, which is what the v3 caps below start from.
 */
enum class DspDifficulty(
    val gridDiv: Int,
    val label: String,
    val deriveProfile: DspDeriveProfile,
) {
    EASY(1, "Easy", DspDeriveProfile(maxNotesPerSecond = 2.0, minGapMs = 300.0)),
    NORMAL(2, "Normal", DspDeriveProfile(maxNotesPerSecond = 4.0, minGapMs = 150.0, melodySlots = 3, percSlots = 1)),
    HARD(4, "Hard", DspDeriveProfile(maxNotesPerSecond = 7.0, minGapMs = 75.0, melodySlots = 5, percSlots = 2)),
    ;

    /** Beat subdivisions per note slot; 1 = one note per beat. v2 only. */
    val slotName: String
        get() = when (this) {
            EASY -> "1/1"
            NORMAL -> "1/2"
            HARD -> "1/4"
        }
}

/**
 * How a derived difficulty turns the master's onsets into notes.
 *
 * Both modes read the same master: neither re-decodes the song.
 */
enum class RhythmDeriveMode {
    /**
     * Snap onto the piecewise beat grid (`RhythmTimingRefiner`). Timestamps move, but the result
     * reads as a chart: patterns line up with the beat instead of following every onset.
     */
    GRID,

    /**
     * Keep the master's onset times and only select which ones survive. More literal than
     * musical: strong percussion tends to win the density budget and melody phrases thin out.
     */
    ONSET,
}

/** How [RhythmDeriveMode.ONSET] spends its density budget across voice classes. */
enum class OnsetSelection {
    /** One global budget; strong (usually percussive) onsets win it. */
    BALANCED,

    /**
     * Split the budget into melody-band and percussion slots ([DspDeriveProfile.melodySlots] /
     * [DspDeriveProfile.percSlots]) so drum onsets cannot starve the melody line.
     */
    MELODY_FIRST,
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
 *
 * Also used at derivation time, which is why the mapping lives here: the master chart stores
 * the dominant band per note, so re-laning a derived difficulty costs one table lookup.
 */
enum class LaneStrategy {
    BAND_ORDER,
    LOW_OUTSIDE,
    ;

    /** Lane for [band] (0..7). Unknown bands fall back to the leftmost lane. */
    fun laneFor(band: Int): Int = when (this) {
        BAND_ORDER -> min(LANE_COUNT - 1, band.coerceAtLeast(0) * LANE_COUNT / BAND_COUNT)
        LOW_OUTSIDE -> LOW_OUTSIDE_LANES[band.coerceIn(0, BAND_COUNT - 1)]
    }

    companion object {
        const val LANE_COUNT = 4
        private const val BAND_COUNT = 8

        /** Low frequency to the outside lanes; index = band. */
        private val LOW_OUTSIDE_LANES = intArrayOf(0, 0, 3, 3, 2, 2, 1, 1)
    }
}

/**
 * Two adjacent notes must not share a lane: a repeated lane reads as a single long note.
 * Shared by the generator and the deriver so `derive(master)` reproduces `generate()` exactly.
 */
internal fun avoidRepeatedLane(lane: Int, lastLane: Int, band: Int): Int =
    if (lane == lastLane) {
        (lane + 1 + band.coerceAtLeast(0) % 2) % LaneStrategy.LANE_COUNT
    } else {
        lane
    }

/**
 * Everything the *analysis* half of the pipeline needs. Difficulty is absent on purpose: the
 * master chart is produced once per song, and every difficulty is derived from it later.
 */
data class RhythmAnalysisParams(
    /**
     * Drop onsets whose local RMS (40 ms window) sits below this percentile of the whole
     * song. `null` = off. Needs the PCM, so it can only be applied while building the master.
     */
    val minRmsPercentile: Int? = null,
    /**
     * Cap notes inside any 1 s window. `null` = off. Also master-level: a difficulty already
     * caps density through its grid.
     */
    val maxLocalNps: Double? = null,
    /** Skip automatic BPM detection (the octave ambiguity is unsolvable for some songs). */
    val bpmOverride: Double? = null,
    /** Shifts the beat grid phase; a generation-time alignment knob, not a playback setting. */
    val extraOffsetMs: Int = 0,
)

/**
 * Everything the generator lets the user tune, for the one-difficulty convenience path.
 *
 * The analysis knobs live in [analysis] because they only apply while the master chart is
 * built; a difficulty derived from an existing master cannot re-run them (they need the PCM).
 */
data class RhythmChartGenParams(
    val difficulty: DspDifficulty = DspDifficulty.EASY,
    val laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
    val analysis: RhythmAnalysisParams = RhythmAnalysisParams(),
)

/** Analysis facts worth showing after a run (and worth logging when quality looks off). */
data class DspDiagnostics(
    val detectedBpm: Double,
    val usedBpm: Double,
    val octaveCorrection: Double,
    /** Anchors in the tracked beat grid; 1 means the tracker fell back to a fixed-rate grid. */
    val timingPoints: Int,
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
