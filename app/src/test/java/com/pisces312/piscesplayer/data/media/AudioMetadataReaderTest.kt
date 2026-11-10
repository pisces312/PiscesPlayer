package com.pisces312.piscesplayer.data.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioMetadataReaderTest {

    @Test
    fun `normalizeArtistMetadataValues preserves repeated fields in source order`() {
        val values = normalizeArtistMetadataValues(
            listOf("Primary Artist", " Guest Artist ", "primary artist", ""),
            listOf("Another Guest")
        )

        assertEquals(
            listOf("Primary Artist", "Guest Artist", "Another Guest"),
            values
        )
    }

    @Test
    fun `parseReleaseDateTag accepts full dates in common tag formats`() {
        assertEquals("1998-05-12", parseReleaseDateTag("1998-05-12"))
        assertEquals("1998-05-12", parseReleaseDateTag("1998-05-12T10:30:00"))
        assertEquals("1998-05-12", parseReleaseDateTag("1998/5/12"))
        assertEquals("1998-05-12", parseReleaseDateTag(" 1998-5-12 "))
    }

    @Test
    fun `parseReleaseDateTag rejects year-only and partial dates`() {
        assertEquals(null, parseReleaseDateTag("1998"))
        assertEquals(null, parseReleaseDateTag("1998-05"))
        assertEquals(null, parseReleaseDateTag(null))
        assertEquals(null, parseReleaseDateTag(""))
        assertEquals(null, parseReleaseDateTag("not a date"))
    }

    @Test
    fun `parseReleaseDateTag rejects invalid calendar values`() {
        assertEquals(null, parseReleaseDateTag("1998-13-01"))
        assertEquals(null, parseReleaseDateTag("1998-00-10"))
        assertEquals(null, parseReleaseDateTag("1998-05-32"))
        assertEquals(null, parseReleaseDateTag("1998-05-00"))
    }

    @Test
    fun `deriveReleaseDateValue uses full date when present`() {
        assertEquals("1998-05-12", deriveReleaseDateValue("1998-05-12", 2001))
        assertEquals("1998-05-12", deriveReleaseDateValue("1998/5/12", null))
    }

    @Test
    fun `deriveReleaseDateValue falls back to january first of the year`() {
        assertEquals("1998-01-01", deriveReleaseDateValue(null, 1998))
        assertEquals("0999-01-01", deriveReleaseDateValue(null, 999))
    }

    @Test
    fun `deriveReleaseDateValue marks files without any date info as checked`() {
        assertEquals("0", deriveReleaseDateValue(null, null))
        assertEquals("0", deriveReleaseDateValue(null, 0))
        assertEquals("0", deriveReleaseDateValue("garbage", -5))
    }
}
