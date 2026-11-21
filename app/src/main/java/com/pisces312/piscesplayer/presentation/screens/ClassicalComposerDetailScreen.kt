@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pisces312.piscesplayer.presentation.screens

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.pisces312.piscesplayer.data.classical.ClassicalDisplay
import com.pisces312.piscesplayer.data.classical.ClassicalDisplayNames
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.viewmodel.ClassicalLibraryViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.ClassicalWorkSection
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel

/**
 * 「古典熏陶 · 作品集」页：某作曲家的曲目按作品集（作品/乐章）分组，带搜索条
 * （命中作品名/opus 显示整组，否则组内只显示命中曲目）与曲目收藏星标。见 plan §B3。
 */
@Composable
fun ClassicalComposerDetailScreen(
    playerViewModel: PlayerViewModel,
    albumId: Long,
    paddingValues: PaddingValues,
    onBack: () -> Unit
) {
    val viewModel: ClassicalLibraryViewModel = hiltViewModel()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val songs by viewModel.classicalSongs.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val favoriteComposers by viewModel.favoriteComposers.collectAsStateWithLifecycle()
    val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    val listBottomPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp

    val album = albums.firstOrNull { it.id == albumId }
    val composerName = album?.let {
        viewModel.nameCatalog.albumName(it.id)?.let { names ->
            ClassicalDisplay.resolve(names, translate = translationEnabled).primary
        } ?: it.artist
    } ?: stringResource(R.string.library_tab_classical)

    val allSections = remember(songs, albumId) { viewModel.workSections(albumId, songs) }
    val sections = remember(allSections, query, translationEnabled) {
        filterSections(allSections, viewModel, query)
    }
    val composerSongs = remember(songs, albumId) {
        songs.filter { it.albumId == albumId }.sortedBy { it.id.toLongOrNull() ?: 0L }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(composerName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.auth_cd_back)
                        )
                    }
                },
                actions = {
                    val isFavorite = favoriteComposers.containsKey(albumId.toString())
                    IconButton(onClick = { viewModel.toggleFavoriteComposer(albumId) }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = stringResource(
                                if (isFavorite) R.string.classical_unfavorite else R.string.classical_favorite
                            ),
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            composerSongs.firstOrNull()?.let { first ->
                                playerViewModel.playSongs(
                                    composerSongs.map(viewModel::playbackTarget),
                                    viewModel.playbackTarget(first),
                                    queueName = composerName
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
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ClassicalSearchField(
                query = query,
                onQueryChange = { query = it },
                hint = stringResource(R.string.classical_search_tracks_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (sections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = listBottomPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.classical_no_search_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(top = 4.dp, bottom = listBottomPadding),
                    modifier = Modifier.fillMaxSize()
                ) {
                    sections.forEach { section ->
                        if (section.work.isMultiTrack) {
                            val workQueueName =
                                workDisplayName(section, translationEnabled).primary
                            item(key = "work-${section.work.key}") {
                                WorkHeader(
                                    section = section,
                                    translationEnabled = translationEnabled,
                                    onPlayAll = {
                                        section.tracks.firstOrNull()?.let { first ->
                                            playerViewModel.playSongs(
                                                section.tracks.map(viewModel::playbackTarget),
                                                viewModel.playbackTarget(first),
                                                queueName = workQueueName
                                            )
                                        }
                                    }
                                )
                            }
                            items(section.tracks, key = { it.id }) { song ->
                                WorkTrackRow(
                                    song = song,
                                    viewModel = viewModel,
                                    translationEnabled = translationEnabled,
                                    favoriteTracks = favoriteTracks,
                                    onClick = {
                                        playerViewModel.playSongs(
                                            section.tracks.map(viewModel::playbackTarget),
                                            viewModel.playbackTarget(song),
                                            queueName = workQueueName
                                        )
                                    }
                                )
                            }
                        } else {
                            val song = section.tracks.first()
                            item(key = "single-${section.work.key}") {
                                WorkTrackRow(
                                    song = song,
                                    viewModel = viewModel,
                                    translationEnabled = translationEnabled,
                                    favoriteTracks = favoriteTracks,
                                    onClick = {
                                        playerViewModel.playSongs(
                                            composerSongs.map(viewModel::playbackTarget),
                                            viewModel.playbackTarget(song),
                                            queueName = composerName
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

/** 搜索过滤：作品名/opus 命中 → 整组；否则只保留命中曲目；空查询不过滤。 */
private fun filterSections(
    sections: List<ClassicalWorkSection>,
    viewModel: ClassicalLibraryViewModel,
    query: String
): List<ClassicalWorkSection> {
    if (query.isBlank()) return sections
    return sections.mapNotNull { section ->
        val workMatch = namesMatchQuery(
            names = section.work.names,
            fallback = section.work.opus,
            query = query
        )
        when {
            workMatch -> section
            else -> {
                val matched = section.tracks.filter { song ->
                    namesMatchQuery(
                        names = viewModel.nameCatalog.songName(song.id),
                        fallback = song.title,
                        query = query
                    )
                }
                if (matched.isEmpty()) null
                else ClassicalWorkSection(section.work, matched)
            }
        }
    }
}

private fun workDisplayName(
    section: ClassicalWorkSection,
    translationEnabled: Boolean
): ClassicalDisplayNames = ClassicalDisplay.resolve(section.work.names, translate = translationEnabled)

@Composable
private fun WorkHeader(
    section: ClassicalWorkSection,
    translationEnabled: Boolean,
    onPlayAll: () -> Unit
) {
    val names = workDisplayName(section, translationEnabled)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 2.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = names.primary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = section.work.opus.ifBlank {
                stringResource(R.string.classical_card_track_count_format, section.tracks.size)
            }
            Text(
                text = subtitle,
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
private fun WorkTrackRow(
    song: Song,
    viewModel: ClassicalLibraryViewModel,
    translationEnabled: Boolean,
    favoriteTracks: Map<String, Long>,
    onClick: () -> Unit
) {
    ClassicalTrackRow(
        title = trackDisplayNames(song, viewModel.nameCatalog, translationEnabled),
        subtitle = null,
        isFavorite = favoriteTracks.containsKey(song.id),
        onToggleFavorite = { viewModel.toggleFavoriteTrack(song.id) },
        onClick = onClick
    )
}
