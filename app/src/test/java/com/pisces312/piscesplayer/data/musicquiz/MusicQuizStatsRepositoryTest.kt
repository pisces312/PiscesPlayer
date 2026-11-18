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
        withRepository { repository ->
            val first = repository.recordRun(MusicQuizMode.STANDARD, summary(score = 6_000, streak = 5))
            val lower = repository.recordRun(MusicQuizMode.STANDARD, summary(score = 4_000, streak = 2))
            val higher = repository.recordRun(MusicQuizMode.STANDARD, summary(score = 8_000, streak = 7))

            assertTrue(first.isNewBestScore)
            assertTrue(first.isNewBestStreak)
            assertEquals(MusicQuizStats(6_000, 5, 1), first.stats)
            assertFalse(lower.isNewBestScore)
            assertFalse(lower.isNewBestStreak)
            assertEquals(MusicQuizStats(6_000, 5, 2), lower.stats)
            assertTrue(higher.isNewBestScore)
            assertTrue(higher.isNewBestStreak)
            assertEquals(
                MusicQuizStats(8_000, 7, 3),
                repository.stats.first().statsFor(MusicQuizMode.STANDARD),
            )
        }
    }

    @Test
    fun `stats are bucketed per mode`() = runTest {
        withRepository { repository ->
            repository.recordRun(MusicQuizMode.KIDS, summary(score = 5_000, streak = 3))
            val standard = repository.recordRun(MusicQuizMode.STANDARD, summary(score = 1_000, streak = 1))

            assertEquals(MusicQuizStats(1_000, 1, 1), standard.stats)
            val stats = repository.stats.first()
            assertEquals(MusicQuizStats(5_000, 3, 1), stats.statsFor(MusicQuizMode.KIDS))
            assertEquals(MusicQuizStats(1_000, 1, 1), stats.statsFor(MusicQuizMode.STANDARD))
        }
    }

    @Test
    fun `best rounds only increases and is tracked per mode`() = runTest {
        withRepository { repository ->
            val first = repository.recordRun(
                MusicQuizMode.ENDLESS,
                summary(score = 3_000, streak = 2, rounds = 42),
            )
            val lower = repository.recordRun(
                MusicQuizMode.ENDLESS,
                summary(score = 9_000, streak = 4, rounds = 7),
            )

            assertTrue(first.isNewBestRounds)
            assertFalse(lower.isNewBestRounds)
            assertEquals(42, lower.bestRounds)
            assertEquals(42, repository.stats.first().bestRoundsFor(MusicQuizMode.ENDLESS))
            assertEquals(0, repository.stats.first().bestRoundsFor(MusicQuizMode.STANDARD))
        }
    }

    @Test
    fun `persisted mode round-trips with standard fallback`() = runTest {
        withRepository { repository ->
            assertEquals(MusicQuizMode.STANDARD, repository.currentMode())
            repository.setMode(MusicQuizMode.CLASSICAL)
            assertEquals(MusicQuizMode.CLASSICAL, repository.currentMode())
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.withRepository(
        block: suspend (MusicQuizStatsRepository) -> Unit,
    ) {
        val tempDir = Files.createTempDirectory("music-quiz-stats-test")
        try {
            block(
                MusicQuizStatsRepository(
                    PreferenceDataStoreFactory.create(
                        scope = backgroundScope,
                        produceFile = { tempDir.resolve("records.preferences_pb").toFile() },
                    ),
                ),
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun summary(score: Int, streak: Int, rounds: Int = 10) = MusicQuizRunSummary(
        score = score,
        correctCount = streak,
        totalRounds = rounds,
        maxStreak = streak,
        averageCorrectAnswerMs = 1_000L,
        records = emptyList(),
    )
}
