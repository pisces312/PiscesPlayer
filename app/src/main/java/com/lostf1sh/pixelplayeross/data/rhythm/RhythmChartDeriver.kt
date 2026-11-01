/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §4.3).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlin.math.abs

/**
 * Turns the per-song master chart into one difficulty.
 *
 * The master holds every detected onset, so a difficulty is simply "one note per cell, keep the
 * strongest" — the same rule the generator applies when it works straight from the analysis.
 * That is what makes the ladder cheap: no PCM, no STFT, no tempo estimate, just a grouping pass
 * over notes that are already sorted by time.
 *
 * Four details are load-bearing:
 * - the cell key is the grid **index**, not the snapped timestamp, so floating point noise can
 *   not move a note into the neighbouring cell;
 * - the note is re-emitted at the cell time, not at the master's timestamp. A difficulty is a
 *   quantized chart; keeping the raw onset time would leave notes off the beat grid;
 * - lane de-duplication is re-applied here. The master de-duplicated lanes across *all* onsets,
 *   so thinning it out can bring two same-lane notes next to each other;
 * - a cell may hold more than one note ([maxChord]). The onset detector runs on a single
 *   summed onset function, so a kick and a snare hitting together collapse into one peak and
 *   never become a chord on their own; recovering the second note from the other onsets that
 *   share the cell is what produces double and triple notes.
 */
internal object RhythmChartDeriver {

    /** Hard ceiling on notes per cell: lanes are only four, and three is already a lot to hit. */
    const val MAX_CHORD_NOTES = 3

    /**
     * A secondary note has to be at least this strong relative to the cell's strongest one.
     * This is a noise floor, not a musical threshold: without it a barely-detected blip next to
     * a loud kick would be promoted into a chord.
     */
    internal const val CHORD_MIN_STRENGTH_RATIO = 0.2f

    /**
     * How close two onsets have to be to count as one hit on two keys, in milliseconds.
     *
     * Without this a coarse grid would turn into a chord machine: an Easy cell spans a whole
     * beat, so every beat would become a double and Easy would stop being easier than Normal.
     * 70 ms sits between the PERFECT (60) and GREAT (90) judgement windows, i.e. close enough
     * that one press of two fingers reads as simultaneous rather than as two separate hits.
     */
    internal const val CHORD_WINDOW_MS = 70.0

    /**
     * @param maxChord notes allowed per cell, 1..[MAX_CHORD_NOTES]. 1 reproduces the single-note
     *   ladder exactly, which is what the golden fixture (and the Python reference) expects.
     */
    fun derive(
        master: RhythmChart,
        difficulty: DspDifficulty,
        laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
        maxChord: Int = 1,
    ): RhythmChart {
        require(master.notes.isNotEmpty()) { "master chart has no notes" }
        val timing = master.timing.firstOrNull() ?: RhythmTimingPoint(0, DEFAULT_BEAT_MS)
        val grid = timing.beatMs / difficulty.gridDiv
        require(grid > 0.0) { "invalid beat length ${timing.beatMs}" }
        val offset = timing.timeMs.toDouble()
        val chord = maxChord.coerceIn(1, MAX_CHORD_NOTES)

        // One bucket per cell. The strongest note wins the cell; the extras only survive when
        // chord notes are allowed, so with maxChord = 1 this is byte-for-byte the old behaviour.
        val cells = HashMap<Long, MutableList<RhythmNote>>(master.notes.size)
        for (note in master.notes) {
            val cell = Math.round((note.timeMs - offset) / grid)
            cells.getOrPut(cell) { ArrayList(2) } += note
        }

        val notes = ArrayList<RhythmNote>(master.notes.size)
        var lastLane = -1
        for (cell in cells.keys.sorted()) {
            val timeMs = Math.round(offset + cell * grid).toInt()
            // Snapping can push the last note of a song past its end; the generator drops those
            // too, so drop them here to keep both paths producing the same note list.
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

    /**
     * Re-lane from the stored frequency band when available, which lets one master feed several
     * lane strategies. Charts without band data (osu imports, charts stored before the ladder)
     * keep whatever lane they already had.
     */
    private fun laneOf(note: RhythmNote, strategy: LaneStrategy): Int =
        if (note.band >= 0) strategy.laneFor(note.band) else note.lane

    private val CANDIDATE_ORDER =
        compareByDescending<RhythmNote> { it.strength }.thenBy { it.id }

    /** 120 BPM, only used when a chart somehow carries no timing point. */
    private const val DEFAULT_BEAT_MS = 500.0
}
