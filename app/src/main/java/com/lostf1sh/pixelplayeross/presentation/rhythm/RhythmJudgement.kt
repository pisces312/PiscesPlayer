/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (judgement windows shared by PC and Android, JVM-testable).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import com.lostf1sh.pixelplayeross.data.rhythm.RhythmNote

/**
 * Judgements, and their osu!mania ScoreV1 values: hit value, bonus value and bonus-meter delta.
 *
 * ScoreV1 is the mania ladder's long-standing standard, so a score means the same thing it
 * means on any osu!mania chart: half of it comes from hit values, half from a 100-point bonus
 * meter that grows on perfects and collapses on a miss. Our four windows map onto its top
 * three judgements; there is no OK/MEH tier because our windows are wider than osu's.
 */
enum class RhythmJudgement(
    val hitValue: Int,
    val bonusValue: Int,
    val bonusDelta: Double,
) {
    PERFECT(320, 32, 2.0),
    GREAT(300, 32, 1.0),
    GOOD(200, 16, -8.0),
    MISS(0, 0, 0.0),
    EMPTY(0, 0, 0.0);

    companion object {
        val SCORING = listOf(PERFECT, GREAT, GOOD, MISS)
    }
}

const val RHYTHM_FULL_SCORE = 1_000_000

/** osu!mania hit values top out at 320, i.e. a MAX. */
private const val HIT_VALUE_MAX = 320.0

/** The bonus meter runs 0..100 and starts full. */
private const val BONUS_METER_MAX = 100.0

/** Windows are identical for every difficulty: only density changes. */
data class RhythmJudgementConfig(
    val perfectMs: Double = 60.0,
    val greatMs: Double = 90.0,
    val goodMs: Double = 140.0,
) {
    fun judge(errorMs: Double): RhythmJudgement {
        val error = kotlin.math.abs(errorMs)
        return when {
            error <= perfectMs -> RhythmJudgement.PERFECT
            error <= greatMs -> RhythmJudgement.GREAT
            error <= goodMs -> RhythmJudgement.GOOD
            else -> RhythmJudgement.MISS
        }
    }
}

/** One judged input or miss. */
data class RhythmJudgementEvent(
    val judgement: RhythmJudgement,
    val lane: Int,
    val noteId: Int,
    val errorMs: Double?,
    val combo: Int,
)

/**
 * Per-run judgement engine: per-lane pending queues, one input consumes at most
 * one note, one note is consumed at most once, time advance settles misses.
 */
