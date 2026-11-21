@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pisces312.piscesplayer.presentation.midilibrary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.screens.ClassicalSearchField
import com.pisces312.piscesplayer.presentation.screens.ClassicalTrackRow
import com.pisces312.piscesplayer.presentation.screens.namesMatchQuery
import com.pisces312.piscesplayer.presentation.screens.trackDisplayNames
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel

/**
 * 「MIDI 曲库」(Music learning → MIDI library)，双 tab（plan §B/§C）：
 *
 * - **内置 MIDI**：古典熏陶之外的内置曲目（赞美诗/艺术歌曲与民歌/爵士/练习曲/进行曲/舞曲），
 *   按风格分节 + 搜索 + 收藏；
 * - **我的 MIDI**：SAF 扫描目录 + 单曲添加（引用原文件），按目录分节 + 搜索，
 *   收藏走全局 Liked。
 *
 * Tab 切换与曲库同款（PrimaryTabRow + HorizontalPager 双向同步、可滑动；
 * 两页搜索状态互相独立）。
 */
@Composable
fun MidiLibraryScreen(
    playerViewModel: PlayerViewModel,
    paddingValues: PaddingValues,
    onBack: () -> Unit
) {
    val viewModel: MidiLibraryViewModel = hiltViewModel()
    val pagerState = rememberPagerState(pageCount = { 2 })
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
    }
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) {
            pagerState.animateScrollToPage(selectedTab)
        }
    }

    val queueName = stringResource(R.string.midi_library_title)
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
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.midi_tab_builtin)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.midi_tab_mine)) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> BuiltinMidiTab(
                        viewModel = viewModel,
                        playerViewModel = playerViewModel,
                        query = "",
                        queueName = queueName,
                        bottomPadding = listBottomPadding
                    )
                    else -> MyMidiTab(
                        viewModel = viewModel,
                        playerViewModel = playerViewModel,
                        query = "",
                        queueName = queueName,
                        bottomPadding = listBottomPadding
                    )
                }
            }
        }
    }
}

// ---- 内置 MIDI -----------------------------------------------------------------

