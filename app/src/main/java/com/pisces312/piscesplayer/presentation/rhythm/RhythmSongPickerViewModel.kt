package com.pisces312.piscesplayer.presentation.rhythm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.classical.ClassicalNameCatalog
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import com.pisces312.piscesplayer.data.rhythm.RhythmChartMeta
import com.pisces312.piscesplayer.data.rhythm.RhythmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RhythmSongPickerViewModel @Inject constructor(
    private val repository: RhythmRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    /** 内置古典曲名目录（原文/英文/中文），供 MIDI tab 的双语显示与搜索。 */
    val nameCatalog: ClassicalNameCatalog,
) : ViewModel() {
    private val _chartCatalog = MutableStateFlow<Map<String, List<RhythmChartMeta>>?>(null)
    val chartCatalog: StateFlow<Map<String, List<RhythmChartMeta>>?> = _chartCatalog.asStateFlow()

    /** MIDI tab 的「曲名翻译」开关，独立于古典熏陶的同名开关；默认关闭。 */
    val translationEnabled: StateFlow<Boolean> = userPreferencesRepository.midiTranslationEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _chartCatalog.value = runCatching { repository.chartCatalog() }
                .getOrElse { _chartCatalog.value ?: emptyMap() }
        }
    }

    fun setTranslationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setMidiTranslationEnabled(enabled)
        }
    }
}
