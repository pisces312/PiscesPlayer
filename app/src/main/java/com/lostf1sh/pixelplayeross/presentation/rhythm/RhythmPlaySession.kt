/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (UI-independent play session with an injected clock).
 */
package com.lostf1sh.pixelplayeross.presentation.rhythm

import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChart
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmSongClock

/**
 * One gameplay run: one chart + injected clock + judgement engine.
 *
 * Notes charted before [leadInMs] are dropped from the run. Derived charts are already stored
 * trimmed, so this only bites for the master chart and for osu imports; the dropped notes are
 * also excluded from the score denominator.
 */
class RhythmPlaySession(
    chart: RhythmChart,
    val clock: RhythmSongClock,
    val approachMs: Double = 1500.0,
    val config: RhythmJudgementConfig = RhythmJudgementConfig(),
    leadInMs: Double = RhythmChart.LEAD_IN_MS,
) {
    val chart: RhythmChart = chart.withoutLeadIn(leadInMs)

    var engine: RhythmJudgementEngine = buildEngine()
        private set

    fun keyDown(lane: Int, songTimeMs: Double): RhythmJudgementEvent = engine.hit(lane, songTimeMs)

    fun tick(songTimeMs: Double): List<RhythmJudgementEvent> = engine.advance(songTimeMs)

    fun isFinished(songTimeMs: Double): Boolean =
        clock.finished || songTimeMs >= clock.durationMs

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
}
