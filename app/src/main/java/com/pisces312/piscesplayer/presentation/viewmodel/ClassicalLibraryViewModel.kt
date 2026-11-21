package com.pisces312.piscesplayer.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pisces312.piscesplayer.data.classical.ClassicalFavoritesStore
import com.pisces312.piscesplayer.data.classical.ClassicalNameCatalog
import com.pisces312.piscesplayer.data.classical.ClassicalStyle
import com.pisces312.piscesplayer.data.classical.ClassicalWork
import com.pisces312.piscesplayer.data.classical.ClassicalWorkCatalog
import com.pisces312.piscesplayer.data.classical.ClassicalWorkNode
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

/** 一位作曲家的一个作品集（跨作曲家平铺「作品集」tab 的一项）。 */
data class ComposerWorkSet(
    val albumId: Long,
    val work: ClassicalWork,
    val tracks: List<Song>
)

/**
 * 平铺作品集派生（纯函数，单测直调）：逐作曲家按 [workOfTrack] 分组（保曲目 id 序），
 * catalog 缺失曲目落成单轨 fallback 作品集（key = "fallback:<songId>"，详情页可解析）。
 */
internal fun buildAllWorkSets(
    albums: List<Album>,
    songs: List<Song>,
    workOfTrack: (Long) -> ClassicalWork?,
): List<ComposerWorkSet> {
    val ordered = songs.sortedBy { it.id.toLongOrNull() ?: 0L }
    return albums.flatMap { album ->
        val composerSongs = ordered.filter { it.albumId == album.id }
        if (composerSongs.isEmpty()) return@flatMap emptyList()
        groupWorks(composerSongs, workOfTrack).map { (work, tracks) ->
            ComposerWorkSet(album.id, work, tracks)
        }
    }
}

/** 作曲内曲目 → 作品集分组（保 id 序）；catalog 缺失曲目落成单轨 fallback 作品集。 */
internal fun groupWorks(
    composerSongs: List<Song>,
    workOfTrack: (Long) -> ClassicalWork?,
): List<Pair<ClassicalWork, List<Song>>> {
    val byWork = LinkedHashMap<ClassicalWork, MutableList<Song>>()
    for (song in composerSongs) {
        val work = song.id.toLongOrNull()?.let(workOfTrack)
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
    return byWork.map { (work, tracks) -> work to tracks }
}

/** 作品集分组：组头 + 其下作品节点（每个节点有自己的名称和曲目列表）。 */
data class ClassicalWorkSetSection(
    val work: ClassicalWork,
    val nodes: List<ClassicalWorkSetNode>
)

data class ClassicalWorkSetNode(
    val node: ClassicalWorkNode,
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
        return groupWorks(composerSongs) { id -> workCatalog.workOfTrack(id) }
            .map { (work, tracks) -> ClassicalWorkSection(work, tracks) }
    }

    /**
     * 「作品集」tab 的数据源：全部作曲家的作品集平铺列表（catalog 缺失曲目落成
     * 单轨 fallback 作品集，key 前缀 "fallback:" 与详情页解析一致）。
     */
    fun allWorkSets(albums: List<Album>, songs: List<Song>): List<ComposerWorkSet> =
        buildAllWorkSets(albums, songs) { id -> workCatalog.workOfTrack(id) }

    /**
     * Two-level variant of [workSections]：组内再按作品（乐章/编曲）细分，产出
     * [ClassicalWorkSetSection] 列表。组内只细分出 1 个作品时不造作品集层，
     * 直接退化为单节点（与单层 workSections 等价）。
     *
     * 仅「作品集详情页」使用：作曲家详情已回单层（v3），作品节点层只在这一页出现。
     */
    fun workSetSections(albumId: Long, allSongs: List<Song>): List<ClassicalWorkSetSection> {
        val composerSongs = allSongs.filter { it.albumId == albumId }
            .sortedBy { it.id.toLongOrNull() ?: 0L }

        return groupWorks(composerSongs) { id -> workCatalog.workOfTrack(id) }.map { (work, tracks) ->
            val nodes = workCatalog.nodesForWork(work.key)
            if (nodes.isEmpty() || nodes.size == 1 && nodes[0].trackIds.size == tracks.size) {
                // 单层：不造作品集，直接退化为单节点
                ClassicalWorkSetSection(
                    work = work,
                    nodes = listOf(
                        ClassicalWorkSetNode(
                            node = ClassicalWorkNode(
                                key = "single:${work.key}",
                                names = work.names,
                                trackIds = work.trackIds
                            ),
                            tracks = tracks
                        )
                    )
                )
            } else {
                val nodeSections = nodes.map { node ->
                    val nodeTracks = tracks.filter { it.id.toLongOrNull() in node.trackIds.toSet() }
                    ClassicalWorkSetNode(node, nodeTracks)
                }.filter { it.tracks.isNotEmpty() }
                ClassicalWorkSetSection(work, nodeSections)
            }
        }
    }
}
