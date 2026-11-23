/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (MIDI event-driven chart generation,
 * docs/../../RhythmChartLab docs/design/rhythm-midi-chart-plan.md §4.2).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** How generator notes map onto the four lanes. */
enum class MidiLaneStrategy {
    /** The part's pitch range is split into four quantiles; each pitch lands in its quartile. */
    PITCH_RANGE,

    /** Lanes rotate, ignoring pitch — the natural fit for drum parts. */
    ROUND_ROBIN,
}

/**
 * One difficulty rung: one chart per part, quantized onto [gridDiv] cells per beat and
 * thinned to a playable density.
 *
 * @param label Display name for the chart title ("Piano · 简单"); the caller localizes it.
 * @param gridDiv How many cells a beat is divided into (1 = quarter notes).
 * @param maxHitsPerSecond Density cap: surviving hits are spaced at least
 *   `1000 / maxHitsPerSecond` ms apart, which keeps every sliding one-second window at
 *   most [maxHitsPerSecond] hits (integral caps; a fractional cap yields at most its
 *   ceiling). See [RhythmMidiChartGenerator] for the survival priority.
 * @param maxChord Notes per hit, 1..4; extras (weakest first) are dropped.
 */
data class MidiDifficulty(
    val label: String,
    val gridDiv: Int,
    val maxHitsPerSecond: Double,
    val maxChord: Int,
) {
    companion object {
        /**
         * The default rungs: about one hit per second on easy up to four on hard, so a dense
         * piano piece stays playable instead of inheriting the file's raw note density.
         */
        val DEFAULTS: List<MidiDifficulty> = listOf(
            MidiDifficulty(label = "简单", gridDiv = 1, maxHitsPerSecond = 1.0, maxChord = 1),
            MidiDifficulty(label = "标准", gridDiv = 2, maxHitsPerSecond = 2.0, maxChord = 2),
            MidiDifficulty(label = "困难", gridDiv = 4, maxHitsPerSecond = 4.0, maxChord = 2),
        )
    }
}

/**
 * Tuning knobs for [RhythmMidiChartGenerator]. All values are deterministic: the same
 * score and options always produce the same charts, which keeps storage content-addressed
 * and re-generation idempotent.
 */
data class MidiChartOptions(
    /** One chart per entry: the difficulty rungs to generate (order is preserved). */
    val difficulties: List<MidiDifficulty> = MidiDifficulty.DEFAULTS,
    /** Parts with fewer notes produce no chart (a silent control track is not a difficulty). */
    val minNotesPerPart: Int = 16,
    /** Onsets closer than this in one quantization cell count as a chord, not as a stream. */
    val chordWindowMs: Int = 25,
    val laneStrategy: MidiLaneStrategy = MidiLaneStrategy.PITCH_RANGE,
)

/**
 * Turns a parsed [MidiScore] into finished charts, one per part and difficulty.
 *
 * Unlike the DSP pipeline there is no master/derive two-tier: MIDI carries exact note-on
 * times and an exact tempo map, so what the file says is what lands on the grid — notes
 * are quantized onto the tempo-map grid ([snap]) but never re-timed by estimation, and the
 * chart's own timing is the file's tempo map.
 *
 * Quantization semantics per cell (one cell = one hit moment):
 * - the strongest note (highest velocity) always wins the cell;
 * - further notes survive only as chord notes: at most [MidiDifficulty.maxChord] of them,
 *   all within [MidiChartOptions.chordWindowMs] of the primary's original onset, strongest
 *   first — two eighth-notes in a quarter-note cell stay one note, an actual simultaneous
 *   chord becomes two or three keys;
 * - every other note in the cell is dropped.
 *
 * Density clipping runs after chord merging and counts hits, not keys: one chord is one
 * hit against the cap but still carries at most [MidiDifficulty.maxChord] keys. Hits
 * survive in priority order — on the beat first, then strongest (velocity), then earliest —
 * and a hit is dropped when a surviving hit is closer than `1000 / maxHitsPerSecond` ms.
 * That spacing caps the run at [MidiDifficulty.maxHitsPerSecond] hits per second while
 * remaining a pure function of the input, so generation stays deterministic.
 */
object RhythmMidiChartGenerator {

    private const val LANE_COUNT = 4

    fun generate(
        score: MidiScore,
        options: MidiChartOptions = MidiChartOptions(),
    ): List<RhythmChart> {
        val grid = score.timing.sortedBy { it.timeMs }
        if (grid.isEmpty()) return emptyList()
        val charts = ArrayList<RhythmChart>()

        for (part in score.parts) {
            if (part.notes.size < options.minNotesPerPart) continue
            val pitchLane = if (part.isDrum || options.laneStrategy == MidiLaneStrategy.ROUND_ROBIN) {
                null
            } else {
                pitchLaneMap(part)
            }
            for (difficulty in options.difficulties) {
                val chart = buildChart(score, part, grid, difficulty, options, pitchLane)
                if (chart != null) charts += chart
            }
        }
        return charts
    }

