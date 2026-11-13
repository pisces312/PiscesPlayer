package com.pisces312.piscesplayer.presentation.rhythm

import com.pisces312.piscesplayer.data.rhythm.RhythmChart
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmSongPickerLogicTest {

    private fun candidate(
        id: String,
        title: String = id,
        artist: String = "Artist",
        keys: List<String?> = emptyList(),
    ) = RhythmSongCandidate(id = id, title = title, artist = artist, difficultyKeys = keys)

    private val songs = listOf(
        candidate("b", title = "Beta", keys = listOf("easy", "normal")),
        candidate("a", title = "Alpha", keys = listOf("easy", "easy")),
        candidate("c", title = "Gamma", keys = listOf(RhythmChart.DIFFICULTY_RAW)),
        candidate("d", title = "Delta", keys = listOf(null)),
        candidate("e", title = "Epsilon", artist = "Other", keys = emptyList()),
    )

    @Test
    fun recentScopeKeepsLatestPlayedOrderAndDropsDuplicates() {
        val history = listOf("b" to 10L, "a" to 30L, "b" to 40L, "missing" to 50L)

        val ids = recentRhythmSongIds(history)

        assertEquals(listOf("missing", "b", "a"), ids)
    }

    @Test
    fun recentScopeResolvesCandidatesInHistoryOrder() {
        val result = filterRhythmSongCandidates(
            candidates = songs,
            recentSongIds = listOf("d", "a", "missing"),
            query = "",
            filters = RhythmSongFilterState(),
        )

        assertEquals(listOf("d", "a"), result.map(RhythmSongCandidate::id))
    }

    @Test
    fun allScopeSortsByTitleThenArtist() {
        val result = filterRhythmSongCandidates(
            candidates = songs,
            recentSongIds = emptyList(),
            query = "",
            filters = RhythmSongFilterState(scope = RhythmSongScope.ALL),
            locale = Locale.ENGLISH,
        )

        assertEquals(listOf("a", "b", "d", "e", "c"), result.map(RhythmSongCandidate::id))
    }

    @Test
    fun difficultyMatchesOnlyStandardKey() {
        val easy = filterRhythmSongCandidates(
            candidates = songs,
            recentSongIds = emptyList(),
            query = "",
            filters = RhythmSongFilterState(
                scope = RhythmSongScope.ALL,
                difficulty = RhythmDifficultyFilter.EASY,
            ),
        )
        val hard = filterRhythmSongCandidates(
            candidates = songs,
            recentSongIds = emptyList(),
            query = "",
            filters = RhythmSongFilterState(
                scope = RhythmSongScope.ALL,
                difficulty = RhythmDifficultyFilter.HARD,
            ),
        )

        assertEquals(listOf("a", "b"), easy.map(RhythmSongCandidate::id))
        assertTrue(hard.isEmpty())
    }

    @Test
    fun ungradedCoversRawAndMissingKey() {
        val result = filterRhythmSongCandidates(
            candidates = songs,
            recentSongIds = emptyList(),
            query = "",
            filters = RhythmSongFilterState(
                scope = RhythmSongScope.ALL,
                difficulty = RhythmDifficultyFilter.UNGRADED,
            ),
        )

        assertEquals(listOf("d", "c"), result.map(RhythmSongCandidate::id))
    }

    @Test
    fun chartPresenceSplitsLibrary() {
        val withCharts = filterRhythmSongCandidates(
            candidates = songs,
            recentSongIds = emptyList(),
            query = "",
            filters = RhythmSongFilterState(
                scope = RhythmSongScope.ALL,
                charts = RhythmChartFilter.WITH_CHARTS,
            ),
        )
        val withoutCharts = filterRhythmSongCandidates(
            candidates = songs,
            recentSongIds = emptyList(),
            query = "",
            filters = RhythmSongFilterState(
                scope = RhythmSongScope.ALL,
                charts = RhythmChartFilter.WITHOUT_CHARTS,
            ),
        )

        assertEquals(listOf("e"), withoutCharts.map(RhythmSongCandidate::id))
        assertEquals(listOf("a", "b", "d", "c"), withCharts.map(RhythmSongCandidate::id))
    }

    @Test
    fun searchMatchesTitleAndArtistIgnoringCaseAndPadding() {
        val byTitle = filterRhythmSongCandidates(
            candidates = songs,
            recentSongIds = emptyList(),
            query = "  beta ",
            filters = RhythmSongFilterState(scope = RhythmSongScope.ALL),
        )
        val byArtist = filterRhythmSongCandidates(
            candidates = songs,
            recentSongIds = emptyList(),
            query = "other",
            filters = RhythmSongFilterState(scope = RhythmSongScope.ALL),
        )

        assertEquals(listOf("b"), byTitle.map(RhythmSongCandidate::id))
        assertEquals(listOf("e"), byArtist.map(RhythmSongCandidate::id))
    }

    @Test
    fun choosingDifficultyReleasesNoChartsFilter() {
        val state = RhythmSongFilterState(charts = RhythmChartFilter.WITHOUT_CHARTS)

        val updated = state.withDifficulty(RhythmDifficultyFilter.HARD)

        assertEquals(RhythmChartFilter.ALL, updated.charts)
        assertEquals(RhythmDifficultyFilter.HARD, updated.difficulty)
    }

    @Test
    fun choosingNoChartsReleasesDifficultyFilter() {
        val state = RhythmSongFilterState(difficulty = RhythmDifficultyFilter.EASY)

        val updated = state.withCharts(RhythmChartFilter.WITHOUT_CHARTS)

        assertEquals(RhythmDifficultyFilter.ALL, updated.difficulty)
        assertEquals(RhythmChartFilter.WITHOUT_CHARTS, updated.charts)
    }

    @Test
    fun hasChartsKeepsChosenDifficulty() {
        val state = RhythmSongFilterState(difficulty = RhythmDifficultyFilter.NORMAL)

        val updated = state.withCharts(RhythmChartFilter.WITH_CHARTS)

        assertEquals(RhythmDifficultyFilter.NORMAL, updated.difficulty)
        assertEquals(RhythmChartFilter.WITH_CHARTS, updated.charts)
    }

    @Test
    fun summaryCountsEveryStoredChartButDedupesDifficultyNames() {
        val song = candidate("x", keys = listOf("easy", "easy", "normal", "raw"))

        assertEquals(4, song.chartCount)
        assertEquals(2, song.countForDifficulty(RhythmDifficultyFilter.EASY))
        assertEquals(1, song.countForDifficulty(RhythmDifficultyFilter.UNGRADED))
        assertEquals(
            listOf(
                RhythmDifficultyFilter.EASY,
                RhythmDifficultyFilter.NORMAL,
                RhythmDifficultyFilter.UNGRADED,
            ),
            song.presentDifficulties(),
        )
    }
}
