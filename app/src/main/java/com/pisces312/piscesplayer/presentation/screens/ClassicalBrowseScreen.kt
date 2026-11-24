@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pisces312.piscesplayer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.classical.ClassicalDisplay
import com.pisces312.piscesplayer.data.classical.ClassicalDisplayNames
import com.pisces312.piscesplayer.data.model.Album
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.components.PlaylistBottomSheet
import com.pisces312.piscesplayer.presentation.components.SongInfoBottomSheet
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.navigation.navigateSafelyReplacing
import com.pisces312.piscesplayer.presentation.viewmodel.ClassicalLibraryViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.PlaylistViewModel
import java.net.URLEncoder
import kotlinx.collections.immutable.persistentListOf

/**
 * 「古典熏陶」browse screen (Music learning → Classical): four tabs —
 *
 * - **作曲家**：紧凑作曲家列表（收藏置顶）+ 搜索条，点按进入按作品集分组的
 *   [ClassicalComposerDetailScreen]；
 * - **作品集**：全库作品集平铺（作品名 + 作曲家 · Op. · N 首），点按进入
 *   [ClassicalWorkDetailScreen]；
 * - **曲目**：全部古典曲目平铺（曲库歌曲行同款：播放高亮/更多菜单/星标），副行作曲家；
 * - **收藏乐曲**：只显示收藏的乐曲（收藏时间倒序）+ 搜索条。
 *
 * 顶部保留原有「播放全部 / 曲名翻译」头部（作曲家 tab）。Tab 切换与曲库同款
 * （PrimaryScrollableTabRow + HorizontalPager 双向同步、可滑动；四页搜索状态互相独立）。
 * 见 plan v3 §0。
 */
