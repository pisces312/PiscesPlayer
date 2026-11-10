/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (AudioTimestamp time-base conversion and jump protection, JVM-testable).
 */
package com.pisces312.piscesplayer.data.rhythm

import kotlin.math.abs
import kotlin.math.pow

/**
 * Pure playback frame position estimator: a local estimate advanced by the monotonic clock
 * between output reports, then pulled back onto the audio device. Direct port of osu!lazer's
 * `InterpolatingFramedClock` (osu.Framework/Timing/InterpolatingFramedClock.cs): advance by
 * wall clock, damp the residual error exponentially (80 ms half-life), snap only once the
 * error exceeds ~33 ms (two 60 fps frames). The device source is AudioTimestamp when present
 * (an exact, latency-compensated pair, anchored directly) and otherwise the playback head.
 * Damping rather than snapping is what stops the notes shimmering on the head's coarse update
 * granularity; the tight snap threshold stops an underrun's error compounding into drift.
 *
 * Deviation from osu! (required on Android): osu! clamps forward playback monotonic
 * (`currentTime = max(lastTime, currentTime)`) because desktop audio never underruns. An
 * AudioTrack DOES underrun, so the head can fall behind the wall estimate and that backward
 * error must stay correctable; the hard monotonic clamp is therefore omitted.
 *
 * `AudioTimestamp.nanoTime` may be either `System.nanoTime()` (TIMEBASE_MONOTONIC)
 * or `SystemClock.elapsedRealtimeNanos()` (BOOTTIME). Mixing the two domains turns
 * accumulated deep sleep into playback progress and freezes gameplay. Timestamps
 * are therefore normalized to the BOOTTIME domain; unknown time bases are dropped.
 *
 * Starting playback holds the clock at the start frame until the output reports its first
 * frame: `play()` returns before sound is emitted, so running on interpolation right away
 * makes the first output sample pull the clock backwards — one visible jump of every note.
 */
