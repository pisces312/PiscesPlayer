package com.pisces312.piscesplayer.data.musicquiz

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Small game-only record store; intentionally separate from user-facing settings and Room.
 * Best records are bucketed per mode so different pools and run lengths never overwrite
 * each other. Standard mode keeps the legacy unsuffixed keys so pre-mode records survive.
 */
@Singleton
class MusicQuizStatsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val mode: Flow<MusicQuizMode> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences -> MusicQuizMode.fromKey(preferences[MODE]) }

    suspend fun setMode(mode: MusicQuizMode) {
        runCatching { dataStore.edit { it[MODE] = mode.key } }
            .onFailure { error -> Timber.e(error, "Unable to persist music quiz mode") }
    }

    val stats: Flow<MusicQuizModeStats> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                Timber.e(error, "Unable to read music quiz records")
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            MusicQuizModeStats(
                byMode = MusicQuizMode.entries.associateWith { mode ->
                    MusicQuizStats(
                        bestScore = preferences[bestScoreKey(mode)] ?: 0,
                        bestStreak = preferences[bestStreakKey(mode)] ?: 0,
                        completedRuns = preferences[completedRunsKey(mode)] ?: 0,
                    )
                },
                bestRounds = MusicQuizMode.entries.associateWith { mode ->
                    preferences[bestRoundsKey(mode)] ?: 0
                },
            )
        }

    suspend fun recordRun(mode: MusicQuizMode, summary: MusicQuizRunSummary): MusicQuizRecordResult {
        var isNewBestScore = false
        var isNewBestStreak = false
        var isNewBestRounds = false
        val stored = dataStore.edit { preferences ->
            val previousBestScore = preferences[bestScoreKey(mode)] ?: 0
            val previousBestStreak = preferences[bestStreakKey(mode)] ?: 0
            val previousBestRounds = preferences[bestRoundsKey(mode)] ?: 0
            isNewBestScore = summary.score > previousBestScore
            isNewBestStreak = summary.maxStreak > previousBestStreak
            isNewBestRounds = summary.totalRounds > previousBestRounds
            preferences[bestScoreKey(mode)] = maxOf(previousBestScore, summary.score)
            preferences[bestStreakKey(mode)] = maxOf(previousBestStreak, summary.maxStreak)
            preferences[bestRoundsKey(mode)] = maxOf(previousBestRounds, summary.totalRounds)
            preferences[completedRunsKey(mode)] = (preferences[completedRunsKey(mode)] ?: 0) + 1
        }
        return MusicQuizRecordResult(
            stats = MusicQuizStats(
                bestScore = stored[bestScoreKey(mode)] ?: summary.score,
                bestStreak = stored[bestStreakKey(mode)] ?: summary.maxStreak,
                completedRuns = stored[completedRunsKey(mode)] ?: 1,
            ),
            bestRounds = stored[bestRoundsKey(mode)] ?: summary.totalRounds,
            isNewBestScore = isNewBestScore,
            isNewBestStreak = isNewBestStreak,
            isNewBestRounds = isNewBestRounds,
        )
    }

    suspend fun currentMode(): MusicQuizMode = mode.first()

    private companion object {
        val MODE = stringPreferencesKey("music_quiz_mode")

        // Standard keeps the legacy keys so records from before modes existed survive.
        private fun suffix(mode: MusicQuizMode): String =
            if (mode == MusicQuizMode.STANDARD) "" else "_${mode.key}"

        fun bestScoreKey(mode: MusicQuizMode) = intPreferencesKey("music_quiz_best_score${suffix(mode)}")
        fun bestStreakKey(mode: MusicQuizMode) = intPreferencesKey("music_quiz_best_streak${suffix(mode)}")
        fun completedRunsKey(mode: MusicQuizMode) = intPreferencesKey("music_quiz_completed_runs${suffix(mode)}")
        fun bestRoundsKey(mode: MusicQuizMode) = intPreferencesKey("music_quiz_best_rounds${suffix(mode)}")
    }
}
