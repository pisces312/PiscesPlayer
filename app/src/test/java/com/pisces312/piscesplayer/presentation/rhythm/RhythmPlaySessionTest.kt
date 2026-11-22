package com.pisces312.piscesplayer.presentation.rhythm

import com.pisces312.piscesplayer.data.rhythm.RhythmChart
import com.pisces312.piscesplayer.data.rhythm.RhythmManualSongClock
import com.pisces312.piscesplayer.data.rhythm.RhythmNote
import com.pisces312.piscesplayer.data.rhythm.RhythmSongClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmPlaySessionTest {

    /**
     * End-of-song stand-in for RhythmAudioClock: the output has drained every frame, so
     * nowMs is pinned at durationMs + offsetMs. The semantics that matter here are the
     * real clock's: draining the PCM does NOT report finished while playback is still
     * nominally running — only an explicit pause does (and then, having frozen at the
     * final frame, finished IS true, matching `!isPlaying && frozenFrame >= frames`).
     */
    private class DrainedAudioClock(
        override val durationMs: Double,
        override var offsetMs: Double = 0.0,
    ) : RhythmSongClock {
        private var playing = false
        override val isPlaying: Boolean get() = playing
        override val finished: Boolean get() = !playing
        override fun nowMs(): Double = durationMs + offsetMs
        override fun start() { playing = true }
        override fun pause() { playing = false }
        override fun resume() { playing = true }
        override fun restart() { playing = true }
        override fun stop() { playing = false }
        override fun release() { playing = false }
    }

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
    fun negativeOffsetRunSettlesWhenTheAudioDrains() {
        // The 2026-11-22 freeze, minimised: offset −74 pinned nowMs at durationMs − 74,
        // so `songTimeMs >= durationMs` was never satisfied and a full-combo run never
        // settled (score unrecorded, screen stuck, back button trapped). The last note
        // sits close enough to the end that the note-based condition has not fired
        // either, isolating the raw-time fallback.
        val clock = DrainedAudioClock(durationMs = 5000.0, offsetMs = -100.0)
        val lateNote = RhythmChart(
            source = RhythmChart.SOURCE_OSU,
            difficultyName = "Test",
            notes = listOf(RhythmNote(0, 4800, 0)),
            durationMs = 5000,
        )
        val session = RhythmPlaySession(lateNote, clock, leadInMs = 0.0)
        clock.start()

        assertTrue(session.isFinished(session.clock.nowMs()))
    }

    @Test
    fun positiveOffsetRunStillSettlesByRawAudioTime() {
        // Guards the fallback's direction: the offset is removed before the comparison,
        // not added — a positive offset must not push the raw time below durationMs.
        val clock = DrainedAudioClock(durationMs = 5000.0, offsetMs = 100.0)
        val lateNote = RhythmChart(
            source = RhythmChart.SOURCE_OSU,
            difficultyName = "Test",
            notes = listOf(RhythmNote(0, 4800, 0)),
            durationMs = 5000,
        )
        val session = RhythmPlaySession(lateNote, clock, leadInMs = 0.0)
        clock.start()

        assertTrue(session.isFinished(session.clock.nowMs()))
    }

    @Test
    fun runSettlesOnceTheLastNoteLeavesTheWidestJudgementWindow() {
        // Long outro (30s audio, last note at 2s): the run ends when the last note has
        // left even the widest (140ms) window — not when the audio file fully drains.
        val clock = DrainedAudioClock(durationMs = 30_000.0)
        val session = RhythmPlaySession(chart(), clock, leadInMs = 0.0)
        clock.start()

        assertFalse(session.isFinished(2139.9))
        assertTrue(session.isFinished(2140.0))
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
        val session = RhythmPlaySession(
            early,
            RhythmManualSongClock(durationMs = 5000.0),
            leadInMs = RhythmChart.LEAD_IN_MS,
        )

        assertEquals(listOf(1), session.chart.notes.map { it.id })
        assertEquals(1, session.engine.totalNotes)
        assertFalse(session.engine.isNotePending(0))
        assertTrue(session.engine.isNotePending(1))
    }
}
