package com.pisces312.piscesplayer.data.home

/**
 * Resolved geometry for one home card: which (row, column) of the 2-column
 * grid it starts at. Size is carried by the [HomeCard]; only the origin is
 * needed here because the grid pitch is fixed by the caller.
 */
data class HomePlacement(val row: Int, val column: Int)

/**
 * First-fit dense placement (home-cards-grid-plan.md §3.2).
 *
 * The card *order* in [cards] is the single source of truth; the geometry is
 * derived deterministically by scanning rows top-to-bottom, columns
 * left-to-right, and placing each card at the first free slot that fits its
 * span. Consequences:
 *
 *  - The layout is always hole-free: removing a card lets the ones below it
 *    back-fill on the next placement.
 *  - A card that is taller or wider than the current tail is placed at the
 *    nearest slot that can hold it, not necessarily directly under the cursor
 *    — that is the inherent behavior of dense first-fit.
 *
 * @return a map from card uid to its [HomePlacement]. The returned row count
 *  ([totalRows]) already counts the trailing rows of every placed card.
 */
fun placeHomeCards(cards: List<HomeCard>): HomeGridPlacement {
    if (cards.isEmpty()) return HomeGridPlacement(emptyMap(), 0)

    val occupied = HashSet<Long>(cards.size * 4)
    val placements = HashMap<String, HomePlacement>(cards.size)

    fun slotKey(row: Int, column: Int): Long = row.toLong() * GRID_COLUMNS + column

    fun isFree(row: Int, column: Int, spanX: Int, spanY: Int): Boolean {
        if (column + spanX > GRID_COLUMNS) return false
        for (dr in 0 until spanY) {
            for (dc in 0 until spanX) {
                if (occupied.contains(slotKey(row + dr, column + dc))) return false
            }
        }
        return true
    }

    fun markOccupied(row: Int, column: Int, spanX: Int, spanY: Int) {
        for (dr in 0 until spanY) {
            for (dc in 0 until spanX) {
                occupied.add(slotKey(row + dr, column + dc))
            }
        }
    }

    var totalRows = 0
    for (card in cards) {
        var placed = false
        var row = 0
        while (!placed && row < MAX_PLACEMENT_ROWS) {
            for (column in 0..GRID_COLUMNS - card.size.spanX) {
                if (isFree(row, column, card.size.spanX, card.size.spanY)) {
                    markOccupied(row, column, card.size.spanX, card.size.spanY)
                    placements[card.uid] = HomePlacement(row = row, column = column)
                    totalRows = maxOf(totalRows, row + card.size.spanY)
                    placed = true
                    break
                }
            }
            if (!placed) row++
        }
        // With a bounded scan this is unreachable in practice; a card that
        // could not be placed is simply skipped so the grid still renders.
    }
    return HomeGridPlacement(placements = placements, totalRows = totalRows)
}

/** Result of [placeHomeCards]: per-card placement plus the grid row count. */
data class HomeGridPlacement(
    val placements: Map<String, HomePlacement>,
    val totalRows: Int,
)

private const val GRID_COLUMNS = 2

/** Safety bound so a pathological list cannot loop forever; far above any real grid. */
private const val MAX_PLACEMENT_ROWS = 10_000
