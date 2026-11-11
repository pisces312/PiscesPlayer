// SPDX-License-Identifier: GPL-3.0-or-later (Original work by pisces312)
package com.pisces312.piscesplayer.data.importer.poweramp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 路径规范化单元测试（poweramp-import-feature-plan §2.2）。 */
class PowerampPathNormalizerTest {

    private val normalizer = PowerampPathNormalizer(
        mapOf(
            "primary" to "/storage/emulated/0",
            "9c33-6bbd" to "/storage/9C33-6BBD"
        )
    )

    @Test
    fun `primary volume maps to emulated storage root`() {
        assertEquals(
            "/storage/emulated/0/Music4Phone/02 - 煎熬.mp3",
            normalizer.normalize("primary/Music4Phone/02 - 煎熬.mp3")
        )
    }

    @Test
    fun `sd card volume prefix is case-insensitive`() {
        assertEquals(
            "/storage/9C33-6BBD/Music4Phone/a.mp3",
            normalizer.normalize("9C33-6BBD/Music4Phone/a.mp3")
        )
    }

    @Test
    fun `bare file name has no absolute path (falls back to level 2 matching)`() {
        assertNull(normalizer.normalize("01 - 彩云追月.mp3"))
    }

    @Test
    fun `unknown volume returns null (device changed or sd removed)`() {
        assertNull(normalizer.normalize("AAAA-BBBB/Music/a.mp3"))
    }

    @Test
    fun `windows separators and trailing slash are unified`() {
        assertEquals(
            "/storage/emulated/0/Music/a.mp3",
            normalizer.normalize("primary\\Music\\a.mp3")
        )
    }

    @Test
    fun `fileName extracts last segment`() {
        assertEquals("b.mp3", normalizer.fileName("primary/Music/sub/b.mp3"))
        assertEquals("b.mp3", normalizer.fileName("b.mp3"))
    }

    @Test
    fun `directoryHints returns intermediate directories without volume and file`() {
        assertEquals(
            listOf("Music4Phone", "周杰伦", "十一月的萧邦"),
            normalizer.directoryHints("primary/Music4Phone/周杰伦/十一月的萧邦/01 - 安静.mp3")
        )
        assertEquals(emptyList<String>(), normalizer.directoryHints("primary/b.mp3"))
        assertEquals(emptyList<String>(), normalizer.directoryHints("b.mp3"))
    }
}
