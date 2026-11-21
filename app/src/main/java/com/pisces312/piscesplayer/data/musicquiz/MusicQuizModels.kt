package com.pisces312.piscesplayer.data.musicquiz

internal const val MUSIC_QUIZ_ROUND_COUNT = 10
internal const val MUSIC_QUIZ_OPTION_COUNT = 5
internal const val MUSIC_QUIZ_CLIP_DURATION_MS = 10_000L
internal const val MUSIC_QUIZ_MINIMUM_SONG_DURATION_MS = 30_000L

/**
 * Flat game modes (v2). The song-pool × format matrix is intentionally not a product:
 * kids/classical play 10 rounds, endless uses the normal pool.
 */
enum class MusicQuizMode(val key: String) {
    STANDARD("standard"),
    /** Kids songs only (v3: classical music is deliberately not mixed into the kids pool). */
    KIDS("kids"),
    CLASSICAL("classical"),
    ENDLESS("endless"),
    /** Built-in classical library (877 tracks) — never depends on the user's library. */
    BUILTIN_CLASSICAL("builtin_classical"),
    ;

    val isEndless: Boolean get() = this == ENDLESS

    companion object {
        fun fromKey(key: String?): MusicQuizMode =
            entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

data class MusicQuizTrack(
    val id: String,
    val title: String,
    val artist: String,
    val albumArtUriString: String?,
    val durationMs: Long,
    val playbackUri: String,
    /** Release year; 0 = unknown. Buckets distractor candidates by era. */
    val year: Int = 0,
    /** Genre label; null/blank treated as unknown for matching. */
    val genre: String? = null,
    /** Album title; feeds classical-name matching. */
    val album: String = "",
    /** Classified as classical music; drives the same-source option rule. */
    val isClassical: Boolean = false,
    /** Classified as a children's song (catalog or keyword tier); independent of [isClassical]. */
    val isKidsSong: Boolean = false,
)

data class MusicQuizOption(
    val songId: String,
    val title: String,
    /** Cover art shown in front of the option label (v3). */
    val albumArtUriString: String? = null,
    /** Performer shown under the title; blank = unknown, subtitle omits it. */
    val artist: String = "",
    /** Release year shown under the title; 0 = unknown, subtitle omits it. */
    val year: Int = 0,
)

enum class MusicQuizQuestionType { TITLE, COMPOSER }

data class MusicQuizQuestion(
    val track: MusicQuizTrack,
    val clipStartMs: Long,
    val clipDurationMs: Long = MUSIC_QUIZ_CLIP_DURATION_MS,
    val options: List<MusicQuizOption>,
    val questionType: MusicQuizQuestionType = MusicQuizQuestionType.TITLE,
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

/** Best records kept per mode so different pools/rules never overwrite each other. */
data class MusicQuizModeStats(
    val byMode: Map<MusicQuizMode, MusicQuizStats> = emptyMap(),
    val bestRounds: Map<MusicQuizMode, Int> = emptyMap(),
) {
    fun statsFor(mode: MusicQuizMode): MusicQuizStats = byMode[mode] ?: MusicQuizStats()

    fun bestRoundsFor(mode: MusicQuizMode): Int = bestRounds[mode] ?: 0
}

data class MusicQuizRecordResult(
    val stats: MusicQuizStats,
    val bestRounds: Int,
    val isNewBestScore: Boolean,
    val isNewBestStreak: Boolean,
    val isNewBestRounds: Boolean,
)

internal fun interface MusicQuizMonotonicClock {
    fun nowNanos(): Long
}

internal object MusicQuizSystemClock : MusicQuizMonotonicClock {
    override fun nowNanos(): Long = System.nanoTime()
}
