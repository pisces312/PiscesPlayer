package com.pisces312.piscesplayer.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * Shape token shared by every content card on the home screen (Recently
 * generated, Discover, Daily Mix). The AI entry card uses the same radius but
 * keeps its gradient: it is a call to action, deliberately set apart from the
 * content cards. Do not hand-roll new radii for home sections — reuse this
 * token so the sections stay visually interchangeable.
 */
val HomeCardShape = AbsoluteSmoothCornerShape(
    cornerRadiusTL = 26.dp,
    smoothnessAsPercentTL = 60,
    cornerRadiusTR = 26.dp,
    smoothnessAsPercentTR = 60,
    cornerRadiusBL = 26.dp,
    smoothnessAsPercentBL = 60,
    cornerRadiusBR = 26.dp,
    smoothnessAsPercentBR = 60
)

/**
 * The uniform home section card: same shape token, same `surfaceContainer`
 * background, same outer gutter (16dp) and inner padding (16dp) for every
 * section, so home reads as one stack of cards instead of mixed layouts.
 */
@Composable
fun HomeSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = HomeCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}
