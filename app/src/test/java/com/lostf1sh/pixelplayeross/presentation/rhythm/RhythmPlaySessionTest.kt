package com.lostf1sh.pixelplayeross.presentation.rhythm

import com.lostf1sh.pixelplayeross.data.rhythm.RhythmChart
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmManualSongClock
import com.lostf1sh.pixelplayeross.data.rhythm.RhythmNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmPlaySessionTest {

    private fun chart() = RhythmChart(
        source = RhythmChart.SOURCE_OSU,
        difficultyName = "Test",
        notes = listOf(
            RhythmNote(0, 1000, 0),
            RhythmNote(1, 2000, 1),
        ),
        durationMs = 5000,
    )

    private fun session() = RhythmPlaySession(
        chart(),
        RhythmManualSongClock(durationMs = 5000.0),
        leadInMs = 0.0,
    )

    @Test
    fun settleAggregatesJudgementsFromTheClock() {
        val session = session()
        session.clock.start()

        assertEquals(RhythmJudgement.PERFECT, session.keyDown(lane = 0, songTimeMs = 1000.0).judgement)
        session.tick(songTimeMs = 3000.0)

        val result = session.settle()
        assertEquals("Test", result.difficultyName)
        assertEquals(2, result.noteCount)
        assertEquals(500_000, result.score)
        assertEquals(0.5, result.accuracy, 1e-9)
        assertEquals(1, result.maxCombo)
        assertEquals(1, result.counts[RhythmJudgement.PERFECT])
        assertEquals(1, result.counts[RhythmJudgement.MISS])
    }

    @Test
    fun restartResetsEngineForTheSameChart() {
        val session = session()
        session.clock.start()
        session.keyDown(lane = 0, songTimeMs = 1000.0)
        assertEquals(500_000, session.engine.score)

        session.restart()

        assertEquals(0, session.engine.score)
        assertEquals(0, session.engine.combo)
        assertTrue(session.engine.isNotePending(0))
        assertTrue(session.engine.isNotePending(1))
    }

    @Test
    fun finishFollowsClockDuration() {
        val clock = RhythmManualSongClock(durationMs = 5000.0)
        val session = RhythmPlaySession(chart(), clock, leadInMs = 0.0)
        session.clock.start()
        assertFalse(session.isFinished(session.clock.nowMs()))

        clock.setTime(5000.0)

        assertTrue(session.isFinished(session.clock.nowMs()))
    }

    @Test
    fun dropsNotesBeforeTheLeadIn() {
        val early = RhythmChart(
            source = RhythmChart.SOURCE_OSU,
            difficultyName = "Test",
            notes = listOf(
                RhythmNote(0, 500, 0),
                RhythmNote(1, 2000, 1),
            ),
            durationMs = 5000,
        )
        val session = RhythmPlaySession(early, RhythmManualSongClock(durationMs = 5000.0))

        assertEquals(listOf(1), session.chart.notes.map { it.id })
        assertEquals(1, session.engine.totalNotes)
        assertFalse(session.engine.isNotePending(0))
        assertTrue(session.engine.isNotePending(1))
    }
}
