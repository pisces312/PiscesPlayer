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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.navigation.NavController
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.classical.ClassicalDisplay
import com.pisces312.piscesplayer.data.classical.ClassicalDisplayNames
import com.pisces312.piscesplayer.data.model.Album
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.viewmodel.ClassicalLibraryViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel

/**
 * 「古典熏陶」browse screen (Music learning → Classical): two tabs —
 *
 * - **作曲家**：紧凑作曲家列表（收藏置顶）+ 搜索条，点按进入按作品集分组的
 *   [ClassicalComposerDetailScreen]；
 * - **收藏乐曲**：只显示收藏的乐曲（收藏时间倒序）+ 搜索条。
 *
 * 顶部保留原有「播放全部 / 曲名翻译」头部。见 plan §B2。
 */
@Composable
fun ClassicalBrowseScreen(
    playerViewModel: PlayerViewModel,
    navController: NavController,
    paddingValues: PaddingValues,
    onBack: () -> Unit
) {
    val viewModel: ClassicalLibraryViewModel = hiltViewModel()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val trackCount by viewModel.trackCount.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val favoriteComposers by viewModel.favoriteComposers.collectAsStateWithLifecycle()
    val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
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
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.classical_tab_composers)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.classical_tab_favorites)) }
                )
            }
            ClassicalSearchField(
                query = query,
                onQueryChange = { query = it },
                hint = stringResource(
                    if (selectedTab == 0) R.string.classical_search_composers_hint
                    else R.string.classical_search_tracks_hint
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            when (selectedTab) {
                0 -> ComposerList(
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
                    bottomPadding = listBottomPadding
                )
                else -> FavoriteTracksList(
                    viewModel = viewModel,
                    playerViewModel = playerViewModel,
                    songs = songs,
                    translationEnabled = translationEnabled,
                    favoriteTracks = favoriteTracks,
                    query = query,
                    queueName = favoritesQueueName,
                    bottomPadding = listBottomPadding
                )
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
                        playerViewModel.playSongs(songs, first, queueName = queueName)
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
                translationEnabled = translationEnabled,
                isFavorite = false,
                onClick = { navController.navigateSafely(Screen.ClassicalComposer.createRoute(album.id)) }
            )
        }
    }
}

@Composable
private fun FavoriteTracksList(
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.classical_card_track_count_format, favoriteSongs.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = {
                        favoriteSongs.firstOrNull()?.let { first ->
                            playerViewModel.playSongs(favoriteSongs, first, queueName = queueName)
                        }
                    },
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
                    playerViewModel.playSongs(favoriteSongs, song, queueName = queueName)
                }
            )
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
    translationEnabled: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit
) {
    val names = viewModel.nameCatalog.albumName(album.id)?.let {
        ClassicalDisplay.resolve(it, translate = translationEnabled)
    } ?: ClassicalDisplayNames(primary = album.artist.ifBlank { album.title }, subtitle = null)
    val fallback = album.artist.ifBlank { album.title }
    val palette = composerPalette[(fallback.hashCode() and Int.MAX_VALUE) % composerPalette.size]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            text = stringResource(R.string.classical_card_track_count_format, album.songCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = { viewModel.toggleFavoriteComposer(album.id) }) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = stringResource(
                    if (isFavorite) R.string.classical_unfavorite else R.string.classical_favorite
                ),
                tint = if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
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
