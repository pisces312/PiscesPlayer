/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (judgement windows shared by PC and Android, JVM-testable).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import com.lostf1sh.pixelplayeross.data.rhythm.RhythmNote

enum class RhythmJudgement {
    PERFECT, GREAT, GOOD, MISS, EMPTY;

    companion object {
        val SCORING = listOf(PERFECT, GREAT, GOOD, MISS)
    }
}

private val JUDGEMENT_WEIGHTS = mapOf(
    RhythmJudgement.PERFECT to 1.0,
    RhythmJudgement.GREAT to 0.7,
    RhythmJudgement.GOOD to 0.4,
    RhythmJudgement.MISS to 0.0,
)

const val RHYTHM_FULL_SCORE = 1_000_000

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
    private var weighted = 0.0
    private val errors = mutableListOf<Double>()

    val totalNotes: Int = notes.size
    private var consumed = 0

    fun isNotePending(noteId: Int): Boolean = noteId in pendingIds

    val score: Int
        get() = if (totalNotes == 0) 0 else Math.round(RHYTHM_FULL_SCORE * weighted / totalNotes).toInt()

    val accuracy: Double
        get() {
            val judged = RhythmJudgement.SCORING.sumOf { counts[it] ?: 0 }
            return if (judged == 0) 1.0 else weighted / judged
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
        when (judgement) {
            RhythmJudgement.MISS -> combo = 0
            RhythmJudgement.EMPTY -> Unit
            else -> {
                combo += 1
                maxCombo = maxOf(maxCombo, combo)
                weighted += JUDGEMENT_WEIGHTS.getValue(judgement)
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
