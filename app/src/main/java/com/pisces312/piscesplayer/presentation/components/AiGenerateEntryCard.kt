package com.pisces312.piscesplayer.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.ui.theme.ShapeCache

/**
 * Home screen entry point for AI playlist generation.
 *
 * One primary capsule carries the whole flow:
 *
 * ```
 * [wand] AI Playlist            [wand Serendipity!]
 *        Short-press to listen · hold to describe
 * ```
 *
 * - Short press on the capsule: gather the moment's signals and generate straight away.
 * - Long press on the capsule: open the shared sheet so the user can describe it
 *   themselves, with the same signals available as read-only chips.
 *
 * v1.3.4: the card BODY is deliberately passive — the surrounding grid card routes a
 * body tap to the full AI-mixes list, so this Surface carries no onClick of its own.
 * The serendipity capsule is the only interactive element inside and gets a gentle
 * breathing highlight so the eye lands on it first.
 *
 * When no provider is configured the subtitle says so and the caller routes to AI settings
 * instead of opening the generation sheet.
 */
@Composable
fun AiGenerateEntryCard(
        configured: Boolean,
        /** Short press on the pill: collect signals and generate without another tap. */
        onQuickGenerate: () -> Unit,
        /** Long press on the pill: open the editable describe sheet. */
        onOpenDescribe: () -> Unit,
        modifier: Modifier = Modifier,
        /** Recent mixes share this card below the generation controls. */
        recentContent: (@Composable () -> Unit)? = null,
        /** v1.3.9: the 2×2 hero gets the plump 96dp jelly (user-picked plan B, grown
         *  twice to eat the leftover vertical gaps); compact spans (banner / 1×1 / 1×2)
         *  keep a 64dp pill so the entry row plus hint still fit a 96dp cell. */
        largePill: Boolean = true
) {
    // Passive container — taps fall through to the grid card (full mixes list).
    Surface(
            shape = HomeCardShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // v1.3.8: the entry row fills whatever vertical space the grid cell
            // leaves above the shelf and centres in it — the old fixed 88dp header
            // left ~27dp of dead space above and below inside the 2×2 hero.
            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                        imageVector = Icons.Rounded.AutoFixHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                        text = stringResource(R.string.ai_playlist_entry_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                )
                // v1.3.5: the hint lives UNDER the pill, so it clearly describes the
                // pill's gestures and not the whole card (user feedback). The pill is
                // the morphing serendipity blob — the only live shape of its kind on home.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ActionPill(
                            text = stringResource(R.string.ai_serendipity_button),
                            icon = Icons.Rounded.AutoFixHigh,
                            color = MaterialTheme.colorScheme.primary,
                            onClick = onQuickGenerate,
                            onLongClick = onOpenDescribe,
                            // The one text-carrying action on home: highlight it with
                            // a breathing pulse (plan v1.3.4/1.3.5 decision 22).
                            highlight = true,
                            large = largePill,
                    )
                    Text(
                            // v1.3.7: ONE line directly under the pill (user feedback:
                            // two lines read like a caption for the whole card). The
                            // " · " separator keeps the two gestures scannable.
                            text =
                                    stringResource(
                                            if (configured) R.string.ai_serendipity_button_hint
                                            else R.string.ai_playlist_entry_unconfigured
                                    ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            if (recentContent != null) {
                // v1.3.3: hug the entry row above (6dp) — the mixes shelf and the
                // show-all button stack directly beneath it inside the 2×2 cell.
                Box(
                        modifier = Modifier.fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)
                ) { recentContent() }
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
        onLongClick: (() -> Unit)? = null,
        /** A soft infinite breathing pulse that pulls the eye without motion clutter. */
        highlight: Boolean = false,
        /** v1.3.9 (user-picked plan B, grown twice): the 2×2 hero pill is a plump 96dp
         *  jelly with 20sp text; compact spans keep a 64dp pill so the row fits a 96dp
         *  cell. */
        large: Boolean = true,
) {
    // v1.3.9 (user feedback): no shadow at all — the animated shadowElevation was the
    // priciest per-frame cost and read as "no visible shadow" anyway. Scale + sway +
    // morph stay.
    val pillHeight = if (large) 96.dp else 64.dp
    val pulse by rememberInfiniteTransition(label = "serendipity_pulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "serendipity_pulse_value",
    )
    // v1.3.7: the morph loop is decoupled from the heartbeat — one full jelly revolution
    // per ~6s (8 phases × 0.75s), while scale/shadow keep the 1.4s breathing pulse.
    val morph by rememberInfiniteTransition(label = "serendipity_morph").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
            label = "serendipity_morph_value",
    )
    // A slow ±8° wobble, like jelly settling on a plate — enough life to read as "alive",
    // gentle enough that the label text stays readable (no full 360° spin).
    val sway by rememberInfiniteTransition(label = "serendipity_sway").animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2800), RepeatMode.Reverse),
            label = "serendipity_sway_value",
    )

    val content: @Composable () -> Unit = {
        Row(
                modifier =
                        Modifier.height(pillHeight)
                                .padding(horizontal = if (large) 18.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(if (large) 26.dp else 18.dp)
                )
            }
            Text(
                    text = text,
                    style =
                            if (large)
                                    MaterialTheme.typography.titleMedium
                                            .copy(fontSize = 20.sp, lineHeight = 24.sp)
                            else MaterialTheme.typography.labelLarge,
                    fontWeight = if (large) FontWeight.SemiBold else null,
                    color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }

    // v1.3.7: the highlighted pill is a live jelly blob — its outline morphs through 8
    // organic phases while the whole pill breathes (scale/shadow) and sways (±8°), all on
    // separate clocks. Everything else stays a circle (icon actions = circles, the one verb
    // action = distinctive shape).
    val eased = 0.5f - 0.5f * kotlin.math.cos(pulse * kotlin.math.PI.toFloat())
    val pillShape: androidx.compose.ui.graphics.Shape =
            if (highlight) ShapeCache.serendipityBlob(morph) else CircleShape

    if (onClick != null || onLongClick != null) {
        Surface(
                shape = pillShape,
                color = color,
                modifier =
                        Modifier.clip(pillShape)
                                .graphicsLayer {
                                    if (highlight) {
                                        // v1.3.5: noticeably livelier than the first pass
                                        // (scale 1→1.05, eased sine). v1.3.9: shadow dropped.
                                        val scale = 1f + 0.05f * eased
                                        scaleX = scale
                                        scaleY = scale
                                        rotationZ = 8f * sway
                                    }
                                }
                                .combinedClickable(
                                        onClick = { onClick?.invoke() },
                                        onLongClick = onLongClick
                                )
        ) {
            content()
        }
    } else {
        Surface(shape = pillShape, color = color) { content() }
    }
}
