/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (MIDI event-driven chart generation,
 * docs/../../RhythmChartLab docs/design/rhythm-midi-chart-plan.md §4.2).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How generator notes map onto the four lanes. */
enum class MidiLaneStrategy {
    /**
     * Default (A1). Lanes follow the melody through a *local* pitch rank: each note is ranked
     * by pitch inside its ±8-note window and that rank is spread over the four lanes. The
     * melody contour survives (ascending pitch never retreats in lane) and a narrow passage
     * spreads across the lanes instead of collapsing into one quartile of the whole-song
     * range. See [RhythmMidiChartGenerator.localRankLanes].
     */
    MELODIC,

    /** The part's pitch range is split into four quantiles; each pitch lands in its quartile. */
    PITCH_RANGE,

    /** Lanes rotate, ignoring pitch — the natural fit for drum parts. */
    ROUND_ROBIN,
}

/**
 * A1: how consecutive hits that want the same lane are spread out. Engaged only when a hit
 * would land on the lane of the hit before it (a repeated pitch is the classic case).
 */
enum class MidiRepeatMode {
    /** A-B-A-B across two adjacent lanes — the default. */
    TWO_LANE,

    /** A-B-C-A across three adjacent lanes. */
    THREE_LANE,
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
 *   ceiling). See [RhythmMidiChartGenerator] for the survival priority. The A2 density
 *   envelope and phrase breathing only ever *widen* that spacing, so the cap never regresses.
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
    /** A1 lane mapping; [MidiLaneStrategy.MELODIC] is the default. Drums always rotate. */
    val laneStrategy: MidiLaneStrategy = MidiLaneStrategy.MELODIC,
    /** A1: how repeated hits that want one lane alternate ([MidiLaneStrategy.MELODIC] only). */
    val repeatMode: MidiRepeatMode = MidiRepeatMode.TWO_LANE,
    /** A2: drop decorations below this velocity before chord merging; 0 disables the filter. */
    val weakNoteVelocity: Int = 16,
    /** A2: thin the 4th bar of every 4-bar phrase (the breath of a phrase). */
    val phraseBreathing: Boolean = true,
    /** A2 density multiplier applied to the breath bar (0.75 = a quarter fewer hits). */
    val phraseBreathFactor: Double = 0.75,
    /** A2: quiet stretches thin out, loud ones run at the full density cap (see the envelope). */
    val densityGradient: Boolean = true,
    /** A1: adjacent hits stay within two lanes unless the pitch leaps ≥ 7 semitones. */
    val jumpPenalty: Boolean = true,
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
 * - the primary note is the best **slot score** — `velocity × (1 − 0.7·|Δt|/cellMs)` — so a
 *   note sitting on the snapped cell time beats a louder note drifting off it (A2 slot
 *   selection; chart-lively-design §4.2 measured on-beat accuracy rising 35% → 52% with
 *   this penalty);
 * - further notes survive only as chord notes: at most [MidiDifficulty.maxChord] of them,
 *   all within [MidiChartOptions.chordWindowMs] of the primary's original onset, best slot
 *   score first — two eighth-notes in a quarter-note cell stay one note, an actual
 *   simultaneous chord becomes two or three keys;
 * - every other note in the cell is dropped.
 *
 * Density clipping runs after chord merging and counts hits, not keys: one chord is one
 * hit against the cap but still carries at most [MidiDifficulty.maxChord] keys. Hits
 * survive in priority order — **bar head first**, then on the beat, then strongest
 * (velocity), then earliest — and a hit is dropped when a surviving hit is closer than the
 * hit's own effective spacing: `1000 / maxHitsPerSecond` widened by the A2 density envelope
 * (a ±2-bar velocity window) and the A2 phrase breath (every 4th bar). Bars are 4/4 counted
 * from the tempo grid's first beat (no time-signature parsing: determinism is what matters).
 * That spacing caps the run at [MidiDifficulty.maxHitsPerSecond] hits per second while
 * remaining a pure function of the input, so generation stays deterministic.
 *
 * Lane mapping (A1, [MidiLaneStrategy.MELODIC]): every note carries its local pitch rank as
 * the desired lane; consecutive hits that want one lane alternate over two (or three)
 * adjacent lanes ([MidiRepeatMode]), and a lane distance of 3 — the whole field — is only
 * allowed when the pitch leaps at least 7 semitones. Chord keys keep [freeLane] mutual
 * exclusion, preferring their local-rank lane.
 */
