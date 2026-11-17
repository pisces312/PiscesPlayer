package com.pisces312.piscesplayer.presentation.model

import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.stats.PlaybackStatsRepository
import com.pisces312.piscesplayer.data.stats.StatsTimeRange
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Parcelize
data class RecentlyPlayedSongUiModel(
    val song: Song,
    val lastPlayedTimestamp: Long
) : Parcelable

fun mapRecentlyPlayedSongs(
    playbackHistory: List<PlaybackStatsRepository.PlaybackHistoryEntry>,
    songs: List<Song>,
    range: StatsTimeRange? = null,
    nowMillis: Long = System.currentTimeMillis(),
    maxItems: Int = Int.MAX_VALUE
): List<RecentlyPlayedSongUiModel> {
    if (maxItems <= 0 || playbackHistory.isEmpty() || songs.isEmpty()) return emptyList()

    val songById = songs.associateBy { it.id }
    val recentSongIds = collectRecentlyPlayedSongIds(
        playbackHistory = playbackHistory,
        range = range,
        nowMillis = nowMillis,
        maxItems = maxItems
    )

    if (recentSongIds.isEmpty()) return emptyList()

    val (startBound, endBound) = range.resolveBounds(
        nowMillis = nowMillis.coerceAtLeast(0L),
        zoneId = ZoneId.systemDefault()
    )

    // collectRecentlyPlayedSongIds 已按时间倒序排过一次并做了 maxItems 截断；
    // 这里不再重复排序，单次 O(n) 扫出「区间内每首歌的最新时间」即可，
    // 输出顺序沿用 recentSongIds（即时间倒序 + 去重后的稳定序）。
    val newestInRange = HashMap<String, Long>(playbackHistory.size)
    for (entry in playbackHistory) {
        val safeTimestamp = entry.timestamp.coerceAtLeast(0L)
        if (safeTimestamp > endBound) continue
        if (startBound != null && safeTimestamp < startBound) continue
        val prev = newestInRange[entry.songId]
        if (prev == null || safeTimestamp > prev) {
            newestInRange[entry.songId] = safeTimestamp
        }
    }

    return recentSongIds.mapNotNull { songId ->
        val song = songById[songId] ?: return@mapNotNull null
        val timestamp = newestInRange[songId] ?: return@mapNotNull null
        RecentlyPlayedSongUiModel(song = song, lastPlayedTimestamp = timestamp)
    }
}

fun collectRecentlyPlayedSongIds(
    playbackHistory: List<PlaybackStatsRepository.PlaybackHistoryEntry>,
    range: StatsTimeRange? = null,
    nowMillis: Long = System.currentTimeMillis(),
    maxItems: Int = Int.MAX_VALUE
): List<String> {
    if (maxItems <= 0 || playbackHistory.isEmpty()) return emptyList()

    val (startBound, endBound) = range.resolveBounds(
        nowMillis = nowMillis.coerceAtLeast(0L),
        zoneId = ZoneId.systemDefault()
    )

    val seenSongIds = LinkedHashSet<String>()
    val sortedHistory = playbackHistory.sortedWith(
        compareByDescending<PlaybackStatsRepository.PlaybackHistoryEntry> { it.timestamp }
            .thenBy { it.songId }
    )

    for (entry in sortedHistory) {
        if (seenSongIds.size >= maxItems) break
        val safeTimestamp = entry.timestamp.coerceAtLeast(0L)
        if (safeTimestamp > endBound) continue
        if (startBound != null && safeTimestamp < startBound) continue
        seenSongIds.add(entry.songId)
    }

    return seenSongIds.toList()
}

private fun StatsTimeRange?.resolveBounds(
    nowMillis: Long,
    zoneId: ZoneId
): Pair<Long?, Long> {
    val safeNow = nowMillis.coerceAtLeast(0L)
    val zonedNow = java.time.Instant.ofEpochMilli(safeNow).atZone(zoneId)

    return when (this) {
        StatsTimeRange.DAY -> {
            val start = zonedNow.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.WEEK -> {
            val startOfWeek = zonedNow.toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val start = startOfWeek.atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.MONTH -> {
            val startOfMonth = zonedNow.toLocalDate().withDayOfMonth(1)
            val start = startOfMonth.atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.YEAR -> {
            val startOfYear = zonedNow.toLocalDate().withDayOfYear(1)
            val start = startOfYear.atStartOfDay(zoneId).toInstant().toEpochMilli()
            start to safeNow
        }
        StatsTimeRange.ALL, null -> {
            null to safeNow
        }
    }
}
