package com.pisces312.piscesplayer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatsTest {

    @Test
    fun formatReleaseDate_nullWhenNeverRead() {
        assertNull(formatReleaseDate(null))
    }

    @Test
    fun formatReleaseDate_nullForNoDateSentinel() {
        assertNull(formatReleaseDate("0"))
    }

    @Test
    fun formatReleaseDate_nullForYearOnlyFallback() {
        // Year-only tags are derived as January 1st; the Year row already shows this.
        assertNull(formatReleaseDate("1998-01-01"))
    }

    @Test
    fun formatReleaseDate_returnsFullDate() {
        assertEquals("1998-05-12", formatReleaseDate("1998-05-12"))
        assertEquals("2024-12-31", formatReleaseDate("2024-12-31"))
    }

    @Test
    fun formatReleaseDate_nullForMalformedValue() {
        assertNull(formatReleaseDate(""))
        assertNull(formatReleaseDate("1998"))
        assertNull(formatReleaseDate("1998-5-12"))
        assertNull(formatReleaseDate("not-a-date"))
    }
}
