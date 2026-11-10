package com.pisces312.piscesplayer.presentation.components.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.media3.common.Player
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.presentation.components.ToggleSegmentButton
import com.pisces312.piscesplayer.presentation.components.subcomps.RatingSegment
import com.pisces312.piscesplayer.presentation.components.subcomps.RatingStars
import kotlin.math.roundToInt
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Player bottom toggle row: shuffle / repeat / favorite, with an optional fourth slot for the
 * five-star rating. Shared by the full player and the lyrics "more" sheet; the two call sites
 * differ only through the styling parameters (theme palette, spacing, colors).
 *
 * When [ratingProvider] and [onRatingSelected] are both supplied, the fourth segment hosts the
 * rating. Tapping it raises an expanded rating pill ON TOP of the row: the segments never reflow
 * (no crushed buttons mid-animation); the pill animates from the rating slot's captured bounds
 * out to the container's full bounds and back. Tapping a star rates and dismisses; tapping
 * anywhere else on the pill, the close button, or the back gesture cancels.
 */
@Composable
fun BottomToggleRow(
    modifier: Modifier,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    isFavoriteProvider: () -> Boolean,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onFavoriteToggle: () -> Unit,
    isShuffleTransitionInProgress: Boolean = false,
    ratingProvider: (() -> Int)? = null,
    onRatingSelected: ((Int) -> Unit)? = null,
    activeColorMain: Color = MaterialTheme.colorScheme.primary,
    activeColorSecondary: Color = MaterialTheme.colorScheme.secondary,
    activeColorTertiary: Color = MaterialTheme.colorScheme.tertiary,
    onActiveColorMain: Color = MaterialTheme.colorScheme.onPrimary,
    onActiveColorSecondary: Color = MaterialTheme.colorScheme.onSecondary,
    onActiveColorTertiary: Color = MaterialTheme.colorScheme.onTertiary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    inactiveContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    ratedColor: Color = activeColorTertiary,
    ratedContentColor: Color = onActiveColorTertiary,
    ratingPillColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    capsuleHorizontalPadding: Dp = 0.dp,
    innerPadding: Dp = 8.dp,
    segmentSpacing: Dp = 8.dp
) {
    val isFavorite = isFavoriteProvider()
    val showRating = ratingProvider != null && onRatingSelected != null
    val rating = ratingProvider?.invoke() ?: 0
    val rowCorners = 60.dp
    val capsuleShape = AbsoluteSmoothCornerShape(
        cornerRadiusBL = rowCorners,
        smoothnessAsPercentTR = 60,
        cornerRadiusBR = rowCorners,
        smoothnessAsPercentBL = 60,
        cornerRadiusTL = rowCorners,
        smoothnessAsPercentBR = 60,
        cornerRadiusTR = rowCorners,
        smoothnessAsPercentTL = 60
    )

    // --- Expanded rating pill state (only meaningful when showRating) ---
    var ratingExpanded by remember { mutableStateOf(false) }
    val expandProgress by animateFloatAsState(
        targetValue = if (ratingExpanded) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ratingExpandProgress"
    )
    // The overlay wears the collapsed slot's own colors when rated; when unrated it is an opaque
    // surface panel (the collapsed slot is translucent, but the expanded pill must hide the
    // covered segments completely). Both animate in sync with RatingSegment's background.
    val pillColor by animateColorAsState(
        targetValue = if (rating > 0) ratedColor else ratingPillColor,
        animationSpec = tween(durationMillis = 250),
        label = "ratingPillColor"
    )
    val pillContentColor = if (rating > 0) ratedContentColor else inactiveContentColor
    // Corner radius the overlay shrinks back into: matches the collapsed slot (rated pill vs
    // plain unrated box) so the handoff at the end of the collapse animation is seamless.
    var collapseTargetCorners by remember { mutableStateOf(8.dp) }
    val density = LocalDensity.current
    var parentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var slotCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val slotBounds = remember(parentCoords, slotCoords) {
        val parent = parentCoords
        val slot = slotCoords
        if (parent != null && slot != null && parent.isAttached && slot.isAttached) {
            val topLeft = parent.localPositionOf(slot, Offset.Zero)
            RatingSlotBounds(
                left = topLeft.x,
                top = topLeft.y,
                width = slot.size.width.toFloat(),
                height = slot.size.height.toFloat()
            )
        } else {
            null
        }
    }

    BackHandler(enabled = showRating && ratingExpanded) { ratingExpanded = false }

    Box(
        modifier = modifier
            .onGloballyPositioned { parentCoords = it }
            .padding(horizontal = capsuleHorizontalPadding)
            .background(color = containerColor, shape = capsuleShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .clip(capsuleShape)
                .background(Color.Transparent)
                // While the overlay pill is up (or animating) the covered segments must not be
                // reachable by accessibility services — the pill provides the only semantics.
                .then(
                    if (expandProgress > 0f) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    }
                ),
            horizontalArrangement = Arrangement.spacedBy(segmentSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val segmentModifier = Modifier.weight(1f)

            ToggleSegmentButton(
                modifier = segmentModifier,
                active = isShuffleEnabled,
                enabled = !isShuffleTransitionInProgress,
                activeColor = activeColorMain,
                activeCornerRadius = rowCorners,
                activeContentColor = onActiveColorMain,
                inactiveColor = inactiveColor,
                inactiveContentColor = inactiveContentColor,
                onClick = onShuffleToggle,
                iconId = R.drawable.rounded_shuffle_24,
                contentDesc = "Shuffle"
            )
            val repeatActive = repeatMode != Player.REPEAT_MODE_OFF
            val repeatIcon = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.rounded_repeat_one_24
                Player.REPEAT_MODE_ALL -> R.drawable.rounded_repeat_24
                else -> R.drawable.rounded_repeat_24
            }
            ToggleSegmentButton(
                modifier = segmentModifier,
                active = repeatActive,
                activeColor = activeColorSecondary,
                activeCornerRadius = rowCorners,
                activeContentColor = onActiveColorSecondary,
                inactiveColor = inactiveColor,
                inactiveContentColor = inactiveContentColor,
                onClick = onRepeatToggle,
                iconId = repeatIcon,
                contentDesc = "Repeat"
            )
            ToggleSegmentButton(
                modifier = segmentModifier,
                active = isFavorite,
                activeColor = activeColorTertiary,
                activeCornerRadius = rowCorners,
                activeContentColor = onActiveColorTertiary,
                inactiveColor = inactiveColor,
                inactiveContentColor = inactiveContentColor,
                onClick = onFavoriteToggle,
                iconId = if (isFavorite) R.drawable.round_favorite_24 else R.drawable.rounded_favorite_24,
                contentDesc = stringResource(
                    if (isFavorite) R.string.player_rating_favorite_on else R.string.player_rating_favorite_off
                )
            )
            if (showRating) {
                RatingSegment(
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { slotCoords = it },
                    rating = rating,
                    rowCorners = rowCorners,
                    ratedColor = ratedColor,
                    ratedContentColor = ratedContentColor,
                    inactiveColor = inactiveColor,
                    inactiveContentColor = inactiveContentColor,
                    onExpand = {
                        collapseTargetCorners = if (rating > 0) rowCorners else 8.dp
                        ratingExpanded = true
                    }
                )
            }
        }

        val bounds = slotBounds
        val parent = parentCoords
        if (showRating && bounds != null && parent != null && expandProgress > 0f) {
            // Fully expanded, the pill coincides with the outer capsule exactly: offset and size
            // both interpolate to the parent's bounds, corners to the capsule's radius.
            val pillLeft = bounds.left * (1f - expandProgress)
            val pillTop = bounds.top * (1f - expandProgress)
            val pillWidth = bounds.width + (parent.size.width - bounds.width) * expandProgress
            val pillHeight = bounds.height + (parent.size.height - bounds.height) * expandProgress
            val pillCorners = lerp(collapseTargetCorners, rowCorners, expandProgress)
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = pillLeft.roundToInt(),
                            y = pillTop.roundToInt()
                        )
                    }
                    .size(
                        width = with(density) { pillWidth.toDp() },
                        height = with(density) { pillHeight.toDp() }
                    )
                    .clip(AbsoluteSmoothCornerShape(pillCorners, 60))
                    .background(pillColor)
                    .clickable { ratingExpanded = false },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RatingStars(
                        rating = rating,
                        onRatingChange = { stars ->
                            onRatingSelected?.invoke(stars)
                            ratingExpanded = false
                        },
                        // Centered when the six capped cells don't fill the pill's width.
                        modifier = Modifier
                            .weight(1f)
                            .wrapContentWidth(Alignment.CenterHorizontally),
                        selectedTint = pillContentColor,
                        unselectedTint = pillContentColor.copy(alpha = 0.45f),
                        trailingContent = {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.player_rating_collapse_cd),
                                tint = pillContentColor,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .clickable { ratingExpanded = false }
                                    .padding(4.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

private data class RatingSlotBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)
