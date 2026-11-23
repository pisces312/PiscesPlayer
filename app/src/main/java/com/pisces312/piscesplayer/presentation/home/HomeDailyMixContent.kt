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
import androidx.compose.material3.FilledTonalButton
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

/** Capsules shown in the 2×2 card; the rest are reachable via「查看全部」. */
private const val CAPSULE_PREVIEW_COUNT = 10

/**
 * The 每日推荐 card body for the home grid (plan §12 decision 9, v1.3.2 decision 16)
 * — the same hero grammar the pre-grid home used (`DailyMixHeroHeader` + view-all),
 * with the middle band rendered as a **song-capsule row** instead of the collage:
 * each capsule is a 40dp rounded cover + title / artist, the same capsule language
 * as `RecentAiMixCard`. Tapping a capsule plays that song directly.
 *
 * - 2×1: only the hero header row (title + subtitle + shuffle action).
 * - 2×2: header, then the capsule row vertically centered in the remaining space,
 *   then the「查看全部」button.
 */
@Composable
fun HomeDailyMixContent(
    card: HomeCard,
    songs: ImmutableList<Song>,
    isShuffleEnabled: Boolean,
    onPlayShuffled: () -> Unit,
    onSongClick: (Song) -> Unit,
    onViewAll: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tall = card.size.spanY == 2

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        HomeSectionHeader(
            title = stringResource(R.string.presentation_batch_b_daily_mix_title),
            subtitle = stringResource(R.string.home_your_mix_subtitle),
            // The grid card already pads 16dp.
            contentPadding = PaddingValues(0.dp),
            action = {
                FilledTonalIconButton(
                    onClick = onPlayShuffled,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isShuffleEnabled) colors.primary else colors.tertiaryContainer,
                        contentColor = if (isShuffleEnabled) colors.onPrimary else colors.onTertiaryContainer,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_shuffle_24),
                        contentDescription = stringResource(R.string.cd_shuffle_play),
                        modifier = Modifier.size(24.dp),
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
            Spacer(Modifier.height(8.dp))
            DailyMixViewAllButton(onClick = onViewAll)
        }
    }
}

/** One song pill: 40dp rounded cover + title over artist, `surfaceContainerHigh`. */
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
                    maxLines = 1,
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

/** Same pill as the pre-grid「查看全部每日推荐」button. */
@Composable
private fun DailyMixViewAllButton(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        shape = AbsoluteSmoothCornerShape(
            cornerRadiusTL = 10.dp,
            cornerRadiusTR = 10.dp,
            smoothnessAsPercentTL = 70,
            smoothnessAsPercentTR = 70,
            cornerRadiusBL = 60.dp,
            cornerRadiusBR = 60.dp,
            smoothnessAsPercentBL = 70,
            smoothnessAsPercentBR = 70,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.presentation_batch_g_daily_mix_see_all),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                painter = painterResource(R.drawable.rounded_arrow_forward_24),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