class RhythmFrameEstimator(
    private val sampleRate: Int,
    private val totalFrames: Long,
) {
    companion object {
        /**
         * An AudioTimestamp whose frame disagrees with the interpolation by more than 500ms is
         * a stale leftover from the previous stream position and is discarded.
         */
        const val JUMP_TOLERANCE_NS = 500_000_000L

        /** A timestamp within 10s of a sampled clock is considered the same time base. */
        const val TIMEBASE_TOLERANCE_NS = 10_000_000_000L

        /**
         * How long the start frame is held while waiting for the first output timestamp.
         * Audio output begins some tens of ms after `play()`; advancing before that and then
         * snapping back on the first timestamp is visible as a one-time jump of every note.
         * Bounded so a device that never reports playback-head timestamps still starts.
         */
        const val STARTUP_GRACE_NS = 400_000_000L

        /**
         * osu! `InterpolatingFramedClock.AllowableErrorMilliseconds` default: two 60fps frames.
         * Head drift within this is damped smoothly; beyond it the estimate snaps to the device.
         */
        const val ALLOWABLE_ERROR_MS = 1000.0 / 60 * 2

        /** osu! `DriftRecoveryHalfLife`, overridden to 80ms by osu!game's FramedBeatmapClock. */
        const val DRIFT_HALF_LIFE_MS = 80.0

        /** AudioTimestamp is the precise source; ignore head samples while one is still fresh. */
        private const val TIMESTAMP_FRESH_NS = 200_000_000L

        /** Clock-sampling slack when rejecting timestamps older than the play/resume anchor. */
        private const val START_TOLERANCE_NS = 2_000_000L
    }

    private var playing = false
    private var anchorFrame = 0.0
    private var anchorBootNs = 0L
    private var floorFrame = 0.0
    private var startedBootNs = 0L
    private var awaitingOutput = false
    private var lastTimestampBootNs = Long.MIN_VALUE
    private var lastHeadFrame = -1.0

    val isPlaying: Boolean get() = playing
    val frozenFrame: Double get() = anchorFrame

    fun start(nowBootNs: Long) {
        playing = true
        anchorFrame = 0.0
        anchorBootNs = nowBootNs
        floorFrame = 0.0
        startedBootNs = nowBootNs
        awaitingOutput = true
        lastTimestampBootNs = Long.MIN_VALUE
        lastHeadFrame = -1.0
    }

    fun pauseAt(frame: Double) {
        playing = false
        awaitingOutput = false
        anchorFrame = frame.coerceIn(0.0, totalFrames.toDouble())
    }

    fun resumeFrom(frame: Double, nowBootNs: Long) {
        playing = true
        val resumed = frame.coerceIn(0.0, totalFrames.toDouble())
        anchorFrame = resumed
        anchorBootNs = nowBootNs
        floorFrame = resumed
        startedBootNs = nowBootNs
        awaitingOutput = false
        lastTimestampBootNs = Long.MIN_VALUE
        lastHeadFrame = -1.0
    }

    /**
     * Offer one AudioTimestamp sample; accepted samples become the new anchor.
     *
     * @param frame reported frame position
     * @param timestampNs reported nanoTime with unknown time base
     * @param monoNs `System.nanoTime()` sampled at the same moment
     * @param bootNs `SystemClock.elapsedRealtimeNanos()` sampled at the same moment
     * @return true when the sample was accepted
     */
    fun offerTimestamp(frame: Double, timestampNs: Long, monoNs: Long, bootNs: Long): Boolean {
        if (!playing || timestampNs <= 0L || frame < 0.0) return false
        val bootAnchorNs = when {
            abs(timestampNs - monoNs) <= TIMEBASE_TOLERANCE_NS ->
                timestampNs + (bootNs - monoNs) // MONOTONIC -> BOOTTIME
            abs(timestampNs - bootNs) <= TIMEBASE_TOLERANCE_NS -> timestampNs
            else -> return false
        }
        // A sample older than the play/resume anchor can only be a leftover from the previous
        // stream position; anchoring on it would run the clock backwards.
        if (bootAnchorNs + START_TOLERANCE_NS < startedBootNs) return false
        val jumpToleranceFrames = sampleRate * (JUMP_TOLERANCE_NS / 1_000_000_000.0)
        if (abs(frame - interpolatedFrame(bootNs)) > jumpToleranceFrames) return false
        anchorFrame = frame
        anchorBootNs = bootAnchorNs
        awaitingOutput = false
        lastTimestampBootNs = bootNs
        return true
    }

    /**
     * Offer one `AudioTrack.playbackHeadPosition` sample: frames the output has really
     * rendered. Available on every device (unlike AudioTimestamp) and it stops advancing when
     * the stream underruns, so it is what keeps the clock pinned to the song.
     *
     * osu! `InterpolatingFramedClock` correction: the estimate has already been advanced by the
     * wall clock (in [interpolatedFrame]); here it is pulled toward the head by an exponential
     * damp (half-life [DRIFT_HALF_LIFE_MS]), or snapped straight onto it once the error exceeds
     * [ALLOWABLE_ERROR_MS]. A stale head (unchanged since the last sample) is ignored so the
     * estimate keeps gliding on wall-clock interpolation instead of being dragged back toward an
     * old value. Ignored entirely while an AudioTimestamp is fresh, because the timestamp is
     * latency-compensated and mixing the two sources would wobble by the output latency.
     *
     * @return true when the sample moved the anchor
     */
    fun offerHeadPosition(frame: Double, bootNs: Long): Boolean {
        if (!playing || frame < 0.0 || bootNs + START_TOLERANCE_NS < startedBootNs) return false
        val clamped = frame.coerceAtMost(totalFrames.toDouble())
        if (awaitingOutput) {
            // No frame rendered yet: keep holding rather than confirming a silent output.
            if (clamped <= 0.0) return false
            anchorFrame = clamped
            anchorBootNs = bootNs
            awaitingOutput = false
            lastHeadFrame = clamped
            return true
        }
        if (lastTimestampBootNs != Long.MIN_VALUE &&
            bootNs - lastTimestampBootNs <= TIMESTAMP_FRESH_NS
        ) {
            return false
        }
        if (clamped == lastHeadFrame) return false // stale source: keep wall-clock interpolation
        lastHeadFrame = clamped

        val prediction = interpolatedFrame(bootNs)
        val drift = clamped - prediction
        val allowableErrorFrames = sampleRate * (ALLOWABLE_ERROR_MS / 1000.0)
        anchorFrame = if (abs(drift) > allowableErrorFrames) {
            clamped // more than two frames of error: trust the device outright (osu! snap)
        } else {
            val deltaMs = (bootNs - anchorBootNs) / 1_000_000.0
            val fraction = 1.0 - 0.5.pow(deltaMs / DRIFT_HALF_LIFE_MS)
            prediction + drift * fraction // exponential drift recovery (osu! DampContinuously)
        }
        anchorBootNs = bootNs
        return true
    }

    /**
     * Current playback frame: anchor + BOOTTIME interpolation, clamped to [floor, total].
     *
     * Held at the anchor until the first output timestamp confirms audio is really playing;
     * once [STARTUP_GRACE_NS] passes without one, interpolation starts from the anchor anyway.
     */
    fun currentFrame(nowBootNs: Long): Double {
        if (!playing) return anchorFrame.coerceAtMost(totalFrames.toDouble())
        if (awaitingOutput) {
            if (nowBootNs - startedBootNs < STARTUP_GRACE_NS) {
                return anchorFrame.coerceAtMost(totalFrames.toDouble())
            }
            awaitingOutput = false
        }
        return interpolatedFrame(nowBootNs)
    }

    /** Raw anchor + time prediction, regardless of the start-up hold. */
    private fun interpolatedFrame(nowBootNs: Long): Double {
        val elapsedNs = nowBootNs - anchorBootNs
        val position = anchorFrame + elapsedNs * sampleRate / 1_000_000_000.0
        return position.coerceAtLeast(floorFrame).coerceAtMost(totalFrames.toDouble())
    }
}
