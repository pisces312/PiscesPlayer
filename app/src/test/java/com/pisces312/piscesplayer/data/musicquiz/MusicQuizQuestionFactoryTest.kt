package com.pisces312.piscesplayer.data.musicquiz

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun optionsCarryCoverArtFromTheirTracks() {
        val source = tracks(10).mapIndexed { index, track ->
            track.copy(albumArtUriString = "content://art/$index")
        }
        val artById = source.associate { it.id to it.albumArtUriString }

        val run = MusicQuizQuestionFactory(Random(7)).createRun(source)

        run.questions.forEach { question ->
            question.options.forEach { option ->
                assertEquals(artById[option.songId], option.albumArtUriString)
            }
        }
    }

    @Test
    fun optionsCarryArtistAndYearFromTheirTracks() {
        val source = tracks(10).mapIndexed { index, track ->
            track.copy(
                artist = if (index % 3 == 0) "  Artist ${track.id}  " else "Artist ${track.id}",
                year = if (index % 4 == 0) 0 else 1990 + index,
            )
        }
        val byId = source.associateBy(MusicQuizTrack::id)

        val run = MusicQuizQuestionFactory(Random(7)).createRun(source)

        run.questions.forEach { question ->
            question.options.forEach { option ->
                val track = byId.getValue(option.songId)
                assertEquals(track.artist.trim(), option.artist)
                assertEquals(track.year, option.year)
            }
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
        // Four display-equivalent titles can never fill five unambiguous options.
        assertTrue(MusicQuizQuestionFactory.answerTracks(tracks).isEmpty())
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

    @Test
    fun classicalAnswerGetsOnlyClassicalOptions() {
        val classical = (1..6).map {
            track("cl-$it").copy(title = "古典曲目 $it", isClassical = true)
        }
        val pop = (1..6).map { track("pop-$it").copy(title = "流行曲目 $it") }
        val all = classical + pop
        val byId = all.associateBy(MusicQuizTrack::id)

        val run = MusicQuizQuestionFactory(Random(1)).createRun(all)

        run.questions.forEach { question ->
            val expectClassical = question.track.isClassical
            question.options.forEach { option ->
                assertEquals(expectClassical, byId.getValue(option.songId).isClassical)
            }
        }
    }

    @Test
    fun nonViableSubPoolLeavesQuestionGenerationEntirely() {
        // Classical sub-pool has only 3 distinct titles: it can neither answer nor distract.
        val classical = (1..3).map {
            track("cl-$it").copy(title = "古典曲目 $it", isClassical = true)
        }
        val pop = (1..12).map { track("pop-$it").copy(title = "流行曲目 $it") }

        val run = MusicQuizQuestionFactory(Random(2)).createRun(classical + pop)

        run.questions.forEach { question ->
            assertFalse(question.track.isClassical)
            question.options.forEach { option ->
                assertFalse(option.songId.startsWith("cl-"))
            }
        }
    }

    @Test
    fun endlessDrawDealsEachSongOnceThenSignalsExhaustion() {
        val tracks = tracks(10)
        val factory = MusicQuizQuestionFactory(Random(2))
        val used = mutableSetOf<String>()
        var last: String? = null

        val drawn = (1..10).map {
            val question = factory.createNextQuestion(tracks, used, last)!!
            used += question.track.id
            last = question.track.id
            question.track.id
        }

        assertEquals(10, drawn.toSet().size)
        assertNull(factory.createNextQuestion(tracks, used, last))
    }

    @Test
    fun endlessDrawAfterCycleRestartsNeverRepeatsPreviousSong() {
        val tracks = tracks(10)
        val factory = MusicQuizQuestionFactory(Random(3))
        val used = mutableSetOf<String>()
        var last: String? = null

        repeat(10) {
            val question = factory.createNextQuestion(tracks, used, last)!!
            used += question.track.id
            last = question.track.id
        }
        used.clear()

        val next = factory.createNextQuestion(tracks, used, last)!!

        assertNotEquals(last, next.track.id)
    }

    @Test
    fun classicalRunMixesTitleAndComposerQuestions() {
        val classical = (1..12).map {
            track("cl-$it").copy(
                title = "Classical Piece $it",
                artist = "Composer ${(it - 1) / 2 + 1}",
                isClassical = true,
            )
        }

        val run = MusicQuizQuestionFactory(Random(7)).createClassicalRun(classical)

        assertEquals(10, run.questions.size)
        val titleCount = run.questions.count { it.questionType == MusicQuizQuestionType.TITLE }
        val composerCount = run.questions.count { it.questionType == MusicQuizQuestionType.COMPOSER }
        assertEquals(5, titleCount)
        assertEquals(5, composerCount)
    }

    @Test
    fun classicalComposerQuestionUsesComposerNamesAsOptions() {
        val classical = (1..12).map {
            track("cl-$it").copy(
                title = "Classical Piece $it",
                artist = "Composer ${(it - 1) / 2 + 1}",
                isClassical = true,
            )
        }

        val run = MusicQuizQuestionFactory(Random(7)).createClassicalRun(classical)
        val composerQuestion = run.questions.first { it.questionType == MusicQuizQuestionType.COMPOSER }

        assertEquals(5, composerQuestion.options.size)
        assertEquals(1, composerQuestion.options.count { it.songId == composerQuestion.track.id })
        // Options are composer names, not piece titles.
        composerQuestion.options.forEach { option ->
            assertTrue(option.title.startsWith("Composer "))
        }
        // All options have distinct composer names.
        assertEquals(5, composerQuestion.options.distinctBy { it.title }.size)
    }

    @Test
    fun classicalTitleQuestionStillUsesPieceTitles() {
        val classical = (1..12).map {
            track("cl-$it").copy(
                title = "Classical Piece $it",
                artist = "Composer ${(it - 1) / 2 + 1}",
                isClassical = true,
            )
        }

        val run = MusicQuizQuestionFactory(Random(7)).createClassicalRun(classical)
        val titleQuestion = run.questions.first { it.questionType == MusicQuizQuestionType.TITLE }

        assertEquals(5, titleQuestion.options.size)
        titleQuestion.options.forEach { option ->
            assertTrue(option.title.startsWith("Classical Piece "))
        }
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
