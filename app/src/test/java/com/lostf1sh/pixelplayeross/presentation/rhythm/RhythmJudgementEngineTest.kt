package com.lostf1sh.pixelplayeross.presentation.rhythm

import com.lostf1sh.pixelplayeross.data.rhythm.RhythmNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmJudgementEngineTest {

    private fun engine(vararg notes: RhythmNote) = RhythmJudgementEngine(notes.toList())

    @Test
    fun perfectWindowScoresFullWeight() {
        val engine = engine(RhythmNote(0, 1000, 1))
        val event = engine.hit(lane = 1, songTimeMs = 1000.0)

        assertEquals(RhythmJudgement.PERFECT, event.judgement)
        assertEquals(RHYTHM_FULL_SCORE, engine.score)
        assertEquals(1, engine.maxCombo)
    }

    @Test
    fun greatAndGoodWindowsUseWeights() {
        val engine = engine(
            RhythmNote(0, 1000, 0),
            RhythmNote(1, 2000, 1),
        )
        engine.hit(lane = 0, songTimeMs = 1075.0)
        engine.hit(lane = 1, songTimeMs = 2120.0)

        assertEquals(1, engine.counts[RhythmJudgement.GREAT])
        assertEquals(1, engine.counts[RhythmJudgement.GOOD])
        // (0.7 + 0.4) / 2 notes * 1_000_000
        assertEquals(550_000, engine.score)
    }

    @Test
    fun pastGoodWindowBecomesMissAndBreaksCombo() {
        val engine = engine(RhythmNote(0, 1000, 0))
        engine.hit(lane = 0, songTimeMs = 800.0)
        engine.advance(songTimeMs = 1141.0)

        assertEquals(1, engine.counts[RhythmJudgement.EMPTY])
        assertEquals(1, engine.counts[RhythmJudgement.MISS])
        assertEquals(0, engine.score)
        assertEquals(0, engine.maxCombo)
        assertFalse(engine.isNotePending(0))
    }

    @Test
    fun oneInputConsumesAtMostOneNote() {
        val engine = engine(
            RhythmNote(0, 1000, 0),
            RhythmNote(1, 1010, 0),
        )
        val event = engine.hit(lane = 0, songTimeMs = 1000.0)

        assertEquals(RhythmJudgement.PERFECT, event.judgement)
        assertTrue(engine.isNotePending(1))
        assertFalse(engine.isNotePending(0))
    }

    @Test
    fun finishSettlesRemainingNotesAsMiss() {
        val engine = engine(
            RhythmNote(0, 1000, 0),
            RhythmNote(1, 2000, 1),
        )
        engine.hit(lane = 0, songTimeMs = 990.0)
        engine.finish()

        assertEquals(1, engine.counts[RhythmJudgement.PERFECT])
        assertEquals(1, engine.counts[RhythmJudgement.MISS])
        assertEquals(500_000, engine.score)
    }

    @Test
    fun emptyInputDoesNotCountAsMiss() {
        val engine = engine(RhythmNote(0, 1000, 2))
        val event = engine.hit(lane = 0, songTimeMs = 995.0)

        assertEquals(RhythmJudgement.EMPTY, event.judgement)
        assertEquals(1, engine.counts[RhythmJudgement.EMPTY])
        assertEquals(0, engine.counts[RhythmJudgement.MISS])
        // Stray taps neither score nor break the combo.
        assertEquals(0, engine.combo)
        assertTrue(engine.isNotePending(0))
    }
}
