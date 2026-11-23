package com.pisces312.piscesplayer.presentation.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.home.HomeCard

/**
 * The edit-mode size chip on a home card. Tapping cycles 1×1 → 2×1 → 2×2 → 1×2
 * (plan §11 decision 2). Rendered by the grid so the chip itself never enters
 * the card's own tap target.
 */
@Composable
fun HomeGridSizeChip(
    card: HomeCard,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = Color(0xB3202130),
        contentColor = Color.White,
        tonalElevation = 1.dp,
    ) {
        Box(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCycle,
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    R.string.home_grid_size_label,
                    card.size.spanX,
                    card.size.spanY,
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
