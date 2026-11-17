package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.stats.PlaybackStatsRepository.PlaybackStatsSummary
import com.pisces312.piscesplayer.data.stats.PlaybackStatsRepository.TimelineEntry
import com.pisces312.piscesplayer.data.stats.StatsTimeRange
import com.pisces312.piscesplayer.utils.formatListeningDurationCompact
import com.pisces312.piscesplayer.utils.formatListeningDurationLong

/**
 * Overview content for the listening-stats section: hero duration, quick
 * stats, top track and the mini timeline. Renders bare — the surrounding
 * section card provides the rounded container and padding, and the section
 * header already carries the "Listening Stats" title, so the former inner
 * title row and card wrapper were dropped (UI review, 2026-11-17).
 */
@Composable
fun ListeningStatsOverview(
    summary: PlaybackStatsSummary?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (summary == null) {
            OverviewPlaceholder()
        } else {
            OverviewContent(summary)
        }
    }
}

@Composable
private fun OverviewContent(summary: PlaybackStatsSummary) {
    Text(
        text = formatListeningDurationLong(summary.totalDurationMs),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.presentation_batch_g_stats_overview_total_plays),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = summary.totalPlayCount.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.presentation_batch_g_stats_overview_avg_per_day),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatListeningDurationCompact(summary.averageDailyDurationMs),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    summary.topSongs.firstOrNull()?.let { topTrack ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.presentation_batch_g_stats_overview_top_track),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = topTrack.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.presentation_batch_g_stats_overview_top_track_line,
                    topTrack.artist,
                    topTrack.playCount
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    MiniListeningTimeline(summary)
}

@Composable
private fun OverviewPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaceholderLine(width = 140.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            PlaceholderLine(width = 64.dp)
            PlaceholderLine(width = 64.dp)
        }
        PlaceholderLine(width = 128.dp)
        MiniListeningTimeline(summary = null)
    }
}

@Composable
private fun MiniListeningTimeline(summary: PlaybackStatsSummary?) {
    val timeline = summary?.timeline.orEmpty()
    if (summary?.range == StatsTimeRange.MONTH && timeline.isNotEmpty()) {
        MonthlyMiniTimeline(timeline)
        return
    }
    val maxDuration = timeline.maxOfOrNull { it.totalDurationMs }?.takeIf { it > 0 } ?: 1L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val entries = timeline.takeLast(7).ifEmpty { List(5) { null } }
        entries.forEach { entry ->
            val fraction = entry
                ?.let { it.totalDurationMs.toFloat() / maxDuration.toFloat() }
                ?.coerceIn(0f, 1f)
                ?: 0.1f
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((70.dp.value * fraction).coerceAtLeast(10f).dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = entry?.label.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonthlyMiniTimeline(timeline: List<TimelineEntry>) {
    val maxDuration = timeline.maxOfOrNull { it.totalDurationMs }?.takeIf { it > 0 } ?: 1L
    val visibleEntries = timeline.takeLast(4)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        visibleEntries.forEach { entry ->
            val fraction = (entry.totalDurationMs.toFloat() / maxDuration.toFloat())
                .coerceIn(0f, 1f)
                .takeIf { it > 0f }
                ?: 0.06f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.label,
                    modifier = Modifier.width(56.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderLine(width: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(18.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    )
}
