package com.pisces312.piscesplayer.presentation.rhythm

import com.pisces312.piscesplayer.data.rhythm.RhythmChart
import java.text.Collator
import java.util.Locale

internal enum class RhythmSongScope { RECENT, ALL }

internal enum class RhythmDifficultyFilter(val key: String?) {
    ALL(null),
    EASY("easy"),
    NORMAL("normal"),
    HARD("hard"),
    UNGRADED(null),
}

internal enum class RhythmChartFilter { ALL, WITH_CHARTS, WITHOUT_CHARTS }

internal data class RhythmSongFilterState(
    val scope: RhythmSongScope = RhythmSongScope.RECENT,
    val difficulty: RhythmDifficultyFilter = RhythmDifficultyFilter.ALL,
    val charts: RhythmChartFilter = RhythmChartFilter.ALL,
) {
    val isDefault: Boolean
        get() = this == RhythmSongFilterState()

    fun withDifficulty(value: RhythmDifficultyFilter): RhythmSongFilterState = copy(
        difficulty = value,
        charts = if (value != RhythmDifficultyFilter.ALL &&
            charts == RhythmChartFilter.WITHOUT_CHARTS
        ) {
            RhythmChartFilter.ALL
        } else {
            charts
        },
    )

    fun withCharts(value: RhythmChartFilter): RhythmSongFilterState = copy(
        charts = value,
        difficulty = if (value == RhythmChartFilter.WITHOUT_CHARTS) {
            RhythmDifficultyFilter.ALL
        } else {
            difficulty
        },
    )
}

internal data class RhythmSongCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val difficultyKeys: List<String?>,
) {
    val chartCount: Int get() = difficultyKeys.size
}

internal fun recentRhythmSongIds(history: List<Pair<String, Long>>): List<String> = history
    .sortedByDescending { it.second }
    .distinctBy { it.first }
    .map { it.first }

internal fun filterRhythmSongCandidates(
    candidates: List<RhythmSongCandidate>,
    recentSongIds: List<String>,
    query: String,
    filters: RhythmSongFilterState,
    locale: Locale = Locale.getDefault(),
): List<RhythmSongCandidate> {
    val scoped = when (filters.scope) {
        RhythmSongScope.RECENT -> {
            val byId = candidates.associateBy(RhythmSongCandidate::id)
            recentSongIds.mapNotNull(byId::get).take(MAX_RECENT_SONGS)
        }
        RhythmSongScope.ALL -> {
            val collator = Collator.getInstance(locale)
            candidates.sortedWith { left, right ->
                collator.compare(left.title, right.title).takeIf { it != 0 }
                    ?: collator.compare(left.artist, right.artist).takeIf { it != 0 }
                    ?: left.id.compareTo(right.id)
            }
        }
    }
    val normalizedQuery = query.trim()
    return scoped.filter { candidate ->
        (normalizedQuery.isEmpty() ||
            candidate.title.contains(normalizedQuery, ignoreCase = true) ||
            candidate.artist.contains(normalizedQuery, ignoreCase = true)) &&
            candidate.matchesDifficulty(filters.difficulty) &&
            when (filters.charts) {
                RhythmChartFilter.ALL -> true
                RhythmChartFilter.WITH_CHARTS -> candidate.chartCount > 0
                RhythmChartFilter.WITHOUT_CHARTS -> candidate.chartCount == 0
            }
    }
}

internal fun RhythmSongCandidate.matchesDifficulty(filter: RhythmDifficultyFilter): Boolean =
    when (filter) {
        RhythmDifficultyFilter.ALL -> true
        RhythmDifficultyFilter.UNGRADED -> difficultyKeys.any {
            it == null || it == RhythmChart.DIFFICULTY_RAW
        }
        else -> difficultyKeys.any { it == filter.key }
    }

internal fun RhythmSongCandidate.countForDifficulty(filter: RhythmDifficultyFilter): Int =
    when (filter) {
        RhythmDifficultyFilter.ALL -> chartCount
        RhythmDifficultyFilter.UNGRADED -> difficultyKeys.count {
            it == null || it == RhythmChart.DIFFICULTY_RAW
        }
        else -> difficultyKeys.count { it == filter.key }
    }

internal fun RhythmSongCandidate.presentDifficulties(): List<RhythmDifficultyFilter> =
    listOf(
        RhythmDifficultyFilter.EASY,
        RhythmDifficultyFilter.NORMAL,
        RhythmDifficultyFilter.HARD,
        RhythmDifficultyFilter.UNGRADED,
    ).filter(::matchesDifficulty)

private const val MAX_RECENT_SONGS = 100
