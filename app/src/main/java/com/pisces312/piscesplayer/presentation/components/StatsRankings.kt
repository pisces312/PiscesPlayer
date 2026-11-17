package com.pisces312.piscesplayer.presentation.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.stats.PlaybackStatsRepository.PlaybackStatsSummary
import com.pisces312.piscesplayer.utils.formatListeningDurationCompact

const val StatsRankingItemCount = 8

/**
 * 热歌 / 艺人 / 专辑三个排行区块，渲染在搜索 idle 页收听统计卡内。
 * 区块各自在数据为空时整体隐藏。
 */
@Composable
fun StatsRankings(
    summary: PlaybackStatsSummary?,
    onSongClick: (songId: String, queueSongIds: List<String>) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onShowAllSongs: () -> Unit,
    onShowAllArtists: () -> Unit,
    onShowAllAlbums: () -> Unit
) {
    if (summary == null) return
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
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
