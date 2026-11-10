package com.pisces312.piscesplayer.data.stats

import com.google.common.truth.Truth.assertThat
import com.pisces312.piscesplayer.data.model.ArtistRef
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PlaybackStatsRepositoryTest {

    /** 最近一次 [createRepository] 用的目录，供需要直接查看历史文件的用例使用。 */
    private var lastFilesDir: File? = null

    @Test
    fun `loadSummary excludes event that only touches the start boundary`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val now = LocalDate.of(2026, 4, 10)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val boundaryTouchingEvent = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = now,
            durationMs = 10_000L,
            startTimestamp = now - 10_000L,
            endTimestamp = now
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = listOf(boundaryTouchingEvent),
            nowMillis = now
        )

        assertThat(summary.totalDurationMs).isEqualTo(0L)
        assertThat(summary.totalPlayCount).isEqualTo(0)
    }

    @Test
    fun `loadSummary preserves playback longer than track duration`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(10, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val listenedMs = TimeUnit.MINUTES.toMillis(15)
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + listenedMs,
            durationMs = listenedMs,
            startTimestamp = start,
            endTimestamp = start + listenedMs
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1", durationMs = TimeUnit.MINUTES.toMillis(3))),
            allEvents = listOf(event),
            nowMillis = start + listenedMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(listenedMs)
        assertThat(summary.totalPlayCount).isEqualTo(1)
        assertThat(summary.songs.single().totalDurationMs).isEqualTo(listenedMs)
    }

    @Test
    fun `loadSummary does not count short gaps between spans as listened time`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(12, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val firstDurationMs = 10_000L
        val secondDurationMs = 10_000L
        val gapMs = 1_000L
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = start + firstDurationMs,
                durationMs = firstDurationMs,
                startTimestamp = start,
                endTimestamp = start + firstDurationMs
            ),
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-2",
                timestamp = start + firstDurationMs + gapMs + secondDurationMs,
                durationMs = secondDurationMs,
                startTimestamp = start + firstDurationMs + gapMs,
                endTimestamp = start + firstDurationMs + gapMs + secondDurationMs
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1"), song("song-2")),
            allEvents = events,
            nowMillis = start + firstDurationMs + gapMs + secondDurationMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(firstDurationMs + secondDurationMs)
        assertThat(summary.totalPlayCount).isEqualTo(2)
    }

    @Test
    fun `buildSummaryFromEvents truncates song rankings to maxRankingCount`() = runTest {
        val repository = createRepository()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        val count = 25
        val songs = (1..count).map { song("song-$it") }
        val events = (1..count).map { i ->
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-$i",
                timestamp = start + durationMs * i,
                durationMs = durationMs,
                startTimestamp = start + durationMs * (i - 1),
                endTimestamp = start + durationMs * i
            )
        }
        val now = start + durationMs * (count + 1)

        val capped = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = songs,
            allEvents = events,
            nowMillis = now,
            maxRankingCount = 10
        )
        assertThat(capped.songs).hasSize(10)

        val unlimited = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = songs,
            allEvents = events,
            nowMillis = now,
            maxRankingCount = Int.MAX_VALUE
        )
        assertThat(unlimited.songs).hasSize(count)
    }

    @Test
    fun `buildSummaryFromEvents uses event spans without filesystem persistence`() = runTest {
        val repository = createRepository()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = start + durationMs,
                durationMs = durationMs,
                startTimestamp = start,
                endTimestamp = start + durationMs
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = events,
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(durationMs)
        assertThat(summary.uniqueSongs).isEqualTo(1)
    }

    @Test
    fun `loadSummary separates multi artist playback in top artists`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(14, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 60_000L
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs
        )
        val collaboration = song(
            songId = "song-1",
            artist = "Artist A",
            artists = listOf(
                ArtistRef(id = 1L, name = "Artist A", isPrimary = true),
                ArtistRef(id = 2L, name = "Artist B", isPrimary = false)
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(collaboration),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.topArtists.map { it.artist })
            .containsExactly("Artist A", "Artist B")
            .inOrder()
        summary.topArtists.forEach { artist ->
            assertThat(artist.totalDurationMs).isEqualTo(durationMs)
            assertThat(artist.playCount).isEqualTo(1)
            assertThat(artist.uniqueSongs).isEqualTo(1)
        }
    }

    @Test
    fun `loadSummary counts separated artists in genre uniqueness`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(15, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs
        )
        val collaboration = song(
            songId = "song-1",
            artist = "Artist A",
            artists = listOf(
                ArtistRef(id = 1L, name = "Artist A", isPrimary = true),
                ArtistRef(id = 2L, name = "Artist B", isPrimary = false)
            ),
            genre = "Pop"
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(collaboration),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.topGenres.single().uniqueArtists).isEqualTo(2)
    }

    @Test
    fun `loadSummary aggregates a weighted event by its playCount`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(16, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        // 第三方导入的一次事件代表累计 5 次播放。
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs,
            playCount = 5
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.totalPlayCount).isEqualTo(5)
        assertThat(summary.songs.single().playCount).isEqualTo(5)
    }

    @Test
    fun `playCount zero falls back to a weight of one`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(17, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        // Gson 对旧版 JSON 缺失字段可能走 Unsafe 分配，把 playCount 落成 0。
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs,
            playCount = 0
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.totalPlayCount).isEqualTo(1)
    }

    @Test
    fun `all time range covers events spread across several days`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val nowMillis = LocalDate.of(2026, 4, 10)
            .atTime(22, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val durationMs = TimeUnit.MINUTES.toMillis(4)
        val events = listOf(0L, 2L, 5L, 10L).mapIndexed { index, daysAgo ->
            val end = nowMillis - daysAgo * dayMs
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-$index",
                timestamp = end,
                durationMs = durationMs,
                startTimestamp = end - durationMs,
                endTimestamp = end
            )
        }

        val summary = repository.buildSummaryFromEvents(
            period = StatsPeriod(StatsTimeRange.ALL),
            songs = events.indices.map { song("song-$it") },
            nowMillis = nowMillis,
            allEvents = events,
            zoneId = zoneId
        )

        assertThat(summary.uniqueSongs).isEqualTo(4)
        assertThat(summary.totalPlayCount).isEqualTo(4)
        assertThat(summary.totalDurationMs).isEqualTo(durationMs * 4)
    }

    @Test
    fun `imported event with zero duration and playCount expands by song duration times weight`() = runTest {
        // Poweramp 导入事件：durationMs = 0, playCount = N → 按 N × 曲长回溯
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val songDuration = 4 * 60 * 1000L // 4 分钟
        val playedAt = LocalDate.of(2026, 4, 10)
            .atTime(20, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = playedAt,
            durationMs = 0L,
            startTimestamp = playedAt,
            endTimestamp = playedAt,
            playCount = 3
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.ALL,
            songs = listOf(song("song-1", durationMs = songDuration)),
            allEvents = listOf(event),
            nowMillis = playedAt + 1_000L,
            zoneId = zoneId
        )

        assertThat(summary.totalPlayCount).isEqualTo(3)
        assertThat(summary.totalDurationMs).isEqualTo(songDuration * 3)
        assertThat(summary.uniqueSongs).isEqualTo(1)
    }

    @Test
    fun `expansion happens before resolveBounds so ALL range includes back-projected duration`() = runTest {
        // 验证展开先于边界计算：played_at 距今很近，但 N×曲长 会回溯到非常早的时间，
        // ALL 范围应该把回溯出的区间整个包住，而不是只取 played_at 为起点。
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val songDuration = 5 * 60 * 1000L // 5 分钟
        val playCount = 100
        val playedAt = LocalDate.of(2026, 4, 10)
            .atTime(20, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = playedAt,
            durationMs = 0L,
            startTimestamp = playedAt,
            endTimestamp = playedAt,
            playCount = playCount
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.ALL,
            songs = listOf(song("song-1", durationMs = songDuration)),
            allEvents = listOf(event),
            nowMillis = playedAt + 1_000L,
            zoneId = zoneId
        )

        // 100 × 5 分钟 = 500 分钟 ≈ 8.3 小时的回溯区间；ALL 范围必须完整计入
        assertThat(summary.totalPlayCount).isEqualTo(playCount)
        assertThat(summary.totalDurationMs).isEqualTo(songDuration * playCount)
        assertThat(summary.startTimestamp).isLessThan(playedAt - songDuration * playCount / 2)
    }

    @Test
    fun `mergeImportedEvents keeps the entry with higher playCount on same dedupe key`() = runTest {
        // 重新导入 Poweramp 备份时，旧的低权重事件应该被带更高次数的事件顶掉
        // （两条事件的 songId + start + end + duration 完全相同）。
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val playedAt = LocalDate.of(2026, 4, 10)
            .atTime(20, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        fun event(count: Int) = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = playedAt,
            durationMs = 0L,
            startTimestamp = playedAt,
            endTimestamp = playedAt,
            playCount = count
        )

        // 旧事件已存在（base），新导入同键但次数更高（incoming）
        val merged = repository.mergeImportedEvents(
            base = listOf(event(1)),
            incoming = listOf(event(15))
        )

        assertThat(merged).hasSize(1)
        assertThat(merged.single().playCount).isEqualTo(15)
    }

    @Test
    fun `mergeImportedEvents preserves distinct songs and stays unbounded`() = runTest {
        // 不同歌（不同去重键）都保留；且不做条数裁剪（无上限）。
        val repository = createRepository()
        val now = LocalDate.of(2026, 4, 10)
            .atTime(20, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val incoming = (1..50).map { i ->
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-$i",
                timestamp = now + i,
                durationMs = 0L,
                startTimestamp = now + i,
                endTimestamp = now + i,
                playCount = i
            )
        }

        val merged = repository.mergeImportedEvents(base = emptyList(), incoming = incoming)

        assertThat(merged).hasSize(50)
    }

    @Test
    fun `loadSummary reuses the cached summary for the same calendar period`() = runTest {
        val repository = createRepository()
        val songs = listOf(song("song-1"))
        val period = StatsPeriod(StatsTimeRange.DAY)
        val now = LocalDate.of(2026, 4, 10).atTime(10, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val first = repository.loadSummary(period, songs, now)

        // 同一周期身份 → 直接复用算好的那份，不再聚合、不再读文件。
        assertThat(repository.loadSummary(period, songs, now)).isSameInstanceAs(first)
    }

    @Test
    fun `loadSummary keys the cache by calendar period rather than the exact now`() = runTest {
        val repository = createRepository()
        val songs = listOf(song("song-1"))
        val period = StatsPeriod(StatsTimeRange.DAY)
        val morning = LocalDate.of(2026, 4, 10).atTime(9, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val first = repository.loadSummary(period, songs, morning)

        // 同一天里 now 往前走几小时 → 周期身份没变，不应重算（否则时钟每走一分钟缓存就失效）。
        val laterToday = repository.loadSummary(period, songs, morning + TimeUnit.HOURS.toMillis(3))
        assertThat(laterToday).isSameInstanceAs(first)

        // 换了日历周期 → 新的条目。
        val tomorrow = morning + TimeUnit.DAYS.toMillis(1)
        assertThat(repository.loadSummary(period, songs, tomorrow)).isNotSameInstanceAs(first)

        // 回到原周期 → 原条目还在，往回翻页不需要重算。
        assertThat(repository.loadSummary(period, songs, morning)).isSameInstanceAs(first)
    }

    @Test
    fun `invalidateSummaryCache forces the next load to recompute`() = runTest {
        val repository = createRepository()
        val songs = listOf(song("song-1"))
        val period = StatsPeriod(StatsTimeRange.DAY)
        val now = LocalDate.of(2026, 4, 10).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val first = repository.loadSummary(period, songs, now)
        assertThat(repository.loadSummary(period, songs, now)).isSameInstanceAs(first)

        repository.invalidateSummaryCache()

        val recomputed = repository.loadSummary(period, songs, now)
        assertThat(recomputed).isNotSameInstanceAs(first)
        // 数据没变，重算出来的内容必须与原来完全一致（口径未变）。
        assertThat(recomputed).isEqualTo(first)
    }

    @Test
    fun `loadSummary folds in the in-flight fragment that is not persisted yet`() = runTest {
        val repository = createRepository()
        val songs = listOf(song("song-1"))
        val period = StatsPeriod(StatsTimeRange.DAY)
        val dayStart = LocalDate.of(2026, 4, 10).atTime(9, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = dayStart + TimeUnit.HOURS.toMillis(2)
        val listenedMs = TimeUnit.MINUTES.toMillis(6)

        val withoutFragment = repository.loadSummary(period, songs, now)
        assertThat(withoutFragment.totalDurationMs).isEqualTo(0L)

        val fragment = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = now,
            durationMs = listenedMs,
            startTimestamp = now - listenedMs,
            endTimestamp = now
        )
        val withFragment = repository.loadSummary(period, songs, now, extraEvents = listOf(fragment))

        assertThat(withFragment.totalDurationMs).isEqualTo(listenedMs)
        assertThat(withFragment.totalPlayCount).isEqualTo(1)
        // 片段还在增长，结果不能进缓存，所以拿到的不是同一个实例。
        assertThat(withFragment).isNotSameInstanceAs(withoutFragment)
    }

    @Test
    fun `in-flight fragment outside the period does not bypass the cache`() = runTest {
        val repository = createRepository()
        val songs = listOf(song("song-1"))
        val period = StatsPeriod(StatsTimeRange.DAY)
        val now = LocalDate.of(2026, 4, 10).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val cached = repository.loadSummary(period, songs, now)

        // 片段落在明天 → 与今天无关，缓存照样命中（播放中去看「上周」同理）。
        val tomorrowFragmentEnd = now + TimeUnit.DAYS.toMillis(1)
        val fragmentMs = TimeUnit.MINUTES.toMillis(5)
        val futureFragment = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = tomorrowFragmentEnd,
            durationMs = fragmentMs,
            startTimestamp = tomorrowFragmentEnd - fragmentMs,
            endTimestamp = tomorrowFragmentEnd
        )

        assertThat(repository.loadSummary(period, songs, now, extraEvents = listOf(futureFragment)))
            .isSameInstanceAs(cached)
    }

    @Test
    fun `recordPlayback no longer notifies the stats refresh flow`() = runTest {
        val repository = createRepository()
        val before = repository.refreshFlow.value

        repository.recordPlayback(songId = "song-1", durationMs = 5_000L, timestamp = 1_000L)

        // 每次落盘都触发重算的话，统计页的数字就无法在刷新之前保持稳定。
        assertThat(repository.refreshFlow.value).isEqualTo(before)
    }

    @Test
    fun `importEventsFromBackup notifies and invalidates the cached summaries`() = runTest {
        val repository = createRepository()
        val songs = listOf(song("song-1"))
        val period = StatsPeriod(StatsTimeRange.DAY)
        val now = LocalDate.of(2026, 4, 10).atTime(20, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val cached = repository.loadSummary(period, songs, now)
        val before = repository.refreshFlow.value
        val importedMs = TimeUnit.MINUTES.toMillis(3)

        val succeeded = repository.importEventsFromBackup(
            events = listOf(
                PlaybackStatsRepository.PlaybackEvent(
                    songId = "song-1",
                    timestamp = now,
                    durationMs = importedMs,
                    startTimestamp = now - importedMs,
                    endTimestamp = now
                )
            )
        )

        assertThat(succeeded).isTrue()
        assertThat(repository.refreshFlow.value).isNotEqualTo(before)
        assertThat(repository.loadSummary(period, songs, now)).isNotSameInstanceAs(cached)
    }

    @Test
    fun `day range counts only the part of history that falls inside the day`() = runTest {
        // 粗筛（先按 endMillis 过滤、再展开候选）必须与「先展开全部历史」等价：
        // 完全落在昨天的事件要排除，跨过零点的事件只计入今天那一段。
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val dayStart = LocalDate.of(2026, 4, 10).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val now = dayStart + TimeUnit.HOURS.toMillis(12)
        val thirtyMinutes = TimeUnit.MINUTES.toMillis(30)
        val yesterdayEnd = dayStart - TimeUnit.HOURS.toMillis(1)
        val overnightMs = TimeUnit.MINUTES.toMillis(20)
        val overnightEnd = dayStart + TimeUnit.MINUTES.toMillis(10)
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = yesterdayEnd,
                durationMs = thirtyMinutes,
                startTimestamp = yesterdayEnd - thirtyMinutes,
                endTimestamp = yesterdayEnd
            ),
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-2",
                timestamp = overnightEnd,
                durationMs = overnightMs,
                startTimestamp = overnightEnd - overnightMs,
                endTimestamp = overnightEnd
            )
        )

        val summary = repository.buildSummaryFromEvents(
            period = StatsPeriod(StatsTimeRange.DAY),
            songs = listOf(song("song-1"), song("song-2")),
            nowMillis = now,
            allEvents = events,
            zoneId = zoneId
        )

        // 23:50 → 00:10 的播放，今天只算 00:00 → 00:10。
        assertThat(summary.totalDurationMs).isEqualTo(TimeUnit.MINUTES.toMillis(10))
        assertThat(summary.uniqueSongs).isEqualTo(1)
        assertThat(summary.songs.map { it.songId }).containsExactly("song-2")
    }

    @Test
    fun `looping the same song counts every repetition`() = runTest {
        // 反例保护：不能用「同歌合并时次数取 max」去修刷新重复计数。
        // 单曲循环产生的两条事件与「被刷新切开的一段」在数据层同构（都首尾相接），
        // 但它们必须计 2 次 —— 这正是修法只能落在「不切分」而不落在「改合并」的原因。
        val repository = createRepository()
        val songs = listOf(song("song-1"))
        val zoneId = ZoneId.systemDefault()
        val dayStart = LocalDate.of(2026, 4, 10).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val trackDuration = TimeUnit.MINUTES.toMillis(3)
        val firstEnd = dayStart + TimeUnit.HOURS.toMillis(1)
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = firstEnd,
                durationMs = trackDuration,
                startTimestamp = firstEnd - trackDuration,
                endTimestamp = firstEnd
            ),
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = firstEnd + trackDuration,
                durationMs = trackDuration,
                startTimestamp = firstEnd,
                endTimestamp = firstEnd + trackDuration
            )
        )

        val summary = repository.buildSummaryFromEvents(
            period = StatsPeriod(StatsTimeRange.DAY),
            songs = songs,
            nowMillis = dayStart + TimeUnit.HOURS.toMillis(12),
            allEvents = events,
            zoneId = zoneId
        )

        assertThat(summary.totalPlayCount).isEqualTo(2)
        // 时长是区间的并集：两段首尾相接 → 合并成 6 分钟，而不是 3 分钟或 9 分钟。
        assertThat(summary.totalDurationMs).isEqualTo(trackDuration * 2)
    }

    private fun createRepository(): PlaybackStatsRepository {
        val uniqueDir = createTempDirectory(
            "playback-stats-test-${Instant.now().toEpochMilli()}-"
        ).toFile()
        lastFilesDir = uniqueDir
        val testContext = mockk<android.content.Context>(relaxed = true)
        every { testContext.filesDir } returns uniqueDir
        val userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true)
        every { userPreferencesRepository.statsRankingLimitFlow } returns flowOf(DEFAULT_RANKING_LIMIT)
        return PlaybackStatsRepository(testContext, userPreferencesRepository)
    }

    private fun song(
        songId: String,
        durationMs: Long = 5 * 60 * 1000L,
        artist: String = "Artist",
        artists: List<ArtistRef> = emptyList(),
        genre: String? = null
    ): Song = Song(
        id = songId,
        title = "Song $songId",
        artist = artist,
        artistId = 1L,
        artists = artists,
        album = "Album",
        albumId = 1L,
        path = "/music/$songId.mp3",
        contentUriString = "content://media/external/audio/media/$songId",
        albumArtUriString = null,
        duration = durationMs,
        genre = genre,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100
    )

    private companion object {
        private const val DEFAULT_RANKING_LIMIT = 100
    }
}