    private fun buildChart(
        score: MidiScore,
        part: MidiPart,
        grid: List<RhythmTimingPoint>,
        difficulty: MidiDifficulty,
        options: MidiChartOptions,
        pitchLane: ((Int) -> Int)?,
    ): RhythmChart? {
        val gridDiv = difficulty.gridDiv.coerceAtLeast(1)
        val maxChord = difficulty.maxChord.coerceIn(1, LANE_COUNT)

        // Cell key: the snapped millisecond. Snapping is a pure function of the segment the
        // note falls into, so cell-mates always key identically (no float-noise drift).
        val cells = HashMap<Int, CellNotes>(part.notes.size)
        for (note in part.notes) {
            val cell = snap(note.startMs.toDouble(), grid, gridDiv)
            val acc = cells.getOrPut(cell.timeMs) { CellNotes() }
            acc.notes += note
            if (cell.onBeat) acc.onBeat = true
        }

        // Chord merge per cell: one hit moment per cell, strongest note primary.
        val hits = ArrayList<Hit>(cells.size)
        for (timeMs in cells.keys.sorted()) {
            if (timeMs > score.durationMs) continue
            val acc = cells.getValue(timeMs)
            val candidates = acc.notes.sortedByDescending { it.velocity }
            val primary = candidates.first()
            val chord = arrayListOf(primary)
            for (extra in candidates.drop(1)) {
                if (chord.size >= maxChord) break
                if (extra.startMs - primary.startMs > options.chordWindowMs) break
                chord += extra
            }
            hits += Hit(
                timeMs = timeMs,
                onBeat = acc.onBeat,
                velocity = primary.velocity,
                notes = chord,
            )
        }

        val kept = cullDensity(hits, difficulty.maxHitsPerSecond)

        val out = ArrayList<RhythmNote>(kept.size)
        // Round-robin state: rotates across every emitted note, so consecutive hits land on
        // different lanes and chord notes fan out to the following lanes.
        var rrNext = 0
        for (hit in kept) {
            val usedLanes = HashSet<Int>(hit.notes.size)
            for (note in hit.notes) {
                val desired = pitchLane?.invoke(note.pitch) ?: (rrNext++ % LANE_COUNT)
                val lane = freeLane(desired, usedLanes) ?: continue
                usedLanes.add(lane)
                out += RhythmNote(
                    id = out.size,
                    timeMs = hit.timeMs,
                    lane = lane,
                    strength = note.velocity / 127f,
                )
            }
        }
        if (out.isEmpty()) return null

        return RhythmChart(
            source = RhythmChart.SOURCE_MIDI,
            difficultyName = "${part.name} · ${difficulty.label}",
            timing = grid,
            notes = out,
            durationMs = score.durationMs,
        ).withoutLeadIn()
    }

    /**
     * Density clipping: [hits] survive in priority order — on-beat first, then strongest,
     * then earliest — and a hit is dropped when a surviving hit is closer than the minimum
     * spacing for [maxHitsPerSecond]. The result is returned in time order, because the
     * round-robin lane rotation walks the chart forward. Deterministic: the priority order
     * is total (hit times are unique) and admission depends only on already-surviving hits.
     */
    private fun cullDensity(hits: List<Hit>, maxHitsPerSecond: Double): List<Hit> {
        val minGapMs = minGapMs(maxHitsPerSecond)
        if (minGapMs <= 0 || hits.size < 2) return hits
        val ranked = hits.sortedWith(
            compareByDescending<Hit> { it.onBeat }
                .thenByDescending { it.velocity }
                .thenBy { it.timeMs },
        )
        val kept = ArrayList<Hit>(hits.size)
        for (hit in ranked) {
            if (kept.all { abs(it.timeMs - hit.timeMs) >= minGapMs }) kept += hit
        }
        return kept.sortedBy { it.timeMs }
    }

    /**
     * Minimum spacing between surviving hits for a cap of [maxHitsPerSecond]: 1000 ms at
     * one hit per second, 250 ms at four. Non-finite caps (and the meaningless non-positive
     * ones) disable clipping instead of emptying the chart.
     */
    private fun minGapMs(maxHitsPerSecond: Double): Int = when {
        !maxHitsPerSecond.isFinite() || maxHitsPerSecond <= 0.0 -> 0
        else -> ceil(1000.0 / maxHitsPerSecond).toInt().coerceAtLeast(1)
    }

    /**
     * Snaps [timeMs] onto the [gridDiv]-subdivided beat grid described by [grid].
     *
     * The grid is piecewise (the file's tempo map): the note is snapped with the beat
     * length of the segment it falls into, and a segment boundary beat is owned by the
     * following segment, so a note on the boundary lands at the same ms from both sides.
     * A cell is on the beat when its index within the segment is a whole beat
     * (`cell % gridDiv == 0`), i.e. its snapped time is a beat point of the tempo map.
     */
    private fun snap(timeMs: Double, grid: List<RhythmTimingPoint>, gridDiv: Int): Cell {
        var segIndex = grid.indexOfLast { it.timeMs <= timeMs }
        if (segIndex < 0) segIndex = 0
        val seg = grid[segIndex]
        val cellMs = seg.beatMs / gridDiv
        val offset = timeMs - seg.timeMs
        val cell = floor(offset / cellMs).toLong()
        val snapped = seg.timeMs + cell * cellMs
        // A note exactly on the last boundary of a segment snaps to the same ms from the
        // next segment's cell 0; round to Int so both sides key the identical cell.
        return Cell(timeMs = snapped.toInt(), onBeat = cell % gridDiv == 0L)
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

    /** One snapped cell: the key notes merge under and whether it sits on a beat. */
    private data class Cell(val timeMs: Int, val onBeat: Boolean)

    /** Accumulator for the notes that quantize into one cell. */
    private class CellNotes {
        val notes = ArrayList<MidiNote>(2)
        var onBeat = false
    }

    /**
     * One hit moment after chord merging: a chord is a single [Hit] whose [notes] carry its
     * keys (strongest first), so density clipping counts it once.
     */
    private data class Hit(
        val timeMs: Int,
        val onBeat: Boolean,
        val velocity: Int,
        val notes: List<MidiNote>,
    )
}
