/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §6.1, §11.1, §11.2).
 */
package com.pisces312.piscesplayer.presentation.postcard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pisces312.piscesplayer.data.postcard.model.PostcardSpec

/**
 * Draws one postcard face at the card aspect ratio (3:2, design §6.1). The Composable owns
 * only sizing and the placeholder; every pixel of the card itself comes from
 * [PostcardRenderer], so the list, the detail screen and the export path share one renderer.
 *
 * A null [spec] (unparsable stored JSON) renders a neutral paper placeholder instead of
 * crashing the list — old specs must stay viewable even after a renderer change.
 */
@Composable
fun PostcardFace(
    spec: PostcardSpec?,
    side: PostcardSide,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12,
) {
    Surface(
        modifier = modifier
            .aspectRatio(3f / 2f)
            .clip(RoundedCornerShape(cornerRadius.dp)),
        color = Color(0xFFF6F1E7),
        tonalElevation = 2.dp,
    ) {
        if (spec == null) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2f)) {
                PostcardRenderer.draw(this, spec, side)
            }
        }
    }
}

/** Small inline preview used by list rows: front face, fixed width, no interaction. */
@Composable
fun PostcardThumbnail(
    spec: PostcardSpec?,
    modifier: Modifier = Modifier,
) {
    PostcardFace(spec = spec, side = PostcardSide.FRONT, modifier = modifier, cornerRadius = 8)
}
