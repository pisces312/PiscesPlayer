package com.pisces312.piscesplayer.presentation.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.home.HomeCard
import com.pisces312.piscesplayer.data.model.Playlist
import com.pisces312.piscesplayer.presentation.components.jellyPress
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * One home grid card — the neutral chrome every card shares since v1.3
 * (plan §12): `surfaceContainer` container with [HomeGridCardShape], laid out
 * exactly like the pre-grid `AiGenerateEntryCard` / `HomeSectionCard` so the
 * grid reads as one family of Material 3 cards instead of colored tiles.
 *
 * The visual body comes from [content]; when null a generic entry row
 * (icon + title + subtitle, `onSurface` / `onSurfaceVariant`) is rendered.
 * In edit mode the card wiggles and exposes a「×」remove badge; the size chip
 * is rendered by the grid so its tap never collides with the card body.
 */
@Composable
fun HomeGridCard(
    card: HomeCard,
    playlist: Playlist?,
    editMode: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val wiggle by rememberInfiniteTransition(label = "home_card_wiggle").animateFloat(
        initialValue = -0.7f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(140), RepeatMode.Reverse),
        label = "home_card_wiggle_angle",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(HomeGridCardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // v1.3.9: jelly press feedback (squash/pivot around the touch point,
            // bouncy settle). Off in edit mode — the drag-lift owns the transform there.
            .jellyPress(enabled = !editMode)
            .graphicsLayer {
                val lift = isDragging
                scaleX = if (lift) 1.03f else 1f
                scaleY = if (lift) 1.03f else 1f
                shadowElevation = if (lift) 16.dp.toPx() else 0f
                rotationZ = if (editMode && !isDragging) wiggle else if (isDragging) 1.2f else 0f
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
    ) {
        if (content != null) {
            Box(Modifier.fillMaxSize()) { content() }
        } else {
            HomeGridCardGenericContent(card = card, playlist = playlist)
        }

        if (editMode) {
            HomeGridRemoveBadge(onRemove = onRemove, title = homeGridCardTitle(card, playlist))
        }
    }
}

/** Title text for the generic tile and the remove-badge content description. */
@Composable
internal fun homeGridCardTitle(card: HomeCard, playlist: Playlist?): String {
    val spec = remember(card.type) { HomeCardCatalog.forType(card.type) }
    return when (card.type) {
        com.pisces312.piscesplayer.data.home.HomeCardType.PLAYLIST -> playlist?.name
            ?: stringResource(R.string.home_grid_playlist_fallback_name)
        else -> stringResource(spec.nameRes)
    }
}

/**
 * The generic v1.3 tile body: one entry row in the spirit of
 * `AiGenerateEntryCard` — 28dp `onSurface` icon, `onSurface` title,
 * `onSurfaceVariant` subtitle — centered vertically in whatever span the card
 * occupies.
 */
@Composable
private fun HomeGridCardGenericContent(
    card: HomeCard,
    playlist: Playlist?,
) {
    val spec = remember(card.type) { HomeCardCatalog.forType(card.type) }
    val title = homeGridCardTitle(card, playlist)
    // v1.3.3 (plan §12 decision 20): 1×1 tiles drop the subtitle entirely — at 96dp
    // tall it only ever truncated; wide 2×1 cards keep a one-line subtitle.
    val subtitle = if (card.size.spanY == 1) null else when (card.type) {
        com.pisces312.piscesplayer.data.home.HomeCardType.PLAYLIST -> stringResource(
            R.string.home_grid_playlist_tracks_format, playlist?.songIds?.size ?: 0
        )
        else -> spec.subtitleRes?.let { stringResource(it) }
    }
    val wide = card.size.spanX == 2

    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            spec.icon.Render(
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(if (wide) 14.dp else 10.dp))
            Column(modifier = Modifier.weight(1f, fill = false)) {
                // Title size is graded by card height (plan v1.3.4 decision 24):
                // 1×1 tiles get titleMedium — titleLarge read too big on a 187×96dp
                // tile; wider/taller cards keep the AI-playlist titleLarge.
                Text(
                    text = title,
                    style = if (card.size.spanY == 1 && card.size.spanX == 1)
                        MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeGridRemoveBadge(onRemove: () -> Unit, title: String) {
    Surface(
        modifier = Modifier
            .padding(7.dp)
            .size(26.dp),
        shape = MaterialTheme.shapes.small,
        color = Color(0xFFFF5252),
        contentColor = Color.White,
        tonalElevation = 2.dp,
        onClick = onRemove,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.home_grid_cd_remove_card, title),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** v1.3: same 26dp smooth shape token as the pre-grid `HomeSectionCard`. */
internal val HomeGridCardShape = AbsoluteSmoothCornerShape(
    cornerRadiusTL = 26.dp,
    smoothnessAsPercentTL = 60,
    cornerRadiusTR = 26.dp,
    smoothnessAsPercentTR = 60,
    cornerRadiusBL = 26.dp,
    smoothnessAsPercentBL = 60,
    cornerRadiusBR = 26.dp,
    smoothnessAsPercentBR = 60,
)
