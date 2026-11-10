/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, GPL-3.0-or-later
 */
package com.pisces312.piscesplayer.data.rhythm

/**
 * Which grid logic places the notes for one run — the A/B switch behind the game-start dialog.
 *
 * The run re-bakes the chart's note times with the chosen logic in memory and never writes the
 * result back: note count, lanes and chords stay exactly as stored, only the times move, so two
 * runs differ in nothing but the placement.
 *
 * The placement maths is shared by every logic ([RhythmChartDeriver.snapTime], which is the
 * osu! editor's snap formula: anchor at the timing point, round to `beatMs / gridDiv`, never
 * accumulate against the previous note). What differs is *which* timing points the notes are
 * placed against:
 *
 * - [AS_STORED]: no re-bake — the control group.
 * - [ADAPTIVE_GRID]: the tracked lattice ([RhythmChart.timing], the estimator's output: one
 *   beat lattice with an evidence-locked phase and a tempo-following local spacing).
 * - [OSU_STYLE]: one global timing point (the median beat) for the whole song — what a basic
 *   osu! map does. osu! itself never estimates timing (the mapper places the points and the
 *   engine trusts them blindly), so this is the honest single-BPM baseline: ideal on a
 *   metronomic song, walking off the beat wherever the song really speeds up or slows down.
 * - [LEGACY_REFINE]: [RhythmTimingRefiner]'s piecewise grid rebuilt from onset strengths — the
 *   pre-lattice estimator, kept as the regression baseline (known to drift mid-song).
 */
enum class RhythmPlayLogic {
    AS_STORED,
    ADAPTIVE_GRID,
    OSU_STYLE,
    LEGACY_REFINE,
    ;

    /**
     * [chart] with every note's time moved onto the chosen grid. [master] is the chart's master
     * (the full onset set) and only feeds [LEGACY_REFINE]; without it the chart's own notes are
     * the refiner's input. [gridDiv] is the difficulty's snap divisor (1 = whole beat cells).
     */
    fun retime(
        chart: RhythmChart,
        master: RhythmChart? = null,
        gridDiv: Int,
    ): RhythmChart = when (this) {
        AS_STORED -> chart
        ADAPTIVE_GRID -> snapOnto(chart, sortedPoints(chart.timing), gridDiv)
        OSU_STYLE -> snapOnto(chart, listOf(globalPoint(chart)), gridDiv)
        LEGACY_REFINE -> snapOnto(chart, refinedPoints(chart, master), gridDiv)
    }

    private fun snapOnto(
        chart: RhythmChart,
        points: List<RhythmTimingPoint>,
        gridDiv: Int,
    ): RhythmChart {
        val divisor = gridDiv.coerceAtLeast(1)
        return chart.copy(
            notes = chart.notes.map { note ->
                note.copy(
                    timeMs = RhythmChartDeriver.snapTime(note.timeMs, points, divisor)
                        .coerceAtLeast(0),
                )
            },
            timing = points,
        )
    }

    companion object {
        /** Beat length used when a chart carries no timing points at all. */
        private const val FALLBACK_BEAT_MS = 500.0

        /** Route-arg parsing: a stale or foreign name simply means "as stored". */
        fun from(raw: String?): RhythmPlayLogic? =
            raw?.let { name -> entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }

        private fun sortedPoints(timing: List<RhythmTimingPoint>): List<RhythmTimingPoint> =
            timing.sortedBy { it.timeMs }.ifEmpty { listOf(RhythmTimingPoint(0, FALLBACK_BEAT_MS)) }

        /** One timing point over the whole song, anchored where the lattice starts. */
        private fun globalPoint(chart: RhythmChart): RhythmTimingPoint {
            val points = sortedPoints(chart.timing)
            val beats = points.map { it.beatMs }.sorted()
            val medianBeat = beats[beats.size / 2]
            return RhythmTimingPoint(points.first().timeMs, medianBeat)
        }

        private fun refinedPoints(
            chart: RhythmChart,
            master: RhythmChart?,
        ): List<RhythmTimingPoint> {
            val onsets = (master?.notes ?: chart.notes)
                .map { RhythmTimingRefiner.Onset(it.timeMs, it.strength) }
            val global = chart.timing.firstOrNull() ?: RhythmTimingPoint(0, FALLBACK_BEAT_MS)
            return RhythmTimingRefiner.refine(
                onsets = onsets,
                global = global,
                durationMs = master?.durationMs ?: chart.durationMs,
            ).sortedBy { it.timeMs }
        }
    }
}
