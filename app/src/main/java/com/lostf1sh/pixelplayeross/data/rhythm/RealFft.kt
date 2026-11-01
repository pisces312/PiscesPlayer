/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (docs/rhythm-dsp-chart-generation-plan.md §4.3).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Real-input FFT of a fixed power-of-two size, used by [RhythmChartGenerator].
 *
 * The transform is computed with a half-size complex FFT by packing even samples into
 * the real part and odd samples into the imaginary part, then splitting the result.
 * Only magnitudes are exposed — the chart pipeline never needs the phase.
 *
 * Instances are stateful (scratch buffers, twiddle tables) and **not** thread-safe.
 * Reuse one instance for a whole song so the tables are built once.
 */
internal class RealFft(val size: Int) {

    init {
        require(size >= 4 && (size and (size - 1)) == 0) { "size must be a power of two >= 4" }
    }

    private val half = size / 2
    private val bins = half + 1

    private val re = DoubleArray(half)
    private val im = DoubleArray(half)
    private val reversed = IntArray(half)

    /** Twiddles for the half-size complex FFT: `cos/sin(2*PI*k/half)`, k in [0, half/2). */
    private val fftCos = DoubleArray(half / 2)
    private val fftSin = DoubleArray(half / 2)

    /** Twiddles for the real-input split: `cos/sin(2*PI*k/size)`, k in [1, half). */
    private val splitCos = DoubleArray(half)
    private val splitSin = DoubleArray(half)

    init {
        val shift = Integer.numberOfTrailingZeros(half)
        for (i in 0 until half) {
            reversed[i] = Integer.reverse(i) ushr (32 - shift)
        }
        for (k in 0 until half / 2) {
            val angle = 2.0 * PI * k / half
            fftCos[k] = cos(angle)
            fftSin[k] = sin(angle)
        }
        for (k in 0 until half) {
            val angle = 2.0 * PI * k / size
            splitCos[k] = cos(angle)
            splitSin[k] = sin(angle)
        }
    }

    /**
     * Computes `|rfft(input[offset until offset + size] * window)|` into [out].
     *
     * [out] must hold [bins] values. The Hann table is applied here (rather than by the
     * caller) so the packing loop touches each sample only once.
     */
    fun magnitude(input: FloatArray, offset: Int, window: FloatArray, out: FloatArray) {
        require(out.size >= bins) { "out needs $bins bins" }
        require(offset >= 0 && offset + size <= input.size) { "input slice out of bounds" }

        for (k in 0 until half) {
            val base = offset + 2 * k
            re[k] = (input[base] * window[2 * k]).toDouble()
            im[k] = (input[base + 1] * window[2 * k + 1]).toDouble()
        }
        transform()

        // DC and Nyquist come straight out of the packed first bin. Both are real-valued
        // but can be negative, so take the magnitude like every other bin.
        out[0] = abs(re[0] + im[0]).toFloat()
        out[half] = abs(re[0] - im[0]).toFloat()

        for (k in 1 until half) {
            val mirror = half - k
            val zr = re[k]
            val zi = im[k]
            val mr = re[mirror]
            val mi = -im[mirror]

            // Even/odd sample DFTs.
            val evenR = (zr + mr) * 0.5
            val evenI = (zi + mi) * 0.5
            val diffR = zr - mr
            val diffI = zi - mi
            // (Z[k] - conj(Z[half-k])) / (2i)
            val oddR = diffI * 0.5
            val oddI = -diffR * 0.5

            // e^(-2*PI*i*k/size)
            val wr = splitCos[k]
            val wi = -splitSin[k]

            val xr = evenR + (oddR * wr - oddI * wi)
            val xi = evenI + (oddR * wi + oddI * wr)
            out[k] = hypot(xr, xi).toFloat()
        }
    }

    /** In-place radix-2 complex FFT over [re]/[im], length [half]. */
    private fun transform() {
        val n = half
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                var twiddle = 0
                for (j in start until start + halfLen) {
                    val wr = fftCos[twiddle * step]
                    val wi = -fftSin[twiddle * step]
                    val partner = j + halfLen
                    val pr = re[partner]
                    val pi = im[partner]
                    val tr = pr * wr - pi * wi
                    val ti = pr * wi + pi * wr
                    re[partner] = re[j] - tr
                    im[partner] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    twiddle++
                }
                start += len
            }
            len *= 2
        }
    }
}
