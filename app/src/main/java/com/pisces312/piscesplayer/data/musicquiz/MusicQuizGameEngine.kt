package com.pisces312.piscesplayer.data.musicquiz

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One deterministic run. Playback and UI call this engine on the main thread; tests inject a clock.
 * Time spent loading or paused is never included in answer time or scoring.
 */
internal class MusicQuizGameEngine(
    questions: List<MusicQuizQuestion>,
    private val answerWindowMs: Long = MUSIC_QUIZ_CLIP_DURATION_MS,
    private val clock: MusicQuizMonotonicClock = MusicQuizSystemClock,
) {
    private val questions = questions.toMutableList()
    private val answerWindowNanos = answerWindowMs * NANOS_PER_MILLISECOND
    private val _records = mutableListOf<MusicQuizAnswerRecord>()

    private var deadlineNanos = 0L
    private var pausedRemainingNanos = answerWindowNanos

    var state: MusicQuizEngineState = MusicQuizEngineState.WAITING_FOR_PLAYBACK
        private set
    var currentRoundIndex: Int = 0
        private set
    var score: Int = 0
        private set
    var currentStreak: Int = 0
        private set
    var maxStreak: Int = 0
        private set

    val totalRounds: Int get() = questions.size
    val currentQuestion: MusicQuizQuestion get() = questions[currentRoundIndex]
    val plannedQuestions: List<MusicQuizQuestion> get() = questions.toList()
    val records: List<MusicQuizAnswerRecord> get() = _records.toList()
    val isLastRound: Boolean get() = currentRoundIndex == questions.lastIndex

    init {
        require(questions.isNotEmpty()) { "A run needs at least one question" }
        require(answerWindowMs > 0L) { "Answer window must be positive" }
    }

    fun onPlaybackStarted(nowNanos: Long = clock.nowNanos()) {
        check(state == MusicQuizEngineState.WAITING_FOR_PLAYBACK) {
            "Playback can only start while waiting for a new round"
        }
        pausedRemainingNanos = answerWindowNanos
        deadlineNanos = saturatedAdd(nowNanos, answerWindowNanos)
        state = MusicQuizEngineState.PLAYING
    }

    /** Returns a resolution if the answer window had already expired when pause was requested. */
    fun pause(nowNanos: Long = clock.nowNanos()): MusicQuizResolution? {
        if (state != MusicQuizEngineState.PLAYING) return null
        val remaining = deadlineNanos - nowNanos
        if (remaining <= 0L) return resolve(null, nowNanos)
        pausedRemainingNanos = remaining
        state = MusicQuizEngineState.PAUSED
        return null
    }

    fun onPlaybackResumed(nowNanos: Long = clock.nowNanos()) {
        check(state == MusicQuizEngineState.PAUSED) { "Only a paused round can resume" }
        deadlineNanos = saturatedAdd(nowNanos, pausedRemainingNanos)
        state = MusicQuizEngineState.PLAYING
    }

    /** An answer at the exact deadline is a timeout. Invalid or repeated answers are ignored. */
    fun answer(
        optionId: String,
        nowNanos: Long = clock.nowNanos(),
    ): MusicQuizResolution? {
        if (state != MusicQuizEngineState.PLAYING) return null
        if (currentQuestion.options.none { it.songId == optionId }) return null
        if (nowNanos >= deadlineNanos) return resolve(null, nowNanos)
        return resolve(optionId, nowNanos)
    }

    /** The audio end callback may arrive just before the wall-clock deadline, or after a pause. */
    fun onClipEnded(
        nowNanos: Long = clock.nowNanos(),
    ): MusicQuizResolution? {
        if (state != MusicQuizEngineState.PLAYING && state != MusicQuizEngineState.PAUSED) return null
        return resolve(null, nowNanos)
    }

    fun tick(nowNanos: Long = clock.nowNanos()): MusicQuizResolution? {
        if (state != MusicQuizEngineState.PLAYING || nowNanos < deadlineNanos) return null
        return resolve(null, nowNanos)
    }

    fun remainingNanos(nowNanos: Long = clock.nowNanos()): Long = when (state) {
        MusicQuizEngineState.WAITING_FOR_PLAYBACK -> answerWindowNanos
        MusicQuizEngineState.PLAYING -> (deadlineNanos - nowNanos).coerceAtLeast(0L)
        MusicQuizEngineState.PAUSED -> pausedRemainingNanos
        MusicQuizEngineState.RESOLVED,
        MusicQuizEngineState.FINISHED,
        -> 0L
    }

    fun advance(): Boolean {
        if (state != MusicQuizEngineState.RESOLVED) return false
        if (isLastRound) {
            state = MusicQuizEngineState.FINISHED
        } else {
            currentRoundIndex += 1
            pausedRemainingNanos = answerWindowNanos
            state = MusicQuizEngineState.WAITING_FOR_PLAYBACK
        }
        return true
    }

    fun replaceCurrentQuestion(question: MusicQuizQuestion): Boolean {
        if (state == MusicQuizEngineState.RESOLVED || state == MusicQuizEngineState.FINISHED) {
            return false
        }
        questions[currentRoundIndex] = question
        pausedRemainingNanos = answerWindowNanos
        state = MusicQuizEngineState.WAITING_FOR_PLAYBACK
        return true
    }

    fun summary(): MusicQuizRunSummary {
        val correctRecords = _records.filter(MusicQuizAnswerRecord::isCorrect)
        return MusicQuizRunSummary(
            score = score,
            correctCount = correctRecords.size,
            totalRounds = totalRounds,
            maxStreak = maxStreak,
            averageCorrectAnswerMs = if (correctRecords.isEmpty()) {
                0L
            } else {
                correctRecords.map(MusicQuizAnswerRecord::activeElapsedMs).average().roundToLong()
            },
            records = records,
        )
    }

    private fun resolve(
        selectedOptionId: String?,
        nowNanos: Long,
    ): MusicQuizResolution {
        check(state == MusicQuizEngineState.PLAYING || state == MusicQuizEngineState.PAUSED)
        val question = currentQuestion
        val remaining = when (state) {
            MusicQuizEngineState.PLAYING ->
                (deadlineNanos - nowNanos).coerceIn(0L, answerWindowNanos)
            MusicQuizEngineState.PAUSED ->
                pausedRemainingNanos.coerceIn(0L, answerWindowNanos)
            else -> 0L
        }
        val isCorrect = selectedOptionId == question.track.id
        val awarded = if (isCorrect) scoreForRemaining(remaining) else 0
        val elapsedMs = ((answerWindowNanos - remaining) / NANOS_PER_MILLISECOND)
            .coerceIn(0L, answerWindowMs)
        if (isCorrect) {
            score += awarded
            currentStreak += 1
            maxStreak = maxOf(maxStreak, currentStreak)
        } else {
            currentStreak = 0
        }
        val record = MusicQuizAnswerRecord(
            roundIndex = currentRoundIndex,
            question = question,
            selectedOptionId = selectedOptionId,
            isCorrect = isCorrect,
            timedOut = selectedOptionId == null,
            scoreAwarded = awarded,
            activeElapsedMs = elapsedMs,
        )
        _records += record
        state = MusicQuizEngineState.RESOLVED
        return MusicQuizResolution(
            record = record,
            totalScore = score,
            currentStreak = currentStreak,
        )
    }

    private fun scoreForRemaining(remainingNanos: Long): Int =
        MINIMUM_CORRECT_SCORE + (
            SPEED_SCORE_RANGE.toDouble() * remainingNanos / answerWindowNanos
            ).roundToInt()

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MINIMUM_CORRECT_SCORE = 100
        private const val SPEED_SCORE_RANGE = 900
    }
}
