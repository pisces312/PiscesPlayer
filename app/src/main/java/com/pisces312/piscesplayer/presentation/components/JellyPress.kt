package com.pisces312.piscesplayer.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch

/**
 * Jelly press feedback for pressable cards, covers and buttons.
 *
 * While a finger is down the element squashes (volume-preserving: taller axis shrinks,
 * shorter axis bulges) and pivots around the touch point like a soft surface giving way;
 * dragging tilts it toward the drag direction. On release it settles back with a bouncy
 * spring that overshoots — the same "living jelly" feel as the Serendipity pill.
 *
 * Implementation notes:
 * - Everything happens in ONE [graphicsLayer]: all animated state is read inside the
 *   layer lambda, so the effect never recomposes — it only re-configures the layer.
 * - The effect runs ONLY while a finger is on the element (the Animatables idle at 0
 *   and nothing ticks), so unlike an infinite animation it costs nothing at rest.
 * - The gesture is observed on [PointerEventPass.Final] and never consumes events, so
 *   clicks, long-presses, scrolling and drag-reorder all keep working unchanged.
 * - A light haptic tick fires on press through the app-scoped LocalHapticFeedback
 *   (honours the in-app haptics switch and the system setting).
 */
private val JellyPressSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
)

private val JellySettleSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
)

@Composable
fun Modifier.jellyPress(enabled: Boolean = true): Modifier {
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(0f) }
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    val pressFraction = remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    val haptics = LocalHapticFeedback.current

    val observed = this.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            pressFraction.value = Offset(
                    (down.position.x / size.width).coerceIn(0f, 1f),
                    (down.position.y / size.height).coerceIn(0f, 1f)
            )
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scope.launch { press.animateTo(1f, JellyPressSpring) }
            val pointerId = down.id
            while (true) {
                // Final pass: keep observing even when a parent (scroll / drag-reorder)
                // consumes the movement first.
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                // snapTo is a suspending call and awaitEachGesture runs in a restricted
                // scope — hand the writes to the plain coroutine scope (Main, in order).
                scope.launch { spin.snapTo((dx * 0.045f).coerceIn(-4f, 4f)) }
                scope.launch { tiltY.snapTo((dx * 0.14f).coerceIn(-9f, 9f)) }
                scope.launch { tiltX.snapTo((-dy * 0.14f).coerceIn(-9f, 9f)) }
                if (!change.pressed) break
            }
            scope.launch { press.animateTo(0f, JellySettleSpring) }
            scope.launch { spin.animateTo(0f, JellySettleSpring) }
            scope.launch { tiltX.animateTo(0f, JellySettleSpring) }
            scope.launch { tiltY.animateTo(0f, JellySettleSpring) }
        }
    }
    if (!enabled) return observed
    return observed.graphicsLayer {
        // Animated state is read HERE (layer phase): zero recomposition per frame.
        val p = press.value
        scaleX = 1f + 0.035f * p
        scaleY = 1f - 0.05f * p
        rotationX = tiltX.value
        rotationY = tiltY.value
        rotationZ = spin.value
        transformOrigin = TransformOrigin(pressFraction.value.x, pressFraction.value.y)
    }
}
