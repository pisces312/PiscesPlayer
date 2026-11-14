package com.pisces312.piscesplayer.data.musicquiz

import java.util.Locale
import kotlin.random.Random

/** Builds a complete run from a fixed library snapshot. No Android or playback dependency. */
internal class MusicQuizQuestionFactory(
    private val random: Random = Random.Default,
) {

    fun createRun(tracks: List<MusicQuizTrack>): MusicQuizRunBlueprint {
        val pool = eligibleTracks(tracks)
        require(pool.size >= MUSIC_QUIZ_ROUND_COUNT) { "Not enough playable songs" }
        require(pool.map { normalizedTitle(it.title) }.distinct().size >= MUSIC_QUIZ_OPTION_COUNT) {
            "Not enough distinct song titles"
        }
        val shuffled = pool.shuffled(random)
        val correctTracks = shuffled.take(MUSIC_QUIZ_ROUND_COUNT)
        return MusicQuizRunBlueprint(
            questions = correctTracks.map { createQuestion(it, pool) },
            reserveTracks = shuffled.drop(MUSIC_QUIZ_ROUND_COUNT),
        )
    }

    /** Replaces a broken question without reusing any song already planned for this run. */
    fun replacement(
        allTracks: List<MusicQuizTrack>,
        reserveTracks: List<MusicQuizTrack>,
        plannedQuestions: List<MusicQuizQuestion>,
    ): MusicQuizReplacement? {
        val plannedIds = plannedQuestions.mapTo(mutableSetOf()) { it.track.id }
        val replacementTrack = reserveTracks.firstOrNull { it.id !in plannedIds } ?: return null
        return MusicQuizReplacement(
            question = createQuestion(replacementTrack, eligibleTracks(allTracks)),
            remainingReserveTracks = reserveTracks.filterNot { it.id == replacementTrack.id },
        )
    }

    private fun createQuestion(
        correctTrack: MusicQuizTrack,
        pool: List<MusicQuizTrack>,
    ): MusicQuizQuestion {
        val correctTitle = normalizedTitle(correctTrack.title)
        val distractors = pool
            .filter { it.id != correctTrack.id && normalizedTitle(it.title) != correctTitle }
            .shuffled(random)
            .asSequence()
            .distinctBy { normalizedTitle(it.title) }
            .take(MUSIC_QUIZ_OPTION_COUNT - 1)
            .toList()
        require(distractors.size == MUSIC_QUIZ_OPTION_COUNT - 1) { "Not enough unambiguous titles" }

        val options = (distractors + correctTrack)
            .shuffled(random)
            .map { MusicQuizOption(songId = it.id, title = it.title.trim()) }
        check(options.size == MUSIC_QUIZ_OPTION_COUNT)
        check(options.distinctBy { normalizedTitle(it.title) }.size == MUSIC_QUIZ_OPTION_COUNT)

        val latestStartMs = correctTrack.durationMs - MUSIC_QUIZ_CLIP_DURATION_MS - END_GUARD_MS
        check(latestStartMs >= 0L)
        return MusicQuizQuestion(
            track = correctTrack,
            clipStartMs = random.nextLong(0L, latestStartMs + 1L),
            options = options,
        )
    }

    companion object {
        private const val END_GUARD_MS = 1_000L
        private val WHITESPACE = Regex("\\s+")

        fun eligibleTracks(tracks: List<MusicQuizTrack>): List<MusicQuizTrack> = tracks
            .asSequence()
            .filter {
                it.title.isNotBlank() &&
                    it.playbackUri.isNotBlank() &&
                    it.durationMs >= MUSIC_QUIZ_MINIMUM_SONG_DURATION_MS
            }
            .distinctBy(MusicQuizTrack::id)
            .toList()

        fun hasEnoughTracks(tracks: List<MusicQuizTrack>): Boolean =
            eligibleTracks(tracks).size >= MUSIC_QUIZ_ROUND_COUNT

        fun hasEnoughTitles(tracks: List<MusicQuizTrack>): Boolean =
            eligibleTracks(tracks)
                .distinctBy { normalizedTitle(it.title) }
                .size >= MUSIC_QUIZ_OPTION_COUNT

        internal fun normalizedTitle(title: String): String = title
            .trim()
            .replace(WHITESPACE, " ")
            .lowercase(Locale.ROOT)
    }
}
