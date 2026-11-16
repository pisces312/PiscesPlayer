package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.lifecycle.ViewModel
import com.pisces312.piscesplayer.data.model.StorageFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryStateHolder: LibraryStateHolder
) : ViewModel() {

    val songsPagingFlow = libraryStateHolder.songsPagingFlow.cachedIn(viewModelScope)

    val albumsPagingFlow = libraryStateHolder.albumsPagingFlow.cachedIn(viewModelScope)

    val artistsPagingFlow = libraryStateHolder.artistsPagingFlow.cachedIn(viewModelScope)

    val favoritesPagingFlow = libraryStateHolder.favoritesPagingFlow.cachedIn(viewModelScope)

    val favoriteSongCountFlow = libraryStateHolder.favoriteSongCountFlow

    val yearBucketsFlow = libraryStateHolder.yearBucketsFlow

    val isLoadingLibrary = libraryStateHolder.isLoadingLibrary

    /**
     * Storage filter actually applied to library lists: when "hide local media" is on this is
     * forced to [StorageFilter.ONLINE] even if the user selected a different filter.
     * UI copy (e.g. empty states) must explain the list contents, so it should read this
     * instead of the raw user selection.
     */
    val effectiveStorageFilter = libraryStateHolder.effectiveStorageFilter
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = StorageFilter.ALL
        )
}