@Composable
fun ClassicalBrowseScreen(
    playerViewModel: PlayerViewModel,
    navController: NavController,
    paddingValues: PaddingValues,
    onBack: () -> Unit
) {
    val viewModel: ClassicalLibraryViewModel = hiltViewModel()
    val albums by viewModel.classicalAlbums.collectAsStateWithLifecycle()
    val songs by viewModel.classicalSongs.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val favoriteComposers by viewModel.favoriteComposers.collectAsStateWithLifecycle()
    val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { 4 })
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
    }
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) {
            pagerState.animateScrollToPage(selectedTab)
        }
    }

    val queueName = stringResource(R.string.library_tab_classical)
    val favoritesQueueName = stringResource(R.string.classical_tab_favorites)
    val listBottomPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(queueName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.classical_tab_composers)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.classical_tab_works)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(stringResource(R.string.classical_tab_tracks)) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text(stringResource(R.string.classical_tab_favorites)) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> ComposerList(
                        viewModel = viewModel,
                        playerViewModel = playerViewModel,
                        albums = albums,
                        songs = songs,
                        trackCount = songs.size,
                        translationEnabled = translationEnabled,
                        favoriteComposers = favoriteComposers,
                        queueName = queueName,
                        navController = navController,
                        bottomPadding = listBottomPadding
                    )
                    1 -> WorkSetsList(
                        viewModel = viewModel,
                        playerViewModel = playerViewModel,
                        albums = albums,
                        songs = songs,
                        translationEnabled = translationEnabled,
                        queueName = queueName,
                        navController = navController,
                        bottomPadding = listBottomPadding
                    )
                    2 -> AllTracksList(
                        viewModel = viewModel,
                        playerViewModel = playerViewModel,
                        songs = songs,
                        translationEnabled = translationEnabled,
                        favoriteTracks = favoriteTracks,
                        queueName = queueName,
                        navController = navController,
                        bottomPadding = listBottomPadding
                    )
                    else -> FavoriteTracksList(
                        viewModel = viewModel,
                        playerViewModel = playerViewModel,
                        songs = songs,
                        translationEnabled = translationEnabled,
                        favoriteTracks = favoriteTracks,
                        queueName = favoritesQueueName,
                        bottomPadding = listBottomPadding
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerList(
    viewModel: ClassicalLibraryViewModel,
    playerViewModel: PlayerViewModel,
    albums: List<Album>,
    songs: List<Song>,
    trackCount: Int,
    translationEnabled: Boolean,
    favoriteComposers: Map<String, Long>,
    queueName: String,
    navController: NavController,
    bottomPadding: Dp
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        ClassicalSearchField(
            query = query,
            onQueryChange = { query = it },
            hint = stringResource(R.string.classical_search_composers_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        ComposerListBody(
            viewModel = viewModel,
            playerViewModel = playerViewModel,
            albums = albums,
            songs = songs,
            trackCount = trackCount,
            translationEnabled = translationEnabled,
            favoriteComposers = favoriteComposers,
            query = query,
            queueName = queueName,
            navController = navController,
            bottomPadding = bottomPadding
        )
    }
}

@Composable
private fun ComposerListBody(
    viewModel: ClassicalLibraryViewModel,
    playerViewModel: PlayerViewModel,
    albums: List<Album>,
    songs: List<Song>,
    trackCount: Int,
    translationEnabled: Boolean,
    favoriteComposers: Map<String, Long>,
    query: String,
    queueName: String,
    navController: NavController,
    bottomPadding: Dp
) {
    val filtered = albums.filter { album ->
        namesMatchQuery(
            names = viewModel.nameCatalog.albumName(album.id),
            fallback = album.artist,
            query = query
        )
    }
    val (favoriteAlbums, otherAlbums) = filtered.partition { favoriteComposers.containsKey(it.id.toString()) }

    if (filtered.isEmpty()) {
        ClassicalEmptyHint(
            text = stringResource(R.string.classical_no_search_results),
            bottomPadding = bottomPadding
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            ClassicalBrowseHeader(
                trackCount = trackCount,
                composerCount = albums.size,
                translationEnabled = translationEnabled,
                onTranslationToggle = { viewModel.setTranslationEnabled(it) },
                onPlayAll = {
                    songs.firstOrNull()?.let { first ->
                        playerViewModel.playSongs(
                            songs.map(viewModel::playbackTarget),
                            viewModel.playbackTarget(first),
                            queueName = queueName
                        )
                    }
                }
            )
        }
        if (favoriteAlbums.isNotEmpty()) {
            item { ClassicalSectionLabel(stringResource(R.string.classical_fav_composers_section)) }
            items(favoriteAlbums, key = { it.id }) { album ->
                ComposerRow(
                    viewModel = viewModel,
                    album = album,
                    songCount = songs.count { it.albumId == album.id },
                    translationEnabled = translationEnabled,
                    isFavorite = true,
                    onClick = { navController.navigateSafely(Screen.ClassicalComposer.createRoute(album.id)) }
                )
            }
        }
        item { ClassicalSectionLabel(stringResource(R.string.classical_all_composers_section)) }
        items(otherAlbums, key = { it.id }) { album ->
            ComposerRow(
                viewModel = viewModel,
                album = album,
                songCount = songs.count { it.albumId == album.id },
                translationEnabled = translationEnabled,
                isFavorite = false,
                onClick = { navController.navigateSafely(Screen.ClassicalComposer.createRoute(album.id)) }
            )
        }
    }
}

// ---- 作品集 ---------------------------------------------------------------------

@Composable
private fun WorkSetsList(
    viewModel: ClassicalLibraryViewModel,
    playerViewModel: PlayerViewModel,
    albums: List<Album>,
    songs: List<Song>,
    translationEnabled: Boolean,
    queueName: String,
    navController: NavController,
    bottomPadding: Dp
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        ClassicalSearchField(
            query = query,
            onQueryChange = { query = it },
            hint = stringResource(R.string.classical_search_works_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        WorkSetsListBody(
            viewModel = viewModel,
            playerViewModel = playerViewModel,
            albums = albums,
            songs = songs,
            translationEnabled = translationEnabled,
            query = query,
            queueName = queueName,
            navController = navController,
            bottomPadding = bottomPadding
        )
    }
}

@Composable
private fun WorkSetsListBody(
    viewModel: ClassicalLibraryViewModel,
    playerViewModel: PlayerViewModel,
    albums: List<Album>,
    songs: List<Song>,
    translationEnabled: Boolean,
    query: String,
    queueName: String,
    navController: NavController,
    bottomPadding: Dp
) {
    val allWorkSets = remember(albums, songs) { viewModel.allWorkSets(albums, songs) }
    val filtered = remember(allWorkSets, query, translationEnabled) {
        allWorkSets
            .filter { set ->
                namesMatchQuery(names = set.work.names, fallback = set.work.opus, query = query) ||
                    namesMatchQuery(
                        names = viewModel.nameCatalog.albumName(set.albumId),
                        fallback = "",
                        query = query
                    )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) {
                ClassicalDisplay.resolve(it.work.names, translate = translationEnabled).primary
            })
    }

    if (filtered.isEmpty()) {
        ClassicalEmptyHint(
            text = stringResource(
                if (query.isBlank()) R.string.classical_works_empty
                else R.string.classical_no_search_results
            ),
            bottomPadding = bottomPadding
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            CountAndPlayAllHeader(
                countText = stringResource(R.string.classical_card_track_count_format, filtered.size),
                onPlayAll = {
                    songs.firstOrNull()?.let { first ->
                        playerViewModel.playSongs(
                            songs.map(viewModel::playbackTarget),
                            viewModel.playbackTarget(first),
                            queueName = queueName
                        )
                    }
                }
            )
        }
        items(filtered, key = { "${it.albumId}:${it.work.key}" }) { set ->
            val names = ClassicalDisplay.resolve(set.work.names, translate = translationEnabled)
            val composer = viewModel.nameCatalog.albumName(set.albumId)?.let {
                ClassicalDisplay.resolve(it, translate = translationEnabled).primary
            } ?: ""
            WorkSetRow(
                title = names.primary,
                meta = listOfNotNull(
                    composer.ifBlank { null },
                    set.work.opus.ifBlank { null },
                    stringResource(R.string.classical_card_track_count_format, set.tracks.size)
                ).joinToString(" · "),
                onClick = {
                    navController.navigateSafely(
                        Screen.ClassicalWork.createRoute(set.albumId, set.work.key)
                    )
                }
            )
        }
    }
}

@Composable
private fun WorkSetRow(
    title: String,
    meta: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---- 曲目 -----------------------------------------------------------------------

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun AllTracksList(
    viewModel: ClassicalLibraryViewModel,
    playerViewModel: PlayerViewModel,
    songs: List<Song>,
    translationEnabled: Boolean,
    favoriteTracks: Map<String, Long>,
    queueName: String,
    navController: NavController,
    bottomPadding: Dp
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSongInfoSheet by remember { mutableStateOf(false) }
    var showPlaylistSheet by remember { mutableStateOf(false) }
    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

    val filtered = remember(songs, query) {
        songs.filter { song ->
            namesMatchQuery(
                names = viewModel.nameCatalog.songName(song.id),
                fallback = song.title,
                query = query
            ) ||
                namesMatchQuery(
                    names = viewModel.nameCatalog.albumName(song.albumId),
                    fallback = song.artist,
                    query = query
                )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ClassicalSearchField(
            query = query,
            onQueryChange = { query = it },
            hint = stringResource(R.string.classical_search_all_tracks_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (filtered.isEmpty()) {
            ClassicalEmptyHint(
                text = stringResource(R.string.classical_no_search_results),
                bottomPadding = bottomPadding
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    CountAndPlayAllHeader(
                        countText = stringResource(
                            R.string.classical_card_track_count_format,
                            filtered.size
                        ),
                        onPlayAll = {
                            filtered.firstOrNull()?.let { first ->
                                playerViewModel.playSongs(
                                    filtered.map(viewModel::playbackTarget),
                                    viewModel.playbackTarget(first),
                                    queueName = queueName
                                )
                            }
                        }
                    )
                }
                items(filtered, key = { it.id }) { song ->
                    val names = trackDisplayNames(song, viewModel.nameCatalog, translationEnabled)
                    val composer = viewModel.nameCatalog.albumName(song.albumId)?.let {
                        ClassicalDisplay.resolve(it, translate = translationEnabled).primary
                    } ?: song.artist
                    LibraryPlaybackAwareSongItem(
                        song = song,
                        playerViewModel = playerViewModel,
                        showAlbumArt = false,
                        titleOverride = names.primary,
                        subtitleOverride = names.subtitle?.let { "$composer · $it" } ?: composer,
                        isFavorite = favoriteTracks.containsKey(song.id),
                        onToggleFavorite = { viewModel.toggleFavoriteTrack(song.id) },
                        onMoreOptionsClick = {
                            playerViewModel.selectSongForInfo(song)
                            showSongInfoSheet = true
                        },
                        onClick = {
                            playerViewModel.playSongs(
                                filtered.map(viewModel::playbackTarget),
                                viewModel.playbackTarget(song),
                                queueName = queueName
                            )
                        }
                    )
                }
            }
        }
    }

    if (showSongInfoSheet && selectedSongForInfo != null) {
        val song = selectedSongForInfo!!
        SongInfoBottomSheet(
            song = song,
            isFavorite = favoriteTracks.containsKey(song.id),
            onToggleFavorite = { viewModel.toggleFavoriteTrack(song.id) },
            onDismiss = {
                showSongInfoSheet = false
                showPlaylistSheet = false
            },
            onPlaySong = {
                playerViewModel.playSongs(
                    listOf(viewModel.playbackTarget(song)),
                    viewModel.playbackTarget(song),
                    queueName = queueName
                )
                showSongInfoSheet = false
            },
            onAddToQueue = {
                playerViewModel.addSongToQueue(viewModel.playbackTarget(song))
                showSongInfoSheet = false
            },
            onAddNextToQueue = {
                playerViewModel.addSongNextToQueue(viewModel.playbackTarget(song))
                showSongInfoSheet = false
            },
            onNavigateToRhythmGame = {
                navController.navigateSafely(Screen.RhythmQuick.createRoute(song.id))
                showSongInfoSheet = false
            },
            onAddToPlayList = { showPlaylistSheet = true },
            onDeleteFromDevice = playerViewModel::deleteFromDevice,
            onNavigateToAlbum = {
                navController.navigateSafelyReplacing(
                    route = Screen.AlbumDetail.createRoute(song.albumId),
                    patternToPop = Screen.AlbumDetail.route
                )
                showSongInfoSheet = false
            },
            onNavigateToArtist = {
                navController.navigateSafelyReplacing(
                    route = Screen.ArtistDetail.createRoute(song.artistId),
                    patternToPop = Screen.ArtistDetail.route
                )
                showSongInfoSheet = false
            },
            onNavigateToGenre = {
                song.genre?.let {
                    navController.navigateSafelyReplacing(
                        route = Screen.GenreDetail.createRoute(URLEncoder.encode(it, "UTF-8")),
                        patternToPop = Screen.GenreDetail.route
                    )
                }
                showSongInfoSheet = false
            },
            onNavigateToYear = { year ->
                navController.navigateSafelyReplacing(
                    route = Screen.YearDetail.createRoute(year),
                    patternToPop = Screen.YearDetail.route
                )
                showSongInfoSheet = false
            },
            onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate, customMetadataChanges ->
                playerViewModel.editSongMetadata(
                    song,
                    newTitle,
                    newArtist,
                    newAlbum,
                    newAlbumArtist,
                    newComposer,
                    newGenre,
                    newLyrics,
                    newTrackNumber,
                    newDiscNumber,
                    replayGainTrackGainDb,
                    replayGainAlbumGainDb,
                    coverArtUpdate,
                    customMetadataChanges
                )
            },
            removeFromListTrigger = {}
        )

        if (showPlaylistSheet) {
            PlaylistBottomSheet(
                playlistUiState = playlistUiState,
                songs = persistentListOf(song),
                onDismiss = { showPlaylistSheet = false },
                bottomBarHeight = 0.dp,
                playerViewModel = playerViewModel,
            )
        }
    }
}

// ---- 收藏乐曲 -------------------------------------------------------------------

@Composable
private fun FavoriteTracksList(
    viewModel: ClassicalLibraryViewModel,
    playerViewModel: PlayerViewModel,
    songs: List<Song>,
    translationEnabled: Boolean,
    favoriteTracks: Map<String, Long>,
    queueName: String,
    bottomPadding: Dp
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        ClassicalSearchField(
            query = query,
            onQueryChange = { query = it },
            hint = stringResource(R.string.classical_search_tracks_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        FavoriteTracksListBody(
            viewModel = viewModel,
            playerViewModel = playerViewModel,
            songs = songs,
            translationEnabled = translationEnabled,
            favoriteTracks = favoriteTracks,
            query = query,
            queueName = queueName,
            bottomPadding = bottomPadding
        )
    }
}

@Composable
private fun FavoriteTracksListBody(
    viewModel: ClassicalLibraryViewModel,
    playerViewModel: PlayerViewModel,
    songs: List<Song>,
    translationEnabled: Boolean,
    favoriteTracks: Map<String, Long>,
    query: String,
    queueName: String,
    bottomPadding: Dp
) {
    val favoriteSongs = songs
        .filter { favoriteTracks.containsKey(it.id) }
        .filter { song ->
            namesMatchQuery(
                names = viewModel.nameCatalog.songName(song.id),
                fallback = song.title,
                query = query
            )
        }
        .sortedByDescending { favoriteTracks[it.id] ?: 0L }

    if (favoriteSongs.isEmpty()) {
        ClassicalEmptyHint(
            text = stringResource(
                if (query.isBlank()) R.string.classical_favorites_empty
                else R.string.classical_no_search_results
            ),
            bottomPadding = bottomPadding
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            CountAndPlayAllHeader(
                countText = stringResource(
                    R.string.classical_card_track_count_format,
                    favoriteSongs.size
                ),
                onPlayAll = {
                    favoriteSongs.firstOrNull()?.let { first ->
                        playerViewModel.playSongs(
                            favoriteSongs.map(viewModel::playbackTarget),
                            viewModel.playbackTarget(first),
                            queueName = queueName
                        )
                    }
                }
            )
        }
        items(favoriteSongs, key = { it.id }) { song ->
            val names = trackDisplayNames(song, viewModel.nameCatalog, translationEnabled)
            val composerName = viewModel.nameCatalog.albumName(song.albumId)?.let {
                ClassicalDisplay.resolve(it, translate = translationEnabled).primary
            } ?: song.title
            ClassicalTrackRow(
                title = names,
                subtitle = composerName,
                isFavorite = true,
                onToggleFavorite = { viewModel.toggleFavoriteTrack(song.id) },
                onClick = {
                    playerViewModel.playSongs(
                        favoriteSongs.map(viewModel::playbackTarget),
                        viewModel.playbackTarget(song),
                        queueName = queueName
                    )
                }
            )
        }
    }
}

// ---- 共享小件 -------------------------------------------------------------------

/** 摘要 + 全部播放 行（作品集 / 曲目 / 收藏乐曲三个平铺 tab 的列表头）。 */
@Composable
private fun CountAndPlayAllHeader(countText: String, onPlayAll: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = countText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        FilledTonalButton(
            onClick = onPlayAll,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.classical_tab_play_all))
        }
    }
}

@Composable
private fun ClassicalBrowseHeader(
    trackCount: Int,
    composerCount: Int,
    translationEnabled: Boolean,
    onTranslationToggle: (Boolean) -> Unit,
    onPlayAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.classical_tab_summary_format, trackCount, composerCount),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                onClick = onPlayAll,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.classical_tab_play_all)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.classical_tab_play_all))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.classical_tab_translate),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = translationEnabled,
                onCheckedChange = onTranslationToggle
            )
        }
    }
}

