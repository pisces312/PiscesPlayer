package com.pisces312.piscesplayer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.stats.RankingCoverArt
import com.pisces312.piscesplayer.presentation.stats.SongSortMetric
import com.pisces312.piscesplayer.presentation.stats.StatRankRow
import com.pisces312.piscesplayer.presentation.stats.StatsEmptyState
import com.pisces312.piscesplayer.presentation.stats.StatsSortActions
import com.pisces312.piscesplayer.presentation.viewmodel.StatsViewModel
import com.pisces312.piscesplayer.utils.formatListeningDurationCompact
import kotlinx.collections.immutable.toImmutableList

/**
 * The full album ranking for the current stats period.
 *
 * The main stats screen shows only the leading few so the cards below stay reachable;
 * this screen lists every album the current period produced (capped by the repository).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsTopAlbumsScreen(
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    paddingValues: PaddingValues,
    statsViewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by statsViewModel.uiState.collectAsStateWithLifecycle()
    val summary = uiState.summary
    var sortMetric by rememberSaveable { mutableStateOf(SongSortMetric.DURATION) }

    val albums = remember(summary, sortMetric) {
        val all = summary?.topAlbums.orEmpty()
        when (sortMetric) {
            SongSortMetric.PLAYS -> all.sortedByDescending { it.playCount }
            SongSortMetric.DURATION -> all.sortedByDescending { it.totalDurationMs }
        }.toImmutableList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.presentation_batch_g_stats_section_top_albums)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            actions = {
                StatsSortActions(
                    sortMetric = sortMetric,
                    onSortMetricChange = { sortMetric = it }
                )
            }
        )

        if (albums.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StatsEmptyState(
                    icon = Icons.Outlined.Album,
                    title = stringResource(R.string.presentation_batch_g_stats_empty_no_albums_title),
                    subtitle = stringResource(R.string.presentation_batch_g_stats_empty_no_albums_subtitle)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(albums, key = { it.album }) { entry ->
                    StatRankRow(
                        title = entry.album,
                        subtitle = stringResource(
                            R.string.presentation_batch_g_stats_plays_tracks,
                            entry.playCount,
                            entry.uniqueSongs
                        ),
                        trailing = formatListeningDurationCompact(entry.totalDurationMs),
                        onClick = { onAlbumClick(entry.album) },
                        leading = {
                            RankingCoverArt(
                                albumArtUri = entry.albumArtUri,
                                fallbackIcon = Icons.Outlined.Album,
                                contentDescription = entry.album
                            )
                        }
                    )
                }
            }
        }
    }
}
