package com.pisces312.piscesplayer.presentation.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Long-press arbitration inside the home grid.
 *
 * The grid's own long press (enter edit mode + drag-reorder) is detected on the grid node with
 * `detectDragGesturesAfterLongPress`, i.e. it starts from a down that a child may already have
 * consumed — `requireUnconsumed = false` is deliberate, because every card is `clickable` and
 * otherwise a long press on a card would never start editing.
 *
 * The flip side is that a control *inside* a card cannot win the long press by consuming it: the
 * grid's `awaitLongPressOrCancellation` only cancels on consumption that happens **after** the
 * down (the down's own consumed flag is ignored on purpose), while a `combinedClickable` child
 * consumes only the down. So both handlers fire: the Serendipity pill opens the describe sheet
 * and the grid enters edit mode behind it.
 *
 * This registry is the deterministic fix for that: a control declares the area it owns (in the
 * grid's local coordinate space) and the grid's detector skips the whole gesture when the press
 * began there. Bounds are read lazily from the child's live [LayoutCoordinates], so they follow
 * reorders without any bookkeeping.
 */
class HomeGridGestureOwners {
    private var gridCoordinates: LayoutCoordinates? = null
    private val owners = mutableMapOf<Any, Claim>()

    private class Claim(val coordinates: LayoutCoordinates, val padPx: Float)

    /** Called by [HomeGrid] once its node is placed; claims are resolved against this space. */
    internal fun attachGrid(coordinates: LayoutCoordinates) {
        gridCoordinates = coordinates
    }

    internal fun claim(key: Any, coordinates: LayoutCoordinates, padPx: Float) {
        owners[key] = Claim(coordinates, padPx)
    }

    internal fun release(key: Any) {
        owners.remove(key)
    }

    /**
     * True when [offset] (in the grid's local space) lands on a control that handles the long
     * press itself, so the grid must leave the gesture alone.
     */
    fun owns(offset: Offset): Boolean {
        val grid = gridCoordinates ?: return false
        if (!grid.isAttached) return false
        return owners.values.any { claim ->
            if (!claim.coordinates.isAttached) return@any false
            val box = grid.localBoundingBoxOf(claim.coordinates, clipBounds = false)
            val pad = claim.padPx
            Rect(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
                .contains(offset)
        }
    }
}

/**
 * The registry provided by the enclosing [HomeGrid], or null outside one. A null local makes
 * [Modifier.homeGridLongPressOwner] a no-op, so a card body can be previewed standalone.
 */
val LocalHomeGridGestureOwners = compositionLocalOf<HomeGridGestureOwners?> { null }

/**
 * Claims this element's bounds for the long-press it handles itself (see [HomeGridGestureOwners]).
 *
 * [pad] covers the sliver a live-shape control may paint outside its layout bounds (the
 * Serendipity pill scales up to 1.05 while breathing), so pressing its edge does not fall
 * through to the grid's edit-mode long press.
 */
@Composable
fun Modifier.homeGridLongPressOwner(pad: Dp = 10.dp): Modifier {
    val owners = LocalHomeGridGestureOwners.current ?: return this
    val key = remember { Any() }
    val padPx = with(LocalDensity.current) { pad.toPx() }
    DisposableEffect(owners, key) {
        onDispose { owners.release(key) }
    }
    return this.onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) owners.claim(key, coordinates, padPx)
    }
}
