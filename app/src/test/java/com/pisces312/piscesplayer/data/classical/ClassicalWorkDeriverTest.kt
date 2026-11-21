package com.pisces312.piscesplayer.data.classical

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassicalWorkDeriverTest {

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
        durationMs = 1000L
    )

    @Test
    fun `tracks sharing an identical opus form one work`() {
        val works = ClassicalWorkDeriver.derive(
            listOf(
                track(1, "I. Allegro", opus = "Op. 2"),
                track(2, "II. Andante", opus = "Op. 2")
            )
        ).getValue(16000000000001L)

        assertEquals(1, works.size)
        assertEquals(listOf(1L, 2L), works[0].trackIds)
        assertEquals("Op. 2", works[0].opus)
        // divergent base titles → the work is named by the opus itself
        assertEquals("Op. 2", works[0].names.original)
    }

    @Test
    fun `different opus designations stay separate works`() {
        // "op. 30, Nr. 2" vs "Op. 30, No. 3" —— 整串不同即不合并（避免把不同奏鸣曲误并为一作）。
        val works = ClassicalWorkDeriver.derive(
            listOf(
                track(1, "Abendlied, op. 30, Nr. 2", opus = "op. 30, Nr. 2"),
                track(2, "Ich halte ihr die Augen zu, Op. 30, No. 3", opus = "Op. 30, No. 3")
            )
        ).getValue(16000000000001L)

        assertEquals(2, works.size)
        assertEquals("Abendlied", works[0].names.original)
        assertEquals("Ich halte ihr die Augen zu", works[1].names.original)
    }

    @Test
    fun `single-track opus work is named by the piece`() {
        val works = ClassicalWorkDeriver.derive(
            listOf(track(1, "Rondo, Opus 42, No. 1", opus = "Opus 42, No. 1"))
        ).getValue(16000000000001L)

        assertEquals(1, works.size)
        assertEquals("Rondo", works[0].names.original)
        assertEquals("Opus 42, No. 1", works[0].opus)
    }

    @Test
    fun `movement titles under a shared prefix form one work`() {
        val works = ClassicalWorkDeriver.derive(
            listOf(
                track(1, "La Traviata - N1 Introduzione"),
                track(2, "La Traviata - N2 Brindisi"),
                track(3, "The St. Louis Rag")
            )
        ).getValue(16000000000001L)

        assertEquals(2, works.size)
        assertEquals(listOf(1L, 2L), works[0].trackIds)
        assertEquals("La Traviata", works[0].names.original)
        assertEquals(listOf(3L), works[1].trackIds)
    }

    @Test
    fun `same-titled arrangements share one work`() {
        val works = ClassicalWorkDeriver.derive(
            listOf(
                track(1, "Heidenröslein"),
                track(2, "Heidenröslein")
            )
        ).getValue(16000000000001L)

        assertEquals(1, works.size)
        assertEquals(listOf(1L, 2L), works[0].trackIds)
    }

    @Test
    fun `distinct single pieces stay separate works`() {
        val works = ClassicalWorkDeriver.derive(
            listOf(
                track(1, "Veni Creator (1er verset)"),
                track(2, "Wellesley (hymntune)")
            )
        ).getValue(16000000000001L)

        assertEquals(2, works.size)
        assertTrue(works.none { it.isMultiTrack })
    }
}
