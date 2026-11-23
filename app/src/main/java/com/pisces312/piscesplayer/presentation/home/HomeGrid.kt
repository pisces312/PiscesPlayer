package com.pisces312.piscesplayer.presentation.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.home.HomeCard
import com.pisces312.piscesplayer.data.home.HomeCardSize
import com.pisces312.piscesplayer.data.home.HomeCardType
import com.pisces312.piscesplayer.data.home.placeHomeCards
import com.pisces312.piscesplayer.data.model.Playlist
import kotlin.math.roundToInt

/**
 * Home grid metrics (plan §3.1, reference device pixel6 411dp wide): 2 columns,
 * unit cell 183.5×122dp (a wide rectangle), 16dp outer gutters, 12dp gaps. Only
 * vertical scrolling is supported — no horizontal scroll, no column wrapping.
 */
object HomeGridSpec {
    const val GridColumns = 2
    val CellWidth = 183.5.dp
    val CellHeight = 122.dp
    val OuterPadding = 16.dp
    val Gap = 12.dp
    val ColumnPitch = CellWidth + Gap
    val RowPitch = CellHeight + Gap
}

/**
 * The home card grid. [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]
 * cannot express row spans, so the grid is a plain custom [Layout]:
 * [placeHomeCards] assigns each card a (row, column) origin from the list order
 * and the cards are measured at their span size and placed absolutely. Reorders
 * and resizes animate because each card is keyed by [HomeCard.uid] and position /
 * size changes run through the default layout animation.
 *
 * The grid itself is not scrollable; the caller nests it in a vertical scroller.
 */
@Composable
fun HomeGrid(
    cards: List<HomeCard>,
    playlists: Map<String, Playlist>,
    editMode: Boolean,
    draggingUid: String?,
    onCardClick: (HomeCard) -> Unit,
    onCardRemove: (Int) -> Unit,
    onCardCycleSize: (String) -> Unit,
    onAddTileClick: () -> Unit,
    modifier: Modifier = Modifier,
    showAddTile: Boolean = true,
    /** v1.3 (plan §12.3): real content for special cards (AI 歌单 / 每日推荐); null → generic tile. */
    contentFor: (HomeCard) -> (@Composable () -> Unit)? = { null },
) {
    if (cards.isEmpty() && !showAddTile) return

    val placement = placeHomeCards(cards)
    val density = LocalDensity.current

    val columnPitchPx = with(density) { HomeGridSpec.ColumnPitch.toPx() }
    val rowPitchPx = with(density) { HomeGridSpec.RowPitch.toPx() }
    val cellWidthPx = with(density) { HomeGridSpec.CellWidth.toPx() }
    val cellHeightPx = with(density) { HomeGridSpec.CellHeight.toPx() }
    val gapPx = with(density) { HomeGridSpec.Gap.toPx() }

    // The「＋ 添加卡片」tile lives at the first free slot after the last card.
    val tailPlacement = placeHomeCards(cards + HomeCard(uid = "__tail__", type = HomeCardType.AI_PLAYLIST, size = HomeCardSize.S_1x1))
        .placements.getValue("__tail__")
    val totalRows = maxOf(placement.totalRows, tailPlacement.row + 1)
    val gridHeightDp = with(density) { (totalRows * rowPitchPx - gapPx).toDp() }

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(gridHeightDp),
        content = {
            cards.forEachIndexed { index, card ->
                key(card.uid) {
                    val widthPx = card.size.spanX * cellWidthPx + (card.size.spanX - 1) * gapPx
                    val heightPx = card.size.spanY * cellHeightPx + (card.size.spanY - 1) * gapPx
                    Box {
                        HomeGridCard(
                            card = card,
                            widthDp = with(density) { widthPx.toDp() },
                            heightDp = with(density) { heightPx.toDp() },
                            playlist = if (card.type == HomeCardType.PLAYLIST)
                                card.targetId?.let { playlists[it] } else null,
                            editMode = editMode,
                            isDragging = draggingUid == card.uid,
                            onClick = { onCardClick(card) },
                            onRemove = { onCardRemove(index) },
                            modifier = Modifier.fillMaxWidth(),
                            content = contentFor(card),
                        )

                        if (editMode) {
                            HomeGridSizeChip(
                                card = card,
                                onCycle = { onCardCycleSize(card.uid) },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(7.dp),
                            )
                        }
                    }
                }
            }
            if (showAddTile) {
                key("__add_tile__") {
                    HomeGridAddTile(onClick = onAddTileClick)
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { i, measurable ->
            val isTile = i == cards.size
            if (isTile) {
                measurable.measure(
                    Constraints.fixed(cellWidthPx.roundToInt(), cellHeightPx.roundToInt())
                )
            } else {
                val card = cards[i]
                val widthPx = card.size.spanX * cellWidthPx + (card.size.spanX - 1) * gapPx
                val heightPx = card.size.spanY * cellHeightPx + (card.size.spanY - 1) * gapPx
                measurable.measure(
                    Constraints.fixed(widthPx.roundToInt(), heightPx.roundToInt())
                )
            }
        }
        val gridWidthPx = HomeGridSpec.GridColumns * cellWidthPx + (HomeGridSpec.GridColumns - 1) * gapPx
        layout(gridWidthPx.roundToInt(), (totalRows * rowPitchPx - gapPx).roundToInt()) {
            placeables.forEachIndexed { i, placeable ->
                val origin = if (i == cards.size) tailPlacement
                else placement.placements[cards[i].uid] ?: return@forEachIndexed
                placeable.placeRelative(
                    x = (origin.column * columnPitchPx).roundToInt(),
                    y = (origin.row * rowPitchPx).roundToInt(),
                )
            }
        }
    }
}

/**
 * The dashed「＋ 添加卡片」tile pinned to the first free slot at the end of the
 * grid. Always visible in browse mode so adding a card is discoverable even when
 * the grid is empty (plan §11 decision 7). v1.3: neutral dashed outline, no
 * accent color.
 */
@Composable
private fun HomeGridAddTile(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(HomeGridCardShape)
            .border(
                width = 1.5.dp,
                color = outline.copy(alpha = 0.5f),
                shape = HomeGridCardShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = outline,
            )
            Text(
                text = stringResource(R.string.home_grid_add_tile),
                style = MaterialTheme.typography.labelMedium,
                color = outline,
            )
        }
    }
}