object RhythmMidiChartGenerator {

    private const val LANE_COUNT = 4

    /** A1 local pitch rank window: the 8 notes before and after a note in time order. */
    private const val LOCAL_RANK_RADIUS = 8

    /** A2 slot score: `velocity × (1 − 0.7·|Δt|/cellMs)` (chart-lively-design §4.2). */
    private const val DISTANCE_PENALTY = 0.7

    /** 4/4 assumption: beats are counted from the tempo grid's first beat. */
    private const val BEATS_PER_BAR = 4

    /** A2 phrase breathing: every 4th bar (barIndex % 4 == 3) is the phrase breath. */
    private const val BARS_PER_PHRASE = 4

    /** A2 density envelope window: ±2 bars around a hit (~4 bars total). */
    private const val GRADIENT_HALF_WINDOW_BARS = 2

    /** A1 jump penalty: a lane distance of 3 needs a pitch leap of at least this many semitones. */
    private const val BIG_JUMP_SEMITONES = 7

    fun generate(
        score: MidiScore,
        options: MidiChartOptions = MidiChartOptions(),
    ): List<RhythmChart> {
        val grid = score.timing.sortedBy { it.timeMs }
        if (grid.isEmpty()) return emptyList()
        val charts = ArrayList<RhythmChart>()

        for (part in score.parts) {
            // A2 weak-note filter: decorations below the threshold die before chord merging.
            val notes = filterWeakNotes(part.notes, options.weakNoteVelocity)
            if (notes.size < options.minNotesPerPart) continue
            // Drum parts rotate regardless of strategy (their "pitch" is a kit slot).
            val pitchLane: ((Int) -> Int)? = when {
                part.isDrum || options.laneStrategy == MidiLaneStrategy.ROUND_ROBIN -> null
                options.laneStrategy == MidiLaneStrategy.PITCH_RANGE -> pitchLaneMap(notes)
                else -> null
            }
            val localLanes =
                if (!part.isDrum && options.laneStrategy == MidiLaneStrategy.MELODIC) {
                    localRankLanes(notes)
                } else {
                    null
                }
            for (difficulty in options.difficulties) {
                val chart = buildChart(
                    score = score,
                    partName = part.name,
                    partNotes = notes,
                    grid = grid,
                    difficulty = difficulty,
                    options = options,
                    pitchLane = pitchLane,
                    localLanes = localLanes,
                )
                if (chart != null) charts += chart
            }
        }
        return charts
    }

    /**
     * A2 weak-note filter: notes weaker than [threshold] velocity are dropped as ornaments;
     * 0 (or negative) turns the filter off. The kept notes keep their original order.
     */
    private fun filterWeakNotes(notes: List<MidiNote>, threshold: Int): List<NoteRef> =
        if (threshold <= 0) {
            notes.mapIndexed { index, note -> NoteRef(index, note) }
        } else {
            var index = 0
            notes.mapNotNull { note ->
                if (note.velocity >= threshold) NoteRef(index++, note) else null
            }
        }

