package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.toAlbums
import com.pisces312.piscesplayer.data.database.toSongs
import com.pisces312.piscesplayer.data.model.Album
import com.pisces312.piscesplayer.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Data source for the library "Classical" tab: the built-in classical MIDI
 * collection grouped into one virtual album per composer.
 */
@HiltViewModel
class ClassicalLibraryViewModel @Inject constructor(
    musicDao: MusicDao
) : ViewModel() {

    val albums: StateFlow<List<Album>> = musicDao.getClassicalAlbums()
        .map { it.toAlbums() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val songs: StateFlow<List<Song>> = musicDao.getClassicalSongs()
        .map { it.toSongs() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trackCount: StateFlow<Int> = musicDao.getClassicalSongCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
