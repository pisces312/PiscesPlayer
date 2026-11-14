package com.pisces312.piscesplayer.data.musicquiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicQuizGameEngineTest {

    @Test
    fun fasterCorrectAnswersScoreMore() {
        val immediate = resolvedEngine(answerAtMs = 0L)
        val later = resolvedEngine(answerAtMs = 1_000L)

        assertEquals(1_000, immediate.score)
        assertEquals(910, later.score)
        assertEquals(0L, immediate.records.single().activeElapsedMs)
        assertEquals(1_000L, later.records.single().activeElapsedMs)
    }

    @Test
    fun wrongAnswerAndTimeoutScoreNothingAndBreakStreak() {
        val engine = MusicQuizGameEngine(listOf(question("one"), question("two")))
        engine.onPlaybackStarted(nowNanos = 0L)
        val wrong = engine.answer("other-one-0", nowNanos = 1_000L.millisToNanos())!!

        assertFalse(wrong.record.isCorrect)
        assertEquals(0, wrong.totalScore)
        assertTrue(engine.advance())
        engine.onPlaybackStarted(nowNanos = 2_000L.millisToNanos())
        val timeout = engine.tick(nowNanos = 12_000L.millisToNanos())!!

        assertTrue(timeout.record.timedOut)
        assertEquals(0, timeout.totalScore)
        assertEquals(0, timeout.currentStreak)
        assertTrue(engine.advance())
        assertEquals(MusicQuizEngineState.FINISHED, engine.state)
        assertEquals(0, engine.summary().correctCount)
    }

    @Test
    fun answerAtDeadlineIsTimeoutAndCannotBeScoredTwice() {
        val engine = MusicQuizGameEngine(listOf(question("one")))
        engine.onPlaybackStarted(nowNanos = 0L)

        val result = engine.answer("one", nowNanos = 10_000L.millisToNanos())

        assertTrue(result!!.record.timedOut)
        assertEquals(0, result.totalScore)
        assertNull(engine.answer("one", nowNanos = 10_001L.millisToNanos()))
        assertNull(engine.tick(nowNanos = 10_001L.millisToNanos()))
    }

    @Test
    fun pausedTimeDoesNotConsumeAnswerWindow() {
        val engine = MusicQuizGameEngine(listOf(question("one")))
        engine.onPlaybackStarted(nowNanos = 0L)

        assertNull(engine.pause(nowNanos = 4_000L.millisToNanos()))
        assertEquals(MusicQuizEngineState.PAUSED, engine.state)
        assertEquals(6_000L.millisToNanos(), engine.remainingNanos(nowNanos = 50_000L.millisToNanos()))
        assertNull(engine.tick(nowNanos = 50_000L.millisToNanos()))
        engine.onPlaybackResumed(nowNanos = 100_000L.millisToNanos())
        val result = engine.answer("one", nowNanos = 101_000L.millisToNanos())!!

        assertEquals(550, result.totalScore)
        assertEquals(5_000L, result.record.activeElapsedMs)
    }

    @Test
    fun naturalClipEndResolvesAsTimeoutBeforeTickerDeadline() {
        val engine = MusicQuizGameEngine(listOf(question("one")))
        engine.onPlaybackStarted(nowNanos = 0L)

        val result = engine.onClipEnded(nowNanos = 9_998L.millisToNanos())

        assertTrue(result!!.record.timedOut)
        assertEquals(0, result.totalScore)
    }

    @Test
    fun summaryAggregatesScoreStreakAndAverageCorrectTime() {
        val engine = MusicQuizGameEngine(listOf(question("one"), question("two")))
        engine.onPlaybackStarted(nowNanos = 0L)
        engine.answer("one", nowNanos = 1_000L.millisToNanos())
        engine.advance()
        engine.onPlaybackStarted(nowNanos = 20_000L.millisToNanos())
        engine.answer("two", nowNanos = 23_000L.millisToNanos())
        engine.advance()

        val summary = engine.summary()
        assertEquals(2, summary.correctCount)
        assertEquals(2, summary.maxStreak)
        assertEquals(2_000L, summary.averageCorrectAnswerMs)
        assertEquals(1_640, summary.score)
    }

    @Test
    fun clipEndWhilePausedResolvesAsTimeoutInsteadOfHanging() {
        val engine = MusicQuizGameEngine(listOf(question("one")))
        engine.onPlaybackStarted(nowNanos = 0L)
        assertNull(engine.pause(nowNanos = 5_000L.millisToNanos()))

        val result = engine.onClipEnded(nowNanos = 6_000L.millisToNanos())

        assertTrue(result!!.record.timedOut)
        assertEquals(0, result.totalScore)
        assertEquals(MusicQuizEngineState.RESOLVED, engine.state)
    }

    @Test
    fun replacingBrokenQuestionDuringPlaybackResetsRoundWithoutPenalty() {
        val engine = MusicQuizGameEngine(listOf(question("broken")))
        engine.onPlaybackStarted(nowNanos = 0L)

        assertTrue(engine.replaceCurrentQuestion(question("replacement")))

        assertEquals(MusicQuizEngineState.WAITING_FOR_PLAYBACK, engine.state)
        assertEquals(0, engine.score)
        assertTrue(engine.records.isEmpty())
        assertEquals(10_000L.millisToNanos(), engine.remainingNanos())
        engine.onPlaybackStarted(nowNanos = 50_000L.millisToNanos())
        val result = engine.answer("replacement", nowNanos = 51_000L.millisToNanos())!!
        assertEquals(910, result.totalScore)
    }

    private fun resolvedEngine(answerAtMs: Long): MusicQuizGameEngine {
        val engine = MusicQuizGameEngine(listOf(question("one")))
        engine.onPlaybackStarted(nowNanos = 0L)
        engine.answer("one", nowNanos = answerAtMs.millisToNanos())
        return engine
    }

    private fun question(id: String): MusicQuizQuestion {
        val track = MusicQuizTrack(
            id = id,
            title = "Title $id",
            artist = "Artist",
            albumArtUriString = null,
            durationMs = 180_000L,
            playbackUri = "content://test/$id",
        )
        return MusicQuizQuestion(
            track = track,
            clipStartMs = 20_000L,
            options = listOf(MusicQuizOption(id, track.title)) +
                List(4) { index -> MusicQuizOption("other-$id-$index", "Other $index") },
        )
    }

    private fun Long.millisToNanos(): Long = this * 1_000_000L
}
