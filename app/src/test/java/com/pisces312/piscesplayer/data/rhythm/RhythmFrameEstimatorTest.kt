package com.pisces312.piscesplayer.data.rhythm

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
    fun holdsTheStartUntilTheOutputReportsFrames() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)

        // play() returned, but no frame has reached the output yet.
        assertEquals(0.0, estimator.currentFrame(80_000_000L), 0.0)

        val accepted = estimator.offerTimestamp(
            frame = 0.0,
            timestampNs = 90_000_000L,
            monoNs = 90_000_000L,
            bootNs = 90_000_000L,
        )

        assertTrue(accepted)
        assertEquals(0.0, estimator.currentFrame(90_000_000L), 0.0)
        assertEquals(50.0, estimator.currentFrame(140_000_000L), 0.0)
    }

    @Test
    fun rejectsTimestampFromBeforeTheRunStarted() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 5_000_000_000L)

        // Leftover sample from the previous stream: frame 0 was reached 100ms before the start.
        val accepted = estimator.offerTimestamp(
            frame = 0.0,
            timestampNs = 4_900_000_000L,
            monoNs = 5_050_000_000L,
            bootNs = 5_050_000_000L,
        )

        assertFalse(accepted)
    }

    @Test
    fun startsOnItsOwnWhenNoTimestampArrives() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)

        assertEquals(0.0, estimator.currentFrame(300_000_000L), 0.0)
        assertEquals(450.0, estimator.currentFrame(450_000_000L), 0.0)
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

    @Test
    fun headPositionReleasesTheStartupHold() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)

        // Holding at 0 until audio really renders.
        assertEquals(0.0, estimator.currentFrame(50_000_000L), 0.0)

        // Head still 0: a silent output must not release the hold.
        assertFalse(estimator.offerHeadPosition(0.0, 60_000_000L))
        assertEquals(0.0, estimator.currentFrame(60_000_000L), 0.0)

        // First real head sample becomes the anchor and starts interpolation.
        assertTrue(estimator.offerHeadPosition(100.0, 80_000_000L))
        assertEquals(100.0, estimator.currentFrame(80_000_000L), 0.0)
        assertEquals(150.0, estimator.currentFrame(130_000_000L), 0.0)
    }

    @Test
    fun headPositionSnapsWhenDriftExceedsAllowableError() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1_000_000)
        estimator.start(nowBootNs = 0)
        // Release the hold on the interpolation line (head matches prediction at t=500ms).
        assertTrue(estimator.offerHeadPosition(500.0, 500_000_000L))
        assertEquals(500.0, estimator.currentFrame(500_000_000L), 0.0)

        // Output stalls: at t=1000ms interpolation predicts 1000 but head only reached 900 —
        // a 100ms error, past osu!'s ~33ms allowable error, so it snaps straight onto the
        // device instead of damping. That cancels an underrun at once rather than letting it
        // compound into progressive drift.
        assertTrue(estimator.offerHeadPosition(900.0, 1_000_000_000L))
        assertEquals(900.0, estimator.currentFrame(1_000_000_000L), 0.0)
        // Interpolation resumes from the corrected anchor.
        assertEquals(950.0, estimator.currentFrame(1_050_000_000L), 0.0)
    }

    @Test
    fun smallHeadDriftIsDampedNotSnapped() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1_000_000)
        estimator.start(nowBootNs = 0)
        assertTrue(estimator.offerHeadPosition(100.0, 100_000_000L)) // release on the line

        // At t=116ms interpolation predicts 116 but head reports 106: a 10ms error, within the
        // ~33ms allowable error, so osu!'s exponential damp corrects only part of it this frame
        // (half-life 80ms -> fraction 1 - 0.5^(16/80) = 0.1294), not a snap. This is what keeps
        // the notes from shimmering on the head's coarse update granularity.
        assertTrue(estimator.offerHeadPosition(106.0, 116_000_000L))
        val damped = estimator.currentFrame(116_000_000L)
        assertTrue("eased toward head: $damped", damped < 116.0)
        assertTrue("but not snapped onto it: $damped", damped > 106.0)
        assertEquals(114.7055, damped, 0.001)
    }

    @Test
    fun staleHeadSampleDoesNotDragTheEstimateBack() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1_000_000)
        estimator.start(nowBootNs = 0)
        assertTrue(estimator.offerHeadPosition(100.0, 100_000_000L)) // release on the line

        // Head has not advanced since the last sample (coarse HAL update). Damping toward this
        // stale value would pull the estimate back and produce a sawtooth, so it is ignored and
        // the estimate keeps gliding forward on wall-clock interpolation.
        assertFalse(estimator.offerHeadPosition(100.0, 116_000_000L))
        assertEquals(116.0, estimator.currentFrame(116_000_000L), 0.0)
    }

    @Test
    fun largeHeadErrorSnapsInsteadOfBeingDiscarded() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1_000_000)
        estimator.start(nowBootNs = 0)
        assertTrue(estimator.offerHeadPosition(1000.0, 1_000_000_000L))

        // A reading far beyond the allowable error still snaps rather than being discarded:
        // discarding it would freeze the clock on a prediction that is already wrong.
        assertTrue(estimator.offerHeadPosition(900_000.0, 1_000_000_000L))
        assertEquals(900_000.0, estimator.currentFrame(1_000_000_000L), 0.0)
    }

    @Test
    fun headPositionIsIgnoredWhileATimestampIsFresh() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1_000_000)
        estimator.start(nowBootNs = 0)
        val accepted = estimator.offerTimestamp(
            frame = 1000.0,
            timestampNs = 1_000_000_000L,
            monoNs = 1_000_000_000L,
            bootNs = 1_000_000_000L,
        )
        assertTrue(accepted)

        // AudioTimestamp is the precise source; a head sample right after it must not re-anchor.
        assertFalse(estimator.offerHeadPosition(5000.0, 1_050_000_000L))
        assertEquals(1050.0, estimator.currentFrame(1_050_000_000L), 0.0)
    }

    @Test
    fun headPositionResumesAfterATimestampGoesStale() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1_000_000)
        estimator.start(nowBootNs = 0)
        estimator.offerTimestamp(
            frame = 1000.0,
            timestampNs = 1_000_000_000L,
            monoNs = 1_000_000_000L,
            bootNs = 1_000_000_000L,
        )

        // Once the timestamp ages past the freshness window, head corrections take over again.
        assertTrue(estimator.offerHeadPosition(1350.0, 1_400_000_000L))
    }
}
