package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmFrameEstimatorTest {

    /** Release the startup park the way a healthy device does: the output's first real frame. */
    private fun releaseStartupHold(estimator: RhythmFrameEstimator, atMs: Long, frame: Double) {
        assertTrue(estimator.offerHeadPosition(frame, atMs * 1_000_000L))
    }

    @Test
    fun interpolatesBetweenAnchors() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)
        releaseStartupHold(estimator, atMs = 100, frame = 100.0)

        assertEquals(100.0, estimator.currentFrame(100_000_000L), 0.0)
        assertEquals(600.0, estimator.currentFrame(600_000_000L), 0.0)
    }

    @Test
    fun parksAtTheAnchorUntilTheOutputReportsRealProgress() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)

        // The clock's zero must land on the output's acoustic zero, so it parks at the anchor
        // while the output starts: the notes wait at their song-time-zero positions instead of
        // falling to the judgement line before their music plays.
        assertEquals(0.0, estimator.currentFrame(30_000_000L), 0.0)
        assertEquals(0.0, estimator.currentFrame(45_000_000L), 0.0)

        // "Nothing rendered yet" samples (framePosition 0, moving clock time) do not release.
        assertFalse(estimator.offerHeadPosition(0.0, 50_000_000L))
        assertFalse(
            estimator.offerTimestamp(
                frame = 0.0,
                timestampNs = 50_000_000L,
                monoNs = 50_000_000L,
                bootNs = 50_000_000L,
            ),
        )
        assertEquals(0.0, estimator.currentFrame(50_000_000L), 0.0)

        // The first sample showing real frame progress is the output actually starting; it
        // releases the hold and anchors the run's line right there.
        releaseStartupHold(estimator, atMs = 60, frame = 15.0)
        assertEquals(15.0, estimator.currentFrame(60_000_000L), 0.0)
        assertEquals(65.0, estimator.currentFrame(110_000_000L), 0.0)
    }

    @Test
    fun graceExpiryFallsBackToTheWallClockLine() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)

        // Parked within the grace window: the emitted frame stays at the anchor.
        assertEquals(0.0, estimator.currentFrame(300_000_000L), 0.0)

        // No progress sample ever arrived (pathological device): gameplay must not stall
        // forever, so the park expires onto the wall-clock line — rate-limited, so even this
        // fallback is a smooth speed-up rather than a jump.
        assertEquals(225.0, estimator.currentFrame(450_000_000L), 0.0)
        assertEquals(900.0, estimator.currentFrame(900_000_000L), 0.0) // caught up with raw
    }

    @Test
    fun leftoverHeadCounterFromAPreviousStreamIsIgnored() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1_000_000)
        estimator.start(nowBootNs = 0)

        // Some devices do not reset playbackHeadPosition across flush/stop/play: the first
        // reading after a restart can be the previous run's counter. The head can never
        // legitimately race ahead of the wall-clock line at start-up, so such a sample must not
        // release the park onto it (that would throw every note downward on retry).
        assertFalse(estimator.offerHeadPosition(1_300_000.0, 50_000_000L))
        assertEquals(0.0, estimator.currentFrame(50_000_000L), 0.0)

        // The real counter (from 0) releases normally.
        releaseStartupHold(estimator, atMs = 60, frame = 15.0)
        assertEquals(15.0, estimator.currentFrame(60_000_000L), 0.0)
    }

    @Test
    fun nonProgressTimestampsDoNotAnchor() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)
        assertTrue(
            estimator.offerTimestamp(
                frame = 100.0,
                timestampNs = 100_000_000L,
                monoNs = 100_000_000L,
                bootNs = 100_000_000L,
            ),
        )

        // The same framePosition with a moving clock time means "the line has not moved";
        // anchoring it would slide the anchor along the time axis and pin the estimate — and
        // the next real sample would then throw every note downward in one jump.
        assertFalse(
            estimator.offerTimestamp(
                frame = 100.0,
                timestampNs = 200_000_000L,
                monoNs = 200_000_000L,
                bootNs = 200_000_000L,
            ),
        )
        assertEquals(200.0, estimator.currentFrame(200_000_000L), 0.0) // keeps gliding
    }

    @Test
    fun backwardCorrectionHoldsTheOutputInsteadOfJumpingBack() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)
        releaseStartupHold(estimator, atMs = 50, frame = 50.0)
        assertEquals(60.0, estimator.currentFrame(60_000_000L), 0.0)

        // A latency-compensated timestamp can sit behind the line the clock is on. The estimate
        // snaps back onto it — but the emitted frame must not: notes leaping upwards is the
        // game-start tremble. The output holds until the corrected line catches up.
        val accepted = estimator.offerTimestamp(
            frame = 5.0,
            timestampNs = 60_000_000L,
            monoNs = 60_000_000L,
            bootNs = 60_000_000L,
        )
        assertTrue(accepted)

        assertEquals(60.0, estimator.currentFrame(60_000_000L), 0.0) // held, not 5
        assertEquals(60.0, estimator.currentFrame(100_000_000L), 0.0) // still catching up
        assertEquals(60.0, estimator.currentFrame(115_000_000L), 0.0) // boundary: caught up
        assertEquals(61.0, estimator.currentFrame(116_000_000L), 0.0) // resumes on the new line
    }

    @Test
    fun forwardCorrectionIsRateLimitedNotJumped() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)
        releaseStartupHold(estimator, atMs = 100, frame = 100.0)
        assertEquals(100.0, estimator.currentFrame(100_000_000L), 0.0)

        // A device line 200 frames ahead (within jump tolerance) must not throw the notes
        // downward in one step: the emitted frame eases toward it at most 1.5x real time.
        assertTrue(
            estimator.offerTimestamp(
                frame = 300.0,
                timestampNs = 100_000_000L,
                monoNs = 100_000_000L,
                bootNs = 100_000_000L,
            ),
        )
        assertEquals(100.0, estimator.currentFrame(100_000_000L), 0.0) // no jump
        assertEquals(124.0, estimator.currentFrame(116_000_000L), 0.0) // exactly at the rate cap
        assertEquals(250.0, estimator.currentFrame(200_000_000L), 0.0) // still capped
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
        assertTrue(
            estimator.offerTimestamp(
                frame = 100.0,
                timestampNs = 100_000_000L,
                monoNs = 100_000_000L,
                bootNs = 100_000_000L,
            ),
        )

        // 10s ahead of the interpolation: a stale leftover, not a real repositioning.
        val accepted = estimator.offerTimestamp(
            frame = 10_000.0,
            timestampNs = 200_000_000L,
            monoNs = 200_000_000L,
            bootNs = 200_000_000L,
        )

        assertFalse(accepted)
        assertEquals(200.0, estimator.currentFrame(200_000_000L), 0.0)
    }

    @Test
    fun pauseFreezesAndResumeReParksThenContinues() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)
        releaseStartupHold(estimator, atMs = 50, frame = 50.0)
        estimator.pauseAt(500.0)
        assertEquals(500.0, estimator.currentFrame(600_000_000L), 0.0)

        estimator.resumeFrom(500.0, nowBootNs = 1_000_000_000L)
        // The output restarts after a pause just like after play(): park again until a real
        // frame re-establishes the line, instead of running ahead of the silent output.
        assertEquals(500.0, estimator.currentFrame(1_050_000_000L), 0.0)
        releaseStartupHold(estimator, atMs = 1100, frame = 510.0)
        assertEquals(510.0, estimator.currentFrame(1_100_000_000L), 0.0)
        assertEquals(560.0, estimator.currentFrame(1_150_000_000L), 0.0)
    }

    @Test
    fun resumeOutputNeverRewindsOntoALateDeviceLine() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 10_000)
        estimator.start(nowBootNs = 0)
        releaseStartupHold(estimator, atMs = 50, frame = 50.0)
        estimator.pauseAt(500.0)
        estimator.resumeFrom(500.0, nowBootNs = 1_000_000_000L)
        releaseStartupHold(estimator, atMs = 1100, frame = 520.0)
        assertEquals(520.0, estimator.currentFrame(1_100_000_000L), 0.0)

        // The device reports a line behind the resumed interpolation; the estimate corrects,
        // the emitted frame holds at 520 and catches up instead of rewinding.
        assertTrue(
            estimator.offerTimestamp(
                frame = 505.0,
                timestampNs = 1_100_000_000L,
                monoNs = 1_100_000_000L,
                bootNs = 1_100_000_000L,
            ),
        )
        assertEquals(520.0, estimator.currentFrame(1_100_000_000L), 0.0)
        assertEquals(520.0, estimator.currentFrame(1_105_000_000L), 0.0) // still catching up
        assertEquals(520.0, estimator.currentFrame(1_115_000_000L), 0.0) // boundary: caught up
        assertEquals(521.0, estimator.currentFrame(1_116_000_000L), 0.0) // resumes
    }

    @Test
    fun smallHeadDriftIsDampedNotSnapped() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1_000_000)
        estimator.start(nowBootNs = 0)
        assertTrue(estimator.offerHeadPosition(100.0, 100_000_000L)) // matches the line exactly

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
        assertTrue(estimator.offerHeadPosition(100.0, 100_000_000L))

        // Head has not advanced since the last sample (coarse HAL update). Damping toward this
        // stale value would pull the estimate back and produce a sawtooth, so it is ignored and
        // the estimate keeps gliding forward on wall-clock interpolation.
        assertFalse(estimator.offerHeadPosition(100.0, 116_000_000L))
        assertEquals(116.0, estimator.currentFrame(116_000_000L), 0.0)
    }

    @Test
    fun largeBackwardHeadErrorSnapsInsteadOfBeingDiscarded() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1_000_000)
        estimator.start(nowBootNs = 0)
        assertTrue(estimator.offerHeadPosition(1000.0, 1_000_000_000L))
        assertEquals(1000.0, estimator.currentFrame(1_000_000_000L), 0.0)

        // An underrun leaves the head far BEHIND the wall estimate; the error is beyond the
        // allowable ~33ms, so the estimate snaps onto the device instead of being discarded —
        // discarding it would freeze the clock on a prediction that is already wrong. The
        // emitted frame holds at 1000 until the snapped-back line catches up.
        assertTrue(estimator.offerHeadPosition(500.0, 1_200_000_000L))
        assertEquals(1000.0, estimator.currentFrame(1_200_000_000L), 0.0) // held, not 500
        assertEquals(1000.0, estimator.currentFrame(1_700_000_000L), 0.0) // boundary: caught up
        assertEquals(1010.0, estimator.currentFrame(1_710_000_000L), 0.0) // tracks the new line
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

    @Test
    fun clampsToTotalFrames() {
        val estimator = RhythmFrameEstimator(sampleRate = 1000, totalFrames = 1000)
        estimator.start(nowBootNs = 0)

        assertEquals(1000.0, estimator.currentFrame(10_000_000_000L), 0.0)
    }
}
