@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.pisces312.piscesplayer.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.data.stats.StatsTimeRange
import com.pisces312.piscesplayer.presentation.stats.shortNameRes

private val StatsRangeChipHeight = 40.dp
private val StatsRangeChipBorderWidth = 2.dp

/**
 * Equal-width segmented control covering every [StatsTimeRange], in enum order
 * (today, week to date, month to date, year to date, all time).
 *
 * Short labels are used so all five fit on one row without scrolling.
 */
@Composable
fun StatsRangeSelector(
    selected: StatsTimeRange,
    onRangeSelected: (StatsTimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StatsTimeRange.entries.forEach { range ->
            StatsRangeChip(
                label = stringResource(range.shortNameRes()),
                selected = selected == range,
                onClick = { onRangeSelected(range) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatsRangeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val motionScheme = MaterialTheme.motionScheme

    val containerColor by animateColorAsState(
        targetValue = if (selected) colors.tertiary else Color.Transparent,
        animationSpec = motionScheme.defaultEffectsSpec(),
        label = "StatsRangeChipContainerColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.onTertiary else colors.tertiary,
        animationSpec = motionScheme.fastEffectsSpec(),
        label = "StatsRangeChipContentColor"
    )

    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.semantics { role = Role.Tab },
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(width = StatsRangeChipBorderWidth, color = colors.tertiary),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .height(StatsRangeChipHeight)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
