/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §17).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlin.math.abs

/**
 * Note filters that used to run between analysis and [RhythmChartGenerator.renderMaster].
 *
 * They now run at derivation time, so a master keeps every onset and the filters cost no decode.
 * The order is fixed — loudness first, then density — because that is the order the generation
 * path applied them in; swapping it changes the kept set.
 */
internal object RhythmDeriveFilters {

    /** Window of the density cap: a centred 1 s window, exactly as the generator measured it. */
    internal const val DENSITY_WINDOW_MS = 500.0

    /** Apply both filters in the historical order. `null` means the filter is off. */
    fun apply(
        notes: List<RhythmNote>,
        minRms: Float?,
        maxLocalNps: Double?,
    ): List<RhythmNote> {
        var filtered = notes
        if (minRms != null) filtered = applyLoudness(filtered, minRms)
        if (maxLocalNps != null) filtered = applyDensityCap(filtered, maxLocalNps)
        return filtered
    }

    /**
     * Drop notes whose onset RMS sits below [threshold].
     *
     * The threshold is the absolute value the master stored, never a percentile recomputed over
     * the onsets: the generation-time cutoffs came from the full-song envelope, a different
     * population. `rms == 0` means "unknown" (osu imports, legacy masters) and is kept.
     */
    fun applyLoudness(notes: List<RhythmNote>, threshold: Float): List<RhythmNote> =
        notes.filter { it.rms == 0f || it.rms >= threshold }

    /**
     * Cap the notes inside any centred 1 s window.
     *
     * Strongest first; a note is dropped once [limit] already-kept notes sit within
     * [DENSITY_WINDOW_MS] of it. This is deliberately not "slide a window and keep the strongest":
     * that changes the kept set, and the goal is to reproduce the generation-time filter exactly.
     */
    fun applyDensityCap(notes: List<RhythmNote>, limit: Double): List<RhythmNote> {
        if (notes.size <= 1) return notes
        val kept = BooleanArray(notes.size)
        val keptTimes = ArrayList<Int>()
        for (index in notes.indices.sortedByDescending { notes[it].strength }) {
            val timeMs = notes[index].timeMs
            var neighbours = 0
            for (keptTime in keptTimes) {
                if (abs(keptTime - timeMs) <= DENSITY_WINDOW_MS) neighbours++
            }
            if (neighbours >= limit) continue
            kept[index] = true
            keptTimes += timeMs
        }
        return notes.filterIndexed { index, _ -> kept[index] }
    }
}
