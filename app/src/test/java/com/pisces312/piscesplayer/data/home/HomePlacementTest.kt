package com.pisces312.piscesplayer.data.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HomePlacementTest {

    private fun card(uid: String, size: HomeCardSize, type: HomeCardType = HomeCardType.AI_PLAYLIST) =
        HomeCard(uid = uid, type = type, size = size)

    @Test
    fun `empty list yields empty grid`() {
        val result = placeHomeCards(emptyList())
        assertEquals(0, result.totalRows)
        assertEquals(emptyMap<String, HomePlacement>(), result.placements)
    }

    @Test
    fun `single 1x1 card occupies origin`() {
        val result = placeHomeCards(listOf(card("a", HomeCardSize.S_1x1)))
        assertEquals(HomePlacement(row = 0, column = 0), result.placements.getValue("a"))
        assertEquals(1, result.totalRows)
    }

    @Test
    fun `two 1x1 cards fill the first row side by side`() {
        val result = placeHomeCards(
            listOf(card("a", HomeCardSize.S_1x1), card("b", HomeCardSize.S_1x1))
        )
        assertEquals(HomePlacement(row = 0, column = 0), result.placements.getValue("a"))
        assertEquals(HomePlacement(row = 0, column = 1), result.placements.getValue("b"))
        assertEquals(1, result.totalRows)
    }

    @Test
    fun `2x1 banner takes the whole row`() {
        val result = placeHomeCards(
            listOf(
                card("banner", HomeCardSize.W_2x1),
                card("tile", HomeCardSize.S_1x1),
            )
        )
        assertEquals(HomePlacement(row = 0, column = 0), result.placements.getValue("banner"))
        assertEquals(HomePlacement(row = 1, column = 0), result.placements.getValue("tile"))
        assertEquals(2, result.totalRows)
    }

    @Test
    fun `2x2 card after two 1x1 tiles drops to the next full row`() {
        val result = placeHomeCards(
            listOf(
                card("a", HomeCardSize.S_1x1),
                card("b", HomeCardSize.S_1x1),
                card("big", HomeCardSize.L_2x2),
            )
        )
        assertEquals(HomePlacement(row = 0, column = 0), result.placements.getValue("a"))
        assertEquals(HomePlacement(row = 0, column = 1), result.placements.getValue("b"))
        assertEquals(HomePlacement(row = 1, column = 0), result.placements.getValue("big"))
        assertEquals(3, result.totalRows)
    }

    @Test
    fun `1x2 card after a 1x1 tile stacks in the same column`() {
        val result = placeHomeCards(
            listOf(
                card("top", HomeCardSize.S_1x1),
                card("tall", HomeCardSize.T_1x2),
            )
        )
        assertEquals(HomePlacement(row = 0, column = 0), result.placements.getValue("top"))
        // Column 1 rows 0-1 are free, so the tall card fills there first.
        assertEquals(HomePlacement(row = 0, column = 1), result.placements.getValue("tall"))
        assertEquals(2, result.totalRows)
    }

    @Test
    fun `removing a card lets the rest back-fill so the grid stays hole-free`() {
        val list = listOf(
            card("a", HomeCardSize.S_1x1),
            card("b", HomeCardSize.S_1x1),
            card("c", HomeCardSize.S_1x1),
        )
        val before = placeHomeCards(list)
        assertEquals(HomePlacement(row = 1, column = 0), before.placements.getValue("c"))

        // Removing the middle card slides the third one up into the freed slot.
        val after = placeHomeCards(listOf(list[0], list[2]))
        assertEquals(HomePlacement(row = 0, column = 1), after.placements.getValue(list[2].uid))
        assertEquals(1, after.totalRows)
    }

    @Test
    fun `default layout matches the documented arrangement`() {
        // From home-cards-grid-plan.md §9 (v1.3): AI 歌单 2×2 first, 每日推荐 2×2,
        // games side by side, 入梦 2×2 block.
        val placements = placeHomeCards(DefaultHomeLayout).placements
        assertEquals(HomePlacement(row = 0, column = 0), placements.getValue("default_ai_playlist"))
        assertEquals(HomePlacement(row = 2, column = 0), placements.getValue("default_daily_mix"))
        assertEquals(HomePlacement(row = 4, column = 0), placements.getValue("default_game_rhythm"))
        assertEquals(HomePlacement(row = 4, column = 1), placements.getValue("default_game_quiz"))
        assertEquals(HomePlacement(row = 5, column = 0), placements.getValue("default_soundscape"))
    }

    @Test
    fun `default layout has no holes`() {
        val result = placeHomeCards(DefaultHomeLayout)
        val occupied = HashMap<Long, String>()
        for (card in DefaultHomeLayout) {
            val p = result.placements[card.uid]!!
            for (dr in 0 until card.size.spanY) {
                for (dc in 0 until card.size.spanX) {
                    val key = (p.row + dr).toLong() * 2 + (p.column + dc)
                    val prior = occupied.put(key, card.uid)
                    assertTrue(prior == null) { "slot ${p.row + dr},${p.column + dc}: $prior vs ${card.uid}" }
                }
            }
        }
    }
}
