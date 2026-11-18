/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §4.2).
 */
package com.pisces312.piscesplayer.data.noise

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.tanh

/**
 * One mixable audio track producing interleaved stereo PCM floats
 * ([frames] * 2 samples) into [out].
 */
interface TrackSource {
    fun generate(out: FloatArray, frames: Int)
}

/**
 * A [TrackSource] the engine can fade in/out per track: [setTargetGain] is
 * called from any thread, applied smoothly inside [generate].
 */
interface GainControlledTrackSource : TrackSource {
    fun setTargetGain(value: Float)
}

/**
 * Pure software mixer for the sleep soundscape: sums any number of
 * [TrackSource]s into one interleaved stereo buffer, applies a smoothed master
 * gain and a transparent soft limiter.
 *
 * The limiter is the technical guarantee that *any* combination of tracks stays
 * listenable: it is exactly unity below [LIMIT_THRESHOLD] and compresses toward
 * 1.0 above it, so stacking four tracks at full volume can never hard-clip.
 *
 * Pure Kotlin (no Android imports); unit-testable on the JVM. Single-threaded:
 * [mix] must be called from the audio thread only, [setMasterTarget] from anywhere.
 */
class SoundscapeMixer {

    private var scratch = FloatArray(0)

    private var masterGain = 0f

    @Volatile private var masterTarget = 0f

    fun setMasterTarget(value: Float) {
        masterTarget = value.coerceIn(0f, 1f)
    }

    fun mix(out: FloatArray, frames: Int, sources: List<TrackSource>) {
        val samples = frames * 2
        if (out.size < samples) throw IllegalArgumentException("out too small: ${out.size} < $samples")
        if (sources.isEmpty()) {
            Arrays.fill(out, 0, samples, 0f)
            return
        }
        if (scratch.size < samples) {
            scratch = FloatArray(samples)
        }

        Arrays.fill(out, 0, samples, 0f)
        for (source in sources) {
            source.generate(scratch, frames)
            for (i in 0 until samples) {
                out[i] += scratch[i]
            }
        }

        val target = masterTarget
        var g = masterGain
        var i = 0
        for (n in 0 until frames) {
            // Smoothed once per frame (stereo pair), matching NoiseSynthesis: a UI
            // volume change converges in ~42 ms, zipper-free.
            g += (target - g) * MASTER_SMOOTHING
            out[i] = softLimit(out[i] * g)
            out[i + 1] = softLimit(out[i + 1] * g)
            i += 2
        }
        masterGain = g
    }

    private fun softLimit(x: Float): Float {
        val ax = abs(x)
        if (ax <= LIMIT_THRESHOLD) return x
        val over = (ax - LIMIT_THRESHOLD) * LIMIT_KNEE_INVERSE
        val limited = LIMIT_THRESHOLD + LIMIT_KNEE * tanh(over)
        return if (x < 0f) -limited else limited
    }

    companion object {
        /** Per-sample master gain smoothing; matches NoiseSynthesis.GAIN_SMOOTHING. */
        const val MASTER_SMOOTHING = 0.0005f

        /** Unity below 0.8, soft compression above; approaches 1.0 asymptotically. */
        const val LIMIT_THRESHOLD = 0.8f
        const val LIMIT_KNEE = 1f - LIMIT_THRESHOLD
        private const val LIMIT_KNEE_INVERSE = 1f / LIMIT_KNEE
    }
}
