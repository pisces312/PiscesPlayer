/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §4.2).
 *
 * Synthesis algorithms: Paul Kellet's refined pink-noise filter and the leaky
 * integrator for brown noise are long-published public-domain DSP recipes
 * (musicdsp.org); the parameterization follows dylan7474/SleepRadio (GPL-3.0)
 * as cross-checked in sleep-noise-soundscape-research.md §3.1.
 */
package com.pisces312.piscesplayer.data.noise

import kotlin.math.cos
import kotlin.math.exp

/**
 * Procedural colored-noise generator: one [TrackSource] producing interleaved
 * stereo PCM floats at the given sample rate.
 *
 * Pure Kotlin (no Android imports) so it is unit-testable on the JVM. Thread
 * model: parameter setters may be called from any thread and take effect at
 * the next [generate] block (~21 ms at 1024 frames / 48 kHz); [generate] itself
 * must be called from a single thread.
 *
 * Stereo is built from two independent random streams, then shaped by the
 * mid/side [width] control: width 1 keeps the two channels fully decorrelated
 * (a wide, enveloping field), width 0 collapses to mono (a point in the middle
 * of the head). Copying one stream to both channels instead would image as a
 * hard center point — the classic "sounds like tinnitus" mistake.
 *
 * The amplitude "motion" LFO breathes both channels together at 0.1 Hz; fully
 * stationary noise reads as dead and tinnitus-like, a gentle swell reads as
 * distant waves.
 */
