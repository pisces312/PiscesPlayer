/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §4.3).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

/**
 * Turns the per-song master chart into one difficulty.
 *
 * The master holds every detected onset, so a difficulty is simply "one note per cell, keep the
 * strongest" — the same rule the generator applies when it works straight from the analysis.
 * That is what makes the ladder cheap: no PCM, no STFT, no tempo estimate, just a grouping pass
 * over notes that are already sorted by time.
 *
 * Two details are load-bearing:
 * - the cell key is the grid **index**, not the snapped timestamp, so floating point noise can
 *   not move a note into the neighbouring cell;
 * - the note is re-emitted at the cell time, not at the master's timestamp. A difficulty is a
 *   quantized chart; keeping the raw onset time would leave notes off the beat grid;
 * - lane de-duplication is re-applied here. The master de-duplicated lanes across *all* onsets,
 *   so thinning it out can bring two same-lane notes next to each other.
 */
internal object RhythmChartDeriver {

    fun derive(
        master: RhythmChart,
        difficulty: DspDifficulty,
        laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
    ): RhythmChart {
        require(master.notes.isNotEmpty()) { "master chart has no notes" }
        val timing = master.timing.firstOrNull() ?: RhythmTimingPoint(0, DEFAULT_BEAT_MS)
        val grid = timing.beatMs / difficulty.gridDiv
        require(grid > 0.0) { "invalid beat length ${timing.beatMs}" }
        val offset = timing.timeMs.toDouble()

        // One note per cell: strongest wins, ties keep the earlier note (strict >), matching the
        // generator's tie-break so both paths agree on the same input.
        val best = HashMap<Long, RhythmNote>(master.notes.size)
        for (note in master.notes) {
            val cell = Math.round((note.timeMs - offset) / grid)
            val previous = best[cell]
            if (previous == null || note.strength > previous.strength) best[cell] = note
        }

        val kept = best.values.sortedBy { it.timeMs }
        val notes = ArrayList<RhythmNote>(kept.size)
        var lastLane = -1
        for (note in kept) {
            val cell = Math.round((note.timeMs - offset) / grid)
            val timeMs = Math.round(offset + cell * grid).toInt()
            // Snapping can push the last note of a song past its end; the generator drops those
            // too, so drop them here to keep both paths producing the same note list.
            if (timeMs < 0 || timeMs > master.durationMs) continue
            val lane = laneOf(note, laneStrategy)
            val adjusted = avoidRepeatedLane(lane, lastLane, note.band)
            notes += RhythmNote(
                id = notes.size,
                timeMs = timeMs,
                lane = adjusted,
                strength = note.strength,
                band = note.band,
            )
            lastLane = adjusted
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

    /** 120 BPM, only used when a chart somehow carries no timing point. */
    private const val DEFAULT_BEAT_MS = 500.0
}
