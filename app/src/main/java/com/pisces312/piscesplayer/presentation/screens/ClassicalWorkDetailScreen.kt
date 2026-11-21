@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pisces312.piscesplayer.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.classical.ClassicalDisplay
import com.pisces312.piscesplayer.data.classical.ClassicalDisplayNames
import com.pisces312.piscesplayer.presentation.components.CollapsibleCommonTopBar
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.viewmodel.ClassicalLibraryViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.ClassicalWorkSetSection
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel

/**
 * 「古典熏陶 · 作品集」详情页（「作品集」tab 点入，plan v3 §0）：某作品集的曲目按
 * **作品节点**（乐章/编曲）分组——多曲目节点显示子组头 + 乐章行，单曲目节点是裸行。
 * 这是全应用唯一保留「作品节点」层的页面（作曲家详情已回单层）。
 *
 * 页头 CollapsibleCommonTopBar：作品名大字 + 「作曲家 · Op. · N 首」+ 播放全部；
 * 搜索命中节点名展开该节点、命中曲目名展开到行。
 */
@Composable
fun ClassicalWorkDetailScreen(
    playerViewModel: PlayerViewModel,
    albumId: Long,
    workKey: String,
    paddingValues: PaddingValues,
    onBack: () -> Unit
) {
    val viewModel: ClassicalLibraryViewModel = hiltViewModel()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val songs by viewModel.classicalSongs.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    val listBottomPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp

    val album = albums.firstOrNull { it.id == albumId }
    val composerName = album?.let {
        viewModel.nameCatalog.albumName(it.id)?.let { names ->
            ClassicalDisplay.resolve(names, translate = translationEnabled).primary
        } ?: it.artist
    } ?: ""

    val sections = remember(songs, albumId) { viewModel.workSetSections(albumId, songs) }
    val section = sections.firstOrNull { it.work.key == workKey }
    val filteredSection = remember(section, query) {
        section?.let { filterSetSection(it, viewModel, query) }
    }

    val workNames: ClassicalDisplayNames? = section?.let {
        ClassicalDisplay.resolve(it.work.names, translate = translationEnabled)
    }
    val title = workNames?.primary ?: workKey
    val allTracks = section?.nodes?.flatMap { it.tracks }.orEmpty()
    val subtitle = section?.let {
        listOfNotNull(
            composerName.ifBlank { null },
            it.work.opus.ifBlank { null },
            stringResource(R.string.classical_card_track_count_format, allTracks.size)
        ).joinToString(" · ")
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            CollapsibleCommonTopBar(
                title = title,
                subtitle = subtitle,
                collapseFraction = 0f,
                headerHeight = 112.dp,
                onBackClick = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            allTracks.firstOrNull()?.let { first ->
                                playerViewModel.playSongs(
                                    allTracks.map(viewModel::playbackTarget),
                                    viewModel.playbackTarget(first),
                                    queueName = title
                                )
                            }
                        }
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.classical_tab_play_all),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )

            ClassicalSearchField(
                query = query,
                onQueryChange = { query = it },
                hint = stringResource(R.string.classical_search_tracks_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (filteredSection == null || filteredSection.nodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = listBottomPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(
                            if (section == null) R.string.classical_works_empty
                            else R.string.classical_no_search_results
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(top = 4.dp, bottom = listBottomPadding),
                    modifier = Modifier.fillMaxSize()
                ) {
                    filteredSection.nodes.forEach { node ->
                        if (node.node.isMultiTrack) {
                            val nodeQueueName = ClassicalDisplay
                                .resolve(node.node.names, translate = translationEnabled).primary
                            item(key = "node-${node.node.key}") {
                                WorkNodeHeader(
                                    node = node,
                                    translationEnabled = translationEnabled,
                                    onPlayAll = {
                                        node.tracks.firstOrNull()?.let { first ->
                                            playerViewModel.playSongs(
                                                node.tracks.map(viewModel::playbackTarget),
                                                viewModel.playbackTarget(first),
                                                queueName = nodeQueueName
                                            )
                                        }
                                    }
                                )
                            }
                            items(node.tracks, key = { it.id }) { song ->
                                WorkTrackRow(
                                    song = song,
                                    viewModel = viewModel,
                                    translationEnabled = translationEnabled,
                                    favoriteTracks = favoriteTracks,
                                    onClick = {
                                        playerViewModel.playSongs(
                                            node.tracks.map(viewModel::playbackTarget),
                                            viewModel.playbackTarget(song),
                                            queueName = nodeQueueName
                                        )
                                    }
                                )
                            }
                        } else {
                            val song = node.tracks.first()
                            item(key = "single-${node.node.key}") {
                                WorkTrackRow(
                                    song = song,
                                    viewModel = viewModel,
                                    translationEnabled = translationEnabled,
                                    favoriteTracks = favoriteTracks,
                                    onClick = {
                                        playerViewModel.playSongs(
                                            allTracks.map(viewModel::playbackTarget),
                                            viewModel.playbackTarget(song),
                                            queueName = title
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 搜索过滤：作品名/opus 命中 → 整组；节点名命中 → 该节点；否则组内只保留命中曲目。 */
private fun filterSetSection(
    section: ClassicalWorkSetSection,
    viewModel: ClassicalLibraryViewModel,
    query: String
): ClassicalWorkSetSection {
    if (query.isBlank()) return section
    val workMatch = namesMatchQuery(
        names = section.work.names,
        fallback = section.work.opus,
        query = query
    )
    if (workMatch) return section
    val matchedNodes = section.nodes.mapNotNull { node ->
        if (namesMatchQuery(names = node.node.names, fallback = "", query = query)) {
            node
        } else {
            val matchedTracks = node.tracks.filter { song ->
                namesMatchQuery(
                    names = viewModel.nameCatalog.songName(song.id),
                    fallback = song.title,
                    query = query
                )
            }
            if (matchedTracks.isEmpty()) null
            else com.pisces312.piscesplayer.presentation.viewmodel.ClassicalWorkSetNode(
                node.node,
                matchedTracks
            )
        }
    }
    return ClassicalWorkSetSection(section.work, matchedNodes)
}
