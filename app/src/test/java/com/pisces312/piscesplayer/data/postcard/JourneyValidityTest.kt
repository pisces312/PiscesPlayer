package com.pisces312.piscesplayer.data.postcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validity rules of design §4.2 and its boundary table (§13): 89.999 s invalid, 90 s valid
 * when the ratio allows, 59.999% invalid, forward seek invalid, pause does not accumulate.
 */
class JourneyValidityTest {

    @Test
    fun sixtyPercentDominatesForLongSongs() {
        // 600 s song: 60% = 360 s, comfortably above the 90 s floor.
        assertEquals(360_000L, JourneyValidity.requiredPlayMs(600_000L))
    }

    @Test
    fun shortSongsUseTheNinetySecondFloor() {
        assertEquals(90_000L, JourneyValidity.requiredPlayMs(100_000L))
        assertEquals(90_000L, JourneyValidity.requiredPlayMs(120_000L))
        // Exactly 150 s: 60% = 90 s, the two thresholds meet.
        assertEquals(90_000L, JourneyValidity.requiredPlayMs(150_000L))
        // 151 s: the ratio now dominates.
        assertEquals(90_600L, JourneyValidity.requiredPlayMs(151_000L))
    }

    @Test
    fun songsShorterThanNinetySecondsCanNeverSettle() {
        assertFalse(JourneyValidity.isValidListen(89_000L, 89_000L, invalidatedBySeek = false))
        assertFalse(JourneyValidity.isValidListen(89_000L, 88_999L, invalidatedBySeek = false))
        assertFalse(JourneyValidity.isValidListen(60_000L, 60_000L, invalidatedBySeek = false))
    }

    @Test
    fun boundaryAtExactlyNinetySeconds() {
        assertFalse(JourneyValidity.isValidListen(100_000L, 89_999L, invalidatedBySeek = false))
        assertTrue(JourneyValidity.isValidListen(100_000L, 90_000L, invalidatedBySeek = false))
    }

    @Test
    fun boundaryAtFiftyNinePointNineNineNinePercent() {
        // 300 s song: 60% = 180 000 ms.
        assertFalse(JourneyValidity.isValidListen(300_000L, 179_999L, invalidatedBySeek = false))
        assertTrue(JourneyValidity.isValidListen(300_000L, 180_000L, invalidatedBySeek = false))
    }

    @Test
    fun forwardSeekInvalidatesRegardlessOfDuration() {
        assertFalse(JourneyValidity.isValidListen(300_000L, 300_000L, invalidatedBySeek = true))
        assertFalse(JourneyValidity.isValidListen(100_000L, 100_000L, invalidatedBySeek = true))
    }

    @Test
    fun unknownDurationNeverSettles() {
        assertFalse(JourneyValidity.isValidListen(0L, 999_999L, invalidatedBySeek = false))
    }

    @Test
    fun seekToleranceIsTwoSeconds() {
        assertEquals(2_000L, JourneyValidity.FORWARD_SEEK_TOLERANCE_MS)
    }

    @Test
    fun forwardSeekBeyondTwoSecondsInvalidates() {
        assertTrue(JourneyValidity.isInvalidatingSeek(30_000L, 32_001L))
        assertTrue(JourneyValidity.isInvalidatingSeek(0L, 100_000L))
    }

    @Test
    fun forwardSeekWithinToleranceAndBackwardSeekStayValid() {
        assertFalse(JourneyValidity.isInvalidatingSeek(30_000L, 32_000L))
        assertFalse(JourneyValidity.isInvalidatingSeek(100_000L, 50_000L))
        assertFalse(JourneyValidity.isInvalidatingSeek(100_000L, 100_000L))
        // Regression: 100 s of normal playback followed by a backward seek used to be
        // misread as a +50 s forward jump when compared against the registration position.
        assertFalse(JourneyValidity.isInvalidatingSeek(100_000L, 50_000L))
    }
}
