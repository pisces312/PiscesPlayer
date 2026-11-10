package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Home screen entry point for AI playlist generation.
 *
 * One primary capsule carries the whole flow:
 *
 * ```
 * AI Playlist
 * Short-press to listen · hold to describe
 *                    [wand Serendipity!]
 * ```
 *
 * - Short press on the capsule: gather the moment's signals and generate straight away.
 * - Long press on the capsule (or tapping the card body): open the shared sheet so the user can
 *   describe it themselves, with the same signals available as read-only chips.
 *
 * When no provider is configured the subtitle says so and the caller routes to AI settings
 * instead of opening the generation sheet.
 */
@Composable
fun AiGenerateEntryCard(
        configured: Boolean,
        /** Short press on the pill: collect signals and generate without another tap. */
        onQuickGenerate: () -> Unit,
        /** Long press on the pill (and the card body): open the editable describe sheet. */
        onOpenDescribe: () -> Unit,
        modifier: Modifier = Modifier
) {
    val shape =
            AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 26.dp,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusTR = 26.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusBL = 26.dp,
                    smoothnessAsPercentBL = 60,
                    cornerRadiusBR = 26.dp,
                    smoothnessAsPercentBR = 60
            )

    val onCard = MaterialTheme.colorScheme.onPrimary

    Surface(onClick = onOpenDescribe, shape = shape, modifier = modifier.fillMaxWidth()) {
        Box(
                modifier =
                        Modifier.background(
                                        Brush.horizontalGradient(
                                                listOf(
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.colorScheme.tertiary
                                                )
                                        )
                                )
                                .heightIn(min = 100.dp)
                                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = onCard,
                            modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                                text = stringResource(R.string.ai_playlist_entry_title),
                                style =
                                        MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold
                                        ),
                                color = onCard
                        )
                        Text(
                                text =
                                        stringResource(
                                                if (configured) R.string.ai_serendipity_button_hint
                                                else R.string.ai_playlist_entry_unconfigured
                                        ),
                                style = MaterialTheme.typography.bodySmall,
                                color = onCard.copy(alpha = 0.85f)
                        )
                    }
                }
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionPill(
                            text = stringResource(R.string.ai_serendipity_button),
                            icon = Icons.Rounded.AutoFixHigh,
                            color = onCard.copy(alpha = 0.24f),
                            onClick = onQuickGenerate,
                            onLongClick = onOpenDescribe
                    )
                }
            }
        }
    }
}

/**
 * Capsule used for the card action; a null [onClick] renders a plain label pill.
 *
 * Long press needs no manual haptic: combinedClickable fires the built-in LongPress feedback on
 * its own, routed through the app-scoped LocalHapticFeedback in MainActivity (which already honours
 * the in-app haptics switch and the system setting).
 */
@Composable
private fun ActionPill(
        text: String,
        color: Color,
        icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null
) {
    val content: @Composable () -> Unit = {
        Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp)
                )
            }
            Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }

    if (onClick != null || onLongClick != null) {
        Surface(
                shape = CircleShape,
                color = color,
                modifier =
                        Modifier.clip(CircleShape).combinedClickable(
                                onClick = { onClick?.invoke() },
                                onLongClick = onLongClick
                        )
        ) {
            content()
        }
    } else {
        Surface(shape = CircleShape, color = color) { content() }
    }
}
