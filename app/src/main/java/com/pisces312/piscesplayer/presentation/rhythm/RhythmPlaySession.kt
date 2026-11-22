/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (UI-independent play session with an injected clock).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import com.pisces312.piscesplayer.data.rhythm.RhythmChart
import com.pisces312.piscesplayer.data.rhythm.RhythmSongClock

/**
 * One gameplay run: one chart + injected clock + judgement engine.
 *
 * Notes charted before [leadInMs] are dropped from the run (the per-chart skip-prefix setting,
 * default 0 = show everything); the dropped notes are also excluded from the score denominator.
 * Purely a play-time filter: the chart keeps every note, so the value is free to change between
 * runs.
 */
class RhythmPlaySession(
    chart: RhythmChart,
    val clock: RhythmSongClock,
    val approachMs: Double = 1500.0,
    val config: RhythmJudgementConfig = RhythmJudgementConfig(),
    leadInMs: Double = 0.0,
) {
    val chart: RhythmChart = chart.withoutLeadIn(leadInMs)

    /**
     * When even the chronologically last note has left the widest judgement window.
     * [RhythmJudgementEngine] sorts its queues internally, so the stored chart order
     * cannot be trusted here — take the max once at construction instead.
     */
    private val lastNoteEndMs: Double = chart.notes.maxOf { it.timeMs } + config.goodMs

    var engine: RhythmJudgementEngine = buildEngine()
        private set

    fun keyDown(lane: Int, songTimeMs: Double): RhythmJudgementEvent = engine.hit(lane, songTimeMs)

    fun tick(songTimeMs: Double): List<RhythmJudgementEvent> = engine.advance(songTimeMs)

    /**
     * End-of-run check, in the judgement time domain (the same nowMs the loop samples).
     *
     * 1. [RhythmSongClock.finished] — the output drained the audio on its own.
     * 2. The last note has left even the widest judgement window — the osu!-standard end
     *    condition, in note time (nowMs domain) rather than audio time. This keeps a long
     *    outro from holding the result screen hostage.
     * 3. Raw audio time exhausted — the fallback for runs whose last note's window extends
     *    past the clock's saturated position (negative latency offsets). The offset must
     *    be removed first: nowMs() carries the latency offset and saturates at
     *    `durationMs + offsetMs`, which is BELOW durationMs for negative offsets — the
     *    direct comparison deadlocked full-combo runs at the end of the song
     *    (2026-11-22, offset −74 ms: songMs pinned at 172800.7 vs durationMs 172874.7,
     *    no settle, score unrecorded).
     *
     * Notes are guaranteed non-empty by [RhythmChart]'s invariant, so the max always
     * exists; [RhythmChart.withoutLeadIn] never empties the chart either.
     */
    fun isFinished(songTimeMs: Double): Boolean {
        if (clock.finished) return true
        if (songTimeMs >= lastNoteEndMs) return true
        return songTimeMs - clock.offsetMs >= clock.durationMs - DURATION_END_EPSILON_MS
    }

    fun pause() = clock.pause()

    fun resume() = clock.resume()

    fun restart() {
        clock.restart()
        engine = buildEngine()
    }

    fun settle(): RhythmSessionResult {
        engine.finish()
        return RhythmSessionResult(
            difficultyName = chart.difficultyName,
            noteCount = engine.totalNotes,
            score = engine.score,
            accuracy = engine.accuracy,
            maxCombo = engine.maxCombo,
            counts = engine.counts.toMap(),
            errorsMs = engine.errorsMs(),
            offsetMs = clock.offsetMs,
        )
    }

    private fun buildEngine(): RhythmJudgementEngine = RhythmJudgementEngine(chart.notes, config)

    companion object {
        /**
         * Tolerance for the raw-time fallback. The saturated frame time is computed as
         * `frames / sampleRate * 1000` while [RhythmSongClock.durationMs] is
         * `frames * 1000 / sampleRate`; the two may differ by 1 ulp at the final frame,
         * and a bare `>=` could then keep the run alive forever at full combo.
         */
        private const val DURATION_END_EPSILON_MS = 1.0
    }
}
