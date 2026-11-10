/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS.
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlin.math.abs
import kotlin.math.min

/**
 * FROZEN REPLICA — do not refactor, do not "share" anything with the live paths.
 *
 * This is the grid derivation exactly as it shipped in the 0.4.3-pisces.1 debug builds
 * (2026-11-01 era: `RhythmChartDeriver.derive` at commit 8250c1bb with the chord expansion),
 * copied into its own file on 2026-11-04 because the live deriver kept evolving (piecewise
 * grids, onset selection, tempo following, play-logic re-bakes) and that evolution changed
 * charts people had already accepted. Everything this needs — the grid, the cell rule, the
 * chord rule, the lane mapping and the lane avoidance — is defined below, so later edits to
 * [RhythmChartDeriver], [LaneStrategy] or the timing model cannot change this output.
 *
 * The one adaptation from the original: the era's masters carried exactly ONE timing point
 * (the song-global beat), while a current master carries a tracked piecewise grid. The single
 * fixed-rate grid used here is anchored at the supplied timing's first point with the MEDIAN
 * beat of that timing; on a single-point timing (the classic variant, i.e. the shape this
 * replica was written from) that is exactly the old code path.
 *
 * The enum `when`s at the boundary are deliberate trip-wires: adding a difficulty or a lane
 * strategy must fail compilation here instead of silently changing these charts.
 */
internal object RhythmClassicGridDeriver {

    /** Hard ceiling on notes per cell: lanes are only four, and three is already a lot to hit. */
    private const val MAX_CHORD_NOTES = 3

    /**
     * A secondary note has to be at least this strong relative to the cell's strongest one.
     * A noise floor, not a musical threshold: without it a barely-detected blip next to a loud
     * kick would be promoted into a chord.
     */
    private const val CHORD_MIN_STRENGTH_RATIO = 0.2f

    /**
     * How close two onsets have to be to count as one hit on two keys, in milliseconds.
     * 70 ms sits between the PERFECT (60) and GREAT (90) judgement windows, i.e. close enough
     * that one press of two fingers reads as simultaneous rather than as two separate hits.
     */
    private const val CHORD_WINDOW_MS = 70.0

    /** 120 BPM, only used when a chart somehow carries no timing point. */
    private const val DEFAULT_BEAT_MS = 500.0

    private const val LANE_COUNT = 4

    /** Low frequency to the outside lanes; index = band 0..7. */
    private val LOW_OUTSIDE_LANES = intArrayOf(0, 0, 3, 3, 2, 2, 1, 1)

