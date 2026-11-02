/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (unified chart model semantics, docs/rhythm-game-integration-plan.md §4).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

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
) {
    init {
        require(timeMs >= 0) { "note timeMs must be >= 0" }
        require(lane in 0..3) { "note lane must be 0..3" }
    }
}

/** Uninherited osu timing point; kept for future DSP generation and quantization. */
@Serializable
data class RhythmTimingPoint(val timeMs: Int, val beatMs: Double)

/** Unified internal chart shared by osu import and (future) DSP generation. */
@Serializable
data class RhythmChart(
    val schemaVersion: Int = 1,
    val source: String,
    val difficultyName: String,
    val timing: List<RhythmTimingPoint> = emptyList(),
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

    companion object {
        val SOURCES = setOf(SOURCE_OSU, SOURCE_DSP, SOURCE_PRC)
        const val SOURCE_OSU = "osu"
        const val SOURCE_DSP = "dsp"
        const val SOURCE_PRC = "prc"

        /** Runway before the first playable note; derived charts are stored without anything earlier. */
        const val LEAD_IN_MS = 2000.0

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