    private fun buildChart(
        score: MidiScore,
        partName: String,
        partNotes: List<NoteRef>,
        grid: List<RhythmTimingPoint>,
        difficulty: MidiDifficulty,
        options: MidiChartOptions,
        pitchLane: ((Int) -> Int)?,
        localLanes: IntArray?,
    ): RhythmChart? {
        val gridDiv = difficulty.gridDiv.coerceAtLeast(1)
        val maxChord = difficulty.maxChord.coerceIn(1, LANE_COUNT)
        val beatPrefix = beatPrefixes(grid)

        // Cell key: the snapped millisecond. Snapping is a pure function of the segment the
        // note falls into, so cell-mates always key identically (no float-noise drift).
        val cells = HashMap<Int, CellNotes>(partNotes.size)
        for (ref in partNotes) {
            val cell = snap(ref.note.startMs.toDouble(), grid, gridDiv, beatPrefix)
            val acc = cells.getOrPut(cell.timeMs) {
                CellNotes(onBeat = cell.onBeat, barHead = cell.barHead, barIndex = cell.barIndex)
            }
            acc.notes += ref
            // Cell size for the A2 distance penalty, measured on this cell's segment.
            if (acc.cellMs <= 0.0) acc.cellMs = cell.cellMs
        }

        // Chord merge per cell: one hit moment per cell, best slot score primary.
        val hits = ArrayList<Hit>(cells.size)
        for (timeMs in cells.keys.sorted()) {
            if (timeMs > score.durationMs) continue
            val acc = cells.getValue(timeMs)
            val cellMs = if (acc.cellMs > 0.0) acc.cellMs else 1.0
            val candidates = acc.notes.sortedWith(
                compareByDescending<NoteRef> { slotScore(it.note, timeMs, cellMs) }
                    .thenByDescending { it.note.velocity }
                    .thenBy { it.note.startMs }
                    .thenBy { it.note.pitch },
            )
            val primary = candidates.first()
            val chord = arrayListOf(primary)
            for (extra in candidates.drop(1)) {
                if (chord.size >= maxChord) break
                if (abs(extra.note.startMs - primary.note.startMs) > options.chordWindowMs) continue
                chord += extra
            }
            hits += Hit(
                timeMs = timeMs,
                onBeat = acc.onBeat,
                barHead = acc.barHead,
                barIndex = acc.barIndex,
                velocity = primary.note.velocity,
                notes = chord,
            )
        }

        val kept = cullDensity(hits, difficulty.maxHitsPerSecond, options, grid)

        val out = ArrayList<RhythmNote>(kept.size)
        if (localLanes != null) {
            // A1 melodic mapping: a small state machine walks the surviving hits forward.
            val planner = LanePlanner(localLanes, options.repeatMode, options.jumpPenalty)
            for (hit in kept) {
                val usedLanes = HashSet<Int>(hit.notes.size)
                for ((slot, ref) in hit.notes.withIndex()) {
                    // Chord keys prefer their local-rank lane but stay mutually exclusive.
                    val lane = if (slot == 0) {
                        planner.primaryLane(ref)
                    } else {
                        freeLane(localLanes[ref.index], usedLanes)
                    } ?: continue
                    usedLanes.add(lane)
                    out += RhythmNote(
                        id = out.size,
                        timeMs = hit.timeMs,
                        lane = lane,
                        strength = ref.note.velocity / 127f,
                    )
                }
            }
        } else {
            // Round-robin state: rotates across every emitted note, so consecutive hits land on
            // different lanes and chord notes fan out to the following lanes.
            var rrNext = 0
            for (hit in kept) {
                val usedLanes = HashSet<Int>(hit.notes.size)
                for (ref in hit.notes) {
                    val desired = pitchLane?.invoke(ref.note.pitch) ?: (rrNext++ % LANE_COUNT)
                    val lane = freeLane(desired, usedLanes) ?: continue
                    usedLanes.add(lane)
                    out += RhythmNote(
                        id = out.size,
                        timeMs = hit.timeMs,
                        lane = lane,
                        strength = ref.note.velocity / 127f,
                    )
                }
            }
        }
        if (out.isEmpty()) return null

        return RhythmChart(
            source = RhythmChart.SOURCE_MIDI,
            difficultyName = "$partName · ${difficulty.label}",
            timing = grid,
            notes = out,
            durationMs = score.durationMs,
        ).withoutLeadIn()
    }

    /**
     * A2 slot score: `velocity × (1 − 0.7·|Δt|/cellMs)` — the primary note of a cell is the
     * best combination of loudness and closeness to the snapped cell time, so a hit lands on
     * the actual onset instead of the loudest drift inside the cell.
     */
    private fun slotScore(note: MidiNote, snappedTimeMs: Int, cellMs: Double): Double {
        if (cellMs <= 0.0) return note.velocity.toDouble()
        return note.velocity * (1.0 - DISTANCE_PENALTY * abs(note.startMs - snappedTimeMs) / cellMs)
    }

