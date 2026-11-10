package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Shared section header for the home screen.
 *
 * Every functional block on home (AI, local recommendations, recently played) uses the
 * same header grammar: a tinted icon tile, a title, an optional subtitle, and an
 * optional trailing action. The tile color is the block's identity color; everything
 * else (typography, spacing, shape) is identical so the sections read as one system.
 */
@Composable
fun HomeSectionHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    iconContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    iconContentColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null || iconPainter != null) {
            Surface(
                shape = AbsoluteSmoothCornerShape(
                    cornerRadiusTL = 12.dp,
                    smoothnessAsPercentTL = 60,
                    cornerRadiusTR = 12.dp,
                    smoothnessAsPercentTR = 60,
                    cornerRadiusBL = 12.dp,
                    smoothnessAsPercentBL = 60,
                    cornerRadiusBR = 12.dp,
                    smoothnessAsPercentBR = 60
                ),
                color = iconContainerColor,
                modifier = Modifier.size(34.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconContentColor,
                        modifier = Modifier.padding(7.dp)
                    )
                } else if (iconPainter != null) {
                    Icon(
                        painter = iconPainter,
                        contentDescription = null,
                        tint = iconContentColor,
                        modifier = Modifier.padding(7.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (action != null) {
            Spacer(modifier = Modifier.width(12.dp))
            action()
        }
    }
}
