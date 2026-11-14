package com.pisces312.piscesplayer.data.musicquiz

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber

/** Small game-only record store; intentionally separate from user-facing settings and Room. */
@Singleton
class MusicQuizStatsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val stats: Flow<MusicQuizStats> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                Timber.e(error, "Unable to read music quiz records")
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            MusicQuizStats(
                bestScore = preferences[BEST_SCORE] ?: 0,
                bestStreak = preferences[BEST_STREAK] ?: 0,
                completedRuns = preferences[COMPLETED_RUNS] ?: 0,
            )
        }

    suspend fun recordRun(summary: MusicQuizRunSummary): MusicQuizRecordResult {
        var isNewBestScore = false
        var isNewBestStreak = false
        val stored = dataStore.edit { preferences ->
            val previousBestScore = preferences[BEST_SCORE] ?: 0
            val previousBestStreak = preferences[BEST_STREAK] ?: 0
            isNewBestScore = summary.score > previousBestScore
            isNewBestStreak = summary.maxStreak > previousBestStreak
            preferences[BEST_SCORE] = maxOf(previousBestScore, summary.score)
            preferences[BEST_STREAK] = maxOf(previousBestStreak, summary.maxStreak)
            preferences[COMPLETED_RUNS] = (preferences[COMPLETED_RUNS] ?: 0) + 1
        }
        return MusicQuizRecordResult(
            stats = MusicQuizStats(
                bestScore = stored[BEST_SCORE] ?: summary.score,
                bestStreak = stored[BEST_STREAK] ?: summary.maxStreak,
                completedRuns = stored[COMPLETED_RUNS] ?: 1,
            ),
            isNewBestScore = isNewBestScore,
            isNewBestStreak = isNewBestStreak,
        )
    }

    private companion object {
        val BEST_SCORE = intPreferencesKey("music_quiz_best_score")
        val BEST_STREAK = intPreferencesKey("music_quiz_best_streak")
        val COMPLETED_RUNS = intPreferencesKey("music_quiz_completed_runs")
    }
}
