package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.model.SortOption
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import com.pisces312.piscesplayer.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YearDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val year: Int = checkNotNull(savedStateHandle["year"]) { "YearDetail requires a year argument" }

    private val _currentSortOption = MutableStateFlow<SortOption>(SortOption.YearSongRelease)
    val currentSortOption: StateFlow<SortOption> = _currentSortOption.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val songs: StateFlow<List<Song>> = _currentSortOption
        .flatMapLatest { sortOption -> musicRepository.getSongsByYear(year, sortOption) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val savedKey = userPreferencesRepository.yearDetailSortOptionFlow.first()
            _currentSortOption.value = SortOption.YEAR_SONGS.find { it.storageKey == savedKey }
                ?: SortOption.YearSongRelease
        }
    }

    fun setSortOption(option: SortOption) {
        if (option !in SortOption.YEAR_SONGS) return
        _currentSortOption.value = option
        viewModelScope.launch {
            userPreferencesRepository.setYearDetailSortOption(option.storageKey)
        }
    }
}
