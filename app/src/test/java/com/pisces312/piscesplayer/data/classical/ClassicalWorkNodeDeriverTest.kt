package com.pisces312.piscesplayer.data.classical

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassicalWorkNodeDeriverTest {

    private fun track(
        id: Long,
        title: String,
        opus: String = "",
        titleOriginal: String = title,
        titleEn: String = title,
        titleZh: String = ""
    ) = ClassicalTrack(
        id = id,
        file = "$id.mid",
        title = title,
        titleOriginal = titleOriginal,
        titleEn = titleEn,
        titleZh = titleZh,
        composer = "C. Test",
        albumName = "Classical · C. Test",
        albumId = 16000000000001L,
        artistId = 17000000000001L,
        opus = opus,
        style = "",
        sha256 = "",
        durationMs = 1000L
    )

    @Test
    fun `single track group yields single node`() {
        val nodes = ClassicalWorkNodeDeriver.derive(
            listOf(track(1, "Rondo, Opus 42, No. 1", opus = "Opus 42, No. 1"))
        )
        assertEquals(1, nodes.size)
        assertEquals(listOf(1L), nodes[0].trackIds)
    }

    @Test
    fun `movement markers with tempo words are stripped and grouped`() {
        val nodes = ClassicalWorkNodeDeriver.derive(
            listOf(
                track(1, "Sonata in C major, Op. 2 - I. Allegro", opus = "Op. 2"),
                track(2, "Sonata in C major, Op. 2 - II. Andante", opus = "Op. 2")
            )
        )
        assertEquals(1, nodes.size)
        assertEquals(listOf(1L, 2L), nodes[0].trackIds)
    }

    @Test
    fun `movement markers without tempo words are not stripped`() {
        // "Abendlied, op. 30, Nr. 2" / "Ich halte ihr die Augen zu, Op. 30, No. 3" ——
        // 乐章标记后无速度词，应保留为独立作品（防把 Op. 27 No.1/No.2 两首夜曲误并为一作）。
        val nodes = ClassicalWorkNodeDeriver.derive(
            listOf(
                track(1, "Abendlied, op. 30, Nr. 2", opus = "op. 30, Nr. 2"),
                track(2, "Ich halte ihr die Augen zu, Op. 30, No. 3", opus = "Op. 30, No. 3")
            )
        )
        assertEquals(2, nodes.size)
        assertEquals(listOf(1L), nodes[0].trackIds)
        assertEquals(listOf(2L), nodes[1].trackIds)
    }

    @Test
    fun `same base title with different movement markers group into one node`() {
        val nodes = ClassicalWorkNodeDeriver.derive(
            listOf(
                track(1, "Sonata in C major, Op. 2 - I. Allegro"),
                track(2, "Sonata in C major, Op. 2 - II. Adagio"),
                track(3, "Sonata in C major, Op. 2 - III. Vivace")
            )
        )
        assertEquals(1, nodes.size)
        assertEquals(listOf(1L, 2L, 3L), nodes[0].trackIds)
    }

    @Test
    fun `distinct titles stay separate nodes`() {
        val nodes = ClassicalWorkNodeDeriver.derive(
            listOf(
                track(1, "Veni Creator (1er verset)"),
                track(2, "Wellesley (hymntune)")
            )
        )
        assertEquals(2, nodes.size)
        assertTrue(nodes.none { it.isMultiTrack })
    }
}
