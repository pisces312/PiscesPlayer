package com.pisces312.piscesplayer.data.musicquiz

import java.util.Locale
import kotlin.random.Random

/** Builds a complete run from a fixed library snapshot. No Android or playback dependency. */
internal class MusicQuizQuestionFactory(
    private val random: Random = Random.Default,
) {

    fun createRun(tracks: List<MusicQuizTrack>): MusicQuizRunBlueprint {
        val answerPool = answerTracks(tracks)
        require(answerPool.size >= MUSIC_QUIZ_ROUND_COUNT) { "Not enough playable songs" }
        val shuffled = answerPool.shuffled(random)
        val correctTracks = shuffled.take(MUSIC_QUIZ_ROUND_COUNT)
        return MusicQuizRunBlueprint(
            questions = correctTracks.map { createQuestion(it, sameSourcePool(it, tracks)) },
            reserveTracks = shuffled.drop(MUSIC_QUIZ_ROUND_COUNT),
        )
    }

    /**
     * Draws one question for endless mode. Songs are dealt without replacement until the
     * usable pool is exhausted; the caller clears [usedIds] to start a new cycle. Returns
     * null when every candidate is used or is the previous question's song.
     */
    fun createNextQuestion(
        tracks: List<MusicQuizTrack>,
        usedIds: Set<String>,
        lastTrackId: String?,
    ): MusicQuizQuestion? {
        val candidates = answerTracks(tracks)
            .filter { it.id !in usedIds && it.id != lastTrackId }
        val correct = candidates.randomOrNull(random) ?: return null
        return createQuestion(correct, sameSourcePool(correct, tracks))
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
            question = createQuestion(replacementTrack, sameSourcePool(replacementTrack, allTracks)),
            remainingReserveTracks = reserveTracks.filterNot { it.id == replacementTrack.id },
        )
    }

    private fun createQuestion(
        correctTrack: MusicQuizTrack,
        pool: List<MusicQuizTrack>,
    ): MusicQuizQuestion {
        val correctTitle = normalizedTitle(correctTrack.title)
        val correctLang = languageKey(correctTrack.title)
        val correctEra = eraKey(correctTrack.year)
        val correctGenre = normalizedGenre(correctTrack.genre)

        // Difficulty: distractors should read as plausible alternatives, so prefer the same
        // language, then the same era, then the same genre. Lower-ranked candidates only
        // fill in when the library lacks enough close matches.
        val ranked = pool
            .filter { it.id != correctTrack.id && normalizedTitle(it.title) != correctTitle }
            .groupBy { candidate ->
                (if (languageKey(candidate.title) == correctLang) 4 else 0) +
                    (if (eraKey(candidate.year) == correctEra) 2 else 0) +
                    (if (normalizedGenre(candidate.genre) == correctGenre) 1 else 0)
            }
        val distractors = ranked.entries
            .sortedByDescending { it.key }
            .flatMap { (_, candidates) -> candidates.shuffled(random) }
            .distinctBy { normalizedTitle(it.title) }
            .take(MUSIC_QUIZ_OPTION_COUNT - 1)
            .toList()
        require(distractors.size == MUSIC_QUIZ_OPTION_COUNT - 1) { "Not enough unambiguous titles" }

        val options = (distractors + correctTrack)
            .shuffled(random)
            .map {
                MusicQuizOption(
                    songId = it.id,
                    title = it.title.trim(),
                    albumArtUriString = it.albumArtUriString,
                )
            }
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

        /**
         * Same-source rule: a question's options all come from the correct answer's own
         * sub-pool (classical or non-classical), so the category of the answer is never a hint.
         */
        fun sameSourcePool(track: MusicQuizTrack, tracks: List<MusicQuizTrack>): List<MusicQuizTrack> =
            tracks.filter { it.isClassical == track.isClassical }

        /**
         * Sub-pools that can actually form a question (>= 5 distinct titles). Sub-pools below
         * that leave question generation entirely — they neither answer nor distract.
         */
        fun viableSubPools(tracks: List<MusicQuizTrack>): List<List<MusicQuizTrack>> = tracks
            .groupBy(MusicQuizTrack::isClassical)
            .values
            .filter { subPool ->
                subPool.map { normalizedTitle(it.title) }.distinct().size >= MUSIC_QUIZ_OPTION_COUNT
            }

        /** Songs that may serve as correct answers: members of viable sub-pools. */
        fun answerTracks(tracks: List<MusicQuizTrack>): List<MusicQuizTrack> =
            viableSubPools(tracks).flatten()

        internal fun normalizedTitle(title: String): String = MusicQuizText.normalizeTitle(title)

        /** Script-based language bucket for the title; coarse on purpose. */
        internal fun languageKey(title: String): String {
            for (ch in title) {
                when (ch) {
                    in '぀'..'ヿ' -> return "ja"
                    in '가'..'힯' -> return "ko"
                    in '一'..'鿿', in '㐀'..'䶿', in '豈'..'﫿' -> return "cjk"
                    in 'Ѐ'..'ӿ' -> return "ru"
                }
                if (ch.isLetter() && ch > 'ɏ') return "other"
            }
            return "latin"
        }

        /** Decade bucket of the release year; -1 = unknown year. */
        internal fun eraKey(year: Int): Int = if (year > 0) year / 10 else -1

        internal fun normalizedGenre(genre: String?): String =
            genre?.trim()?.lowercase(Locale.ROOT).orEmpty()
    }
}