@Composable
private fun ComposerRow(
    viewModel: ClassicalLibraryViewModel,
    album: Album,
    songCount: Int,
    translationEnabled: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit
) {
    val names = viewModel.nameCatalog.albumName(album.id)?.let {
        ClassicalDisplay.resolve(it, translate = translationEnabled)
    } ?: ClassicalDisplayNames(primary = album.artist.ifBlank { album.title }, subtitle = null)
    val fallback = album.artist.ifBlank { album.title }
    val palette = composerPalette[(fallback.hashCode() and Int.MAX_VALUE) % composerPalette.size]

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(palette.first), Color(palette.second)))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = names.primary.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Light,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = names.primary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                names.subtitle?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = stringResource(R.string.classical_card_track_count_format, songCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { viewModel.toggleFavoriteComposer(album.id) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.classical_unfavorite else R.string.classical_favorite
                    ),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun ClassicalSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun ClassicalEmptyHint(text: String, bottomPadding: Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val composerPalette = listOf(
    0xFF5C6BC0 to 0xFF283593,
    0xFF26A69A to 0xFF00695C,
    0xFFEC407A to 0xFFAD1457,
    0xFFFFA726 to 0xFFEF6C00,
    0xFF42A5F5 to 0xFF1565C0,
    0xFF66BB6A to 0xFF2E7D32,
    0xFFAB47BC to 0xFF6A1B9A,
    0xFF8D6E63 to 0xFF4E342E
)