class NoiseSynthesis(
    seed: Long,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : TrackSource {

    private val left = ChannelState(seed)
    private val right = ChannelState(seed + SEED_CHANNEL_DELTA)

    @Volatile private var color: NoiseColor = NoiseColor.BROWN
    @Volatile private var lpAlpha: Float = lowPassAlpha(TONE_CUTOFF_DEFAULT_HZ)
    @Volatile private var motionDepth: Float = motionDepthFromDb(MOTION_DEPTH_DEFAULT_DB)
    @Volatile private var width: Float = WIDTH_DEFAULT
    @Volatile private var targetGain: Float = 0f

    // Generator-local smoothing state; only touched on the audio thread.
    private var gain: Float = 0f
    private var lfoPhase: Float = 0f

    fun setColor(value: NoiseColor) {
        color = value
    }

    /** Perceived brightness: low-pass cutoff in Hz, clamped to [TONE_CUTOFF_MIN_HZ, TONE_CUTOFF_MAX_HZ]. */
    fun setTone(cutoffHz: Float) {
        lpAlpha = lowPassAlpha(cutoffHz.coerceIn(TONE_CUTOFF_MIN_HZ, TONE_CUTOFF_MAX_HZ))
    }

    /** Amplitude modulation depth in dB, clamped to [MOTION_DEPTH_MIN_DB, MOTION_DEPTH_MAX_DB]. */
    fun setMotion(depthDb: Float) {
        motionDepth = motionDepthFromDb(depthDb.coerceIn(MOTION_DEPTH_MIN_DB, MOTION_DEPTH_MAX_DB))
    }

    /** Stereo decorrelation, 0 (mono center) .. 1 (fully independent channels). */
    fun setWidth(value: Float) {
        width = value.coerceIn(0f, 1f)
    }

    /** Target linear gain 0..1; approached per-sample (no zipper noise). */
    fun setTargetGain(value: Float) {
        targetGain = value.coerceIn(0f, 1f)
    }

    override fun generate(out: FloatArray, frames: Int) {
        // Snapshot the volatile parameters once per block: consistent within the
        // block, and parameter changes land at block boundaries (~21 ms) which is
        // imperceptible for tone/motion/width and matches gain fade timing.
        val c = color
        val alpha = lpAlpha
        val depth = motionDepth
        val w = width
        val target = targetGain

        val lfoInc = MOTION_HZ / sampleRate
        var g = gain
        var phase = lfoPhase

        var i = 0
        var n = 0
        while (n < frames) {
            g += (target - g) * GAIN_SMOOTHING
            phase += lfoInc
            if (phase >= 1f) phase -= 1f

            // Depth 0 -> envelope stays at 1 (fully stationary); depth d -> dips to -d dB.
            val envelope = 1f - depth + depth * (0.5f + 0.5f * cos(LFO_TWO_PI * phase))
            val amp = envelope * g

            var l = left.next(c, alpha)
            var r = right.next(c, alpha)

            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f
            l = mid + w * side
            r = mid - w * side

            out[i] = l * amp
            out[i + 1] = r * amp
            i += 2
            n++
        }
        gain = g
        lfoPhase = phase
    }

    private class ChannelState(seed: Long) {
        private val rng = kotlin.random.Random(seed)

        // Paul Kellet's refined pink filter state.
        private var b0 = 0f
        private var b1 = 0f
        private var b2 = 0f
        private var b3 = 0f
        private var b4 = 0f
        private var b5 = 0f
        private var b6 = 0f

        // Leaky integrator state for brown noise.
        private var brown = 0f

        // One-pole low-pass state for the tone control.
        private var lp = 0f

        fun next(color: NoiseColor, lpAlpha: Float): Float {
            val white = rng.nextFloat() * 2f - 1f
            var s = when (color) {
                NoiseColor.WHITE -> white * WHITE_GAIN
                NoiseColor.PINK -> pink(white)
                NoiseColor.BROWN -> brown(white, BROWN_LEAK, BROWN_SCALE)
                NoiseColor.DEEP_BROWN -> brown(white, DEEP_BROWN_LEAK, DEEP_BROWN_SCALE)
            }
            // Filter state carries across color switches so there is no discontinuity.
            lp += (1f - lpAlpha) * (s - lp)
            return lp
        }

        private fun pink(white: Float): Float {
            b0 = 0.99886f * b0 + white * 0.0555179f
            b1 = 0.99332f * b1 + white * 0.0750759f
            b2 = 0.96900f * b2 + white * 0.1538520f
            b3 = 0.86650f * b3 + white * 0.3104856f
            b4 = 0.55000f * b4 + white * 0.5329522f
            b5 = -0.7616f * b5 - white * 0.0168980f
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
            b6 = white * 0.115926f
            return pink * PINK_GAIN
        }

        private fun brown(white: Float, leak: Float, scale: Float): Float {
            brown = (brown + leak * white) / (1f + leak)
            return (brown * scale).coerceIn(-1f, 1f)
        }
    }

    /** One-pole low-pass coefficient from a cutoff; -6 dB/oct above the corner. */
    private fun lowPassAlpha(cutoffHz: Float): Float {
        val alpha = exp(-TWO_PI_F * cutoffHz / sampleRate)
        // exp() can round to exactly 1f at very low cutoffs; clamp keeps the
        // filter live without changing the audible range of the control.
        return if (alpha >= 1f) 0.999_999f else alpha
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val SEED_CHANNEL_DELTA = 7_919L

        const val GAIN_SMOOTHING = 0.0005f

        const val MOTION_HZ = 0.1f
        private const val LFO_TWO_PI = (Math.PI * 2).toFloat()

        const val MOTION_DEPTH_MIN_DB = 0f
        const val MOTION_DEPTH_MAX_DB = 4f
        const val MOTION_DEPTH_DEFAULT_DB = 1.5f

        const val TONE_CUTOFF_MIN_HZ = 4_000f
        const val TONE_CUTOFF_MAX_HZ = 16_000f
        const val TONE_CUTOFF_DEFAULT_HZ = 12_000f

        const val WIDTH_DEFAULT = 0.8f

        const val WHITE_GAIN = 0.5f
        const val PINK_GAIN = 0.11f
        const val BROWN_LEAK = 0.02f
        const val BROWN_SCALE = 3.5f
        const val DEEP_BROWN_LEAK = 0.008f
        const val DEEP_BROWN_SCALE = 4.5f

        private const val TWO_PI_F = (Math.PI * 2).toFloat()

        private fun motionDepthFromDb(depthDb: Float): Float =
            1f - exp(depthDb * -LN10_OVER_20)

        /** ln(10)/20, precomputed so this can stay a compile-time constant. */
        private const val LN10_OVER_20 = 0.115_129_255f
    }
}
