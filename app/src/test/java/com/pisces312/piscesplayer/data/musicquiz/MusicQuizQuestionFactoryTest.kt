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
