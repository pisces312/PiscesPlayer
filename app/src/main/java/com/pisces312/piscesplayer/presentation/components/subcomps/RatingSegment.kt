package com.pisces312.piscesplayer.presentation.components.subcomps

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisces312.piscesplayer.R

/**
 * Player toggle row slot dedicated to the five star rating.
 *
 * Collapsed visual only: an outlined star, or a filled star with the current score inside once
 * the song is rated. Tapping reports [onExpand]; the expanded star picker is drawn as an overlay
 * by the host (see `BottomToggleRow`), which owns the expanded state.
 */
@Composable
fun RatingSegment(
    modifier: Modifier = Modifier,
    rating: Int,
    rowCorners: Dp,
    ratedColor: Color,
    ratedContentColor: Color,
    inactiveColor: Color,
    inactiveContentColor: Color,
    onExpand: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (rating > 0) ratedColor else inactiveColor,
        animationSpec = tween(durationMillis = 250),
        label = "RatingSegmentBg"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (rating > 0) rowCorners else 8.dp,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "RatingSegmentCorner"
    )
    val contentColor = if (rating > 0) ratedContentColor else inactiveContentColor

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(cornerRadius))
            .background(bgColor)
            .clickable(onClick = onExpand),
        contentAlignment = Alignment.Center
    ) {
        val contentDescription = if (rating > 0) {
            stringResource(R.string.player_rating_value_cd, rating) +
                ", " +
                stringResource(R.string.player_rating_tap_hint)
        } else {
            stringResource(R.string.player_rating_cd_unrated)
        }
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (rating > 0) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(26.dp)
            )
            if (rating > 0) {
                Text(
                    text = rating.toString(),
                    color = bgColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
