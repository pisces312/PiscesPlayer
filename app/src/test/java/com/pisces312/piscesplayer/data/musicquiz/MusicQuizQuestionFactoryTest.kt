package com.pisces312.piscesplayer.data.musicquiz

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicQuizQuestionFactoryTest {

    @Test
    fun runHasTenUniqueSongsAndFiveUnambiguousOptions() {
        val run = MusicQuizQuestionFactory(Random(7)).createRun(tracks(14))

        assertEquals(10, run.questions.size)
        assertEquals(10, run.questions.distinctBy { it.track.id }.size)
        run.questions.forEach { question ->
            assertEquals(5, question.options.size)
            assertEquals(1, question.options.count { it.songId == question.track.id })
            assertEquals(5, question.options.distinctBy {
                MusicQuizQuestionFactory.normalizedTitle(it.title)
            }.size)
            assertTrue(question.clipStartMs >= 0L)
            assertTrue(question.clipStartMs + question.clipDurationMs < question.track.durationMs)
        }
    }

    @Test
    fun fixedSeedReproducesQuestionsAndClipPositions() {
        val tracks = tracks(12)

        val first = MusicQuizQuestionFactory(Random(42)).createRun(tracks)
        val second = MusicQuizQuestionFactory(Random(42)).createRun(tracks)
        val different = MusicQuizQuestionFactory(Random(43)).createRun(tracks)

        assertEquals(first, second)
        assertNotEquals(first.questions.map { it.track.id }, different.questions.map { it.track.id })
    }

    @Test
    fun eligibilityDropsInvalidTracksAndDuplicateIds() {
        val valid = track("valid")
        val tracks = listOf(
            valid,
            valid.copy(title = "Duplicate ID"),
            track("blank-title").copy(title = " "),
            track("short").copy(durationMs = 29_999L),
            track("no-uri").copy(playbackUri = ""),
        )

        val eligible = MusicQuizQuestionFactory.eligibleTracks(tracks)

        assertEquals(listOf(valid), eligible)
        assertFalse(MusicQuizQuestionFactory.hasEnoughTracks(tracks))
    }

    @Test
    fun titleCheckIgnoresCaseAndWhitespace() {
        val tracks = tracks(10).mapIndexed { index, track ->
            val title = "Title ${index % 4}"
            track.copy(title = if (index % 2 == 0) "  $title  " else title.uppercase())
        }

        assertTrue(MusicQuizQuestionFactory.hasEnoughTracks(tracks))
        assertFalse(MusicQuizQuestionFactory.hasEnoughTitles(tracks))
    }

    @Test
    fun replacementUsesReserveWithoutTouchingFutureQuestions() {
        val factory = MusicQuizQuestionFactory(Random(9))
        val run = factory.createRun(tracks(13))

        val replacement = factory.replacement(
            allTracks = tracks(13),
            reserveTracks = run.reserveTracks,
            plannedQuestions = run.questions,
        )!!

        assertFalse(replacement.question.track.id in run.questions.map { it.track.id })
        assertEquals(2, replacement.remainingReserveTracks.size)
        assertFalse(replacement.question.track.id in replacement.remainingReserveTracks.map { it.id })
    }

    @Test
    fun distractorsMatchCorrectTrackLanguageWhenPoolAllows() {
        val zh = (1..8).map {
            track("zh-$it").copy(title = "中文歌曲 $it", year = 2000 + it, genre = "Pop")
        }
        val en = (1..6).map {
            track("en-$it").copy(title = "English Song $it", year = 2000 + it, genre = "Pop")
        }

        val run = MusicQuizQuestionFactory(Random(3)).createRun(zh + en)

        run.questions.forEach { question ->
            val lang = MusicQuizQuestionFactory.languageKey(question.track.title)
            question.options
                .filter { it.songId != question.track.id }
                .forEach { option ->
                    assertEquals(lang, MusicQuizQuestionFactory.languageKey(option.title))
                }
        }
    }

    @Test
    fun distractorsPreferSameEra() {
        val sameEra = (1..4).map {
            track("new-$it").copy(title = "新歌 $it", year = 2010 + it, genre = "Rock")
        }
        val oldEra = (1..5).map {
            track("old-$it").copy(title = "老歌 $it", year = 1990 + it, genre = "Jazz")
        }
        val target = track("target").copy(title = "目标歌曲", year = 2015, genre = "Rock")

        val run = MusicQuizQuestionFactory(Random(11)).createRun(listOf(target) + sameEra + oldEra)
        val question = run.questions.first { it.track.id == "target" }

        val distractorEras = question.options
            .filter { it.songId != question.track.id }
            .map { option ->
                MusicQuizQuestionFactory.eraKey(
                    (listOf(target) + sameEra + oldEra).first { it.title.trim() == option.title.trim() }.year,
                )
            }
        assertEquals(setOf(201), distractorEras.toSet())
    }

    @Test
    fun distractorsFallBackAcrossLanguagesWhenPoolExhausted() {
        val zh = (1..9).map { track("zh-$it").copy(title = "中文歌曲 $it") }
        val en = track("en-1").copy(title = "English One")

        val run = MusicQuizQuestionFactory(Random(5)).createRun(zh + en)
        val enQuestion = run.questions.first { it.track.id == "en-1" }

        assertEquals(5, enQuestion.options.size)
        assertEquals(1, enQuestion.options.count { it.songId == "en-1" })
    }

    private fun tracks(count: Int): List<MusicQuizTrack> = List(count, ::trackFromIndex)

    private fun trackFromIndex(index: Int): MusicQuizTrack = track("song-$index")

    private fun track(id: String): MusicQuizTrack = MusicQuizTrack(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        albumArtUriString = null,
        durationMs = 180_000L,
        playbackUri = "content://test/$id",
    )
}