class RhythmJudgementEngine(
    notes: List<RhythmNote>,
    private val config: RhythmJudgementConfig = RhythmJudgementConfig(),
) {
    private val lanes: Map<Int, MutableList<RhythmNote>> =
        notes.sortedWith(compareBy({ it.timeMs }, { it.lane }))
            .groupBy { it.lane }
            .mapValues { (_, value) -> value.toMutableList() }
    private val cursor: MutableMap<Int, Int> = lanes.keys.associateWith { 0 }.toMutableMap()
    private val pendingIds: MutableSet<Int> = notes.map { it.id }.toMutableSet()

    val counts: MutableMap<RhythmJudgement, Int> =
        RhythmJudgement.entries.associateWith { 0 }.toMutableMap()
    var combo: Int = 0
        private set
    var maxCombo: Int = 0
        private set
    private var hitValueSum = 0L
    private var bonusMeter = BONUS_METER_MAX
    private var bonusSum = 0.0
    private val errors = mutableListOf<Double>()

    val totalNotes: Int = notes.size
    private var consumed = 0

    fun isNotePending(noteId: Int): Boolean = noteId in pendingIds

    /**
     * osu!mania ScoreV1: `1_000_000 × (0.5 × hitValue / 320 + 0.5 × bonus) / totalNotes`, where
     * `bonus` sums `bonusValue × sqrt(bonusMeter) / 320` over the judged notes.
     */
    val score: Int
        get() {
            if (totalNotes == 0) return 0
            val halfSlice = RHYTHM_FULL_SCORE / 2.0 / totalNotes
            val base = halfSlice * hitValueSum / HIT_VALUE_MAX
            val bonus = halfSlice * bonusSum
            return Math.round(base + bonus).toInt()
        }

    /** Hit-value accuracy, independent of the bonus meter. */
    val accuracy: Double
        get() {
            val judged = RhythmJudgement.SCORING.sumOf { counts[it] ?: 0 }
            return if (judged == 0) 1.0 else hitValueSum / (HIT_VALUE_MAX * judged)
        }

    fun errorsMs(): List<Double> = errors.toList()

    fun hit(lane: Int, songTimeMs: Double): RhythmJudgementEvent {
        advance(songTimeMs)
        val candidates = pendingInLane(lane, songTimeMs)
        var best: RhythmNote? = null
        var bestError = 0.0
        for (note in candidates) {
            val error = songTimeMs - note.timeMs
            if (best == null || kotlin.math.abs(error) < kotlin.math.abs(bestError)) {
                best = note
                bestError = error
            }
        }
        if (best == null) {
            return record(RhythmJudgement.EMPTY, lane, -1, null)
        }
        consume(best)
        return record(config.judge(bestError), lane, best.id, bestError)
    }

    fun advance(songTimeMs: Double): List<RhythmJudgementEvent> {
        val out = mutableListOf<RhythmJudgementEvent>()
        for (laneNumber in lanes.keys) {
            val notes = lanes.getValue(laneNumber)
            var index = cursor.getValue(laneNumber)
            while (index < notes.size && notes[index].timeMs + config.goodMs < songTimeMs) {
                val note = notes[index]
                index++
                consume(note)
                out += record(RhythmJudgement.MISS, note.lane, note.id, null)
            }
            cursor[laneNumber] = index
        }
        return out.sortedBy { it.noteId }
    }

    fun finish(): List<RhythmJudgementEvent> {
        val out = mutableListOf<RhythmJudgementEvent>()
        for (laneNumber in lanes.keys) {
            val notes = lanes.getValue(laneNumber)
            val remaining = notes.drop(cursor.getValue(laneNumber)).toList()
            for (note in remaining) {
                consume(note)
                out += record(RhythmJudgement.MISS, note.lane, note.id, null)
            }
        }
        return out
    }

    private fun pendingInLane(lane: Int, songTimeMs: Double): List<RhythmNote> {
        val notes = lanes[lane] ?: return emptyList()
        val start = cursor[lane] ?: 0
        val out = mutableListOf<RhythmNote>()
        for (i in start until notes.size) {
            val note = notes[i]
            if (note.timeMs > songTimeMs + config.goodMs) break
            if (note.timeMs >= songTimeMs - config.goodMs) out += note
        }
        return out
    }

    private fun consume(note: RhythmNote) {
        val notes = lanes.getValue(note.lane)
        val start = cursor.getValue(note.lane)
        if (start < notes.size && notes[start] === note) {
            cursor[note.lane] = start + 1
        } else {
            for (i in start until notes.size) {
                if (notes[i] === note) {
                    notes.removeAt(i)
                    break
                }
            }
        }
        consumed++
        pendingIds.remove(note.id)
    }

    private fun record(
        judgement: RhythmJudgement,
        lane: Int,
        noteId: Int,
        errorMs: Double?,
    ): RhythmJudgementEvent {
        counts[judgement] = (counts[judgement] ?: 0) + 1
        if (judgement != RhythmJudgement.EMPTY) {
            // ScoreV1 moves the meter before scoring the note; a miss empties it, so the next
            // hit earns bonus from zero instead of from whatever streak survived.
            bonusMeter = if (judgement == RhythmJudgement.MISS) {
                0.0
            } else {
                (bonusMeter + judgement.bonusDelta).coerceIn(0.0, BONUS_METER_MAX)
            }
            hitValueSum += judgement.hitValue
            bonusSum += judgement.bonusValue * kotlin.math.sqrt(bonusMeter) / HIT_VALUE_MAX
        }
        when (judgement) {
            RhythmJudgement.MISS -> combo = 0
            RhythmJudgement.EMPTY -> Unit
            else -> {
                combo += 1
                maxCombo = maxOf(maxCombo, combo)
                if (errorMs != null) errors += errorMs
            }
        }
        return RhythmJudgementEvent(judgement, lane, noteId, errorMs, combo)
    }
}

/** Final result of one run. */
data class RhythmSessionResult(
    val difficultyName: String,
    val noteCount: Int,
    val score: Int,
    val accuracy: Double,
    val maxCombo: Int,
    val counts: Map<RhythmJudgement, Int>,
    val errorsMs: List<Double>,
    val offsetMs: Double,
) {
    val earlyCount: Int get() = errorsMs.count { it < -0.5 }
    val lateCount: Int get() = errorsMs.count { it > 0.5 }
    val meanErrorMs: Double get() = if (errorsMs.isEmpty()) 0.0 else errorsMs.average()
}
