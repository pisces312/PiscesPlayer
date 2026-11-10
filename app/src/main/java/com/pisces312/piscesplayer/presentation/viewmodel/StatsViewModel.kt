package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.repository.MusicRepository
import com.pisces312.piscesplayer.data.stats.PlaybackStatsRepository
import com.pisces312.piscesplayer.data.stats.PlaybackStatsRepository.PlaybackStatsSummary
import com.pisces312.piscesplayer.data.stats.StatsPeriod
import com.pisces312.piscesplayer.data.stats.StatsTimeRange
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val musicRepository: MusicRepository,
    private val listeningStatsTracker: ListeningStatsTracker
) : ViewModel() {

    data class StatsUiState(
        val selectedRange: StatsTimeRange = StatsTimeRange.DAY,
        val selectedPeriod: StatsPeriod = StatsPeriod(StatsTimeRange.DAY),
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val summary: PlaybackStatsSummary? = null,
        val availableRanges: ImmutableList<StatsTimeRange> = StatsTimeRange.entries.toImmutableList()
    )

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    @Volatile
    private var cachedSongs: List<Song>? = null

    /** Cancelled on every range change so a slow load cannot overwrite a newer selection. */
    private var rangeJob: Job? = null

    init {
        observeStatsRefreshFlow()
        // 打开统计页不再落盘（原来是 flushCurrentSession → 整文件重写 + fsync + 随后的重解析）。
        // 未落盘的当前片段改为以内存事件叠加进这次计算，见 [loadRange] 里的 pendingFragment。
        loadRange(
            period = StatsPeriod.current(StatsTimeRange.DAY),
            showLoading = true
        )
    }

    fun onRangeSelected(range: StatsTimeRange) {
        if (range == _uiState.value.selectedRange && !_uiState.value.isLoading) {
            return
        }
        loadRange(
            period = StatsPeriod.current(range),
            showLoading = true
        )
    }

    fun onPeriodShift(steps: Int) {
        val current = _uiState.value.selectedPeriod
        val shifted = current.shift(steps, System.currentTimeMillis())
        if (shifted == current) return
        loadRange(period = shifted, showLoading = true)
    }

    fun onPeriodReset() {
        val current = _uiState.value.selectedPeriod
        val reset = StatsPeriod.current(current.range)
        if (reset == current) return
        loadRange(period = reset, showLoading = true)
    }

    /**
     * 刷新按钮：丢弃已算好的结果、重查歌曲表，然后重算当前周期。
     *
     * 这里**刻意不落盘**。原实现会先 `flushCurrentSession()` 把在途片段写成一条新事件，但那等于把
     * 「一次连续播放」切成多段：聚合对区间是「取并集 + 次数求和」（`mergeSongEvents` 里首尾相接必合并、
     * 合并时 `currentPlayCount += weight`），于是**时长正确、播放次数每刷一次 +1** —— JSON 的
     * `totalPlayCount` 与 Room `song_engagements.play_count` 双双虚高。
     *
     * 在途片段已由 [loadRange] 里的 `pendingFragment()` 以只读内存事件叠加，落盘对「看到最新数字」
     * 没有任何必要；落盘只应发生在真正结束一段播放时（`ListeningStatsTracker.finalizeCurrentSession`）。
     */
    fun requestStatsRefresh() {
        viewModelScope.launch {
            playbackStatsRepository.invalidateSummaryCache()
            cachedSongs = null
            loadRange(period = _uiState.value.selectedPeriod, showLoading = false)
        }
    }

    /** 设置页的「重新生成统计」：丢弃结果缓存与歌曲表，由 refreshFlow 驱动重算。 */
    fun forceRegenerateStats() {
        cachedSongs = null
        playbackStatsRepository.requestRefresh()
    }

    /**
     * 只算 [period] 这一个周期。结果由 [PlaybackStatsRepository] 按「日历周期身份」缓存，
     * 命中时不读文件、不做聚合，也不闪 loading。
     */
    private fun loadRange(period: StatsPeriod, showLoading: Boolean = true) {
        rangeJob?.cancel()
        rangeJob = viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true, isRefreshing = false, selectedRange = period.range, selectedPeriod = period) }
            } else {
                _uiState.update { it.copy(isRefreshing = true, selectedRange = period.range, selectedPeriod = period) }
            }
            // 尚未落盘的当前片段：落在该周期内时 repository 会重算而不走缓存，落在周期外则无影响。
            val extraEvents = listOfNotNull(listeningStatsTracker.pendingFragment())
            val summary = runCatching {
                withContext(Dispatchers.IO) {
                    playbackStatsRepository.loadSummary(
                        period = period,
                        songs = loadSongs(),
                        extraEvents = extraEvents
                    )
                }
            }
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    isRefreshing = false,
                    summary = summary.getOrNull(),
                    selectedRange = period.range,
                    selectedPeriod = period
                )
            }
            summary.exceptionOrNull()?.let { Timber.e(it, "Failed to load stats for range %s", period) }
        }
    }

    /**
     * 只有「外部数据变更」才会走到这里（导入 / 恢复备份 / 排序上限，见
     * [PlaybackStatsRepository.requestRefresh]）。播放产生的写入刻意不再通知：否则每次落盘都会
     * 触发一次重算，统计页的数字就无法在刷新前保持稳定。
     */
    private fun observeStatsRefreshFlow() {
        viewModelScope.launch {
            playbackStatsRepository.refreshFlow
                .drop(1)
                .collectLatest {
                    loadRange(period = _uiState.value.selectedPeriod, showLoading = false)
                }
        }
    }

    fun resolveArtistId(name: String): Long? {
        return cachedSongs?.firstOrNull { it.displayArtist.equals(name, ignoreCase = true) }?.artistId
    }

    fun resolveAlbumId(name: String): Long? {
        return cachedSongs?.firstOrNull { it.album.equals(name, ignoreCase = true) }?.albumId
    }

    private suspend fun loadSongs(): List<Song> {
        cachedSongs?.let { existing ->
            if (existing.isNotEmpty()) return existing
        }
        val songs = musicRepository.getAllSongsOnce()
        cachedSongs = songs
        return songs
    }
}
