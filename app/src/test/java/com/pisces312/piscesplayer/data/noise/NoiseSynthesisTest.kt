/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §6).
 */
package com.pisces312.piscesplayer.data.noise

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Objective acceptance tests for the synth, mirroring the measured values from
 * sleep-noise-soundscape-research.md §7.2 (band-integrated spectrum slopes,
 * channel decorrelation, no clipping, no zipper noise on gain changes).
 */
class NoiseSynthesisTest {

    private val sampleRate = 48_000

    private fun synth(seed: Long = 11L): NoiseSynthesis = NoiseSynthesis(seed, sampleRate)

    /**
     * One second of steady-state audio at full gain, no motion. Width is NOT
     * forced here — the decorrelation tests set it themselves.
     */
    private fun renderSteady(s: NoiseSynthesis, color: NoiseColor, seconds: Int = 2): FloatArray {
        s.setColor(color)
        s.setMotion(0f)
        s.setTargetGain(1f)
        val frames = sampleRate * seconds
        val out = FloatArray(frames * 2)
        // Warm-up: filter state and gain smoothing settle during the first render;
        // the measurement uses a second pass over the same (now warm) generator.
        s.generate(FloatArray(frames * 2), frames)
        s.generate(out, frames)
        return out
    }

    @Test
    fun sameSeedSameColorProducesIdenticalOutput() {
        val frames = 48_000
        val a = FloatArray(frames * 2)
        val b = FloatArray(frames * 2)
        val sa = synth()
        val sb = synth()
        for (s in NoiseColor.entries) {
            sa.setColor(s)
            sb.setColor(s)
            sa.setTargetGain(1f)
            sb.setTargetGain(1f)
            sa.generate(a, frames)
            sb.generate(b, frames)
            for (i in a.indices) {
                assertEquals("sample $i differs for $s", a[i], b[i], 0f)
            }
        }
    }

    @Test
    fun spectrumSlopeMatchesTheExpectedColorSignature() {
        // Band-integrated energy difference (200-400 Hz vs 3200-6400 Hz), in dB.
        val white = bandSlope(renderSteady(synth(), NoiseColor.WHITE))
        val pink = bandSlope(renderSteady(synth(), NoiseColor.PINK))
        val brown = bandSlope(renderSteady(synth(), NoiseColor.BROWN))

        assertTrue("white slope $white dB should be clearly rising", white >= 10f)
        assertTrue("pink slope $pink dB should be roughly flat", abs(pink) <= 4f)
        assertTrue("brown slope $brown dB should be clearly falling", brown <= -10f)
    }

    @Test
    fun toneControlDarkensTheHighBand() {
        val bright = renderSteady(synth(), NoiseColor.PINK).also { /* tone default 12k */ }
        val darkSynth = synth().apply { setTone(4_000f) }
        val dark = renderSteady(darkSynth, NoiseColor.PINK)

        val brightHigh = bandEnergyDb(bright, 12_000f, 20_000f)
        val darkHigh = bandEnergyDb(dark, 12_000f, 20_000f)
        assertTrue(
            "expected >= 6 dB attenuation at 4 kHz cutoff (got ${brightHigh - darkHigh})",
            brightHigh - darkHigh >= 6f,
        )
    }

    @Test
    fun widthControlRunsFromDecorrelatedToMono() {
        val wide = renderSteady(synth(), NoiseColor.WHITE)
        val rWide = correlation(wide)
        assertTrue("width 1 channels must be decorrelated, r=$rWide", abs(rWide) < 0.3f)

        val monoSynth = synth().apply { setWidth(0f) }
        val mono = renderSteady(monoSynth, NoiseColor.WHITE)
        val rMono = correlation(mono)
        assertTrue("width 0 channels must be identical, r=$rMono", rMono > 0.95f)
    }

    @Test
    fun outputNeverClipsOrDriftsIntoDc() {
        for (color in NoiseColor.entries) {
            val s = synth()
            val out = renderSteady(s, color)
            var peak = 0f
            var mean = 0.0
            for (i in out.indices) {
                peak = max(peak, abs(out[i]))
                mean += out[i].toDouble()
            }
            mean /= out.size
            assertTrue("$color clipped, peak=$peak", peak <= 1f)
            assertTrue("$color has DC offset $mean", abs(mean) < 0.01)
        }
    }

    @Test
    fun gainRampsInSmoothlyInsteadOfStepping() {
        val s = synth().apply { setColor(NoiseColor.PINK); setMotion(0f); setWidth(1f) }
        val frames = 48_000
        // Gain starts at 0: the generator opens with target 0 for a whole block.
        s.setTargetGain(0f)
        s.generate(FloatArray(frames * 2), frames)
        // Now open up and verify the first 10 ms stay quiet (fade-in, not a step).
        s.setTargetGain(1f)
        val first = FloatArray(100 * 2)
        s.generate(first, 100)
        var maxAbs = 0f
        for (v in first) maxAbs = max(maxAbs, abs(v))
        assertTrue("fade-in leaked, first 100 frames peak=$maxAbs", maxAbs < 0.05f)

        // And it actually reaches the steady state soon after.
        val settle = FloatArray(frames * 2)
        s.generate(settle, frames)
        val rms = rms(settle)
        assertTrue("fade-in never reached audible level, rms=$rms", rms > 0.01f)
    }

