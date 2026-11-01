package com.lostf1sh.pixelplayeross.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmFrameEstimatorTest {

    @Test
    fun interpolatesBetweenAnchors() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)

        assertEquals(0.0, estimator.currentFrame(0), 0.0)
        assertEquals(500.0, estimator.currentFrame(500_000_000L), 0.0)
    }

    @Test
    fun acceptsMonotonicTimestampAndConvertsToBootTime() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        // Device has been up ~99s, so the two time bases are far apart: taking
        // timestampNs as BOOTTIME directly would put the anchor 99s in the past.
        val bootOffsetNs = 99_000_000_000L
        estimator.start(nowBootNs = bootOffsetNs)
        val monoNs = 1_000_000_000L
        val bootNs = bootOffsetNs + monoNs

        val accepted = estimator.offerTimestamp(
            frame = 1000.0,
            timestampNs = monoNs,
            monoNs = monoNs,
            bootNs = bootNs,
        )

        assertTrue(accepted)
        assertEquals(1500.0, estimator.currentFrame(bootNs + 500_000_000L), 0.0)
    }

    @Test
    fun rejectsUnknownTimeBase() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)

        val accepted = estimator.offerTimestamp(
            frame = 500.0,
            timestampNs = 100_000_000_000L,
            monoNs = 0,
            bootNs = 0,
        )

        assertFalse(accepted)
        assertEquals(0.0, estimator.currentFrame(0), 0.0)
    }

    @Test
    fun discardsLargeJumps() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1_000_000)
        estimator.start(nowBootNs = 0)

        val accepted = estimator.offerTimestamp(
            frame = 10_000.0, // 10s ahead of the interpolation at t=0
            timestampNs = 0,
            monoNs = 0,
            bootNs = 0,
        )

        assertFalse(accepted)
    }

    @Test
    fun pauseFreezesAndResumeDoesNotRewind() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)
        estimator.pauseAt(500.0)
        assertEquals(500.0, estimator.currentFrame(600_000_000L), 0.0)

        estimator.resumeFrom(500.0, nowBootNs = 1_000_000_000L)
        assertEquals(500.0, estimator.currentFrame(1_000_000_000L), 0.0)
        assertEquals(600.0, estimator.currentFrame(1_100_000_000L), 0.0)
    }

    @Test
    fun clampsToTotalFrames() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1000)
        estimator.start(nowBootNs = 0)

        assertEquals(1000.0, estimator.currentFrame(10_000_000_000L), 0.0)
    }
}
