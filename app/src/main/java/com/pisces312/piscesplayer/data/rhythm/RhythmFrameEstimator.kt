/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (AudioTimestamp time-base conversion and jump protection, JVM-testable).
 */
package com.pisces312.piscesplayer.data.rhythm

import java.util.Locale
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
 * Startup — the clock's zero must land on the output's ACOUSTIC zero (the moment frame 0 is
 * heard), or the chart's first notes reach the judgement line before their music does. That
 * instant is only observable from device samples, so the clock PARKS at the play anchor (notes
 * wait at their song-time-zero positions, which is the right place during the output's silent
 * start-up) until the first sample showing real frame progress; that sample anchors the line
 * and playback runs from there. The playback head reports every frame, so the park normally
 * lasts only the output's start-up delay (tens of ms); [STARTUP_GRACE_NS] bounds it for a
 * device that never reports progress. Parking instead of running on the wall clock is what
 * keeps the first note ON the music; requiring frame progress (an earlier design accepted the
 * ambiguous framePosition=0 reports, and another waited on a sparse AudioTimestamp while
 * ignoring the head) is what keeps the release from throwing the notes downward.
 *
 * Output rate limit: the emitted frame is monotonic and advances at most [OUTPUT_MAX_RATE] x
 * real time, so corrections show as a brief hold (device line behind the estimate) or a slight
 * speed-up (ahead) — never a jump in either direction. The estimate itself stays freely
 * correctable (an AudioTrack underruns; the clock must be able to fall back onto the device).
 */
