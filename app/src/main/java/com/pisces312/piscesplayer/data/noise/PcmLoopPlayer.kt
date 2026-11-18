/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §4.2).
 */
package com.pisces312.piscesplayer.data.noise

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.cos

/**
 * Seamless-loop reader over a decoded interleaved stereo PCM buffer.
 *
 * The buffer is treated as [totalFrames] = size/2 frames of which the last
 * [crossfadeFrames] double as an overlap zone: while the read head is inside
 * that zone, the tail is crossfaded (raised-cosine) over the buffer head, and
 * wrapping jumps to [crossfadeFrames] — so the fade-out flows exactly into
 * the content the fade-in was blended with. The effective loop length is
 * totalFrames - crossfadeFrames and there is no audible seam click, provided
 * the asset was faded at both ends when it was prepared.
 *
 * Pure Kotlin (no Android imports); unit-testable on the JVM. Thread model
 * matches [NoiseSynthesis]: [setTargetGain] from any thread, [generate] from
 * the audio thread only.
 */
class PcmLoopPlayer(
    private val interleaved: FloatArray,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : TrackSource {

    /** Raised-cosine overlap length; 400 ms masks the seam of any stationary bed. */
    val crossfadeFrames: Int = (sampleRate * CROSSFADE_MS / 1000).coerceIn(1, interleaved.size / 4)

    @Volatile private var targetGain: Float = 0f

    // Audio-thread state.
    private var gain: Float = 0f
    private var position: Int = 0

    init {
        require(interleaved.size >= MIN_SECONDS * 2 * sampleRate) {
            "Loop asset too short: ${interleaved.size / 2} frames @ $sampleRate Hz"
        }
    }

    /** Target linear gain 0..1; approached per-sample (no zipper noise). */
    fun setTargetGain(value: Float) {
        targetGain = value.coerceIn(0f, 1f)
    }

    /** Restart the read head at the loop start (e.g. after a seek-less restart). */
    fun resetPosition() {
        position = 0
    }

    override fun generate(out: FloatArray, frames: Int) {
        val buf = interleaved
        val total = buf.size / 2
        if (total < MIN_SECONDS * sampleRate) {
            Arrays.fill(out, 0, frames * 2, 0f)
            return
        }
        val cf = crossfadeFrames
        val cfStart = total - cf
        var pos = position
        var g = gain
        val target = targetGain

        var i = 0
        var n = 0
        while (n < frames) {
            g += (target - g) * GAIN_SMOOTHING
            // Seamless wrap: skip the head segment already heard inside the
            // previous pass's crossfade, landing exactly on the continuation.
            if (pos >= total) pos -= (total - cf)

            val l: Float
            val r: Float
            if (pos < cfStart) {
                l = buf[pos * 2]
                r = buf[pos * 2 + 1]
            } else {
                val alpha = (pos - cfStart).toFloat() / cf
                val w = 0.5f - 0.5f * cos(PI * alpha).toFloat()
                val head = pos - cfStart
                l = (1f - w) * buf[pos * 2] + w * buf[head * 2]
                r = (1f - w) * buf[pos * 2 + 1] + w * buf[head * 2 + 1]
            }

            out[i] = l * g
            out[i + 1] = r * g
            i += 2
            n++
            pos++
        }
        position = pos
        gain = g
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val CROSSFADE_MS = 400
        const val MIN_SECONDS = 2

        /** Per-sample gain smoothing; matches NoiseSynthesis.GAIN_SMOOTHING. */
        const val GAIN_SMOOTHING = 0.0005f
    }
}
