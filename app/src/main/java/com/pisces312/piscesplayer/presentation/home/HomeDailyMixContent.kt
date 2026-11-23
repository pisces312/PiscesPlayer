package com.pisces312.piscesplayer.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.home.HomeCard
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.components.HomeSectionHeader
import com.pisces312.piscesplayer.presentation.components.SmartImage
import kotlinx.collections.immutable.ImmutableList
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/** Capsules shown in the 2×2 card; the rest are reachable via the view-all action. */
private const val CAPSULE_PREVIEW_COUNT = 10

/**
 * The 每日推荐 card body for the home grid (plan §12 decisions 9/16/21/23) —
 * hero header + song-capsule row. v1.3.4: shuffle play is the only header action
 * (48dp circle, primary while active);「查看全部」moved to a card-body tap, which
 * freed the middle band: capsules are vertically centred and the song name may
 * wrap to two lines.
 *
 * - 2×1: only the hero header row (title + subtitle + shuffle).
 * - 2×2: header, then the capsule row centred in the remaining space.
 */
@Composable
fun HomeDailyMixContent(
    card: HomeCard,
    songs: ImmutableList<Song>,
    isShuffleEnabled: Boolean,
    onPlayShuffled: () -> Unit,
    onSongClick: (Song) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tall = card.size.spanY == 2

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        HomeSectionHeader(
            title = stringResource(R.string.presentation_batch_b_daily_mix_title),
            subtitle = stringResource(R.string.home_your_mix_subtitle),
            // v1.3.3: every card title carries a leading icon, like the AI playlist card.
            icon = Icons.Rounded.Recommend,
            // The grid card already pads 16dp.
            contentPadding = PaddingValues(0.dp),
            action = {
                // v1.3.4: the only card action — view-all moved to a card-body tap, so
                // shuffle plays the corner alone (48dp circle, primary while active).
                FilledTonalIconButton(
                    onClick = onPlayShuffled,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isShuffleEnabled) colors.primary else colors.tertiaryContainer,
                        contentColor = if (isShuffleEnabled) colors.onPrimary else colors.onTertiaryContainer,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_shuffle_24),
                        contentDescription = stringResource(R.string.cd_shuffle_play),
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        )

        if (tall) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(songs.take(CAPSULE_PREVIEW_COUNT), key = { it.id }) { song ->
                        DailyMixSongCapsule(song = song, onClick = { onSongClick(song) })
                    }
                }
            }
        }
    }
}

/** One song pill: 40dp rounded cover + a two-line-capable song name over the artist. */
@Composable
private fun DailyMixSongCapsule(song: Song, onClick: () -> Unit) {
    val capsuleShape = AbsoluteSmoothCornerShape(
        cornerRadiusTL = 20.dp,
        smoothnessAsPercentTL = 60,
        cornerRadiusTR = 20.dp,
        smoothnessAsPercentTR = 60,
        cornerRadiusBL = 20.dp,
        smoothnessAsPercentBL = 60,
        cornerRadiusBR = 20.dp,
        smoothnessAsPercentBR = 60,
    )
    Surface(
        onClick = onClick,
        shape = capsuleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmartImage(
                model = song.albumArtUriString,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(AbsoluteSmoothCornerShape(
                        cornerRadiusTL = 10.dp,
                        smoothnessAsPercentTL = 60,
                        cornerRadiusTR = 10.dp,
                        smoothnessAsPercentTR = 60,
                        cornerRadiusBL = 10.dp,
                        smoothnessAsPercentBL = 60,
                        cornerRadiusBR = 10.dp,
                        smoothnessAsPercentBR = 60,
                    )),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    // v1.3.3: the actions moved out of the card body, so the name
                    // can take two lines instead of truncating at one.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.displayArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
