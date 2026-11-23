package com.pisces312.piscesplayer.presentation.rhythm

import com.pisces312.piscesplayer.data.classical.NameSet
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.model.SortOption
import com.pisces312.piscesplayer.data.rhythm.DspDifficultySpec
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
    // Defaults on purpose: scope ALL (a "recent" scope only surfaces a handful of very
    // recent songs, which reads as "my charts are gone"), charts WITH_CHARTS (the entry
    // exists to play charts; songs without any are the deliberate opt-in view).
    val scope: RhythmSongScope = RhythmSongScope.ALL,
    val difficulty: RhythmDifficultyFilter = RhythmDifficultyFilter.ALL,
    val charts: RhythmChartFilter = RhythmChartFilter.WITH_CHARTS,
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
    /** See [Song.isRhythmMidiSong]: MIDI rows live in the picker's own MIDI tab. */
    val isMidi: Boolean = false,
    /** Built-in classical three-layer names (original/en/zh); null for user MIDI rows. */
    val names: NameSet? = null,
) {
    val chartCount: Int get() = difficultyKeys.size

    /**
     * Search hit against the three-layer catalog names: any of original/en/zh containing
     * the query (case-insensitive). Pure-function twin of
     * `presentation/screens/ClassicalBrowseCommon.kt` `namesMatchQuery`.
     */
    fun matchesNames(query: String): Boolean {
        val names = names ?: return false
        return listOf(names.original, names.en, names.zh)
            .any { it.contains(query, ignoreCase = true) }
    }
}

/**
 * MIDI songs are regular library rows (built-in classical `piscesmidi://` content and the
 * user MIDI library both store `audio/midi`), so the picker's MIDI tab filters the normal
 * song list instead of talking to MidiLibraryRepository. The same marker keeps them out of
 * the 无谱 tab, where MIDI charts are generated from notes rather than the DSP path.
 */
internal fun Song.isRhythmMidiSong(): Boolean {
    if (mimeType?.contains("midi", ignoreCase = true) == true) return true
    if (contentUriString.startsWith("piscesmidi://")) return true
    return path.endsWith(".mid", ignoreCase = true) ||
        path.endsWith(".midi", ignoreCase = true) ||
        contentUriString.endsWith(".mid", ignoreCase = true) ||
        contentUriString.endsWith(".midi", ignoreCase = true)
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
            candidate.artist.contains(normalizedQuery, ignoreCase = true) ||
            candidate.matchesNames(normalizedQuery)) &&
            candidate.matchesDifficulty(filters.difficulty) &&
            when (filters.charts) {
                RhythmChartFilter.ALL -> true
                RhythmChartFilter.WITH_CHARTS -> candidate.chartCount > 0
                // MIDI songs are excluded on purpose: they have their own picker tab,
                // so "songs awaiting a chart" should only offer audio the DSP path can take.
                RhythmChartFilter.WITHOUT_CHARTS ->
                    candidate.chartCount == 0 && !candidate.isMidi
            }
    }
}

internal fun RhythmSongCandidate.matchesDifficulty(filter: RhythmDifficultyFilter): Boolean =
    when (filter) {
        RhythmDifficultyFilter.ALL -> true
        // "Ungraded" is the bucket for everything outside the built-in ladder: the raw master,
        // osu/tap imports and custom difficulties.
        RhythmDifficultyFilter.UNGRADED -> difficultyKeys.any {
            it == null || it == RhythmChart.DIFFICULTY_RAW || it == DspDifficultySpec.KEY_CUSTOM
        }
        else -> difficultyKeys.any { it == filter.key }
    }

internal fun RhythmSongCandidate.countForDifficulty(filter: RhythmDifficultyFilter): Int =
    when (filter) {
        RhythmDifficultyFilter.ALL -> chartCount
        RhythmDifficultyFilter.UNGRADED -> difficultyKeys.count {
            it == null || it == RhythmChart.DIFFICULTY_RAW || it == DspDifficultySpec.KEY_CUSTOM
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

internal typealias RhythmSongPair = Pair<Song, RhythmSongCandidate>

/**
 * The common song sort shared with the library. [SortOption.SongDefaultOrder] keeps the
 * filtered order [filterRhythmSongCandidates] produced (title for the 全部 scope, recency
 * for 最近); every other option re-orders by that option's semantics.
 */
internal fun sortRhythmSongPairs(
    pairs: List<RhythmSongPair>,
    option: SortOption,
): List<RhythmSongPair> {
    val comparator: Comparator<RhythmSongPair> = when (option) {
        SortOption.SongDefaultOrder -> return pairs
        SortOption.SongTitleZA -> compareByDescending<RhythmSongPair> { it.first.title.lowercase() }
            .thenBy { it.first.artist.lowercase() }
            .thenBy { it.first.id }
        SortOption.SongArtist -> compareBy(
            { it.first.artist.lowercase() },
            { it.first.title.lowercase() },
            { it.first.id },
        )
        SortOption.SongArtistDesc -> compareByDescending<RhythmSongPair> { it.first.artist.lowercase() }
            .thenBy { it.first.title.lowercase() }
            .thenBy { it.first.id }
        SortOption.SongAlbum -> compareBy(
            { it.first.album.lowercase() },
            { it.first.title.lowercase() },
            { it.first.id },
        )
        SortOption.SongAlbumDesc -> compareByDescending<RhythmSongPair> { it.first.album.lowercase() }
            .thenBy { it.first.title.lowercase() }
            .thenBy { it.first.id }
        SortOption.SongDateAdded -> compareByDescending<RhythmSongPair> { it.first.dateAdded }
            .thenBy { it.first.id }
        SortOption.SongDateAddedAsc -> compareBy(
            { it.first.dateAdded },
            { it.first.id },
        )
        SortOption.SongDuration -> compareByDescending<RhythmSongPair> { it.first.duration }
            .thenBy { it.first.id }
        SortOption.SongDurationAsc -> compareBy(
            { it.first.duration },
            { it.first.id },
        )
        else -> compareBy(
            { it.first.title.lowercase() },
            { it.first.artist.lowercase() },
            { it.first.id },
        )
    }
    return pairs.sortedWith(comparator)
}

private const val MAX_RECENT_SONGS = 100
