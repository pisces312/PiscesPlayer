package com.pisces312.piscesplayer.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.presentation.components.ListeningStatsOverviewCard
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.components.RecentlyPlayedSection
import com.pisces312.piscesplayer.presentation.components.StatsRangeSelector
import com.pisces312.piscesplayer.presentation.components.StatsRankings
import com.pisces312.piscesplayer.presentation.model.rememberRecentlyPlayedHomeState
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.StatsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 完整统计子页（从搜索页顶栏进入）。内容 = 最近播放 + 周期选择 + 概览卡 + 排行；
 * 与搜索 idle 页的排行区块共用 [StatsRankings]。
 * 注意：本页与搜索 idle 页的排行各自用不同 StatsViewModel 实例（本页按路由各自 hiltViewModel），
 * 在本页改周期不会影响 idle 的排行 —— 这是有意设计，勿当 bug。
 */
@Composable
fun ListeningStatsScreen(
    navController: NavController,
    paddingValuesParent: PaddingValues,
    playerViewModel: PlayerViewModel,
    statsViewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by statsViewModel.uiState.collectAsStateWithLifecycle()
    val summary = uiState.summary
    val hotSongsQueueName = stringResource(R.string.presentation_batch_g_stats_hot_songs)
    val recentState = rememberRecentlyPlayedHomeState(playerViewModel)
    val currentSongId by remember(playerViewModel.stablePlayerState) {
        playerViewModel.stablePlayerState.map { it.currentSong?.id }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val listState = rememberLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp,
                bottom = paddingValuesParent.calculateBottomPadding() +
                    MiniPlayerHeight +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            item(key = "stats_recently_played", contentType = "recently_played_section") {
                RecentlyPlayedSection(
                    songs = recentState.songs,
                    onSongClick = { song ->
                        if (recentState.queue.isNotEmpty()) {
                            playerViewModel.playSongs(
                                songsToPlay = recentState.queue,
                                startSong = song,
                                queueName = "Recently Played"
                            )
                        }
                    },
                    onOpenAllClick = {
                        navController.navigateSafely(Screen.RecentlyPlayed.route)
                    },
                    themeStateHolder = playerViewModel.themeStateHolder,
                    currentSongId = currentSongId,
                    contentPadding = PaddingValues(start = 8.dp, end = 24.dp)
                )
            }
            item(key = "stats_overview", contentType = "listening_stats_overview") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatsRangeSelector(
                        selected = uiState.selectedRange,
                        onRangeSelected = statsViewModel::onRangeSelected,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    ListeningStatsOverviewCard(summary = summary)
                }
            }
            item(key = "stats_rankings", contentType = "listening_stats_rankings") {
                StatsRankings(
                    summary = summary,
                    onSongClick = { songId, queueSongIds ->
                        playerViewModel.playSongByIds(
                            startSongId = songId,
                            queueSongIds = queueSongIds,
                            queueName = hotSongsQueueName
                        )
                    },
                    onArtistClick = { artist ->
                        statsViewModel.resolveArtistId(artist) { artistId ->
                            navController.navigateSafely(Screen.ArtistDetail.createRoute(artistId))
                        }
                    },
                    onAlbumClick = { album ->
                        statsViewModel.resolveAlbumId(album) { albumId ->
                            navController.navigateSafely(Screen.AlbumDetail.createRoute(albumId))
                        }
                    },
                    onShowAllSongs = {
                        navController.navigateSafely(Screen.StatsHotSongs.route)
                    },
                    onShowAllArtists = {
                        navController.navigateSafely(Screen.StatsTopArtists.route)
                    },
                    onShowAllAlbums = {
                        navController.navigateSafely(Screen.StatsTopAlbums.route)
                    }
                )
            }
        }
        StatsTopBar(
            isRefreshing = uiState.isLoading || uiState.isRefreshing,
            onRefresh = statsViewModel::requestStatsRefresh,
            modifier = Modifier.statusBarsPadding()
        )
    }
}

@Composable
private fun StatsTopBar(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.presentation_batch_g_stats_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        FilledIconButton(
            onClick = onRefresh,
            enabled = !isRefreshing,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.presentation_batch_g_stats_cd_refresh)
            )
        }
    }
}
