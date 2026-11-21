package com.pisces312.piscesplayer.presentation.midilibrary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.pisces312.piscesplayer.data.classical.ClassicalFavoritesStore
import com.pisces312.piscesplayer.data.classical.ClassicalNameCatalog
import com.pisces312.piscesplayer.data.classical.ClassicalStyle
import com.pisces312.piscesplayer.data.classical.ClassicalWorkCatalog
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.toSongs
import com.pisces312.piscesplayer.data.midilibrary.MidiDirectory
import com.pisces312.piscesplayer.data.midilibrary.MidiLibraryRepository
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 「MIDI 曲库 · 内置 MIDI」的一节：风格 key + 其下曲目。 */
data class MidiBuiltinSection(
    val styleKey: String,
    val tracks: List<Song>
)

/**
 * Data source for the「MIDI 曲库」screen (Music learning → MIDI library): built-in
 * non-classical MIDI (hymns, songs, jazz, exercises, marches, dances — everything the
 * classical pack ships that is not period classical) and, later, the user's own MIDI
 * files (plan §B/§C).
 *
 * Favorites reuse [ClassicalFavoritesStore]: built-ins stay APP_PROVIDED content.
 */
@HiltViewModel
class MidiLibraryViewModel @Inject constructor(
    musicDao: MusicDao,
    userPreferencesRepository: UserPreferencesRepository,
    val nameCatalog: ClassicalNameCatalog,
    val workCatalog: ClassicalWorkCatalog,
    val favorites: ClassicalFavoritesStore,
    private val repository: MidiLibraryRepository
) : ViewModel() {

    val songs: StateFlow<List<Song>> = musicDao.getClassicalSongs()
        .map { it.toSongs() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 内置非古典曲目（古典熏陶之外的内置 MIDI）。 */
    val builtInSongs: StateFlow<List<Song>> = songs
        .map { list -> list.filter { !workCatalog.isClassicalTrack(it.id) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 用户 MIDI（扫描目录 + 单曲添加，引用原文件）。 */
    val userSongs: StateFlow<List<Song>> = repository.userMidiSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val directories: StateFlow<List<MidiDirectory>> = repository.directories

    val translationEnabled: StateFlow<Boolean> =
        userPreferencesRepository.classicalTranslationEnabledFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val favoriteTracks: StateFlow<Map<String, Long>> = favorites.favoriteTracks

    fun toggleFavoriteTrack(songId: String) {
        favorites.toggleTrack(songId)
    }

    fun addDirectory(uri: Uri) {
        viewModelScope.launch { repository.addDirectory(uri) }
    }

    fun addSingleFile(uri: Uri) {
        viewModelScope.launch { repository.addSingleFile(uri) }
    }

    fun rescanAll() {
        viewModelScope.launch { repository.rescanAll() }
    }

    fun removeDirectory(uri: String) {
        viewModelScope.launch { repository.removeDirectory(uri) }
    }

    fun removeFile(songId: String) {
        viewModelScope.launch { repository.removeFile(songId) }
    }

    /** 按 [ClassicalStyle.BUILTIN_SECTIONS] 固定顺序分节（Other 殿后），节内按标题排序。 */
    fun sections(all: List<Song>): List<MidiBuiltinSection> {
        val grouped = all.groupBy { song ->
            ClassicalStyle.sectionOf(workCatalog.styleOf(song.id.toLongOrNull() ?: -1L))
        }
        return (ClassicalStyle.BUILTIN_SECTIONS + ClassicalStyle.SECTION_OTHER).mapNotNull { key ->
            grouped[key]?.let { tracks ->
                MidiBuiltinSection(key, tracks.sortedBy { it.title })
            }
        }
    }
}
