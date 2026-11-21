@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pisces312.piscesplayer.presentation.midilibrary

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
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
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.screens.ClassicalSearchField
import com.pisces312.piscesplayer.presentation.screens.ClassicalTrackRow
import com.pisces312.piscesplayer.presentation.screens.namesMatchQuery
import com.pisces312.piscesplayer.presentation.screens.trackDisplayNames
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel

/**
 * 「MIDI 曲库 · 内置 MIDI」：古典熏陶之外的内置曲目（赞美诗/艺术歌曲与民歌/爵士/
 * 练习曲/进行曲/舞曲），按风格分节 + 搜索 + 收藏。见 plan §B。
 */
@Composable
fun MidiLibraryScreen(
    playerViewModel: PlayerViewModel,
    paddingValues: PaddingValues,
    onBack: () -> Unit
) {
    val viewModel: MidiLibraryViewModel = hiltViewModel()
    val allSongs by viewModel.builtInSongs.collectAsStateWithLifecycle()
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    val queueName = stringResource(R.string.midi_library_title)
    val listBottomPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp

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
                        text = stringResource(
                            if (query.isBlank()) R.string.midi_builtin_empty
                            else R.string.classical_no_search_results
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = listBottomPadding),
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
                            text = stringResource(R.string.classical_card_track_count_format, allSongs.size),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                allSongs.firstOrNull()?.let { first ->
                                    playerViewModel.playSongs(allSongs, first, queueName = queueName)
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
