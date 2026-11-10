package com.pisces312.piscesplayer.data.stats

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.pisces312.piscesplayer.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import timber.log.Timber
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Singleton
class PlaybackStatsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository
) {

    private val gson = Gson()
    private val historyFile = File(context.filesDir, "playback_history.json")
    private val atomicHistoryFile = AtomicFile(historyFile)
    private val fileLock = Any()
    private var cachedEvents: List<PlaybackEvent>? = null
    private val eventsType = object : TypeToken<MutableList<PlaybackEvent>>() {}.type
    private val _refreshVersion = MutableStateFlow(0L)
    val refreshFlow: StateFlow<Long> = _refreshVersion.asStateFlow()

    /**
     * 算好的区间统计，键为「日历周期身份」（range + 起止日期）而不是 `now` 截断后的边界：
     * 同一周期内时间流逝不会让缓存失效，跨天 / 跨周才需要算一次新的。
     *
     * 打开统计页、来回切换周期都不再触发全量聚合；只有显式刷新（[invalidateSummaryCache]）
     * 或外部数据变更（导入 / 恢复备份 / 排序上限，见 [requestRefresh]）才会丢弃结果。
     * 播放本身**不会**清缓存，这样「来回切换不变」才成立。
     */
    private val summaryCacheLock = Any()
    private val summaryCache =
        object : LinkedHashMap<SummaryCacheKey, PlaybackStatsSummary>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<SummaryCacheKey, PlaybackStatsSummary>
            ): Boolean = size > MAX_SUMMARY_CACHE_ENTRIES
        }

    private val statsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Recompute listening stats when the ranking-limit preference changes so the
        // stats screens pick up the new cap without the user manually refreshing.
        statsScope.launch {
            userPreferencesRepository.statsRankingLimitFlow
                .drop(1)
                .collect { requestRefresh() }
        }
    }

    private val sessionGapThresholdMs = TimeUnit.MINUTES.toMillis(30)

    data class PlaybackEvent(
        val songId: String,
        val timestamp: Long,
        val durationMs: Long,
        val startTimestamp: Long? = null,
        val endTimestamp: Long? = null,
        val playCount: Int = 1
    ) {
        /** 归一化后的权重：JSON 缺字段或非法值时退化为 1。 */
        val weight: Int
            get() = if (playCount > 0) playCount else 1
    }

    data class PlaybackHistoryEntry(
        val songId: String,
        val timestamp: Long,
        /** How long this play actually lasted; 0 when unknown. */
        val durationMs: Long = 0L
    )

    data class SongPlaybackSummary(
        val songId: String,
        val title: String,
        val artist: String,
        val albumArtUri: String?,
        val totalDurationMs: Long,
        val playCount: Int
    )

    data class ArtistPlaybackSummary(
        val artist: String,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueSongs: Int
    )

    data class GenrePlaybackSummary(
        val genre: String,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueArtists: Int
    )

    data class AlbumPlaybackSummary(
        val album: String,
        val albumArtUri: String?,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueSongs: Int
    )

    data class TimelineEntry(
        val label: String,
        val totalDurationMs: Long,
        val playCount: Int
    )

    data class PlaybackSegment(
        val songId: String,
        val startMillis: Long,
        val endMillis: Long,
        val playCount: Int = 1
    ) {
        val durationMs: Long
            get() = (endMillis - startMillis).coerceAtLeast(0L)
    }

    data class PlaybackSpan(
        val startMillis: Long,
        val endMillis: Long,
        val playCount: Int = 1
    ) {
        val durationMs: Long
            get() = (endMillis - startMillis).coerceAtLeast(0L)
    }

    data class DayListeningDistribution(
        val bucketSizeMinutes: Int,
        val buckets: List<DailyListeningBucket>,
        val maxBucketDurationMs: Long,
        val days: List<DailyListeningDay>
    )

    data class DailyListeningBucket(
        val startMinute: Int,
        val endMinuteExclusive: Int,
        val totalDurationMs: Long
    )

    data class DailyListeningDay(
        val date: LocalDate,
        val buckets: List<DailyListeningBucket>,
        val totalDurationMs: Long
    )

    data class PlaybackStatsSummary(
        val range: StatsTimeRange,
        val startTimestamp: Long?,
        val endTimestamp: Long,
        val totalDurationMs: Long,
        val totalPlayCount: Int,
        val uniqueSongs: Int,
        val averageDailyDurationMs: Long,
        val songs: List<SongPlaybackSummary> = emptyList(),
        val topSongs: List<SongPlaybackSummary> = emptyList(),
        val topGenres: List<GenrePlaybackSummary> = emptyList(),
        val timeline: List<TimelineEntry>,
        /**
         * Full ranked artist/album lists for the period, capped by the stats ranking-limit
         * preference (default [MAX_RANKING_STATS_COUNT]).
         * The stats screen trims these for display and hands the whole list to the "show all" screens.
         */
        val topArtists: List<ArtistPlaybackSummary>,
        val topAlbums: List<AlbumPlaybackSummary>,
        val activeDays: Int,
        val longestStreakDays: Int,
        val totalSessions: Int,
        val averageSessionDurationMs: Long,
        val longestSessionDurationMs: Long,
        val averageSessionsPerDay: Double,
        val dayListeningDistribution: DayListeningDistribution?,
        val peakTimeline: TimelineEntry?,
        val peakDayLabel: String?,
        val peakDayDurationMs: Long
    )

    suspend fun recordPlayback(
        songId: String,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis(),
        playCount: Int = 1
    ): Unit = withContext(Dispatchers.IO) {
        if (songId.isBlank()) return@withContext
        val coercedTimestamp = timestamp.coerceAtLeast(0L)
        val coercedDuration = durationMs.coerceAtLeast(0L)
        val start = (coercedTimestamp - coercedDuration).coerceAtLeast(0L)
        val sanitizedEvent = PlaybackEvent(
            songId = songId,
            timestamp = coercedTimestamp,
            durationMs = coercedDuration,
            startTimestamp = start,
            endTimestamp = coercedTimestamp,
            playCount = playCount.coerceAtLeast(1)
        )
        // 刻意不调用 notifyStatsChanged()：那会让每次落盘都触发一次重算，与
        // 「打开统计页 / 来回切换周期时统计只算一次」直接冲突。新事件会在用户点刷新
        // （StatsViewModel.requestStatsRefresh）或周期身份变化时被算进去。
        updateEventsAtomically { events ->
            events += sanitizedEvent
            events
        }
    }

    suspend fun loadSummary(
        range: StatsTimeRange,
        songs: List<Song>,
        nowMillis: Long = System.currentTimeMillis(),
        extraEvents: List<PlaybackEvent> = emptyList()
    ): PlaybackStatsSummary = loadSummary(StatsPeriod(range), songs, nowMillis, extraEvents)

    /**
     * 区间统计。
     *
     * 结果按「日历周期身份」缓存（见 [summaryCache]）：命中时直接返回同一个实例，不读文件、
     * 不做任何聚合 —— 这是「打开统计页 / 来回切换周期时统计只算一次」的实现基础。
     *
     * @param extraEvents 尚未落盘的内存事件（当前播放中的片段，见
     *   `ListeningStatsTracker.pendingFragment`）。它落在 [period] 内时该周期视为未命中 ——
     *   片段还在增长，缓存没有意义；落在周期外（例如播放中去看「上周」）则不影响缓存命中。
     */
    suspend fun loadSummary(
        period: StatsPeriod,
        songs: List<Song>,
        nowMillis: Long = System.currentTimeMillis(),
        extraEvents: List<PlaybackEvent> = emptyList()
    ): PlaybackStatsSummary = withContext(Dispatchers.IO) {
        val zoneId = ZoneId.systemDefault()
        val cacheKey = summaryCacheKey(period, nowMillis, zoneId)
        val affectsPeriod = extraEvents.isNotEmpty() &&
            periodOverlapsEvents(period, extraEvents, nowMillis, zoneId)
        if (!affectsPeriod) {
            synchronized(summaryCacheLock) { summaryCache[cacheKey] }?.let { return@withContext it }
        }

        val limit = userPreferencesRepository.statsRankingLimitFlow.first().let { raw ->
            if (raw <= 0) Int.MAX_VALUE else raw
        }
        val summary = buildSummaryFromEvents(
            period = period,
            songs = songs,
            nowMillis = nowMillis,
            allEvents = readEvents() + extraEvents,
            zoneId = zoneId,
            maxRankingCount = limit
        )
        if (!affectsPeriod) {
            synchronized(summaryCacheLock) { summaryCache[cacheKey] = summary }
        }
        summary
    }

    /**
     * 丢弃已算好的区间统计。用在「显式刷新」「导入 / 恢复备份」「排序上限变更」上；
     * 播放产生的新事件不清缓存（见 [recordPlayback]）。
     */
    fun invalidateSummaryCache() {
        synchronized(summaryCacheLock) { summaryCache.clear() }
    }

    private fun summaryCacheKey(
        period: StatsPeriod,
        nowMillis: Long,
        zoneId: ZoneId
    ): SummaryCacheKey = SummaryCacheKey(
        range = period.range,
        startDate = period.startDate(nowMillis, zoneId),
        endDateExclusive = period.endDateExclusive(nowMillis, zoneId)
    )

    /**
     * [events] 是否与 [period] 的区间有交集。
     *
     * 非 ALL 的边界只由日历决定（不依赖事件），可以直接用空事件表算；ALL 的起点取自最早事件，
     * 一律视为受影响（重算），以免漏掉回溯出来的区间。
     */
    private fun periodOverlapsEvents(
        period: StatsPeriod,
        events: List<PlaybackEvent>,
        nowMillis: Long,
        zoneId: ZoneId
    ): Boolean {
        if (period.range == StatsTimeRange.ALL) return true
        val (startBound, endBound) = period.resolveBounds(emptyList(), nowMillis, zoneId)
        val lowerBound = startBound ?: Long.MIN_VALUE
        return events.any { event ->
            event.endMillis() >= lowerBound && event.startMillis() <= endBound
        }
    }

    /**
     * 把「只有最后播放时间戳」的导入事件展开成真实播放区间。
     *
     * 第三方导入（Poweramp）只提供 played_at，事件退化成 start == end 的零长度点。
     * 真实播放区间应为 `[t − 时长, t]`（向过去回溯，而不是向未来延伸）；
     * 带权重 `[playCount] = N` 的事件按 `N × 歌曲时长` 回溯，与本地「播 N 次累加 N 次时长」口径一致。
     *
     * 本地播放事件（start < end）原样返回。
     */
    private fun expandImportedSpan(
        event: PlaybackEvent,
        songMap: Map<String, Song>
    ): PlaybackEvent {
        val start = event.startMillis()
        val rawEnd = event.endMillis()
        if (rawEnd > start) return event
        val songDuration = songMap[event.songId]?.duration?.takeIf { it > 0L } ?: return event
        val expandedDuration = songDuration * event.weight
        return event.copy(
            durationMs = expandedDuration,
            startTimestamp = (rawEnd - expandedDuration).coerceAtLeast(0L),
            endTimestamp = rawEnd
        )
    }

    internal fun buildSummaryFromEvents(
        range: StatsTimeRange,
        songs: List<Song>,
        nowMillis: Long,
        allEvents: List<PlaybackEvent>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        maxRankingCount: Int = MAX_RANKING_STATS_COUNT
    ): PlaybackStatsSummary = buildSummaryFromEvents(
        period = StatsPeriod(range),
        songs = songs,
        nowMillis = nowMillis,
        allEvents = allEvents,
        zoneId = zoneId,
        maxRankingCount = maxRankingCount
    )

    internal fun buildSummaryFromEvents(
        period: StatsPeriod,
        songs: List<Song>,
        nowMillis: Long,
        allEvents: List<PlaybackEvent>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        maxRankingCount: Int = MAX_RANKING_STATS_COUNT
    ): PlaybackStatsSummary {
        val songMap = songs.associateBy { it.id }
        // 边界必须先算对，再决定展开谁：
        // - StatsTimeRange.ALL 的起点取「最早事件的 start」，而导入事件的原始 start 就是
        //   last_played 时间戳；若先算边界，回溯出的 [t − N×时长, t] 会整条落在边界之前被裁掉，
        //   所以 ALL 先展开再算边界。
        // - 其余 range 的边界只由日历决定（不依赖事件），因此可以先用 endMillis 粗筛、再展开候选：
        //   展开只改 start 不改 end，粗筛不会漏事件。历史永不裁剪，这一步把「看今天」的成本
        //   从 O(全部历史) 压到 O(命中区间)。
        val startBound: Long?
        val endBound: Long
        val expandedEvents: List<PlaybackEvent>
        if (period.range == StatsTimeRange.ALL) {
            expandedEvents = allEvents.map { event -> expandImportedSpan(event, songMap) }
            val bounds = period.resolveBounds(expandedEvents, nowMillis, zoneId)
            startBound = bounds.first
            endBound = bounds.second
        } else {
            val bounds = period.resolveBounds(emptyList(), nowMillis, zoneId)
            startBound = bounds.first
            endBound = bounds.second
            val lowerBound = startBound ?: Long.MIN_VALUE
            expandedEvents = allEvents
                .filter { event -> event.endMillis() >= lowerBound }
                .map { event -> expandImportedSpan(event, songMap) }
        }
        val filteredEvents = expandedEvents.mapNotNull { event ->
            val start = event.startMillis()
            val end = event.endMillis()
            val lowerBound = startBound ?: Long.MIN_VALUE
            if (end < lowerBound || start > endBound) {
                return@mapNotNull null
            }

            val clippedStart = max(start, lowerBound)
            val clippedEnd = min(end, endBound)
            val clippedDuration = (clippedEnd - clippedStart).coerceAtLeast(0L)
            if (clippedDuration <= 0L) {
                return@mapNotNull null
            }

            event.copy(
                timestamp = clippedEnd,
                durationMs = clippedDuration,
                startTimestamp = clippedStart,
                endTimestamp = clippedEnd
            )
        }

        val normalizedEvents = filteredEvents

        val segmentsBySong = normalizedEvents
            .groupBy { it.songId }
            .mapValues { (_, eventsForSong) -> mergeSongEvents(eventsForSong) }

        val overallSpans = mergeSpans(segmentsBySong.values.flatten().map { PlaybackSpan(it.startMillis, it.endMillis, it.playCount) })

        val effectiveStart = startBound
            ?: overallSpans.minOfOrNull { it.startMillis }
            ?: normalizedEvents.minOfOrNull { it.startMillis() }
            ?: allEvents.minOfOrNull { it.startMillis() }
        val effectiveEnd = overallSpans.maxOfOrNull { it.endMillis } ?: endBound

        val totalDuration = overallSpans.sumOf { it.durationMs }
        val totalPlays = segmentsBySong.values.sumOf { segments -> segments.sumOf { it.playCount } }
        val uniqueSongs = segmentsBySong.keys.size

        val allSongs = segmentsBySong
            .mapNotNull { (songId, segmentsForSong) ->
                val song = songMap[songId] ?: return@mapNotNull null
                val title = song.title.takeIf { it.isNotBlank() }
                    ?: song.path.substringAfterLast('/').ifBlank { return@mapNotNull null }
                val artist = song.displayArtist.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                SongPlaybackSummary(
                    songId = songId,
                    title = title,
                    artist = artist,
                    albumArtUri = song.albumArtUriString,
                    totalDurationMs = segmentsForSong.sumOf { it.durationMs },
                    playCount = segmentsForSong.sumOf { it.playCount }
                )
            }
            .sortedWith(
                compareByDescending<SongPlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(maxRankingCount)
        val topSongs = allSongs.take(5)

        val topGenres = segmentsBySong.entries
            .groupBy { (songId, _) ->
                val genre = songMap[songId]?.genre
                if (genre.isNullOrBlank()) "Unknown Genre" else genre
            }
            .map { (genre, groupedSongs) ->
                val flattened = groupedSongs.flatMap { it.value }
                val uniqueArtists = groupedSongs
                    .flatMap { (songId, _) ->
                        statsArtistNames(songMap[songId])
                    }
                    .distinctBy { it.normalizedArtistKey() }
                    .size
                GenrePlaybackSummary(
                    genre = genre,
                    totalDurationMs = flattened.sumOf { it.durationMs },
                    playCount = flattened.sumOf { it.playCount },
                    uniqueArtists = uniqueArtists
                )
            }
            .sortedWith(
                compareByDescending<GenrePlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(5)

        var daySpan = 1L
        val averageDailyDuration = if (effectiveStart != null) {
            val startInstant = Instant.ofEpochMilli(effectiveStart)
            val endInstant = Instant.ofEpochMilli(effectiveEnd)
            val startDate = startInstant.atZone(zoneId).toLocalDate()
            val endDate = endInstant.atZone(zoneId).toLocalDate()
            daySpan = max(1L, java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1)
            if (daySpan > 0) totalDuration / daySpan else totalDuration
        } else {
            totalDuration
        }

        val daySlices = overallSpans.flatMap { sliceSpanByDay(it, zoneId) }
        val eventsByDay = daySlices.groupBy { it.date }
        val activeDays = eventsByDay.size
        val sortedDays = eventsByDay.keys.sorted()
        var longestStreak = 0
        var currentStreak = 0
        var lastDay: java.time.LocalDate? = null
        sortedDays.forEach { day ->
            if (lastDay == null || day == lastDay.plusDays(1)) {
                currentStreak += 1
            } else {
                currentStreak = 1
            }
            if (currentStreak > longestStreak) {
                longestStreak = currentStreak
            }
            lastDay = day
        }

        val sessions = computeListeningSessions(overallSpans)
        val totalSessions = sessions.size
        val totalSessionDuration = sessions.sumOf { it.totalDuration }
        val averageSessionDuration = if (totalSessions > 0) totalSessionDuration / totalSessions else 0L
        val longestSessionDuration = sessions.maxOfOrNull { it.totalDuration } ?: 0L
        val averageSessionsPerDay = if (daySpan > 0) totalSessions.toDouble() / daySpan else 0.0

        val timelineBuckets = createTimelineBuckets(
            range = period.range,
            zoneId = zoneId,
            anchor = Instant.ofEpochMilli(period.anchorMillis ?: nowMillis),
            spans = overallSpans,
            fallbackStart = effectiveStart ?: endBound
        )
        val timelineEntries = accumulateTimelineEntries(timelineBuckets, overallSpans)

        val topArtists = segmentsBySong.entries
            .flatMap { (songId, segmentsForSong) ->
                statsArtistNames(songMap[songId]).map { artist ->
                    ArtistSongPlayback(
                        artist = artist,
                        songId = songId,
                        segments = segmentsForSong
                    )
                }
            }
            .groupBy { it.artist }
            .map { (artist, artistSongs) ->
                val flattened = artistSongs.flatMap { it.segments }
                val uniqueSongCount = artistSongs
                    .map { it.songId }
                    .toSet()
                    .size
                ArtistPlaybackSummary(
                    artist = artist,
                    totalDurationMs = flattened.sumOf { it.durationMs },
                    playCount = flattened.sumOf { it.playCount },
                    uniqueSongs = uniqueSongCount
                )
            }
            .sortedWith(
                compareByDescending<ArtistPlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(maxRankingCount)

        val topAlbums = segmentsBySong.entries
            .groupBy { (songId, _) ->
                val song = songMap[songId]
                song?.album?.takeIf { it.isNotBlank() } ?: "Unknown Album"
            }
            .map { (album, groupedSongs) ->
                val flattened = groupedSongs.flatMap { it.value }
                val uniqueSongCount = groupedSongs.size
                val firstSong = groupedSongs
                    .asSequence()
                    .mapNotNull { songMap[it.key] }
                    .firstOrNull()
                AlbumPlaybackSummary(
                    album = album,
                    albumArtUri = firstSong?.albumArtUriString,
                    totalDurationMs = flattened.sumOf { it.durationMs },
                    playCount = flattened.sumOf { it.playCount },
                    uniqueSongs = uniqueSongCount
                )
            }
            .sortedWith(
                compareByDescending<AlbumPlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(maxRankingCount)

        val peakTimeline = timelineEntries
            .filter { it.totalDurationMs > 0L }
            .maxByOrNull { it.totalDurationMs }

        val durationsByDayOfWeek = daySlices.groupBy { slice ->
            slice.date.dayOfWeek
        }
        val peakDay = durationsByDayOfWeek.maxByOrNull { entry ->
            entry.value.sumOf { it.durationMs }
        }
        val peakDayLabel = peakDay?.key?.getDisplayName(TextStyle.FULL, Locale.US)
        val peakDayDuration = peakDay?.value?.sumOf { it.durationMs } ?: 0L
        val dayListeningDistribution = if (period.range == StatsTimeRange.DAY || period.range == StatsTimeRange.WEEK) {
            computeDayListeningDistribution(
                spans = overallSpans,
                zoneId = zoneId,
                range = period.range,
                startBound = startBound,
                endBound = endBound
            )
        } else null

        return PlaybackStatsSummary(
            range = period.range,
            startTimestamp = startBound,
            endTimestamp = endBound,
            totalDurationMs = totalDuration,
            totalPlayCount = totalPlays,
            uniqueSongs = uniqueSongs,
            averageDailyDurationMs = averageDailyDuration,
            songs = allSongs,
            topSongs = topSongs,
            timeline = timelineEntries,
            topGenres = topGenres,
            topArtists = topArtists,
            topAlbums = topAlbums,
            activeDays = activeDays,
            longestStreakDays = longestStreak,
            totalSessions = totalSessions,
            averageSessionDurationMs = averageSessionDuration,
            longestSessionDurationMs = longestSessionDuration,
            averageSessionsPerDay = averageSessionsPerDay,
            dayListeningDistribution = dayListeningDistribution,
            peakTimeline = peakTimeline,
            peakDayLabel = peakDayLabel,
            peakDayDurationMs = peakDayDuration
        )
    }

    suspend fun exportEventsForBackup(): List<PlaybackEvent> = withContext(Dispatchers.IO) {
        readEvents().map { event -> sanitizeEvent(event) }
    }

    suspend fun loadPlaybackHistory(limit: Int = DEFAULT_PLAYBACK_HISTORY_LIMIT): List<PlaybackHistoryEntry> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val safeLimit = limit.coerceAtMost(MAX_PLAYBACK_HISTORY_LIMIT)
        readEvents()
            .asSequence()
            .sortedByDescending { event -> event.timestamp }
            .take(safeLimit)
            .map { event ->
                PlaybackHistoryEntry(
                    songId = event.songId,
                    timestamp = event.timestamp.coerceAtLeast(0L),
                    durationMs = event.durationMs.coerceAtLeast(0L)
                )
            }
            .toList()
    }

    /**
     * 批量导入播放事件。
     * @return 写入是否成功（false = 落盘失败，调用方不应把计数当成已生效）。
     */
    suspend fun importEventsFromBackup(
        events: List<PlaybackEvent>,
        clearExisting: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val writeSucceeded = updateEventsAtomically { existingEvents ->
            val base = if (clearExisting) {
                emptyList()
            } else {
                existingEvents
            }
            mergeImportedEvents(base, events)
        }
        if (writeSucceeded) {
            // 导入属于「外部数据变更」：结果缓存失效，并通知统计页重算。
            requestRefresh()
        }
        writeSucceeded
    }

    /**
     * 合并已有事件与新导入事件，按 (songId, start, end, duration) 去重。
     * 去重键相同（同一首歌、同一区间）时保留权重更大的一条：
     * 重新导入 Poweramp 备份时，旧的无权重事件会被带次数的事件顶掉，
     * 否则 distinctBy 保留先出现的旧事件，重导入将永远不生效。
     * 无条数上限，与「永久保留历史」一致。
     */
    internal fun mergeImportedEvents(
        base: List<PlaybackEvent>,
        incoming: List<PlaybackEvent>
    ): MutableList<PlaybackEvent> {
        return (base + incoming)
            .map { event -> sanitizeEvent(event) }
            .groupBy { event ->
                "${event.songId}:${event.startMillis()}:${event.endMillis()}:${event.durationMs}"
            }
            .values
            .map { group -> group.maxBy { event -> event.playCount } }
            .sortedBy { event -> event.timestamp }
            .toMutableList()
    }

    /** 外部数据变更（导入 / 恢复备份 / 排序上限）：已算好的结果不再成立，丢弃并通知观察者。 */
    fun requestRefresh() {
        invalidateSummaryCache()
        notifyStatsChanged()
    }

    private fun readEvents(): List<PlaybackEvent> {
        synchronized(fileLock) { cachedEvents }?.let { return it }
        val raw = synchronized(fileLock) { readRawHistoryLocked() }
        return parseEvents(raw).also { parsed ->
            synchronized(fileLock) { if (cachedEvents == null) cachedEvents = parsed }
        }
    }

    private fun readRawHistoryLocked(): String? {
        return runCatching {
            atomicHistoryFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
            .onFailure { throwable ->
                if (throwable !is FileNotFoundException) {
                    Timber.e(throwable, "Failed reading playback history")
                }
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseEvents(raw: String?): MutableList<PlaybackEvent> {
        if (raw.isNullOrBlank()) {
            return mutableListOf()
        }

        return runCatching {
            val element = gson.fromJson(raw, JsonElement::class.java)
            parseElement(element)
        }.getOrElse { throwable ->
            Timber.e(throwable, "Failed parsing playback history")
            mutableListOf()
        }
    }

    private fun parseElement(element: JsonElement?): MutableList<PlaybackEvent> {
        if (element == null || element.isJsonNull) return mutableListOf()
        if (element.isJsonArray) {
            val parsed: MutableList<PlaybackEvent> = gson.fromJson(element, eventsType)
            return parsed.mapTo(mutableListOf()) { sanitizeEvent(it) }
        }
        return mutableListOf()
    }

    private fun sanitizeEvent(event: PlaybackEvent): PlaybackEvent {
        val safeDuration = event.durationMs.coerceAtLeast(0L)
        val safeEnd = (event.endTimestamp ?: event.timestamp).coerceAtLeast(0L)
        val safeStart = when {
            event.startTimestamp != null -> event.startTimestamp.coerceIn(0L, safeEnd)
            safeDuration > 0L -> (safeEnd - safeDuration).coerceAtLeast(0L)
            else -> safeEnd
        }
        val normalizedDuration = (safeEnd - safeStart).coerceAtLeast(0L)
        val finalDuration = when {
            event.startTimestamp == null && safeDuration in 1 until normalizedDuration -> safeDuration
            else -> normalizedDuration
        }
        val finalStart = (safeEnd - finalDuration).coerceAtLeast(0L)
        return event.copy(
            songId = event.songId,
            timestamp = safeEnd,
            durationMs = finalDuration,
            startTimestamp = finalStart,
            endTimestamp = safeEnd,
            // 旧版 JSON 没有 playCount 字段。Gson 可能用 Unsafe 分配（此时 Int 落 0），
            // 因此统一经 weight 兜底为 1。
            playCount = event.weight
        )
    }

    private fun mergeSongEvents(events: List<PlaybackEvent>): List<PlaybackSegment> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.startMillis() }
        val songId = sorted.first().songId
        val segments = mutableListOf<PlaybackSegment>()
        var currentStart = sorted.first().startMillis()
        var currentEnd = sorted.first().endMillis()
        // 区间会被合并成一份时长，但次数必须逐条累加（含带权重事件）。
        var currentPlayCount = sorted.first().weight
        for (index in 1 until sorted.size) {
            val event = sorted[index]
            val start = event.startMillis()
            val end = event.endMillis()
            if (start <= currentEnd + SEGMENT_JOIN_TOLERANCE_MS) {
                currentEnd = max(currentEnd, end)
                currentPlayCount += event.weight
            } else {
                segments += PlaybackSegment(songId, currentStart, currentEnd, currentPlayCount)
                currentStart = start
                currentEnd = end
                currentPlayCount = event.weight
            }
        }
        segments += PlaybackSegment(songId, currentStart, currentEnd, currentPlayCount)
        return segments
    }

    private fun mergeSpans(spans: List<PlaybackSpan>): List<PlaybackSpan> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.startMillis }
        val merged = mutableListOf<PlaybackSpan>()
        var currentStart = sorted.first().startMillis
        var currentEnd = sorted.first().endMillis
        // 同 [mergeSongEvents]：区间合并成一份时长，次数逐段累加。
        var currentPlayCount = sorted.first().playCount
        for (index in 1 until sorted.size) {
            val span = sorted[index]
            val start = span.startMillis
            val end = span.endMillis
            if (start <= currentEnd + SEGMENT_JOIN_TOLERANCE_MS) {
                currentEnd = max(currentEnd, end)
                currentPlayCount += span.playCount
            } else {
                merged += PlaybackSpan(currentStart, currentEnd, currentPlayCount)
                currentStart = start
                currentEnd = end
                currentPlayCount = span.playCount
            }
        }
        merged += PlaybackSpan(currentStart, currentEnd, currentPlayCount)
        return merged
    }

    /**
     * 结果缓存的键 = 日历周期身份。刻意不含 `now`：用截断后的边界做键的话，时钟每走一分钟
     * 缓存就会失效一次，「每个统计只算一次」不成立。
     */
    private data class SummaryCacheKey(
        val range: StatsTimeRange,
        val startDate: LocalDate?,
        val endDateExclusive: LocalDate?
    )

    private data class DaySlice(
        val date: LocalDate,
        val durationMs: Long
    )

    private data class ArtistSongPlayback(
        val artist: String,
        val songId: String,
        val segments: List<PlaybackSegment>
    )

    private fun statsArtistNames(song: Song?): List<String> {
        if (song == null) return listOf(UNKNOWN_ARTIST)

        val separatedArtists = song.artists
            .sortedByDescending { it.isPrimary }
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedArtistKey() }
        if (separatedArtists.isNotEmpty()) {
            return separatedArtists
        }

        val fallbackArtist = song.displayArtist.trim()
        return listOf(fallbackArtist.ifBlank { UNKNOWN_ARTIST })
    }

    private fun String.normalizedArtistKey(): String = trim().lowercase(Locale.ROOT)

    private fun sliceSpanByDay(span: PlaybackSpan, zoneId: ZoneId): List<DaySlice> {
        if (span.durationMs <= 0L) return emptyList()
        val slices = mutableListOf<DaySlice>()
        var cursor = span.startMillis
        val end = span.endMillis
        while (cursor < end) {
            val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
            val dayStart = zoned.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
            val nextDayStart = zoned.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val sliceEnd = min(end, nextDayStart)
            val sliceDuration = (sliceEnd - cursor).coerceAtLeast(0L)
            if (sliceDuration > 0L) {
                slices += DaySlice(zoned.toLocalDate(), sliceDuration)
            }
            cursor = sliceEnd
        }
        return slices
    }

    private fun computeDayListeningDistribution(
        spans: List<PlaybackSpan>,
        zoneId: ZoneId,
        range: StatsTimeRange,
        startBound: Long?,
        endBound: Long,
        bucketSizeMinutes: Int = 5
    ): DayListeningDistribution? {
        if (spans.isEmpty()) return null
        val bucketDurationMs = TimeUnit.MINUTES.toMillis(bucketSizeMinutes.toLong())
        val minutesPerDay = TimeUnit.DAYS.toMinutes(1)
        val bucketCount = (minutesPerDay / bucketSizeMinutes).toInt().coerceAtLeast(1)
        val totals = LongArray(bucketCount)
        val totalsByDay = mutableMapOf<LocalDate, LongArray>()
        spans.forEach { span ->
            var cursor = span.startMillis
            val end = span.endMillis
            while (cursor < end) {
                val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
                val day = zoned.toLocalDate()
                val dayStart = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
                var bucketIndex = ((cursor - dayStart) / bucketDurationMs).toInt()
                if (bucketIndex >= bucketCount) {
                    bucketIndex = bucketCount - 1
                } else if (bucketIndex < 0) {
                    bucketIndex = 0
                }
                val bucketStart = dayStart + bucketIndex * bucketDurationMs
                val bucketEnd = min(end, bucketStart + bucketDurationMs)
                val contribution = (bucketEnd - cursor).coerceAtLeast(0L)
                if (contribution > 0L) {
                    totals[bucketIndex] += contribution
                    val dayTotals = totalsByDay.getOrPut(day) { LongArray(bucketCount) }
                    dayTotals[bucketIndex] += contribution
                }
                cursor = if (bucketEnd > cursor) bucketEnd else end
            }
        }
        val buckets = buildList {
            for (index in 0 until bucketCount) {
                val durationMs = totals[index]
                if (durationMs > 0L) {
                    add(
                        DailyListeningBucket(
                            startMinute = index * bucketSizeMinutes,
                            endMinuteExclusive = (index + 1) * bucketSizeMinutes,
                            totalDurationMs = durationMs
                        )
                    )
                }
            }
        }
        if (buckets.isEmpty()) return null
        val maxBucketDuration = buckets.maxOf { it.totalDurationMs }.coerceAtLeast(0L)

        val daySequence: List<LocalDate> = when (range) {
            StatsTimeRange.DAY -> {
                val anchor = startBound?.let { Instant.ofEpochMilli(it) }
                    ?: spans.minOfOrNull { Instant.ofEpochMilli(it.startMillis) }
                    ?: Instant.ofEpochMilli(endBound)
                listOf(anchor.atZone(zoneId).toLocalDate())
            }
            StatsTimeRange.WEEK -> {
                val anchor = startBound?.let { Instant.ofEpochMilli(it) }
                    ?: spans.minOfOrNull { Instant.ofEpochMilli(it.startMillis) }
                    ?: Instant.ofEpochMilli(endBound)
                val startDate = anchor.atZone(zoneId).toLocalDate()
                (0 until 7).map { offset -> startDate.plusDays(offset.toLong()) }
            }
            else -> totalsByDay.keys.sorted()
        }

        val days = daySequence.map { date ->
            val bucketTotals = totalsByDay[date]
            val dayBuckets = buildList {
                if (bucketTotals != null) {
                    for (index in 0 until bucketCount) {
                        val duration = bucketTotals[index]
                        if (duration > 0L) {
                            add(
                                DailyListeningBucket(
                                    startMinute = index * bucketSizeMinutes,
                                    endMinuteExclusive = (index + 1) * bucketSizeMinutes,
                                    totalDurationMs = duration
                                )
                            )
                        }
                    }
                }
            }
            val totalDuration = bucketTotals?.sumOf { it } ?: 0L
            DailyListeningDay(
                date = date,
                buckets = dayBuckets,
                totalDurationMs = totalDuration
            )
        }

        return DayListeningDistribution(
            bucketSizeMinutes = bucketSizeMinutes,
            buckets = buckets,
            maxBucketDurationMs = maxBucketDuration,
            days = days
        )
    }

    private data class ListeningSessionAggregate(
        var start: Long,
        var end: Long,
        var totalDuration: Long,
        var playCount: Int
    )

    private fun computeListeningSessions(spans: List<PlaybackSpan>): List<ListeningSessionAggregate> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.startMillis }
        val sessions = mutableListOf<ListeningSessionAggregate>()

        var current = ListeningSessionAggregate(
            start = sorted.first().startMillis,
            end = sorted.first().endMillis,
            totalDuration = sorted.first().durationMs,
            playCount = sorted.first().playCount
        )

        for (index in 1 until sorted.size) {
            val span = sorted[index]
            val spanStart = span.startMillis
            val spanEnd = span.endMillis
            val gap = spanStart - current.end
            if (gap <= sessionGapThresholdMs) {
                current.end = max(current.end, spanEnd)
                current.totalDuration += span.durationMs
                current.playCount += span.playCount
            } else {
                sessions += current
                current = ListeningSessionAggregate(
                    start = spanStart,
                    end = spanEnd,
                    totalDuration = span.durationMs,
                    playCount = span.playCount
                )
            }
        }

        sessions += current
        return sessions
    }

    /**
     * 以内存里的事件表为基准改写历史文件。
     *
     * 进程内所有写入都经过本方法、且都持 [fileLock]，因此 `cachedEvents` 就是最新基准 ——
     * 不需要「锁外解析 + 锁内重读比对」（那会多读一次整文件、多解析一次全量历史）。
     * 写成功后缓存直接换成刚写下去的那份（已 sanitize，与磁盘一致），下次读取无需重新解析。
     *
     * [MAX_FILE_UPDATE_RETRIES] 现在只在写失败时起作用。
     */
    private fun updateEventsAtomically(
        transform: (MutableList<PlaybackEvent>) -> MutableList<PlaybackEvent>
    ): Boolean {
        repeat(MAX_FILE_UPDATE_RETRIES) {
            val base = synchronized(fileLock) { cachedEvents?.toMutableList() }
                ?: parseEvents(synchronized(fileLock) { readRawHistoryLocked() })
            val sanitized = transform(base).map { event -> sanitizeEvent(event) }
            val payload = gson.toJson(sanitized).toByteArray(Charsets.UTF_8)

            val writeSucceeded = synchronized(fileLock) {
                writePayloadLocked(payload).also { written ->
                    if (written) cachedEvents = sanitized
                }
            }
            if (writeSucceeded) {
                return true
            }
        }
        return false
    }

    private fun writePayloadLocked(payload: ByteArray): Boolean {
        var outputStream: FileOutputStream? = null
        return runCatching {
            val parent = historyFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Timber.w("Unable to ensure playback history directory: ${parent.absolutePath}")
            }
            outputStream = atomicHistoryFile.startWrite()
            outputStream?.write(payload)
            outputStream?.fd?.sync()
            outputStream?.let { atomicHistoryFile.finishWrite(it) }
            outputStream = null
            true
        }.onFailure { throwable ->
            outputStream?.let { stream -> atomicHistoryFile.failWrite(stream) }
            Timber.e(throwable, "Failed to persist playback history")
        }.getOrDefault(false)
    }

    private fun accumulateTimelineEntries(
        buckets: List<TimelineBucket>,
        spans: List<PlaybackSpan>
    ): List<TimelineEntry> {
        if (buckets.isEmpty()) return emptyList()
        val durationByBucket = LongArray(buckets.size)
        val playCountByBucket = DoubleArray(buckets.size)
        spans.forEach { span ->
            val spanStart = span.startMillis
            val spanEnd = span.endMillis
            val spanDuration = span.durationMs
            if (spanDuration <= 0L) return@forEach
            buckets.forEachIndexed { index, bucket ->
                val bucketEndExclusive = if (bucket.inclusiveEnd) bucket.endMillis + 1 else bucket.endMillis
                val overlapStart = max(spanStart, bucket.startMillis)
                val overlapEnd = min(spanEnd, bucketEndExclusive)
                val overlap = (overlapEnd - overlapStart).coerceAtLeast(0L)
                if (overlap > 0) {
                    durationByBucket[index] += overlap
                    // 按区间落在桶内的时长占比折算次数；带权重的导入事件（playCount = N）
                    // 会把它代表的 N 次播放一并分摊到覆盖到的桶里。
                    playCountByBucket[index] +=
                        span.playCount * (overlap.toDouble() / spanDuration.toDouble())
                }
            }
        }
        return buckets.mapIndexed { index, bucket ->
            TimelineEntry(
                label = bucket.label,
                totalDurationMs = durationByBucket[index],
                playCount = playCountByBucket[index].roundToInt()
            )
        }
    }

    private fun createTimelineBuckets(
        range: StatsTimeRange,
        zoneId: ZoneId,
        anchor: Instant,
        spans: List<PlaybackSpan>,
        fallbackStart: Long
    ): List<TimelineBucket> {
        return when (range) {
            StatsTimeRange.DAY -> createDayBuckets(zoneId, anchor)
            StatsTimeRange.WEEK -> createWeekBuckets(zoneId, anchor)
            StatsTimeRange.MONTH -> createMonthBuckets(zoneId, anchor)
            StatsTimeRange.YEAR -> createYearBuckets(zoneId, anchor)
            StatsTimeRange.ALL -> createAllTimeBuckets(zoneId, spans, fallbackStart, anchor)
        }
    }

    private fun createDayBuckets(zoneId: ZoneId, anchor: Instant): List<TimelineBucket> {
        val dayStart = anchor.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()
        val formatter = DateTimeFormatter.ofPattern("ha", Locale.US)
        return (0 until 6).map { index ->
            val bucketStart = dayStart.plus(Duration.ofHours((index * 4).toLong()))
            val bucketEnd = bucketStart.plus(Duration.ofHours(4))
            val label = formatter.format(bucketStart.atZone(zoneId)).lowercase(Locale.US)
            TimelineBucket(
                label = label,
                startMillis = bucketStart.toEpochMilli(),
                endMillis = bucketEnd.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun createWeekBuckets(zoneId: ZoneId, anchor: Instant): List<TimelineBucket> {
        val startOfWeek = anchor.atZone(zoneId)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0 until 7).map { index ->
            val day = startOfWeek.plusDays(index.toLong())
            val start = day.atStartOfDay(zoneId).toInstant()
            val end = day.plusDays(1).atStartOfDay(zoneId).toInstant()
            TimelineBucket(
                label = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US),
                startMillis = start.toEpochMilli(),
                endMillis = end.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    /** One bucket per day: a month split into four "weeks" hides which days were actually busy. */
    private fun createMonthBuckets(zoneId: ZoneId, anchor: Instant): List<TimelineBucket> {
        val yearMonth = YearMonth.from(anchor.atZone(zoneId))
        return (1..yearMonth.lengthOfMonth()).map { day ->
            val start = yearMonth.atDay(day).atStartOfDay(zoneId).toInstant()
            val end = yearMonth.atDay(day).plusDays(1).atStartOfDay(zoneId).toInstant()
            TimelineBucket(
                label = day.toString(),
                startMillis = start.toEpochMilli(),
                endMillis = end.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun createYearBuckets(zoneId: ZoneId, anchor: Instant): List<TimelineBucket> {
        val year = Year.from(anchor.atZone(zoneId))
        return (1..12).map { monthIndex ->
            val start = year.atMonth(monthIndex).atDay(1).atStartOfDay(zoneId).toInstant()
            val end = year.atMonth(monthIndex).atEndOfMonth().plusDays(1).atStartOfDay(zoneId).toInstant()
            TimelineBucket(
                label = year.atMonth(monthIndex).month.getDisplayName(TextStyle.SHORT, Locale.US),
                startMillis = start.toEpochMilli(),
                endMillis = end.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun createAllTimeBuckets(
        zoneId: ZoneId,
        spans: List<PlaybackSpan>,
        fallbackStart: Long,
        anchor: Instant
    ): List<TimelineBucket> {
        val allSpans = if (spans.isEmpty()) listOf(PlaybackSpan(fallbackStart, fallbackStart)) else spans
        val minTimestamp = allSpans.minOfOrNull { it.startMillis } ?: fallbackStart
        val maxTimestamp = allSpans.maxOfOrNull { it.endMillis } ?: anchor.toEpochMilli()
        val startYear = Instant.ofEpochMilli(minTimestamp).atZone(zoneId).year
        val endYear = Instant.ofEpochMilli(maxTimestamp).atZone(zoneId).year
        if (startYear > endYear) return emptyList()
        return (startYear..endYear).map { yearValue ->
            val year = Year.of(yearValue)
            val start = year.atDay(1).atStartOfDay(zoneId).toInstant()
            val end = year.plusYears(1).atDay(1).atStartOfDay(zoneId).toInstant()
            TimelineBucket(
                label = yearValue.toString(),
                startMillis = start.toEpochMilli(),
                endMillis = end.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    /**
     * Absolute bounds of [period].
     *
     * The upper bound is capped at `now` so the current period never reports time that has not
     * happened yet; past periods end at their own last millisecond.
     */
    private fun StatsPeriod.resolveBounds(
        events: List<PlaybackEvent>,
        nowMillis: Long,
        zoneId: ZoneId
    ): Pair<Long?, Long> {
        val start = startDate(nowMillis, zoneId)
            ?: return events.minOfOrNull { it.startMillis() } to nowMillis

        val startMillis = start.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endExclusive = endDateExclusive(nowMillis, zoneId)
            ?.atStartOfDay(zoneId)
            ?.toInstant()
            ?.toEpochMilli()
            ?: nowMillis
        return startMillis to min(nowMillis, endExclusive - 1L).coerceAtLeast(startMillis)
    }

    private data class TimelineBucket(
        val label: String,
        val startMillis: Long,
        val endMillis: Long,
        val inclusiveEnd: Boolean
    )

    private fun PlaybackEvent.startMillis(): Long {
        val end = (endTimestamp ?: timestamp).coerceAtLeast(0L)
        val inferredStart = (startTimestamp ?: (end - durationMs)).coerceAtLeast(0L)
        return min(inferredStart, end)
    }

    private fun PlaybackEvent.endMillis(): Long {
        val end = (endTimestamp ?: timestamp).coerceAtLeast(0L)
        val start = startMillis()
        return max(end, start)
    }

    private fun notifyStatsChanged() {
        _refreshVersion.update { current ->
            if (current == Long.MAX_VALUE) {
                1L
            } else {
                current + 1L
            }
        }
    }

    companion object {
        private const val DEFAULT_PLAYBACK_HISTORY_LIMIT = 500
        private const val MAX_PLAYBACK_HISTORY_LIMIT = 5_000
        private const val MAX_FILE_UPDATE_RETRIES = 3
        private const val UNKNOWN_ARTIST = "Unknown Artist"
        private const val SEGMENT_JOIN_TOLERANCE_MS = 0L
        private const val MAX_RANKING_STATS_COUNT = 100

        /** 结果缓存的 LRU 上限：够覆盖「本周 + 上周 + 本月 + …」的来回翻页，又不会长期累积。 */
        private const val MAX_SUMMARY_CACHE_ENTRIES = 12
    }
}

enum class StatsTimeRange(val displayName: String) {
    DAY("Today"),
    WEEK("Week to Date"),
    MONTH("Month to Date"),
    YEAR("Year to Date"),
    ALL("All Time")
}
