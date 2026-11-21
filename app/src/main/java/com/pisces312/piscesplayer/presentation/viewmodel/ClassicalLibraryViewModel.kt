package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.classical.ClassicalFavoritesStore
import com.pisces312.piscesplayer.data.classical.ClassicalNameCatalog
import com.pisces312.piscesplayer.data.classical.ClassicalWork
import com.pisces312.piscesplayer.data.classical.ClassicalWorkCatalog
import com.pisces312.piscesplayer.data.classical.NameSet
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.toAlbums
import com.pisces312.piscesplayer.data.database.toSongs
import com.pisces312.piscesplayer.data.model.Album
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 作品分组的一节：作品头 + 其下的曲目（乐章）。 */
data class ClassicalWorkSection(
    val work: ClassicalWork,
    val tracks: List<Song>
)

/**
 * Data source for the 「古典熏陶」browse screens (Music learning → Classical): the
 * built-in classical MIDI collection grouped into one virtual album per composer, and
 * per composer into works (作品集, see [ClassicalWorkCatalog]).
 *
 * Also owns the browsing-UI「翻译」toggle (translated vs original titles;
 * see [com.pisces312.piscesplayer.data.classical.ClassicalDisplay]) and the
 * classical-scoped favorites (作曲家 / 乐曲).
 */
@HiltViewModel
class ClassicalLibraryViewModel @Inject constructor(
    musicDao: MusicDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    val nameCatalog: ClassicalNameCatalog,
    val workCatalog: ClassicalWorkCatalog,
    val favorites: ClassicalFavoritesStore
) : ViewModel() {

    val albums: StateFlow<List<Album>> = musicDao.getClassicalAlbums()
        .map { it.toAlbums() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val songs: StateFlow<List<Song>> = musicDao.getClassicalSongs()
        .map { it.toSongs() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trackCount: StateFlow<Int> = musicDao.getClassicalSongCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val translationEnabled: StateFlow<Boolean> = userPreferencesRepository.classicalTranslationEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val favoriteComposers: StateFlow<Map<String, Long>> = favorites.favoriteComposers

    val favoriteTracks: StateFlow<Map<String, Long>> = favorites.favoriteTracks

    fun setTranslationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setClassicalTranslationEnabled(enabled)
        }
    }

    fun toggleFavoriteComposer(albumId: Long) {
        favorites.toggleComposer(albumId)
    }

    fun toggleFavoriteTrack(songId: String) {
        favorites.toggleTrack(songId)
    }

    /**
     * Builds the work sections for one composer from its songs. Song ids equal the
     * index track ids, so the [ClassicalWorkCatalog] joins on them directly; tracks
     * missing from the catalog (older pack + newer app) fall into a per-track section.
     */
    fun workSections(albumId: Long, allSongs: List<Song>): List<ClassicalWorkSection> {
        val composerSongs = allSongs.filter { it.albumId == albumId }
            .sortedBy { it.id.toLongOrNull() ?: 0L }
        val byWork = LinkedHashMap<ClassicalWork, MutableList<Song>>()
        for (song in composerSongs) {
            val work = song.id.toLongOrNull()?.let { workCatalog.workOfTrack(it) }
            if (work != null) {
                byWork.getOrPut(work) { mutableListOf() }.add(song)
            } else {
                val solo = ClassicalWork(
                    key = "fallback:${song.id}",
                    names = NameSet(original = song.title, en = "", zh = ""),
                    opus = "",
                    trackIds = listOfNotNull(song.id.toLongOrNull())
                )
                byWork.getOrPut(solo) { mutableListOf() }.add(song)
            }
        }
        return byWork.map { (work, tracks) -> ClassicalWorkSection(work, tracks) }
    }
}
