/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (MIDI event-driven chart generation,
 * docs/../../RhythmChartLab docs/design/rhythm-midi-chart-plan.md §4.2).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlin.math.floor

/** How generator notes map onto the four lanes. */
enum class MidiLaneStrategy {
    /** The part's pitch range is split into four quantiles; each pitch lands in its quartile. */
    PITCH_RANGE,

    /** Lanes rotate, ignoring pitch — the natural fit for drum parts. */
    ROUND_ROBIN,
}

/**
 * Tuning knobs for [RhythmMidiChartGenerator]. All values are deterministic: the same
 * score and options always produce the same charts, which keeps storage content-addressed
 * and re-generation idempotent.
 */
data class MidiChartOptions(
    /** One chart per entry: how many cells a beat is divided into (1 = quarter notes). */
    val gridDivs: List<Int> = listOf(1, 2, 4),
    /** Parts with fewer notes produce no chart (a silent control track is not a difficulty). */
    val minNotesPerPart: Int = 16,
    /** Onsets closer than this in one quantization cell count as a chord, not as a stream. */
    val chordWindowMs: Int = 25,
    /** Notes per cell, 1..4; extras (weakest first) are dropped. */
    val maxChord: Int = 2,
    val laneStrategy: MidiLaneStrategy = MidiLaneStrategy.PITCH_RANGE,
)

/**
 * Turns a parsed [MidiScore] into finished charts, one per part and grid divisor.
 *
 * Unlike the DSP pipeline there is no master/derive two-tier: MIDI carries exact note-on
 * times and an exact tempo map, so what the file says is what lands on the grid — notes
 * are quantized onto the tempo-map grid ([snap]) but never re-timed by estimation, and the
 * chart's own timing is the file's tempo map.
 *
 * Quantization semantics per cell (one cell = one hit moment):
 * - the strongest note (highest velocity) always wins the cell;
 * - further notes survive only as chord notes: at most [MidiChartOptions.maxChord] of them,
 *   all within [MidiChartOptions.chordWindowMs] of the primary's original onset, strongest
 *   first — two eighth-notes in a quarter-note cell stay one note, an actual simultaneous
 *   chord becomes two or three keys;
 * - every other note in the cell is dropped.
 */
object RhythmMidiChartGenerator {

    private const val LANE_COUNT = 4

    fun generate(
        score: MidiScore,
        options: MidiChartOptions = MidiChartOptions(),
    ): List<RhythmChart> {
        val grid = score.timing.sortedBy { it.timeMs }
        if (grid.isEmpty()) return emptyList()
        val maxChord = options.maxChord.coerceIn(1, LANE_COUNT)
        val charts = ArrayList<RhythmChart>()

        for (part in score.parts) {
            if (part.notes.size < options.minNotesPerPart) continue
            val pitchLane = if (part.isDrum || options.laneStrategy == MidiLaneStrategy.ROUND_ROBIN) {
                null
            } else {
                pitchLaneMap(part)
            }
            for (gridDiv in options.gridDivs.distinct().filter { it >= 1 }.sorted()) {
                val chart = buildChart(score, part, grid, gridDiv, maxChord, options, pitchLane)
                if (chart != null) charts += chart
            }
        }
        return charts
    }

    private fun buildChart(
        score: MidiScore,
        part: MidiPart,
        grid: List<RhythmTimingPoint>,
        gridDiv: Int,
        maxChord: Int,
        options: MidiChartOptions,
        pitchLane: ((Int) -> Int)?,
    ): RhythmChart? {
        // Cell key: the snapped millisecond. Snapping is a pure function of the segment the
        // note falls into, so cell-mates always key identically (no float-noise drift).
        val cells = HashMap<Int, MutableList<MidiNote>>(part.notes.size)
        for (note in part.notes) {
            val snapped = snap(note.startMs.toDouble(), grid, gridDiv)
            cells.getOrPut(snapped) { ArrayList(2) } += note
        }

        val out = ArrayList<RhythmNote>(cells.size)
        // Round-robin state: rotates across every emitted note, so consecutive hits land on
        // different lanes and chord notes fan out to the following lanes.
        var rrNext = 0
        for (timeMs in cells.keys.sorted()) {
            if (timeMs > score.durationMs) continue
            val candidates = cells.getValue(timeMs).sortedByDescending { it.velocity }
            val primary = candidates.first()
            val chord = arrayListOf(primary)
            for (extra in candidates.drop(1)) {
                if (chord.size >= maxChord) break
                if (extra.startMs - primary.startMs > options.chordWindowMs) break
                chord += extra
            }
            val usedLanes = HashSet<Int>(chord.size)
            for (note in chord) {
                val desired = pitchLane?.invoke(note.pitch) ?: (rrNext++ % LANE_COUNT)
                val lane = freeLane(desired, usedLanes) ?: continue
                usedLanes.add(lane)
                out += RhythmNote(
                    id = out.size,
                    timeMs = timeMs,
                    lane = lane,
                    strength = note.velocity / 127f,
                )
            }
        }
        if (out.isEmpty()) return null

        return RhythmChart(
            source = RhythmChart.SOURCE_MIDI,
            difficultyName = "${part.name} · 1/$gridDiv",
            timing = grid,
            notes = out,
            durationMs = score.durationMs,
        ).withoutLeadIn()
    }

    /**
     * Snaps [timeMs] onto the [gridDiv]-subdivided beat grid described by [grid].
     *
     * The grid is piecewise (the file's tempo map): the note is snapped with the beat
     * length of the segment it falls into, and a segment boundary beat is owned by the
     * following segment, so a note on the boundary lands at the same ms from both sides.
     */
    private fun snap(timeMs: Double, grid: List<RhythmTimingPoint>, gridDiv: Int): Int {
        var segIndex = grid.indexOfLast { it.timeMs <= timeMs }
        if (segIndex < 0) segIndex = 0
        val seg = grid[segIndex]
        val cellMs = seg.beatMs / gridDiv
        val offset = timeMs - seg.timeMs
        val cell = floor(offset / cellMs).toLong()
        val snapped = seg.timeMs + cell * cellMs
        // A note exactly on the last boundary of a segment snaps to the same ms from the
        // next segment's cell 0; round to Int so both sides key the identical cell.
        return snapped.toInt()
    }

    /** Pitch → lane: quartiles of the part's range, clamped (single-pitch parts → lane 1). */
    private fun pitchLaneMap(part: MidiPart): (Int) -> Int {
        val min = part.notes.minOf { it.pitch }
        val max = part.notes.maxOf { it.pitch }
        val span = (max - min + 1).coerceAtLeast(1)
        return { pitch -> ((pitch - min) * LANE_COUNT / span).coerceIn(0, LANE_COUNT - 1) }
    }

    /**
     * The lane for a note whose preferred [desired] lane is taken: nudge to a free
     * neighbour so two keys of one chord never share a lane. Null when every lane is
     * taken (a four-note chord on four lanes), which drops the note at the call site.
     */
    private fun freeLane(desired: Int, usedLanes: Set<Int>): Int? {
        if (desired !in usedLanes) return desired
        for (delta in 1 until LANE_COUNT) {
            val up = desired + delta
            if (up < LANE_COUNT && up !in usedLanes) return up
            val down = desired - delta
            if (down >= 0 && down !in usedLanes) return down
        }
        return null
    }
}