@Composable
private fun BuiltinMidiTab(
    viewModel: MidiLibraryViewModel,
    playerViewModel: PlayerViewModel,
    query: String,
    queueName: String,
    bottomPadding: Dp
) {
    var query by rememberSaveable { mutableStateOf(query) }
    Column(modifier = Modifier.fillMaxSize()) {
        ClassicalSearchField(
            query = query,
            onQueryChange = { query = it },
            hint = stringResource(R.string.classical_search_tracks_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        BuiltinMidiTabBody(
            viewModel = viewModel,
            playerViewModel = playerViewModel,
            query = query,
            queueName = queueName,
            bottomPadding = bottomPadding
        )
    }
}

@Composable
private fun BuiltinMidiTabBody(
    viewModel: MidiLibraryViewModel,
    playerViewModel: PlayerViewModel,
    query: String,
    queueName: String,
    bottomPadding: Dp
) {
    val allSongs by viewModel.builtInSongs.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()

    val sections = remember(allSongs, query) {
        val filtered = allSongs.filter { song ->
            namesMatchQuery(
                names = viewModel.nameCatalog.songName(song.id),
                fallback = song.title + " " + song.artist,
                query = query
            )
        }
        viewModel.sections(filtered)
    }

    if (sections.isEmpty()) {
        MidiEmptyHint(
            text = stringResource(
                if (query.isBlank()) R.string.midi_builtin_empty
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
            MidiListHeader(
                summary = stringResource(R.string.classical_card_track_count_format, allSongs.size),
                onPlayAll = {
                    allSongs.firstOrNull()?.let { first ->
                        playerViewModel.playSongs(allSongs, first, queueName = queueName)
                    }
                }
            )
        }
        sections.forEach { section ->
            item(key = "section-${section.styleKey}") {
                val sectionLabel = stringResource(styleLabelRes(section.styleKey))
                MidiSectionHeader(
                    label = sectionLabel,
                    trackCount = section.tracks.size,
                    onPlayAll = {
                        section.tracks.firstOrNull()?.let { first ->
                            playerViewModel.playSongs(section.tracks, first, queueName = sectionLabel)
                        }
                    }
                )
            }
            items(section.tracks, key = { it.id }) { song ->
                val sectionLabel = stringResource(styleLabelRes(section.styleKey))
                ClassicalTrackRow(
                    title = trackDisplayNames(song, viewModel.nameCatalog, translationEnabled),
                    subtitle = song.artist.takeIf { it.isNotBlank() },
                    isFavorite = favoriteTracks.containsKey(song.id),
                    onToggleFavorite = { viewModel.toggleFavoriteTrack(song.id) },
                    onClick = {
                        playerViewModel.playSongs(section.tracks, song, queueName = sectionLabel)
                    }
                )
            }
        }
    }
}

// ---- 我的 MIDI -----------------------------------------------------------------

@Composable
private fun MyMidiTab(
    viewModel: MidiLibraryViewModel,
    playerViewModel: PlayerViewModel,
    query: String,
    queueName: String,
    bottomPadding: Dp
) {
    var query by rememberSaveable { mutableStateOf(query) }
    Column(modifier = Modifier.fillMaxSize()) {
        ClassicalSearchField(
            query = query,
            onQueryChange = { query = it },
            hint = stringResource(R.string.classical_search_tracks_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        MyMidiTabBody(
            viewModel = viewModel,
            playerViewModel = playerViewModel,
            query = query,
            queueName = queueName,
            bottomPadding = bottomPadding
        )
    }
}

@Composable
private fun MyMidiTabBody(
    viewModel: MidiLibraryViewModel,
    playerViewModel: PlayerViewModel,
    query: String,
    queueName: String,
    bottomPadding: Dp
) {
    val directories by viewModel.directories.collectAsStateWithLifecycle()
    val userSongs by viewModel.userSongs.collectAsStateWithLifecycle()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle()

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::addDirectory) }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::addSingleFile) }
    val openMimeTypes = remember { arrayOf("audio/midi", "audio/mid", "application/x-midi", "*/*") }

    val filteredSongs = remember(userSongs, query) {
        if (query.isBlank()) userSongs else userSongs.filter {
            namesMatchQuery(names = null, fallback = it.title + " " + it.album, query = query)
        }
    }
    val grouped = remember(filteredSongs) {
        filteredSongs.groupBy { it.album.ifBlank { "" } }
    }

    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { treeLauncher.launch(null) }) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.midi_add_directory))
                }
                OutlinedButton(onClick = { fileLauncher.launch(openMimeTypes) }) {
                    Icon(Icons.Rounded.NoteAdd, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.midi_add_file))
                }
            }
        }

        if (directories.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.midi_directories_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.rescanAll() }) {
                        Text(stringResource(R.string.midi_rescan))
                    }
                }
            }
            items(directories, key = { it.uri }) { dir ->
                MidiDirectoryRow(
                    name = dir.displayName,
                    summary = stringResource(
                        R.string.classical_card_track_count_format,
                        dir.trackCount
                    ) + if (dir.needsReauth) {
                        " · " + stringResource(R.string.midi_needs_reauth)
                    } else {
                        ""
                    },
                    onRemove = { viewModel.removeDirectory(dir.uri) }
                )
            }
        }

        if (filteredSongs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp, horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            if (query.isBlank()) R.string.midi_mine_empty
                            else R.string.classical_no_search_results
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            grouped.forEach { (dirName, tracks) ->
                item(key = "dir-$dirName") {
                    MidiSectionHeader(
                        label = dirName.ifBlank { stringResource(R.string.midi_files_section_singles) },
                        trackCount = tracks.size,
                        onPlayAll = {
                            tracks.firstOrNull()?.let { first ->
                                playerViewModel.playSongs(tracks, first, queueName = queueName)
                            }
                        }
                    )
                }
                items(tracks, key = { it.id }) { song ->
                    val isFavorite = favoriteIds.contains(song.id)
                    ClassicalTrackRow(
                        title = trackDisplayNames(song, viewModel.nameCatalog, translationEnabled = false),
                        subtitle = song.album.takeIf { it.isNotBlank() },
                        isFavorite = isFavorite,
                        onToggleFavorite = {
                            playerViewModel.toggleFavoriteSpecificSong(song, removing = isFavorite)
                        },
                        onClick = {
                            playerViewModel.playSongs(tracks, song, queueName = queueName)
                        }
                    )
                }
            }
        }
    }
}

// ---- shared bits --------------------------------------------------------------

@Composable
private fun MidiListHeader(
    summary: String,
    onPlayAll: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = summary,
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
private fun MidiSectionHeader(
    label: String,
    trackCount: Int,
    onPlayAll: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 2.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.classical_card_track_count_format, trackCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        FilledTonalButton(
            onClick = onPlayAll,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.classical_tab_play_all))
        }
    }
}

@Composable
private fun MidiDirectoryRow(
    name: String,
    summary: String,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.midi_remove_directory))
        }
    }
}

@Composable
private fun MidiEmptyHint(text: String, bottomPadding: Dp) {
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

@StringRes
internal fun styleLabelRes(styleKey: String): Int = when (styleKey) {
    "Hymn" -> R.string.midi_style_hymn
    "Song" -> R.string.midi_style_song
    "Jazz" -> R.string.midi_style_jazz
    "Technique" -> R.string.midi_style_technique
    "March" -> R.string.midi_style_march
    "Popular / Dance" -> R.string.midi_style_dance
    else -> R.string.midi_style_other
}