class RhythmFrameEstimator(
    private val sampleRate: Int,
    private val totalFrames: Long,
    /** Optional startup recorder; every decision below writes one line. Null in unit tests. */
    private val trace: RhythmStartupTrace? = null,
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
         * How long the startup park waits for the first frame-progress sample before giving up
         * and running on the wall-clock line. Healthy devices report progress within tens of
         * milliseconds (the playback head is sampled every frame); this only bounds a device
         * that never does, so gameplay cannot stall forever.
         */
        const val STARTUP_GRACE_NS = 400_000_000L

        /**
         * The emitted frame advances at most this multiple of real time (and never backwards):
         * together the two limits turn every correction into a brief hold or a slight speed-up
         * instead of a jump in either direction.
         */
        const val OUTPUT_MAX_RATE = 1.5

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

        /**
         * Startup-trace window: estimator decisions are recorded only this long after each
         * start/resume. Steady-state drift is the drift recorder's job; without the cap a
         * timestamp-less device would flood the ring buffer off the opening seconds.
         */
        private const val TRACE_WINDOW_NS = 3_000_000_000L
    }

    private var playing = false
    private var anchorFrame = 0.0
    private var anchorBootNs = 0L
    private var floorFrame = 0.0
    private var startedBootNs = 0L
    private var awaitingLine = false
    private var emittedFrame = 0.0
    private var lastEmitBootNs = 0L
    private var lastTimestampFrame = 0.0
    private var lastTimestampBootNs = Long.MIN_VALUE
    private var lastHeadFrame = -1.0

    val isPlaying: Boolean get() = playing
    val frozenFrame: Double get() = anchorFrame

    /**
     * True once the emitted frame has reached the final frame — the output has drained
     * every decoded frame, whether or not playback is still nominally "playing". Draining
     * the PCM does not stop an AudioTrack by itself (nothing flips `playing` at end of
     * song), so end-of-run detection must not depend on the pause path either.
     * A mid-song underrun cannot set this: the wall-clock estimate is far below
     * [totalFrames] there, so the emitted frame is too.
     */
    val reachedEnd: Boolean get() = emittedFrame >= totalFrames.toDouble()

    fun start(nowBootNs: Long) {
        playing = true
        anchorFrame = 0.0
        anchorBootNs = nowBootNs
        floorFrame = 0.0
        startedBootNs = nowBootNs
        awaitingLine = true
        emittedFrame = 0.0
        lastEmitBootNs = nowBootNs
        lastTimestampFrame = 0.0
        lastTimestampBootNs = Long.MIN_VALUE
        lastHeadFrame = -1.0
        trace?.add(nowBootNs, "estimator.start: park at frame=0, awaiting first frame-progress sample")
    }

    fun pauseAt(frame: Double) {
        playing = false
        awaitingLine = false
        val frozen = frame.coerceIn(0.0, totalFrames.toDouble())
        anchorFrame = frozen
        emittedFrame = frozen
    }

    fun resumeFrom(frame: Double, nowBootNs: Long) {
        playing = true
        val resumed = frame.coerceIn(0.0, totalFrames.toDouble())
        anchorFrame = resumed
        anchorBootNs = nowBootNs
        floorFrame = resumed
        startedBootNs = nowBootNs
        // The output restarts after a pause just like it starts after play(): park until a
        // frame-progress sample re-establishes the line, or the clock's zero drifts from the
        // music's by the output's restart delay.
        awaitingLine = true
        emittedFrame = resumed
        lastEmitBootNs = nowBootNs
        lastTimestampFrame = resumed
        lastTimestampBootNs = Long.MIN_VALUE
        lastHeadFrame = -1.0
        trace?.add(nowBootNs, "estimator.resume: park at frame=%.0f".format(Locale.US, resumed))
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
        if (!playing || timestampNs <= 0L || frame < 0.0) {
            if (tracing(bootNs)) trace?.add(bootNs, "ts reject: not playing / empty (frame=%.0f)".format(Locale.US, frame))
            return false
        }
        val bootAnchorNs = when {
            abs(timestampNs - monoNs) <= TIMEBASE_TOLERANCE_NS ->
                timestampNs + (bootNs - monoNs) // MONOTONIC -> BOOTTIME
            abs(timestampNs - bootNs) <= TIMEBASE_TOLERANCE_NS -> timestampNs
            else -> {
                if (tracing(bootNs)) trace?.add(bootNs, "ts reject: unknown time base (frame=%.0f)".format(Locale.US, frame))
                return false
            }
        }
        // A sample older than the play/resume anchor can only be a leftover from the previous
        // stream position; anchoring on it would run the clock backwards.
        if (bootAnchorNs + START_TOLERANCE_NS < startedBootNs) {
            if (tracing(bootNs)) trace?.add(bootNs, "ts reject: stale, from before start (frame=%.0f)".format(Locale.US, frame))
            return false
        }
        // No-progress reports must not anchor: while the output is still starting, getTimestamp
        // returns the same framePosition with a moving clock time. Sliding the anchor along an
        // unchanging frame pins the estimate below its own wall-clock line, and the first real
        // sample then steps every note downward in one jump (the game-start "sudden drop").
        // Only samples that advance the presented frame say where the line is.
        if (frame <= lastTimestampFrame) {
            if (tracing(bootNs)) trace?.add(bootNs, "ts reject: no progress (frame=%.0f <= last %.0f)".format(Locale.US, frame, lastTimestampFrame))
            return false
        }
        val jumpToleranceFrames = sampleRate * (JUMP_TOLERANCE_NS / 1_000_000_000.0)
        if (abs(frame - interpolatedFrame(bootNs)) > jumpToleranceFrames) {
            if (tracing(bootNs)) trace?.add(bootNs, "ts reject: jump >500ms vs estimate (frame=%.0f = %.1f ms)".format(Locale.US, frame, frameMs(frame)))
            return false
        }
        anchorFrame = frame
        anchorBootNs = bootAnchorNs
        awaitingLine = false
        lastTimestampFrame = frame
        lastTimestampBootNs = bootNs
        if (tracing(bootNs)) trace?.add(
            bootNs,
            "ts ANCHOR frame=%.0f (%.1f ms): releases the park".format(Locale.US, frame, frameMs(frame)),
        )
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
        if (!playing || frame < 0.0 || bootNs + START_TOLERANCE_NS < startedBootNs) {
            if (tracing(bootNs)) trace?.add(bootNs, "head reject: not playing / stale (frame=%.0f)".format(Locale.US, frame))
            return false
        }
        val clamped = frame.coerceAtMost(totalFrames.toDouble())
        if (awaitingLine) {
            // Parked at the anchor until the output shows real frame progress. A head at (or
            // below) the parked frame means "nothing rendered yet"; the first one ahead of it is
            // the output actually starting and releases the hold, anchoring the run's line there.
            // The head cannot legitimately run ahead of the wall-clock line at start-up, so a
            // sample that does is a leftover counter from a previous stream (some devices do not
            // reset playbackHeadPosition across flush/stop/play) and is ignored.
            if (clamped <= anchorFrame) {
                if (tracing(bootNs)) trace?.add(bootNs, "head park: frame=%.0f <= anchor %.0f, output not started".format(Locale.US, clamped, anchorFrame))
                return false
            }
            if (clamped > interpolatedFrame(bootNs)) {
                if (tracing(bootNs)) trace?.add(bootNs, "head reject: ahead of wall line (leftover counter) frame=%.0f".format(Locale.US, clamped))
                return false
            }
            anchorFrame = clamped
            anchorBootNs = bootNs
            awaitingLine = false
            lastHeadFrame = clamped
            if (tracing(bootNs)) trace?.add(
                bootNs,
                "head RELEASE-PARK frame=%.0f (%.1f ms): output started, anchors the line".format(Locale.US, clamped, frameMs(clamped)),
            )
            return true
        }
        if (lastTimestampBootNs != Long.MIN_VALUE &&
            bootNs - lastTimestampBootNs <= TIMESTAMP_FRESH_NS
        ) {
            return false
        }
        if (clamped == lastHeadFrame) return false // stale source: keep wall-clock interpolation

        val prediction = interpolatedFrame(bootNs)
        val drift = clamped - prediction
        // The head advances at most at real-time rate from play(), so it can lag arbitrarily far
        // behind (underruns) but can never legitimately race ahead of the wall estimate. A sample
        // far ahead is a leftover counter from a previous stream (some devices do not reset
        // playbackHeadPosition across flush/stop/play); snapping onto it would throw every note
        // downward in one step on retry.
        val jumpToleranceFrames = sampleRate * (JUMP_TOLERANCE_NS / 1_000_000_000.0)
        if (drift > jumpToleranceFrames) {
            if (tracing(bootNs)) trace?.add(bootNs, "head reject: ahead >500ms (leftover counter) frame=%.0f".format(Locale.US, clamped))
            return false
        }
        lastHeadFrame = clamped

        val driftMs = drift / sampleRate * 1000.0
        val allowableErrorFrames = sampleRate * (ALLOWABLE_ERROR_MS / 1000.0)
        anchorFrame = if (abs(drift) > allowableErrorFrames) {
            if (tracing(bootNs)) trace?.add(bootNs, "head SNAP: drift=%.1f ms > 33ms, trust device".format(Locale.US, driftMs))
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
     * Current playback frame: anchor + BOOTTIME interpolation, clamped to [floor, total] and to
     * the output rate limit — never backwards, at most [OUTPUT_MAX_RATE] x real time forward.
     * That is what makes corrections invisible to gameplay: the estimate may snap onto the
     * device, but the emitted frame either waits for it (device behind) or eases toward it
     * (ahead) instead of every note jumping.
     *
     * While the startup park holds, the emitted frame stays at the anchor — the notes wait at
     * their song-time-zero positions until the output's line is known.
     */
    fun currentFrame(nowBootNs: Long): Double {
        if (!playing) return anchorFrame.coerceAtMost(totalFrames.toDouble())
        if (awaitingLine) {
            if (nowBootNs - startedBootNs < STARTUP_GRACE_NS) {
                lastEmitBootNs = nowBootNs
                return emittedFrame.coerceAtMost(totalFrames.toDouble())
            }
            // No progress sample ever arrived (pathological device): run on the wall-clock line
            // rather than hold gameplay forever.
            // Intentionally not gated on tracing(): a grace expiry on a healthy device is the
            // single most important line this recorder can emit.
            trace?.add(
                nowBootNs,
                "GRACE %.0f ms expired with no frame-progress sample: wall-clock line starts anyway"
                    .format(Locale.US, STARTUP_GRACE_NS / 1_000_000.0),
            )
            awaitingLine = false
        }
        val raw = interpolatedFrame(nowBootNs)
        val elapsedNs = (nowBootNs - lastEmitBootNs).coerceAtLeast(0L)
        val maxAdvance = sampleRate * OUTPUT_MAX_RATE * elapsedNs / 1_000_000_000.0
        val output = maxOf(emittedFrame, minOf(raw, emittedFrame + maxAdvance))
        emittedFrame = output
        lastEmitBootNs = nowBootNs
        return output
    }

    /** Raw anchor + time prediction, regardless of the start-up hold. */
    private fun interpolatedFrame(nowBootNs: Long): Double {
        val elapsedNs = nowBootNs - anchorBootNs
        val position = anchorFrame + elapsedNs * sampleRate / 1_000_000_000.0
        return position.coerceAtLeast(floorFrame).coerceAtMost(totalFrames.toDouble())
    }

    private fun frameMs(frame: Double): Double = frame / sampleRate * 1000.0

    /** Trace lines are for the opening window only; see [TRACE_WINDOW_NS]. */
    private fun tracing(bootNs: Long): Boolean =
        trace != null && bootNs - startedBootNs <= TRACE_WINDOW_NS
}
