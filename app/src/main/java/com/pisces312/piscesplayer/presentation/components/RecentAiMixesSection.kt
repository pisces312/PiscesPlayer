package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.Playlist
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/** How many mixes the home row shows before pointing at the full list. */
private const val HOME_AI_MIX_PREVIEW_COUNT = 6

private val MIX_CARD_WIDTH = 152.dp

/** Fixed so every card lines up, whatever the name and timestamp lengths are. */
private val MIX_CARD_HEIGHT = 72.dp

/**
 * Home screen row of the most recent generated mixes.
 *
 * Tapping a card plays it from the first track; the mixes themselves are ordinary playlists
 * tagged with an AI source, so nothing needs to be persisted for this row to exist.
 */
@Composable
fun RecentAiMixesSection(
    mixes: List<Playlist>,
    onMixClick: (Playlist) -> Unit,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
    previewCount: Int = HOME_AI_MIX_PREVIEW_COUNT
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_recent_ai_mixes_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 22.dp, bottom = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(mixes.take(previewCount), key = { it.id }) { mix ->
                RecentAiMixCard(mix = mix, onClick = { onMixClick(mix) })
            }
        }
        if (mixes.size > previewCount) {
            FilledTonalButton(
                onClick = onShowAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            ) {
                Text(stringResource(R.string.home_recent_ai_mixes_show_more, mixes.size))
            }
        }
    }
}

@Composable
private fun RecentAiMixCard(mix: Playlist, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(MIX_CARD_WIDTH).height(MIX_CARD_HEIGHT),
        shape = AbsoluteSmoothCornerShape(
            cornerRadiusTL = 22.dp,
            smoothnessAsPercentTL = 60,
            cornerRadiusTR = 22.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusBL = 22.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusBR = 22.dp,
            smoothnessAsPercentBR = 60
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = aiMixDisplayName(mix.name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Not weighted: the timestamp keeps its natural width so it never gets clipped,
                // which is what tells two mixes made minutes apart apart.
                Text(
                    text = formatMixTimestamp(mix.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.ai_mix_result_count, mix.songIds.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
