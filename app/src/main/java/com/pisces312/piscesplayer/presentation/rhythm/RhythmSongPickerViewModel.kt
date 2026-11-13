package com.pisces312.piscesplayer.presentation.rhythm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.rhythm.RhythmChartMeta
import com.pisces312.piscesplayer.data.rhythm.RhythmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class RhythmSongPickerViewModel @Inject constructor(
    private val repository: RhythmRepository,
) : ViewModel() {
    private val _chartCatalog = MutableStateFlow<Map<String, List<RhythmChartMeta>>?>(null)
    val chartCatalog: StateFlow<Map<String, List<RhythmChartMeta>>?> = _chartCatalog.asStateFlow()

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
}
