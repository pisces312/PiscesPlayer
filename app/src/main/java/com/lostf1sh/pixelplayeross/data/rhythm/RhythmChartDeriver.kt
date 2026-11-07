/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §4.3).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/**
 * Turns the per-song master chart into one difficulty. Two derivation paths live here:
 *
 * - [derive] is v2: it snaps every note to a quantization cell. It survives for osu-style grids,
 *   the golden fixtures and the direct `RhythmChartGenerator.render` path; **it moves timestamps**.
 * - [deriveOnsetNative] is v3 and is what the app uses: it only *selects* master notes, so every
 *   derived note keeps the exact onset time the detector produced
 *   (`docs/rhythm-chart-ladder-plan.md` §17).
 *
 * The v2 rules are still described here because the legacy path is tested against the direct
 * generator note for note. Four details are load-bearing there:
 * - the cell key is the grid **index**, not the snapped timestamp, so floating point noise can
 *   not move a note into the neighbouring cell;
 * - the note is re-emitted at the cell time, not at the master's timestamp. A v2 difficulty is a
 *   quantized chart; keeping the raw onset time would leave notes off the beat grid;
 * - lane de-duplication is re-applied. Thinning a master can bring two same-lane notes next to
 *   each other;
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
     * @param timing beat grid to snap to. The default is the master's own timing — the tracked
     *   piecewise grid for a current master; the repository passes the same list (or a
     *   [RhythmTimingRefiner] result for a legacy single-point master) so the grid follows
     *   tempo drift between anchors.
     */
    fun derive(
        master: RhythmChart,
        difficulty: DspDifficulty,
        laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
        maxChord: Int = 1,
        timing: List<RhythmTimingPoint> = master.timing,
    ): RhythmChart {
        require(master.notes.isNotEmpty()) { "master chart has no notes" }
        val points = timing.ifEmpty { listOf(RhythmTimingPoint(0, DEFAULT_BEAT_MS)) }
            .sortedBy { it.timeMs }
        require(points.all { it.beatMs > 0.0 }) {
            "invalid beat length ${points.firstOrNull { it.beatMs <= 0.0 }?.beatMs}"
        }
        val chord = maxChord.coerceIn(1, MAX_CHORD_NOTES)

        // One bucket per snapped millisecond. The strongest note wins the cell; the extras only
        // survive when chord notes are allowed, so with maxChord = 1 this is byte-for-byte the
        // old behaviour. With a piecewise grid the boundary beat is shared by two segments, and
        // keying by the snapped time merges those cells instead of emitting the beat twice.
        val cells = HashMap<Int, MutableList<RhythmNote>>(master.notes.size)
        for (note in master.notes) {
            val cell = snapTime(note.timeMs, points, difficulty.gridDiv)
            cells.getOrPut(cell) { ArrayList(2) } += note
        }

        val notes = ArrayList<RhythmNote>(master.notes.size)
        var lastLane = -1
        for (timeMs in cells.keys.sorted()) {
            // Snapping can push the last note of a song past its end; the generator drops those
            // too, so drop them here to keep both paths producing the same note list.
            if (timeMs < 0 || timeMs > master.durationMs) continue

            // Strongest first, ties to the earlier onset: that is the generator's tie-break.
            val candidates = cells.getValue(timeMs).sortedWith(CANDIDATE_ORDER)
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

        return master.copy(
            difficultyName = difficulty.label,
            timing = if (timing.isEmpty()) master.timing else points,
            notes = notes,
        )
    }

    /**
     * Snap [timeMs] to the local grid of the timing segment that contains it. Anchors are beat
     * lines, so the grid stays continuous: the boundary beat is both the last cell of one segment
     * and the first cell of the next, and both map to the same millisecond.
     *
     * The anchors are integers while `beatMs` is not, so a segment's own last grid line rounds to
     * within a millisecond of the next anchor. Emitting both would split one beat into two cells
     * a millisecond apart, so the last cell of a segment snaps to the anchor itself.
     *
     * Shared with [RhythmChartGenerator.render] so the direct render and a derivation of the
     * master land on identical cells.
     */
    internal fun snapTime(
        timeMs: Int,
        points: List<RhythmTimingPoint>,
        gridDiv: Int,
    ): Int {
        var index = 0
        while (index + 1 < points.size && points[index + 1].timeMs <= timeMs) index++
        val point = points[index]
        val grid = point.beatMs / gridDiv
        val cell = Math.round((timeMs - point.timeMs) / grid).toInt()
        val next = points.getOrNull(index + 1)
        if (next != null) {
            val boundaryCell = Math.round((next.timeMs - point.timeMs) / grid).toInt()
            if (boundaryCell >= 1 && cell >= boundaryCell) return next.timeMs
        }
        return Math.round(point.timeMs + cell * grid).toInt()
    }

    /**
     * v3 derivation: select a difficulty out of the master without moving a single timestamp.
     *
     * Snapping to a grid was the only remaining source of chart/audio mismatch once the playback
     * clock was ruled out (`docs/rhythm-clock-drift-diagnostics.md` §6-§7). Here `timeMs` is
     * copied straight from the master; a difficulty becomes a density budget
     * ([DspDeriveProfile]) plus the beat prior below.
     *
     * 1. Group onsets that lie within [CHORD_WINDOW_MS] of the group's first onset. Chords keep
     *    up to [maxChord] notes in distinct lanes.
     * 2. Pick groups with a weighted interval DP, so the gap between one hit's last note and the
     *    next hit's first note is at least `minGapMs` and the kept score is maximal.
     * 3. Trim whole groups until no 1 s window holds more than `maxNotesPerSecond` notes, then
     *    until `maxNotes` (when set) is respected. A chord costs one slot per note.
     * 4. Re-lane and re-apply lane avoidance, because thinning can bring two same-lane notes
     *    next to each other.
     */
    fun deriveOnsetNative(
        master: RhythmChart,
        difficulty: DspDifficulty,
        laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
        maxChord: Int = 1,
        profile: DspDeriveProfile = difficulty.deriveProfile,
        selection: OnsetSelection = OnsetSelection.BALANCED,
        /** Beat grid for the accent prior; the derive sheet passes the selected timing variant. */
        timing: List<RhythmTimingPoint> = master.timing,
    ): RhythmChart {
        require(master.notes.isNotEmpty()) { "master chart has no notes" }
        val chord = maxChord.coerceIn(1, MAX_CHORD_NOTES)
        val gridTiming = timing.ifEmpty { listOf(RhythmTimingPoint(0, DEFAULT_BEAT_MS)) }

        val selected = when (selection) {
            OnsetSelection.BALANCED ->
                selectGroups(groupOnsets(master.notes, laneStrategy, chord, gridTiming), profile)
            OnsetSelection.MELODY_FIRST -> {
                // Two pools with their own slot budgets: drum onsets cannot eat the melody's
                // slots. Chord grouping is per pool, so a chord straddling both becomes two hits
                // (measured trade-off in tools/rhythm-lab/docs/2026-11-03-melody-selection-comparison.md).
                val melody = master.notes.filter { it.band in MELODY_BANDS }
                val perc = master.notes.filterNot { it.band in MELODY_BANDS }
                val melodyGroups = selectGroups(
                    groupOnsets(melody, laneStrategy, chord, gridTiming),
                    profile.copy(maxNotesPerSecond = profile.melodySlots.toDouble()),
                )
                val percGroups = selectGroups(
                    groupOnsets(perc, laneStrategy, chord, gridTiming),
                    profile.copy(maxNotesPerSecond = profile.percSlots.toDouble()),
                )
                (melodyGroups + percGroups).sortedBy { it.startMs }
            }
        }
        return master.copy(
            difficultyName = difficulty.label,
            notes = emitNotes(selected, laneStrategy),
        )
    }

    /**
     * One hit: a primary onset plus the chord notes played with it. A hit spans [startMs]..[endMs]
     * (at most [CHORD_WINDOW_MS]); the gap constraint is measured between hits, not between two
     * primaries, so a chord note can not sneak into the previous hit's min-gap.
     */
    private class OnsetGroup(
        val startMs: Int,
        val endMs: Int,
        /** Primary first (the strongest onset), then the chord extras. */
        val notes: List<RhythmNote>,
        val score: Double,
    )

    private fun groupOnsets(
        notes: List<RhythmNote>,
        laneStrategy: LaneStrategy,
        maxChord: Int,
        timing: List<RhythmTimingPoint>,
    ): List<OnsetGroup> {
        val ordered = notes.sortedWith(compareBy({ it.timeMs }, { it.id }))
        val groups = ArrayList<OnsetGroup>(ordered.size)
        var index = 0
        while (index < ordered.size) {
            val first = ordered[index]
            index++
            // A fixed window from the group's first onset. Clustering "within 70 ms of the
            // previous note" would chain a whole 32nd-note run into one endless chord.
            val candidates = ArrayList<RhythmNote>(maxChord + 1)
            candidates += first
            while (index < ordered.size && ordered[index].timeMs - first.timeMs <= CHORD_WINDOW_MS) {
                candidates += ordered[index]
                index++
            }
            candidates.sortWith(CANDIDATE_ORDER)
            val primary = candidates.first()
            val members = ArrayList<RhythmNote>(maxChord)
            val lanes = HashSet<Int>(maxChord)
            members += primary
            lanes += laneOf(primary, laneStrategy)
            for (candidate in candidates.drop(1)) {
                if (members.size >= maxChord) break
                // Sorted by strength, so the first candidate below the floor ends the loop.
                if (candidate.strength < primary.strength * CHORD_MIN_STRENGTH_RATIO) break
                if (!lanes.add(laneOf(candidate, laneStrategy))) continue
                members += candidate
            }
            groups += OnsetGroup(
                startMs = members.minOf { it.timeMs },
                endMs = members.maxOf { it.timeMs },
                notes = members,
                score = members.sumOf { note ->
                    (BASE_NOTE_SCORE + note.strength.toDouble()) *
                        beatWeight(note.timeMs, timing)
                },
            )
        }
        return groups
    }

    /**
     * Soft accent prior: a note on the beat is up to 25% more valuable than one exactly halfway
     * between beats. It never overrides a clearly stronger onset, it only breaks near-ties, and
     * the BPM it reads never touches a timestamp.
     */
    private fun beatWeight(timeMs: Int, timing: List<RhythmTimingPoint>): Double {
        val point = timing.lastOrNull { it.timeMs <= timeMs } ?: timing.firstOrNull() ?: return 1.0
        if (point.beatMs <= 0.0) return 1.0
        val beats = (timeMs - point.timeMs) / point.beatMs
        val fraction = beats - floor(beats)
        return 1.0 + BEAT_PRIOR_AMPLITUDE * cos(2.0 * PI * fraction)
    }

    private fun selectGroups(
        groups: List<OnsetGroup>,
        profile: DspDeriveProfile,
    ): List<OnsetGroup> {
        if (groups.isEmpty()) return emptyList()
        return enforceDensity(minGapSelection(groups, profile.minGapMs), profile)
    }

    /**
     * Weighted interval scheduling: the highest-scoring subset whose hits are separated by at
     * least [minGapMs], measured end-to-start. Equal scores prefer more notes, then the later
     * candidate (the `include` branch wins the tie).
     */
    private fun minGapSelection(groups: List<OnsetGroup>, minGapMs: Double): List<OnsetGroup> {
        val n = groups.size
        val bestScore = DoubleArray(n + 1)
        val bestCount = IntArray(n + 1)
        val take = BooleanArray(n + 1)
        val previous = IntArray(n + 1)
        for (i in 1..n) {
            val group = groups[i - 1]
            val eligible = eligibleBefore(groups, group.startMs - minGapMs, i - 1)
            val includeScore = group.score + bestScore[eligible]
            val includeCount = group.notes.size + bestCount[eligible]
            val skipScore = bestScore[i - 1]
            val skipCount = bestCount[i - 1]
            val include = if (abs(includeScore - skipScore) <= SCORE_EPSILON) {
                includeCount >= skipCount
            } else {
                includeScore > skipScore
            }
            if (include) {
                bestScore[i] = includeScore
                bestCount[i] = includeCount
                take[i] = true
                previous[i] = eligible
            } else {
                bestScore[i] = skipScore
                bestCount[i] = skipCount
                previous[i] = i - 1
            }
        }
        val selected = ArrayList<OnsetGroup>(n)
        var i = n
        while (i > 0) {
            if (take[i]) {
                selected += groups[i - 1]
                i = previous[i]
            } else {
                i -= 1
            }
        }
        selected.reverse()
        return selected
    }

    /**
     * Number of leading hits that ended at least [minGapMs] before [limit], capped at
     * [upperBound]. Hit ranges never overlap, so their end times are sorted too.
     */
    private fun eligibleBefore(groups: List<OnsetGroup>, limit: Double, upperBound: Int): Int {
        var low = 0
        var high = upperBound
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (groups[mid - 1].endMs <= limit) low = mid else high = mid - 1
        }
        return low
    }

    /**
     * Apply the two density caps in chronological order. A hit is admitted only if every one of
     * its notes fits the 1 s window; a rejected hit never displaces one that is already in the
     * chart. [DspDeriveProfile.maxNotes] is the only step that removes hits, so neither cap
     * (nor minGap) can be broken after the fact.
     *
     * Admission instead of "remove the weakest in the window" is what keeps the delivered
     * density close to the cap: on the 20 s fixture, weak-first removal left EASY at 21 notes
     * (the 1 s window kept eating notes it had already accepted) where admission keeps 29,
     * essentially the exact-search optimum (~30) under the same cap.
     */
    private fun enforceDensity(
        groups: List<OnsetGroup>,
        profile: DspDeriveProfile,
    ): List<OnsetGroup> {
        if (groups.isEmpty()) return groups
        val kept = BooleanArray(groups.size) { true }
        // Fractional caps round down: 2.5 NPS means at most 2 notes in any 1 s window.
        val perWindow = profile.maxNotesPerSecond.toInt().coerceAtLeast(1)

        class TimedNote(val timeMs: Int)
        val window = ArrayList<TimedNote>()
        var head = 0

        for (groupIndex in groups.indices) {
            val times = groups[groupIndex].notes.map { it.timeMs }.sorted()
            var fits = true
            for (timeMs in times) {
                // Expiry only moves forward, so a rejected hit can leave the window ahead of
                // the next candidate without losing a note that candidate would still count.
                while (head < window.size && window[head].timeMs <= timeMs - NPS_WINDOW_MS) {
                    head++
                }
                val accepted = window.size - head
                val pending = times.count { it > timeMs - NPS_WINDOW_MS && it <= timeMs }
                if (accepted + pending > perWindow) {
                    fits = false
                    break
                }
            }
            if (!fits) {
                kept[groupIndex] = false
                continue
            }
            for (timeMs in times) window += TimedNote(timeMs)
        }

        val maxNotes = profile.maxNotes
        if (maxNotes != null) {
            var total = groups.indices.filter { kept[it] }.sumOf { groups[it].notes.size }
            if (total > maxNotes) {
                val weakestFirst = groups.indices
                    .filter { kept[it] }
                    .sortedWith(compareBy({ groups[it].score }, { groups[it].startMs }))
                for (groupIndex in weakestFirst) {
                    if (total <= maxNotes) break
                    kept[groupIndex] = false
                    total -= groups[groupIndex].notes.size
                }
            }
        }

        return groups.indices.filter { kept[it] }.map { groups[it] }
    }

    private fun emitNotes(groups: List<OnsetGroup>, laneStrategy: LaneStrategy): List<RhythmNote> {
        val emitted = ArrayList<RhythmNote>(groups.sumOf { it.notes.size })
        var lastLane = -1
        for (group in groups) {
            val primary = group.notes.first()
            val primaryLane = avoidRepeatedLane(
                laneOf(primary, laneStrategy),
                lastLane,
                primary.band,
            )
            lastLane = primaryLane
            val used = HashSet<Int>(group.notes.size)
            used += primaryLane
            val lanes = ArrayList<Pair<RhythmNote, Int>>(group.notes.size)
            lanes += primary to primaryLane
            for (index in 1 until group.notes.size) {
                val note = group.notes[index]
                val lane = laneOf(note, laneStrategy)
                if (!used.add(lane)) continue
                lanes += note to lane
            }
            // Canonical order: time, then lane. Chord notes may be up to 70 ms apart.
            lanes.sortWith(compareBy({ it.first.timeMs }, { it.second }))
            for ((note, lane) in lanes) {
                emitted += RhythmNote(
                    id = emitted.size,
                    timeMs = note.timeMs,
                    lane = lane,
                    strength = note.strength,
                    band = note.band,
                )
            }
        }
        return emitted
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

    /** Window length behind [DspDeriveProfile.maxNotesPerSecond]. */
    private const val NPS_WINDOW_MS = 1000

    /** Onset bands 2..5 (108 Hz..2.4 kHz): vocals, bass lines and leads. */
    private val MELODY_BANDS = 2..5

    /** Keeps the score strictly positive even for a zero-strength onset. */
    private const val BASE_NOTE_SCORE = 0.05

    /** ±25% around 1.0: the beat only decides between onsets whose strengths are close. */
    private const val BEAT_PRIOR_AMPLITUDE = 0.25

    private const val SCORE_EPSILON = 1e-9

    /** 120 BPM, only used when a chart somehow carries no timing point. */
    private const val DEFAULT_BEAT_MS = 500.0
}