    /**
     * Quantize the master onto the frozen single grid.
     *
     * @param maxChord notes allowed per cell, 1..3. The derive sheet defaults to 2 (double).
     */
    fun derive(
        master: RhythmChart,
        difficulty: DspDifficulty,
        laneStrategy: LaneStrategy,
        maxChord: Int,
        /** Grid source; the derive sheet passes the selected timing variant. */
        timing: List<RhythmTimingPoint> = master.timing,
    ): RhythmChart {
        require(master.notes.isNotEmpty()) { "master chart has no notes" }
        val gridDiv = gridDivOf(difficulty)
        val grid = globalBeatMs(timing) / gridDiv
        require(grid > 0.0) { "invalid beat length ${globalBeatMs(timing)}" }
        val offset = globalAnchorMs(timing).toDouble()
        val chord = maxChord.coerceIn(1, MAX_CHORD_NOTES)

        // One bucket per cell index. The strongest note wins the cell; the extras only survive
        // when chord notes are allowed, so with maxChord = 1 this is the single-note ladder.
        val cells = HashMap<Long, MutableList<RhythmNote>>(master.notes.size)
        for (note in master.notes) {
            val cell = Math.round((note.timeMs - offset) / grid)
            cells.getOrPut(cell) { ArrayList(2) } += note
        }

        val notes = ArrayList<RhythmNote>(master.notes.size)
        var lastLane = -1
        for (cell in cells.keys.sorted()) {
            val timeMs = Math.round(offset + cell * grid).toInt()
            // Snapping can push the last note of a song past its end; drop those.
            if (timeMs < 0 || timeMs > master.durationMs) continue

            // Strongest first, ties to the earlier onset: that is the generator's tie-break.
            val candidates = cells.getValue(cell).sortedWith(CANDIDATE_ORDER)
            val primary = candidates.first()
            val primaryLane = avoidRepeatedLane(laneOf(primary, laneStrategy), lastLane, primary.band)
            notes += RhythmNote(
                id = notes.size,
                timeMs = timeMs,
                lane = primaryLane,
                strength = primary.strength,
                band = primary.band,
            )
            lastLane = primaryLane

            if (chord == 1 || candidates.size == 1) continue
            val used = HashSet<Int>(chord)
            used += primaryLane
            val extras = ArrayList<RhythmNote>(chord - 1)
            for (index in 1 until candidates.size) {
                if (extras.size >= chord - 1) break
                val extra = candidates[index]
                // Sorted by strength, so once one is too weak every later one is as well.
                if (extra.strength < primary.strength * CHORD_MIN_STRENGTH_RATIO) break
                // One hit on two keys means the two onsets fire together; anything further
                // apart is a separate note the grid simply cannot place.
                if (abs(extra.timeMs - primary.timeMs) > CHORD_WINDOW_MS) continue
                val lane = laneOf(extra, laneStrategy)
                if (!used.add(lane)) continue
                extras += extra
            }
            // Ascending lane keeps chords canonical in the stored JSON.
            extras.sortBy { laneOf(it, laneStrategy) }
            for (extra in extras) {
                notes += RhythmNote(
                    id = notes.size,
                    timeMs = timeMs,
                    lane = laneOf(extra, laneStrategy),
                    strength = extra.strength,
                    band = extra.band,
                )
            }
        }

        return master.copy(difficultyName = difficulty.label, notes = notes)
    }

    /** Frozen difficulty ladder: one beat cell / half-beat cell / quarter-beat cell. */
    private fun gridDivOf(difficulty: DspDifficulty): Int = when (difficulty) {
        DspDifficulty.EASY -> 1
        DspDifficulty.NORMAL -> 2
        DspDifficulty.HARD -> 4
    }

    /** The single grid's beat length: the median of the supplied timing points. */
    private fun globalBeatMs(timing: List<RhythmTimingPoint>): Double {
        val beats = timing.map { it.beatMs }.sorted()
        return if (beats.isEmpty()) DEFAULT_BEAT_MS else beats[beats.size / 2]
    }

    /** The single grid's anchor: where the supplied grid starts. */
    private fun globalAnchorMs(timing: List<RhythmTimingPoint>): Int =
        timing.minByOrNull { it.timeMs }?.timeMs ?: 0

    /**
     * Two adjacent notes must not share a lane: a repeated lane reads as a single long note.
     */
    private fun avoidRepeatedLane(lane: Int, lastLane: Int, band: Int): Int =
        if (lane == lastLane) {
            (lane + 1 + band.coerceAtLeast(0) % 2) % LANE_COUNT
        } else {
            lane
        }

    /**
     * Re-lane from the stored frequency band when available. Charts without band data (osu
     * imports, charts stored before the ladder) keep whatever lane they already had.
     */
    private fun laneOf(note: RhythmNote, strategy: LaneStrategy): Int =
        if (note.band >= 0) laneFor(note.band, strategy) else note.lane

    /** Frozen lane tables: `lane = band * lanes / bands` and the low-outside variant. */
    private fun laneFor(band: Int, strategy: LaneStrategy): Int = when (strategy) {
        LaneStrategy.BAND_ORDER -> min(LANE_COUNT - 1, band.coerceAtLeast(0) * LANE_COUNT / 8)
        LaneStrategy.LOW_OUTSIDE -> LOW_OUTSIDE_LANES[band.coerceIn(0, 7)]
    }

    private val CANDIDATE_ORDER =
        compareByDescending<RhythmNote> { it.strength }.thenBy { it.id }
}
