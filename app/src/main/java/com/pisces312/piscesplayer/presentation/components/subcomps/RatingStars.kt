package com.pisces312.piscesplayer.presentation.components.subcomps

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R

/**
 * Five star rating control laid out as equal-width square cells (weight + aspectRatio), so all
 * stars shrink uniformly when horizontal space runs out instead of the last one being crushed.
 * Cells cap at [maxStarSize]; [starGap] controls spacing between cells. An optional
 * [trailingContent] occupies one extra equal-width cell (e.g. the expanded pill's close button),
 * keeping every icon in the row the same size.
 */
@Composable
fun RatingStars(
    rating: Int,
    onRatingChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starCount: Int = 5,
    maxStarSize: Dp = 36.dp,
    starGap: Dp = 2.dp,
    selectedTint: Color = MaterialTheme.colorScheme.tertiary,
    unselectedTint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    trailingContent: (@Composable () -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    val cellCount = starCount + if (trailingContent != null) 1 else 0
    Row(
        modifier = modifier.widthIn(max = maxStarSize * cellCount + starGap * (cellCount - 1)),
        horizontalArrangement = Arrangement.spacedBy(starGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (star in 1..starCount) {
            val selected = star <= rating
            val scale by animateFloatAsState(
                targetValue = if (selected) 1f else 0.88f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "RatingStarScale"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (selected) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(R.string.song_info_cd_rate_stars, star),
                    tint = if (selected) selectedTint else unselectedTint,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            haptics.performHapticFeedback(
                                if (star == rating) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
                            )
                            onRatingChange(if (star == rating) 0 else star)
                        }
                        .padding(2.dp)
                        .scale(scale)
                )
            }
        }
        if (trailingContent != null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                trailingContent()
            }
        }
    }
}
