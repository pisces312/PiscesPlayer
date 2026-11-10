package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.Song
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MergedRecentlyPlayedSongItem(
    item: MergedRecentlyPlayedSong,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMoreOptionsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = when {
        isCurrentSong && isPlaying -> MaterialTheme.colorScheme.primaryContainer
        isCurrentSong -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = when {
        isCurrentSong && isPlaying -> MaterialTheme.colorScheme.onPrimaryContainer
        isCurrentSong -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val supportingColor = contentColor.copy(alpha = 0.76f)
    // Read the locale from an observable source so a locale change recomposes the timestamp.
    val locale = LocalLocale.current.platformLocale
    val timeFormatter = remember(locale) { DateTimeFormatter.ofPattern("HH:mm", locale) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = item.song.albumArtUriString,
                contentDescription = item.song.title,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.song.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.song.displayArtist} · ${timeFormatter.format(Instant.ofEpochMilli(item.lastPlayedTimestamp).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = supportingColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.playCount > 1) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = supportingColor
                ) {
                    Text(
                        text = "×${item.playCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = supportingColor
                    )
                }
            }
            FilledIconButton(
                onClick = onMoreOptionsClick,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = contentColor
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.presentation_batch_b_more_options)
                )
            }
        }
    }
}

data class MergedRecentlyPlayedSong(
    val song: Song,
    val lastPlayedTimestamp: Long,
    val playCount: Int,
    val totalDurationMs: Long
)