    @Test
    fun colorSwitchingDoesNotBlast() {
        val s = synth().apply { setMotion(0f); setWidth(1f); setTargetGain(1f) }
        val frames = 48_000 / 2
        val warm = FloatArray(frames * 2)
        s.setColor(NoiseColor.PINK)
        s.generate(warm, frames)

        val switch = FloatArray(frames * 2)
        s.setColor(NoiseColor.BROWN)
        s.generate(switch, frames)

        // The switch must never exceed what steady brown looks like: filter state
        // carries over, so a blast would show up as an RMS/peak way above steady.
        val steadyBrownRms = rms(renderSteady(synth(), NoiseColor.BROWN))
        val afterRms = rmsWindow(switch, 0, 2_000)
        assertTrue(
            "color switch blasted: after=$afterRms steady brown=$steadyBrownRms",
            afterRms <= steadyBrownRms * 2f,
        )
        var peak = 0f
        for (v in switch) peak = max(peak, abs(v))
        assertTrue("color switch clipped, peak=$peak", peak <= 1f)
    }

    @Test
    fun motionModulatesAmplitudeWithinConfiguredDepth() {
        val s = synth().apply { setColor(NoiseColor.PINK); setMotion(4f); setWidth(1f); setTargetGain(1f) }
        val frames = sampleRate * 40 // 0.1 Hz -> need several LFO periods
        val out = FloatArray(frames * 2)
        s.generate(out, frames)

        // Per-second envelope RMS; depth 4 dB -> max/min envelope ratio ~ 10^(4/20) = 1.58.
        // 1 s windows (not 100 ms): pink noise correlates for a long time, short
        // windows add several dB of RMS estimation noise.
        val windowFrames = sampleRate
        var minE = Float.MAX_VALUE
        var maxE = 0f
        var w = 0
        while (w < frames) {
            val e = rmsWindow(out, w * 2, (w + windowFrames) * 2)
            minE = minOf(minE, e)
            maxE = maxOf(maxE, e)
            w += windowFrames
        }
        val ratioDb = 20 * log10(maxE / minE)
        assertTrue("motion depth out of range: $ratioDb dB (max=$maxE min=$minE)", ratioDb in 2.5f..5.5f)
    }

    // ---------------- measurement helpers ----------------

    private fun rms(out: FloatArray): Float = rmsWindow(out, 0, out.size)

    private fun rmsWindow(out: FloatArray, from: Int, to: Int): Float {
        var acc = 0.0
        for (i in from until to) {
            acc += out[i].toDouble() * out[i]
        }
        return sqrt(acc / (to - from)).toFloat()
    }

    private fun correlation(out: FloatArray): Float {
        var sl = 0.0
        var sr = 0.0
        var sll = 0.0
        var srr = 0.0
        var slr = 0.0
        var n = 0
        while (n * 2 + 1 < out.size) {
            val l = out[n * 2].toDouble()
            val r = out[n * 2 + 1].toDouble()
            sl += l
            sr += r
            sll += l * l
            srr += r * r
            slr += l * r
            n++
        }
        val cov = slr / n - (sl / n) * (sr / n)
        val vl = sll / n - (sl / n) * (sl / n)
        val vr = srr / n - (sr / n) * (sr / n)
        return (cov / sqrt(vl * vr)).toFloat()
    }

    private fun bandSlope(out: FloatArray): Float =
        bandEnergyDb(out, 2_00f, 4_00f).let { low -> bandEnergyDb(out, 3_200f, 6_400f) - low }

    /** Band-integrated energy in dB over the left channel, Hann-windowed FFT. */
    private fun bandEnergyDb(out: FloatArray, loHz: Float, hiHz: Float): Float {
        val n = 8_192
        // Start mid-signal on an even index so the i*2 stride stays on the left channel.
        val start = (out.size / 2 - n * 2).coerceAtLeast(0)
        val x = DoubleArray(n)
        for (i in 0 until n) {
            x[i] = out[start + i * 2].toDouble() * (0.5 - 0.5 * cos(2 * PI * i / (n - 1)))
        }
        val mag = fftMagnitudes(x)
        val df = sampleRate.toDouble() / n
        var energy = 0.0
        var i = max(1, (loHz / df).toInt())
        val hi = max(i + 1, (hiHz / df).toInt())
        while (i < hi && i < mag.size) {
            energy += mag[i]
            i++
        }
        return (10 * ln(energy + 1e-30) / ln(10.0)).toFloat()
    }

    /** Iterative radix-2 FFT returning |X[k]|^2 for k in 0..n/2. */
    private fun fftMagnitudes(input: DoubleArray): DoubleArray {
        val n = input.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two" }
        val re = input.copyOf()
        val im = DoubleArray(n)

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + half] * cr - im[i + k + half] * ci
                    val vi = re[i + k + half] * ci + im[i + k + half] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }

        val mags = DoubleArray(n / 2)
        for (k in 0 until n / 2) {
            mags[k] = re[k] * re[k] + im[k] * im[k]
        }
        return mags
    }
}
