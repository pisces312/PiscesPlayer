/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS.
 */
package com.pisces312.piscesplayer.data.rhythm

/**
 * The classic difficulty ladder as shipped from 2026-11-11: one step easier than the
 * 0.4.3-pisces.1 ladder, plus a new sparser Easy.
 *
 * The frozen replica ([RhythmClassicGridDeriver]) keeps its original 1/1, 1/2, 1/4 ladder
 * forever — its contract tests and the legacy 544-note reproduction pin it. This layer maps
 * the current difficulties onto the replica instead of editing it:
 *
 * - NORMAL -> replica EASY (one cell per beat): exactly the old Easy, so the legacy
 *   note-for-note reproduction now lives at Normal.
 * - HARD -> replica NORMAL (one cell per half beat): the old Normal density.
 * - EASY -> replica EASY on a doubled beat length (one cell per TWO beats): about half the
 *   old Easy note count. Doubling [RhythmTimingPoint.beatMs] keeps the anchors, the chord
 *   rule and the lane avoidance inside the replica; the returned chart still carries the
 *   master's original timing because the replica only replaces notes.
 *
 * Only the CLASSIC derive mode uses this ladder; GRID and ONSET keep their own measured
 * ladders (see [DspDifficulty]).
 */
internal object RhythmClassicLadder {

    fun derive(
        master: RhythmChart,
        difficulty: DspDifficulty,
        laneStrategy: LaneStrategy,
        maxChord: Int,
        timing: List<RhythmTimingPoint>,
    ): RhythmChart = when (difficulty) {
        DspDifficulty.EASY -> RhythmClassicGridDeriver.derive(
            master = master,
            difficulty = DspDifficulty.EASY,
            laneStrategy = laneStrategy,
            maxChord = maxChord,
            timing = doubledBeat(timing),
        )
        DspDifficulty.NORMAL -> RhythmClassicGridDeriver.derive(
            master = master,
            difficulty = DspDifficulty.EASY,
            laneStrategy = laneStrategy,
            maxChord = maxChord,
            timing = timing,
        )
        DspDifficulty.HARD -> RhythmClassicGridDeriver.derive(
            master = master,
            difficulty = DspDifficulty.NORMAL,
            laneStrategy = laneStrategy,
            maxChord = maxChord,
            timing = timing,
        )
    }

    /** Two-beat cells for Easy, anchors unchanged; empty timing falls back to 120 BPM doubled. */
    private fun doubledBeat(timing: List<RhythmTimingPoint>): List<RhythmTimingPoint> =
        if (timing.isEmpty()) {
            listOf(RhythmTimingPoint(0, 1000.0))
        } else {
            timing.map { it.copy(beatMs = it.beatMs * 2) }
        }
}