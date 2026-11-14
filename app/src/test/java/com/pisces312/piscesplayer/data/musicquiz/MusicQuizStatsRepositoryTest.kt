package com.pisces312.piscesplayer.data.musicquiz

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MusicQuizStatsRepositoryTest {

    @Test
    fun `records only increase and completed runs increment`() = runTest {
        val tempDir = Files.createTempDirectory("music-quiz-stats-test")
        try {
            val repository = MusicQuizStatsRepository(
                PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("records.preferences_pb").toFile() },
                ),
            )

            val first = repository.recordRun(summary(score = 6_000, streak = 5))
            val lower = repository.recordRun(summary(score = 4_000, streak = 2))
            val higher = repository.recordRun(summary(score = 8_000, streak = 7))

            assertTrue(first.isNewBestScore)
            assertTrue(first.isNewBestStreak)
            assertEquals(MusicQuizStats(6_000, 5, 1), first.stats)
            assertFalse(lower.isNewBestScore)
            assertFalse(lower.isNewBestStreak)
            assertEquals(MusicQuizStats(6_000, 5, 2), lower.stats)
            assertTrue(higher.isNewBestScore)
            assertTrue(higher.isNewBestStreak)
            assertEquals(MusicQuizStats(8_000, 7, 3), repository.stats.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun summary(score: Int, streak: Int) = MusicQuizRunSummary(
        score = score,
        correctCount = streak,
        totalRounds = 10,
        maxStreak = streak,
        averageCorrectAnswerMs = 1_000L,
        records = emptyList(),
    )
}