    /**
     * A2 density clipping. [hits] survive in priority order — bar head first, then on-beat,
     * then strongest (velocity), then earliest — and a hit is dropped when a surviving hit is
     * closer than the hit's own effective spacing (see [effectiveGapMs]). The result is
     * returned in time order. Deterministic: the priority order is total (hit times are
     * unique) and admission depends only on already-surviving hits.
     */
    private fun cullDensity(
        hits: List<Hit>,
        maxHitsPerSecond: Double,
        options: MidiChartOptions,
        grid: List<RhythmTimingPoint>,
    ): List<Hit> {
        val minGapMs = minGapMs(maxHitsPerSecond)
        if (minGapMs <= 0 || hits.size < 2) return hits
        val gaps = IntArray(hits.size) { effectiveGapMs(hits, it, minGapMs, options, grid) }
        val ranked = hits.indices.sortedWith(
            compareByDescending<Int> { hits[it].barHead }
                .thenByDescending { hits[it].onBeat }
                .thenByDescending { hits[it].velocity }
                .thenBy { hits[it].timeMs },
        )
        val kept = ArrayList<Hit>(hits.size)
        for (i in ranked) {
            if (kept.all { abs(it.timeMs - hits[i].timeMs) >= gaps[i] }) kept += hits[i]
        }
        return kept.sortedBy { it.timeMs }
    }

    /**
     * The spacing one hit demands from its neighbours, built on the difficulty's [minGapMs]:
     * - A2 density gradient: the mean velocity of a ±2-bar window maps to an envelope of
     *   0.75..1.25. Only the thinning half acts: an envelope at or above 1 saturates at the
     *   difficulty's own cap, so loud sections run at full density while quiet ones get more
     *   air and `maxHitsPerSecond` never regresses;
     * - A2 phrase breathing: the 4th bar of every 4-bar phrase multiplies the density by
     *   [MidiChartOptions.phraseBreathFactor] (0.75 by default).
     */
    private fun effectiveGapMs(
        hits: List<Hit>,
        index: Int,
        minGapMs: Int,
        options: MidiChartOptions,
        grid: List<RhythmTimingPoint>,
    ): Int {
        val hit = hits[index]
        var density = 1.0
        if (options.densityGradient) {
            val envelope = 0.75 + 0.5 * (windowAverageVelocity(hits, index, grid) / 127.0)
            density *= min(envelope, 1.0)
        }
        if (options.phraseBreathing && hit.barIndex % BARS_PER_PHRASE == BARS_PER_PHRASE - 1) {
            density *= options.phraseBreathFactor
        }
        if (density <= 0.0) return Int.MAX_VALUE
        return ceil(minGapMs / density).toInt().coerceAtLeast(1)
    }

    /** Mean hit velocity inside the ±2-bar envelope window around [index]'s hit. */
    private fun windowAverageVelocity(
        hits: List<Hit>,
        index: Int,
        grid: List<RhythmTimingPoint>,
    ): Double {
        val hit = hits[index]
        val halfWindowMs = GRADIENT_HALF_WINDOW_BARS * barMsAt(grid, hit.timeMs)
        var sum = 0L
        var count = 0
        for (other in hits) {
            if (abs(other.timeMs - hit.timeMs) <= halfWindowMs) {
                sum += other.velocity
                count++
            }
        }
        return if (count == 0) hit.velocity.toDouble() else sum.toDouble() / count
    }

