/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (unified chart model semantics, docs/rhythm-game-integration-plan.md §4).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlinx.serialization.Serializable

/** A single 4K tap note. `timeMs` is absolute song time in milliseconds. */
@Serializable
data class RhythmNote(val id: Int, val timeMs: Int, val lane: Int) {
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
)

@Serializable
internal data class RhythmLibraryData(
    val schemaVersion: Int = 1,
    val songs: MutableMap<String, MutableList<RhythmChartMeta>> = mutableMapOf(),
)
