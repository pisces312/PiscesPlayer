package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.classical.ClassicalFavoritesStore
import com.pisces312.piscesplayer.data.classical.ClassicalNameCatalog
import com.pisces312.piscesplayer.data.classical.ClassicalStyle
import com.pisces312.piscesplayer.data.classical.ClassicalWork
import com.pisces312.piscesplayer.data.classical.ClassicalWorkCatalog
import com.pisces312.piscesplayer.data.classical.NameSet
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.toAlbums
import com.pisces312.piscesplayer.data.database.toSongs
import com.pisces312.piscesplayer.data.midilibrary.MidiLibraryRepository
import com.pisces312.piscesplayer.data.midilibrary.MidiShadowIndex
import com.pisces312.piscesplayer.data.model.Album
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val favorites: ClassicalFavoritesStore,
    private val midiRepository: MidiLibraryRepository
) : ViewModel() {

    val albums: StateFlow<List<Album>> = musicDao.getClassicalAlbums()
        .map { it.toAlbums() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val songs: StateFlow<List<Song>> = musicDao.getClassicalSongs()
        .map { it.toSongs() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 「古典熏陶」收录范围：只保留正统古典（period styles，见 [ClassicalStyle]）；
     * 非古典流派（赞美诗/艺术歌曲/爵士…）归「MIDI 曲库 · 内置 MIDI」。
     */
    val classicalSongs: StateFlow<List<Song>> = songs
        .map { list -> list.filter { workCatalog.isClassicalTrack(it.id) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 有古典曲目的作曲家（虚拟专辑）；纯非古典作曲家不再出现在古典熏陶。 */
    val classicalAlbums: StateFlow<List<Album>> = combine(albums, classicalSongs) { albums, songs ->
        albums.filter { album -> songs.any { it.albumId == album.id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** 用户文件遮蔽索引（plan §2-E）。 */
    val shadowIndex: StateFlow<MidiShadowIndex> = midiRepository.shadowIndex

    private val userSongsById: StateFlow<Map<String, Song>> = midiRepository.userMidiSongs()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * 播放目标：被用户文件遮蔽的内置曲目改由用户的文件播放。显示身份（曲名目录、
     * 作品分组、收藏键）保持内置条目 —— 展示上仍是「原位显示 + 继承元数据」，收藏与
     * 播放列表引用不受遮蔽变化影响。
     */
    fun playbackTarget(song: Song): Song {
        val builtInId = song.id.toLongOrNull() ?: return song
        val entry = shadowIndex.value.substituteFor(builtInId) ?: return song
        return userSongsById.value[entry.songId] ?: song
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
