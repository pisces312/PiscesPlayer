package com.pisces312.piscesplayer.presentation.home

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Recommend
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.home.HomeCard
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.presentation.components.HomeSectionHeader
import com.pisces312.piscesplayer.presentation.components.SmartImage
import kotlinx.collections.immutable.ImmutableList
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/** Capsules shown in the 2×2 card; the rest are reachable by swiping / the full page. */
private const val CAPSULE_PREVIEW_COUNT = 10

/** Column gap, row gap and the capsule cap: two 56dp capsules + an 8dp gap = 120dp band. */
private val CapsuleGap = 8.dp

/** Start padding + cover + cover gap + end padding of one capsule; caps the text column. */
private val CapsuleChromeWidth = 72.dp

/** 20dp / 60% smooth pill, shared by the song capsules. */
private val DailyMixCapsuleShape = AbsoluteSmoothCornerShape(
    cornerRadiusTL = 20.dp,
    smoothnessAsPercentTL = 60,
    cornerRadiusTR = 20.dp,
    smoothnessAsPercentTR = 60,
    cornerRadiusBL = 20.dp,
    smoothnessAsPercentBL = 60,
    cornerRadiusBR = 20.dp,
    smoothnessAsPercentBR = 60,
)

/** 17dp / 60% pill for the 34dp shuffle button (half its height, so it reads as a capsule). */
private val DailyMixButtonShape = AbsoluteSmoothCornerShape(
    cornerRadiusTL = 17.dp,
    smoothnessAsPercentTL = 60,
    cornerRadiusTR = 17.dp,
    smoothnessAsPercentTR = 60,
    cornerRadiusBL = 17.dp,
    smoothnessAsPercentBL = 60,
    cornerRadiusBR = 17.dp,
    smoothnessAsPercentBR = 60,
)

/** 10dp / 60% smooth album cover inside a capsule. */
private val DailyMixCoverShape = AbsoluteSmoothCornerShape(
    cornerRadiusTL = 10.dp,
    smoothnessAsPercentTL = 60,
    cornerRadiusTR = 10.dp,
    smoothnessAsPercentTR = 60,
    cornerRadiusBL = 10.dp,
    smoothnessAsPercentBL = 60,
    cornerRadiusBR = 10.dp,
    smoothnessAsPercentBR = 60,
)

/**
 * The 每日推荐 card body for the home grid (plan §12 decisions 9/16/21/23/28/29).
 *
 * v1.3.10: the subtitle is gone and the capsule band is two rows instead of one. The second
 * row is paid for by the shuffle control: it became a 34dp pill (icon + label) instead of a
 * 48dp icon-only circle, so the header row shrank 48→34dp and the body band grew 112→126dp —
 * the 120dp two-row block (2×56 + 8) now sits with 3dp above and below. The card title drops to
 * 18sp (titleMedium + SemiBold) so it no longer outranks its own content. Song capsules keep
 * their v1.3.3 look (40dp cover, two text lines) with the name capped to a single line so the
 * slot height stays 56dp. 34dp is the floor: below it the 34dp leading icon tile decides the
 * header height, not the button.
 *
 * - 2×1: only the header row (title + shuffle pill).
 * - 2×2: header, then two horizontally scrolling capsule rows filling the remaining space.
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
            // v1.3.10: subtitle dropped — the 48dp header row was set by the shuffle control,
            // so removing it bought no height; the pill-shaped control below is what does.
            icon = Icons.Rounded.Recommend,
            // v1.3.11 (decision 37 revision): no icon tile — this is a grid card, so the
            // bare 28dp glyph matches AiGenerateEntryCard and the generic tiles. Header
            // height stays 34dp (the shuffle pill), so the capsule band is unaffected.
            iconTile = false,
            titleStyle = MaterialTheme.typography.titleMedium,
            // The grid card already pads 16dp.
            contentPadding = PaddingValues(0.dp),
            action = {
                // v1.3.10: a 34dp pill (icon + label) replaces the 48dp icon-only circle.
                // It matches the 34dp icon tile, which is what now sets the header height —
                // shrinking further buys nothing. Height must be forced: M3 would otherwise
                // keep a 48dp layout around a 40dp visual and give nothing back.
                FilledTonalButton(
                    onClick = onPlayShuffled,
                    modifier = Modifier.height(34.dp),
                    shape = DailyMixButtonShape,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isShuffleEnabled) colors.primary else colors.tertiaryContainer,
                        contentColor = if (isShuffleEnabled) colors.onPrimary else colors.onTertiaryContainer,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_shuffle_24),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_daily_mix_shuffle),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
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
                // Two capsules per row need each capsule to stay within half the band (minus
                // the 8dp gap); the cap is derived so wide capsules cannot spill over.
                val maxCapsuleWidth = (maxWidth - CapsuleGap) / 2f
                LazyHorizontalGrid(
                    // Fills the whole body band: with two rows the grid derives 56dp slots
                    // from the 126dp height, which is exactly the capsule height.
                    rows = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(CapsuleGap),
                    verticalArrangement = Arrangement.spacedBy(CapsuleGap),
                ) {
                    items(songs.take(CAPSULE_PREVIEW_COUNT), key = { it.id }) { song ->
                        DailyMixSongCapsule(
                            song = song,
                            maxCapsuleWidth = maxCapsuleWidth,
                            onClick = { onSongClick(song) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One song pill: 40dp rounded cover + a one-line song name over the artist. Both text lines
 * are capped at [maxCapsuleWidth] to keep the two-row grid from being widened by long titles.
 */
@Composable
private fun DailyMixSongCapsule(song: Song, maxCapsuleWidth: Dp, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = DailyMixCapsuleShape,
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
                    .clip(DailyMixCoverShape),
            )
            Spacer(Modifier.width(10.dp))
            // v1.3.10: capped and single-line (v1.3.3's maxLines = 2 was dead code anyway —
            // a LazyList measures children with an unbounded main axis, so a long name just
            // widened the pill instead of wrapping); the two-row grid needs a fixed 56dp slot.
            Column(modifier = Modifier.widthIn(max = maxCapsuleWidth - CapsuleChromeWidth)) {
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
