package com.pisces312.piscesplayer.presentation.screens

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.stats.PlaybackStatsRepository.PlaybackStatsSummary
import com.pisces312.piscesplayer.presentation.components.ListeningStatsOverviewCard
import com.pisces312.piscesplayer.presentation.components.MiniPlayerHeight
import com.pisces312.piscesplayer.presentation.components.RecentlyPlayedSection
import com.pisces312.piscesplayer.presentation.components.SmartImage
import com.pisces312.piscesplayer.presentation.components.StatsRangeSelector
import com.pisces312.piscesplayer.presentation.model.rememberRecentlyPlayedHomeState
import com.pisces312.piscesplayer.presentation.navigation.Screen
import com.pisces312.piscesplayer.presentation.navigation.navigateSafely
import com.pisces312.piscesplayer.presentation.viewmodel.PlayerViewModel
import com.pisces312.piscesplayer.presentation.viewmodel.StatsViewModel
import com.pisces312.piscesplayer.utils.formatListeningDurationCompact
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val StatsRankingItemCount = 8

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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    com.pisces312.piscesplayer.presentation.components.RootTabScrollToTopEffect(
        playerViewModel = playerViewModel,
        route = com.pisces312.piscesplayer.presentation.navigation.Screen.Stats.route,
        listState = listState
    )

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
                        statsViewModel.resolveArtistId(artist)?.let { artistId ->
                            navController.navigateSafely(Screen.ArtistDetail.createRoute(artistId))
                        }
                    },
                    onAlbumClick = { album ->
                        statsViewModel.resolveAlbumId(album)?.let { albumId ->
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

@Composable
private fun StatsRankings(
    summary: PlaybackStatsSummary?,
    onSongClick: (songId: String, queueSongIds: List<String>) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onShowAllSongs: () -> Unit,
    onShowAllArtists: () -> Unit,
    onShowAllAlbums: () -> Unit
) {
    if (summary == null) return
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        RankingSection(
            title = stringResource(R.string.presentation_batch_g_stats_hot_songs),
            items = summary.songs.take(StatsRankingItemCount),
            headline = { it.title },
            supporting = {
                it.artist
            },
            trailing = { stringResource(R.string.presentation_batch_g_stats_n_plays, it.playCount) },
            leadingIcon = Icons.Rounded.MusicNote,
            coverArtUri = { it.albumArtUri },
            onClick = { onSongClick(it.songId, summary.songs.map { song -> song.songId }) },
            showMoreLabel = showAllLabel(
                totalCount = summary.songs.size,
                labelRes = R.string.presentation_batch_g_stats_hot_songs_show_more
            ),
            onShowMore = onShowAllSongs
        )
        RankingSection(
            title = stringResource(R.string.presentation_batch_g_stats_section_top_artists),
            items = summary.topArtists.take(StatsRankingItemCount),
            headline = { it.artist },
            supporting = {
                stringResource(
                    R.string.presentation_batch_g_stats_plays_artists,
                    it.playCount,
                    it.uniqueSongs
                )
            },
            trailing = { formatListeningDurationCompact(it.totalDurationMs) },
            leadingIcon = Icons.Rounded.Person,
            coverArtUri = null,
            onClick = { onArtistClick(it.artist) },
            showMoreLabel = showAllLabel(
                totalCount = summary.topArtists.size,
                labelRes = R.string.presentation_batch_g_stats_hot_artists_show_more
            ),
            onShowMore = onShowAllArtists
        )
        RankingSection(
            title = stringResource(R.string.presentation_batch_g_stats_section_top_albums),
            items = summary.topAlbums.take(StatsRankingItemCount),
            headline = { it.album },
            supporting = {
                stringResource(
                    R.string.presentation_batch_g_stats_plays_tracks,
                    it.playCount,
                    it.uniqueSongs
                )
            },
            trailing = { formatListeningDurationCompact(it.totalDurationMs) },
            leadingIcon = Icons.Rounded.Album,
            coverArtUri = { it.albumArtUri },
            onClick = { onAlbumClick(it.album) },
            showMoreLabel = showAllLabel(
                totalCount = summary.topAlbums.size,
                labelRes = R.string.presentation_batch_g_stats_hot_albums_show_more
            ),
            onShowMore = onShowAllAlbums
        )
    }
}

/** Label for a "show all" button, or null when the ranking already fits on the main screen. */
@Composable
private fun showAllLabel(@StringRes labelRes: Int, totalCount: Int): String? =
    if (totalCount > StatsRankingItemCount) stringResource(labelRes, totalCount) else null

@Composable
private fun <T> RankingSection(
    title: String,
    items: List<T>,
    headline: @Composable (T) -> String,
    supporting: @Composable (T) -> String,
    trailing: @Composable (T) -> String,
    leadingIcon: ImageVector,
    coverArtUri: ((T) -> String?)?,
    onClick: (T) -> Unit,
    showMoreLabel: String? = null,
    onShowMore: (() -> Unit)? = null
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                RankingRow(
                    title = headline(item),
                    subtitle = supporting(item),
                    trailing = trailing(item),
                    leadingIcon = leadingIcon,
                    coverArtUri = coverArtUri?.invoke(item),
                    onClick = { onClick(item) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (showMoreLabel != null && onShowMore != null) {
            FilledTonalButton(
                onClick = onShowMore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = showMoreLabel)
            }
        }
    }
}

@Composable
private fun RankingRow(
    title: String,
    subtitle: String,
    trailing: String,
    leadingIcon: ImageVector,
    coverArtUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (coverArtUri != null) {
                SmartImage(
                    model = coverArtUri,
                    contentDescription = title,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    shape = RoundedCornerShape(10.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
