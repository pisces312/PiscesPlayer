package com.pisces312.piscesplayer.presentation.home

import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.home.HomeCard
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.preferences.CollagePattern
import com.pisces312.piscesplayer.presentation.components.AlbumArtCollage
import com.pisces312.piscesplayer.presentation.components.HomeSectionHeader
import kotlinx.collections.immutable.ImmutableList
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * The 每日推荐 card body for the home grid (plan §12 decision 9) — the same
 * hero grammar the pre-grid home used (`DailyMixHeroHeader` + `AlbumArtCollage`
 * + view-all), re-created inside the neutral grid card chrome.
 *
 * - 2×1: only the hero header row (title + subtitle + shuffle action).
 * - 2×2: header, then the collage **fills** whatever vertical space remains,
 *   then the「查看全部」button. No auto-rotation: the pattern is the user's
 *   fixed setting, matching the full Daily Mix screen.
 */
@Composable
fun HomeDailyMixContent(
    card: HomeCard,
    songs: ImmutableList<Song>,
    isShuffleEnabled: Boolean,
    collagePattern: CollagePattern,
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
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AlbumArtCollage(
                    modifier = Modifier.fillMaxWidth(),
                    songs = songs,
                    padding = 0.dp,
                    height = maxHeight,
                    pattern = collagePattern,
                    onSongClick = onSongClick,
                )
            }
            Spacer(Modifier.height(8.dp))
            DailyMixViewAllButton(onClick = onViewAll)
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
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
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
