/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (AudioTimestamp time-base conversion and jump protection, JVM-testable).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlin.math.abs

/**
 * Pure playback frame position estimator: anchor + monotonic clock interpolation,
 * AudioTimestamp time-base detection, and bidirectional jump protection.
 *
 * `AudioTimestamp.nanoTime` may be either `System.nanoTime()` (TIMEBASE_MONOTONIC)
 * or `SystemClock.elapsedRealtimeNanos()` (BOOTTIME). Mixing the two domains turns
 * accumulated deep sleep into playback progress and freezes gameplay. Timestamps
 * are therefore normalized to the BOOTTIME domain; unknown time bases are dropped.
 *
 * Starting playback holds the clock at the start frame until the output reports its first
 * frame: `play()` returns before sound is emitted, so running on interpolation right away
 * makes the first output timestamp pull the clock backwards — one visible jump of every note.
 */
class RhythmFrameEstimator(
    private val sampleRate: Int,
    private val totalFrames: Long,
) {
    companion object {
        /** Anchor vs interpolated prediction larger than 500ms of frames is discarded. */
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

        /** Clock-sampling slack when rejecting timestamps older than the play/resume anchor. */
        private const val START_TOLERANCE_NS = 2_000_000L
    }

    private var playing = false
    private var anchorFrame = 0.0
    private var anchorBootNs = 0L
    private var floorFrame = 0.0
    private var startedBootNs = 0L
    private var awaitingOutput = false

    val isPlaying: Boolean get() = playing
    val frozenFrame: Double get() = anchorFrame

    fun start(nowBootNs: Long) {
        playing = true
        anchorFrame = 0.0
        anchorBootNs = nowBootNs
        floorFrame = 0.0
        startedBootNs = nowBootNs
        awaitingOutput = true
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