    /** Bar length at [timeMs]: four beats of that segment (the 4/4 assumption). */
    private fun barMsAt(grid: List<RhythmTimingPoint>, timeMs: Int): Double {
        var segIndex = grid.indexOfLast { it.timeMs <= timeMs }
        if (segIndex < 0) segIndex = 0
        return BEATS_PER_BAR * grid[segIndex].beatMs
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
     * Whole beats from the tempo grid's first point to each point's timeMs — the running
     * count bars are numbered from (4/4, no time-signature parsing: deterministic is enough).
     */
    private fun beatPrefixes(grid: List<RhythmTimingPoint>): DoubleArray {
        val prefix = DoubleArray(grid.size)
        for (i in 1 until grid.size) {
            prefix[i] = prefix[i - 1] + (grid[i].timeMs - grid[i - 1].timeMs) / grid[i - 1].beatMs
        }
        return prefix
    }

    /**
     * Snaps [timeMs] onto the [gridDiv]-subdivided beat grid described by [grid].
     *
     * The grid is piecewise (the file's tempo map): the note is snapped with the beat
     * length of the segment it falls into, and a segment boundary beat is owned by the
     * following segment, so a note on the boundary lands at the same ms from both sides.
     * A cell is on the beat when its index within the segment is a whole beat
     * (`cell % gridDiv == 0`), i.e. its snapped time is a beat point of the tempo map.
     * [Cell.beatIndex] counts beats from the grid's first beat ([beatPrefixes]); a cell whose
     * beat index is a multiple of four is a bar head under the 4/4 assumption.
     */
    private fun snap(
        timeMs: Double,
        grid: List<RhythmTimingPoint>,
        gridDiv: Int,
        beatPrefix: DoubleArray,
    ): Cell {
        var segIndex = grid.indexOfLast { it.timeMs <= timeMs }
        if (segIndex < 0) segIndex = 0
        val seg = grid[segIndex]
        val cellMs = seg.beatMs / gridDiv
        val offset = timeMs - seg.timeMs
        val cell = floor(offset / cellMs).toLong()
        val snapped = seg.timeMs + cell * cellMs
        val onBeat = cell % gridDiv == 0L
        // A note exactly on the last boundary of a segment snaps to the same ms from the
        // next segment's cell 0; round to Int so both sides key the identical cell.
        val beatIndex = (beatPrefix[segIndex] + cell.toDouble() / gridDiv).roundToInt()
        return Cell(
            timeMs = snapped.toInt(),
            onBeat = onBeat,
            beatIndex = beatIndex,
            barHead = onBeat && beatIndex % BEATS_PER_BAR == 0,
            barIndex = beatIndex / BEATS_PER_BAR,
            cellMs = cellMs,
        )
    }

    /**
     * A1 local pitch rank: for every note, its ±8-note window (time order) is sorted by
     * (pitch, time order) and the note's rank in that order is spread evenly over the four
     * lanes. Same pitches break ties by time order, so the mapping is stable, and a
     * non-descending pitch sequence gets non-descending ranks — the melody contour survives
     * (ascending pitch never retreats in lane) — while a narrow passage keeps spreading
     * across the lanes instead of collapsing into one quartile of the whole-song range.
     */
    private fun localRankLanes(notes: List<NoteRef>): IntArray {
        val lanes = IntArray(notes.size)
        for (i in notes.indices) {
            val from = max(0, i - LOCAL_RANK_RADIUS)
            val to = min(notes.size - 1, i + LOCAL_RANK_RADIUS)
            val window = (from..to).sortedWith(compareBy({ notes[it].note.pitch }, { it }))
            val rank = window.indexOf(i)
            lanes[i] = (rank * LANE_COUNT / window.size).coerceIn(0, LANE_COUNT - 1)
        }
        return lanes
    }

    /** Pitch → lane: quartiles of the part's range, clamped (single-pitch parts → lane 1). */
    private fun pitchLaneMap(notes: List<NoteRef>): (Int) -> Int {
        val min = notes.minOf { it.note.pitch }
        val max = notes.maxOf { it.note.pitch }
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

    /**
     * A1 lane state machine over the surviving hits (MELODIC only). Walked forward in time,
     * the primary key of each hit decides its lane from the local rank plus three rules:
     *
     * 1. **Same-lane avoidance / repeated-note alternation** — a hit that wants the lane of
     *    the hit before it continues (or opens) a repeat run and takes the next lane of the
     *    run's group: A-B-A-B on two adjacent lanes ([MidiRepeatMode.TWO_LANE]) or A-B-C-A
     *    on three ([MidiRepeatMode.THREE_LANE]). An ascending pitch prefers the upper
     *    neighbour, so a rising line never retreats.
     * 2. **Jump penalty** — a lane distance of 3 spans the whole field and is only allowed
     *    when the pitch leaps at least [BIG_JUMP_SEMITONES] semitones; smaller jumps clamp
     *    to distance 2 in the same direction (soft cap 2).
     * 3. Chord keys do not enter this machine: they take their local-rank lane through
     *    [freeLane] inside the hit, and the next hit compares against this hit's primary.
     */
    private class LanePlanner(
        private val localLanes: IntArray,
        private val repeatMode: MidiRepeatMode,
        private val jumpPenalty: Boolean,
    ) {
        private var prevLane: Int? = null
        private var prevPitch: Int? = null
        /** The alternation group of the current repeat run; run[0] is the anchor lane. */
        private var run: List<Int> = emptyList()
        /** Index in [run] the next repeated hit takes. */
        private var runPhase = 0

        fun primaryLane(ref: NoteRef): Int {
            val desired = localLanes[ref.index]
            val pitch = ref.note.pitch
            val prev = prevLane
            val repeatHit = prev != null && desired == prev
            val repeatRun = run.isNotEmpty() && desired == run[0]
            var lane = if (repeatHit || repeatRun) {
                if (run.isEmpty() || run[0] != desired) {
                    // A repeat run opens here; its first lane was the previous hit's.
                    run = repeatGroup(desired, pitch, prevPitch)
                    runPhase = 1 % run.size
                }
                val next = run[runPhase]
                runPhase = (runPhase + 1) % run.size
                next
            } else {
                run = emptyList()
                desired
            }
            if (jumpPenalty && prev != null && abs(lane - prev) == LANE_COUNT - 1) {
                val pitchDelta = abs(pitch - (prevPitch ?: pitch))
                if (pitchDelta < BIG_JUMP_SEMITONES) {
                    lane = if (lane > prev) prev + LANE_COUNT - 2 else prev - (LANE_COUNT - 2)
                }
            }
            prevLane = lane
            prevPitch = pitch
            return lane
        }

        /**
         * The alternation group anchored at [anchor]: two adjacent lanes (A-B-A-B) or three
         * (A-B-C-A), ordered so consecutive lanes stay neighbours. An ascending pitch
         * prefers the upper neighbour (melody contour), a descending one the lower, and the
         * field edges force the only valid direction.
         */
        private fun repeatGroup(anchor: Int, pitch: Int, prevPitch: Int?): List<Int> {
            val preferUp = prevPitch == null || pitch >= prevPitch
            return when (repeatMode) {
                MidiRepeatMode.TWO_LANE -> {
                    val second = when {
                        anchor + 1 < LANE_COUNT && (preferUp || anchor == 0) -> anchor + 1
                        else -> anchor - 1
                    }
                    listOf(anchor, second)
                }
                MidiRepeatMode.THREE_LANE -> when {
                    preferUp && anchor + 2 < LANE_COUNT -> listOf(anchor, anchor + 1, anchor + 2)
                    anchor - 2 >= 0 -> listOf(anchor, anchor - 1, anchor - 2)
                    else -> listOf(anchor, anchor + 1, anchor + 2)
                }
            }
        }
    }

    /** One note with its position among the part's (weak-filtered) notes. */
    private data class NoteRef(val index: Int, val note: MidiNote)

    /** One snapped cell: the key notes merge under and where it sits on the grid. */
    private data class Cell(
        val timeMs: Int,
        val onBeat: Boolean,
        val beatIndex: Int,
        val barHead: Boolean,
        val barIndex: Int,
        val cellMs: Double,
    )

    /** Accumulator for the notes that quantize into one cell. */
    private class CellNotes(
        val onBeat: Boolean,
        val barHead: Boolean,
        val barIndex: Int,
    ) {
        val notes = ArrayList<NoteRef>(2)
        var cellMs = 0.0
    }

    /**
     * One hit moment after chord merging: a chord is a single [Hit] whose [notes] carry its
     * keys (best slot score first), so density clipping counts it once.
     */
    private data class Hit(
        val timeMs: Int,
        val onBeat: Boolean,
        val barHead: Boolean,
        val barIndex: Int,
        val velocity: Int,
        val notes: List<NoteRef>,
    )
}
