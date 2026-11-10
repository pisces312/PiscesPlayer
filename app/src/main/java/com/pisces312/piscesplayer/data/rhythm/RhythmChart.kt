/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (unified chart model semantics, docs/rhythm-game-integration-plan.md §4).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlinx.serialization.Serializable

/**
 * A single 4K tap note. `timeMs` is absolute song time in milliseconds.
 *
 * [strength] and [band] are only populated by the DSP pipeline: the master chart keeps them so
 * a difficulty can be derived later without decoding audio again (the derivation needs to know
 * which onset wins a quantization cell, and which frequency band to re-map to a lane).
 */
@Serializable
data class RhythmNote(
    val id: Int,
    val timeMs: Int,
    val lane: Int,
    /** Peak strength in 0..1-ish; 0 when unknown (osu imports). */
    val strength: Float = 0f,
    /** Dominant frequency band 0..7; -1 when unknown (osu imports). */
    val band: Int = -1,
    /** Local RMS at the onset (40 ms envelope, interpolated); 0 when unknown (osu imports, legacy masters). */
    val rms: Float = 0f,
) {
    init {
        require(timeMs >= 0) { "note timeMs must be >= 0" }
        require(lane in 0..3) { "note lane must be 0..3" }
    }
}

/** Uninherited osu timing point; kept for future DSP generation and quantization. */
@Serializable
data class RhythmTimingPoint(val timeMs: Int, val beatMs: Double)

/** Stable keys for the timing alternatives stored on a DSP master; they never follow the UI language. */
object RhythmTimingVariantKey {
    const val TRACKED = "tracked"
    const val CLASSIC = "classic"
}

/** One selectable beat grid alternative of a master chart. */
@Serializable
data class RhythmTimingVariant(
    val key: String,
    val timing: List<RhythmTimingPoint>,
)

/** Absolute loudness cutoff for one percentile of the master's full-song RMS envelope. */
@Serializable
data class RhythmRmsThreshold(
    /** Percentile of the envelope values, e.g. 25 or 40. */
    val percentile: Int,
    /** Absolute RMS threshold computed at generation time; derivation only compares against it. */
    val rms: Float,
)

/**
 * A timing variant resolved for derivation. [approximate] is true when a legacy master had to be
 * approximated instead of carrying the variant as stored (see [RhythmChart.timingVariantsFor]).
 */
data class RhythmTimingChoice(
    val key: String,
    val timing: List<RhythmTimingPoint>,
    val approximate: Boolean = false,
)

/** Unified internal chart shared by osu import and (future) DSP generation. */
@Serializable
data class RhythmChart(
    val schemaVersion: Int = 1,
    val source: String,
    val difficultyName: String,
    /** Primary timing: playback and every legacy reader use it; a new master stores `tracked` here. */
    val timing: List<RhythmTimingPoint> = emptyList(),
    /** Selectable timing alternatives; only a master writes them, derived charts leave this empty. */
    val timingVariants: List<RhythmTimingVariant> = emptyList(),
    /** Loudness cutoffs for the offered percentiles; only a master writes them. */
    val rmsThresholds: List<RhythmRmsThreshold> = emptyList(),
    val notes: List<RhythmNote> = emptyList(),
    val durationMs: Int = 0,
) {
    init {
        require(schemaVersion == 1) { "unsupported chart schemaVersion $schemaVersion" }
        require(source in SOURCES) { "unsupported chart source $source" }
        require(notes.isNotEmpty()) { "chart must contain at least one note" }
    }

    fun noteCount(): Int = notes.size

    /**
     * Drop every note charted before the first [leadInMs] of the song.
     *
     * A run starts with that much runway, so a note inside it can not be reacted to. Derived
     * charts are stored already trimmed; the master and osu imports are trimmed at play time.
     * A chart living entirely inside the lead-in keeps its notes instead of becoming unplayable.
     */
    fun withoutLeadIn(leadInMs: Double = LEAD_IN_MS): RhythmChart {
        if (leadInMs <= 0.0) return this
        val kept = notes.filter { it.timeMs >= leadInMs }
        if (kept.isEmpty() || kept.size == notes.size) return this
        return copy(notes = kept)
    }

    /** Average notes per second over the chart span. */
    fun averageNps(): Double {
        val span = if (durationMs > 0) durationMs else (notes.last().timeMs + 1)
        return notes.size * 1000.0 / span
    }

    /**
     * Timing variants this chart offers for derivation, in a stable order.
     *
     * A master written by the current build carries both variants already. Older masters carry
     * only [timing], so the alternatives are synthesized on read and nothing is rewritten on disk:
     * a single-point master is its own classic grid (that is exactly the 0.4.3-pisces.1 shape),
     * while a piecewise master gets an approximate classic grid (median beat + earliest anchor),
     * which the UI labels as approximate.
     */
    fun timingVariantsFor(): List<RhythmTimingChoice> {
        if (timingVariants.isNotEmpty()) {
            return timingVariants.map { RhythmTimingChoice(it.key, it.timing) }
        }
        if (timing.isEmpty()) return emptyList()
        if (timing.size == 1) {
            return listOf(
                RhythmTimingChoice(RhythmTimingVariantKey.TRACKED, timing),
                RhythmTimingChoice(RhythmTimingVariantKey.CLASSIC, timing),
            )
        }
        val beats = timing.map { it.beatMs }.sorted()
        val classic = listOf(
            RhythmTimingPoint(
                timeMs = timing.minOf { it.timeMs },
                beatMs = beats[beats.size / 2],
            ),
        )
        return listOf(
            RhythmTimingChoice(RhythmTimingVariantKey.TRACKED, timing),
            RhythmTimingChoice(RhythmTimingVariantKey.CLASSIC, classic, approximate = true),
        )
    }

    /** Absolute loudness cutoff for [percentile]; null when the master has no RMS data for it. */
    fun rmsThresholdFor(percentile: Int): Float? =
        rmsThresholds.firstOrNull { it.percentile == percentile }?.rms

    companion object {
        val SOURCES = setOf(SOURCE_OSU, SOURCE_DSP, SOURCE_PRC)
        const val SOURCE_OSU = "osu"
        const val SOURCE_DSP = "dsp"
        const val SOURCE_PRC = "prc"

        /** Runway before the first playable note; derived charts are stored without anything earlier. */
        const val LEAD_IN_MS = 1000.0

        /** [RhythmChartMeta.difficultyKey] of the per-song master chart. */
        const val DIFFICULTY_RAW = "raw"
    }
}

/** Persisted per-song chart metadata and best score. */
@Serializable
data class RhythmChartMeta(
    val chartId: String,
    var songId: String,
    val source: String,
    val difficultyName: String,
    val noteCount: Int,
    val averageNps: Double,
    val durationMs: Int,
    val importedAtMs: Long,
    var bestScore: Int = 0,
    /**
     * `"raw"` for the single master chart, a [DspDifficulty] name in lower case for derived
     * charts, null for osu imports and for charts stored before the ladder existed.
     */
    val difficultyKey: String? = null,
    /** Derived charts point at the master they came from; the master itself has no parent. */
    val parentChartId: String? = null,
    /**
     * [importedAtMs] of the master at derivation time. When it no longer matches the current
     * master's timestamp the chart was derived from an older analysis (different beat grid),
     * which the list surfaces as a "master updated" hint.
     */
    val masterGeneratedAtMs: Long? = null,
)

@Serializable
internal data class RhythmLibraryData(
    val schemaVersion: Int = 1,
    val songs: MutableMap<String, MutableList<RhythmChartMeta>> = mutableMapOf(),
)
