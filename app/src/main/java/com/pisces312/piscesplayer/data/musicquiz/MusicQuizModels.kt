package com.pisces312.piscesplayer.data.musicquiz

internal const val MUSIC_QUIZ_ROUND_COUNT = 10
internal const val MUSIC_QUIZ_OPTION_COUNT = 5
internal const val MUSIC_QUIZ_CLIP_DURATION_MS = 10_000L
internal const val MUSIC_QUIZ_MINIMUM_SONG_DURATION_MS = 30_000L

data class MusicQuizTrack(
    val id: String,
    val title: String,
    val artist: String,
    val albumArtUriString: String?,
    val durationMs: Long,
    val playbackUri: String,
)

data class MusicQuizOption(
    val songId: String,
    val title: String,
)

data class MusicQuizQuestion(
    val track: MusicQuizTrack,
    val clipStartMs: Long,
    val clipDurationMs: Long = MUSIC_QUIZ_CLIP_DURATION_MS,
    val options: List<MusicQuizOption>,
)

data class MusicQuizRunBlueprint(
    val questions: List<MusicQuizQuestion>,
    val reserveTracks: List<MusicQuizTrack>,
)

data class MusicQuizReplacement(
    val question: MusicQuizQuestion,
    val remainingReserveTracks: List<MusicQuizTrack>,
)

internal enum class MusicQuizEngineState {
    WAITING_FOR_PLAYBACK,
    PLAYING,
    PAUSED,
    RESOLVED,
    FINISHED,
}

data class MusicQuizAnswerRecord(
    val roundIndex: Int,
    val question: MusicQuizQuestion,
    val selectedOptionId: String?,
    val isCorrect: Boolean,
    val timedOut: Boolean,
    val scoreAwarded: Int,
    val activeElapsedMs: Long,
)

data class MusicQuizResolution(
    val record: MusicQuizAnswerRecord,
    val totalScore: Int,
    val currentStreak: Int,
)

data class MusicQuizRunSummary(
    val score: Int,
    val correctCount: Int,
    val totalRounds: Int,
    val maxStreak: Int,
    val averageCorrectAnswerMs: Long,
    val records: List<MusicQuizAnswerRecord>,
)

data class MusicQuizStats(
    val bestScore: Int = 0,
    val bestStreak: Int = 0,
    val completedRuns: Int = 0,
)

data class MusicQuizRecordResult(
    val stats: MusicQuizStats,
    val isNewBestScore: Boolean,
    val isNewBestStreak: Boolean,
)

internal fun interface MusicQuizMonotonicClock {
    fun nowNanos(): Long
}

internal object MusicQuizSystemClock : MusicQuizMonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}
