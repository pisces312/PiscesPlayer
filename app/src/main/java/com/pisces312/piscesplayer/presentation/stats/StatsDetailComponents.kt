package com.pisces312.piscesplayer.presentation.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.pisces312.piscesplayer.presentation.components.SmartImage
import com.pisces312.piscesplayer.utils.formatListeningDurationCompact

/**
 * Shared building blocks for the stats rankings and their "show all" detail screens.
 *
 * The detail screens live in `presentation.screens`, so these are `internal` rather than private.
 */

/** How the "show all" detail screens order their entries. */
internal enum class SongSortMetric {
    PLAYS,
    DURATION
}

/** The plays/duration toggle pair shown in the top bar of every "show all" detail screen. */
@Composable
internal fun StatsSortActions(
    sortMetric: SongSortMetric,
    onSortMetricChange: (SongSortMetric) -> Unit
) {
    Row(
        modifier = Modifier.padding(end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SortToggleButton(
            label = stringResource(R.string.presentation_batch_g_stats_sort_plays),
            selected = sortMetric == SongSortMetric.PLAYS,
            onClick = { onSortMetricChange(SongSortMetric.PLAYS) }
        )
        SortToggleButton(
            label = stringResource(R.string.presentation_batch_g_stats_sort_duration),
            selected = sortMetric == SongSortMetric.DURATION,
            onClick = { onSortMetricChange(SongSortMetric.DURATION) }
        )
    }
}

@Composable
internal fun SortToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        onClick = onClick
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

/**
 * One ranking row, shaped like a library row: flat surface, library spacing, 10dp corners.
 * The artist/album lists on the stats screen and the hot songs detail screen all share it.
 */
@Composable
internal fun StatRankRow(
    title: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (leading != null) {
                leading()
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
        }
    }
}

@Composable
internal fun SongRow(
    title: String,
    artist: String,
    albumArtUri: String?,
    playCount: Int,
    totalDurationMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    StatRankRow(
        title = title,
        subtitle = if (artist.isNotBlank()) {
            "$artist · ×$playCount"
        } else {
            "×$playCount"
        },
        trailing = formatListeningDurationCompact(totalDurationMs),
        onClick = onClick,
        leading = {
            SmartImage(
                model = albumArtUri,
                contentDescription = title,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp)
            )
        },
        modifier = modifier
    )
}

/** Cover art slot for artist/album rows; artists fall back to a placeholder tile. */
@Composable
internal fun RankingCoverArt(
    albumArtUri: String?,
    fallbackIcon: ImageVector,
    contentDescription: String
) {
    if (albumArtUri != null) {
        SmartImage(
            model = albumArtUri,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
internal fun StatsEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
