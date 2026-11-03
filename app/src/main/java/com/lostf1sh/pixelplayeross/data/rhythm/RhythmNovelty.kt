/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS (tools/rhythm-lab/rhythm_lab/novelty.py).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import java.util.Arrays
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The spectral-flux novelty curve the whole rhythm pipeline runs on.
 *
 * Stages: STFT magnitudes -> log-spaced band maxima -> running-max whitening -> half-wave
 * rectified band flux -> p95 normalisation. One band count serves the onset detector (8,
 * dominant band per onset becomes the lane hint) and another serves the beat tracker (64,
 * where the extra bands turn the curve into a much sharper beat marker).
 *
 * Pure Kotlin with no Android types so the PC lab and unit tests can run it unchanged.
 */
internal object RhythmNovelty {

    const val N_FFT = 2048
    const val HOP = 512

    /** Running-max window in frames, inclusive of the current frame. */
    const val WHITEN_WINDOW = 32

    class Result(
        /** p95-normalised novelty, one value per frame. */
        val values: FloatArray,
        /** Whitened band energies, frameCount * bandCount; empty when `keepBands` was false. */
        val bands: FloatArray,
        val bandCount: Int,
        val frameCount: Int,
        val fps: Double,
        val hopMs: Double,
        val frameOffsetMs: Double,
    ) {
        /** Centre-of-window time for a (possibly fractional) frame index. */
        fun frameToMs(frame: Double): Double = frameOffsetMs + frame * hopMs
    }

    /**
     * Log-spaced band edges as FFT bin indices (not Hz) so both band counts stay comparable
     * with the reference implementation: `[1, 2, 6, 13, 32, 76, 181, 431, 1024]` at 2048 with
     * 8 bands. Edges collapse where a band would be narrower than one bin, so the effective
     * band count can be below [bandCount] (64 bands give 52 at 44.1 kHz).
     */
    fun bandEdges(bandCount: Int): IntArray {
        val highestBin = N_FFT / 2
        val edges = IntArray(bandCount + 1)
        val logLow = ln(1.0)
        val logHigh = ln(highestBin.toDouble())
        for (index in 0..bandCount) {
            val value = exp(logLow + (logHigh - logLow) * index / bandCount)
            edges[index] = Math.round(value).toInt()
        }
        return edges.distinct().toIntArray()
    }

    /**
     * @param keepBands also return the whitened band matrix (the onset detector needs it to
     *   pick a dominant band). The beat tracker only needs the summed novelty.
     * @param onProgress progress in 0..1 over this computation.
     */
    fun compute(
        pcmMono: FloatArray,
        sampleRate: Int,
        bandCount: Int,
        keepBands: Boolean,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        if (pcmMono.size < N_FFT) {
            throw RhythmGenerationException("Audio is too short to analyse")
        }
        val frameCount = 1 + (pcmMono.size - N_FFT) / HOP
        if (frameCount < 16) {
            throw RhythmGenerationException("Audio is too short to analyse")
        }

        val edges = bandEdges(bandCount)
        val bands = edges.size - 1
        val window = FloatArray(N_FFT) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / (N_FFT - 1))).toFloat()
        }
        val fft = RealFft(N_FFT)
        val magnitudes = FloatArray(N_FFT / 2 + 1)
        val bandRaw = FloatArray(frameCount * bands)
        val progressStep = max(1, frameCount / 100)

        for (frame in 0 until frameCount) {
            if (isCancelled()) throw CancellationException("Chart generation cancelled")
            fft.magnitude(pcmMono, frame * HOP, window, magnitudes)
            val base = frame * bands
            for (band in 0 until bands) {
                var peak = 0f
                for (bin in edges[band] until edges[band + 1]) {
                    val value = magnitudes[bin]
                    if (value > peak) peak = value
                }
                bandRaw[base + band] = ln(1.0 + peak.toDouble()).toFloat()
            }
            if (frame % progressStep == 0) onProgress(0.60f * frame / frameCount)
        }

        // Running-max whitening (window includes the current frame) and half-wave flux in one
        // pass per band; the whitened matrix is only materialised when the caller needs it.
        val band = if (keepBands) FloatArray(frameCount * bands) else FloatArray(0)
        val flux = FloatArray(frameCount)
        val deque = IntArray(frameCount)
        for (index in 0 until bands) {
            var head = 0
            var tail = 0
            var previous = 0f
            for (frame in 0 until frameCount) {
                val value = bandRaw[frame * bands + index]
                while (tail > head && bandRaw[deque[tail - 1] * bands + index] <= value) {
                    tail--
                }
                deque[tail++] = frame
                val lowest = frame - WHITEN_WINDOW
                while (deque[head] < lowest) head++
                val whitened = value / (bandRaw[deque[head] * bands + index] + 1e-4f)
                if (keepBands) band[frame * bands + index] = whitened
                if (frame > 0) {
                    val delta = whitened - previous
                    if (delta > 0f) flux[frame] += delta
                }
                previous = whitened
            }
        }
        onProgress(0.85f)

        val p95 = percentile(flux, 0.95)
        val normalization = p95 + 1e-9f
        val values = FloatArray(frameCount) { flux[it] / normalization }
        onProgress(1f)

        return Result(
            values = values,
            bands = band,
            bandCount = bands,
            frameCount = frameCount,
            fps = sampleRate.toDouble() / HOP,
            hopMs = HOP * 1000.0 / sampleRate,
            frameOffsetMs = (N_FFT - HOP / 2.0) * 1000.0 / sampleRate,
        )
    }

    /** numpy's default linear interpolation, kept identical so fixtures stay comparable. */
    private fun percentile(values: FloatArray, fraction: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf()
        Arrays.sort(sorted)
        val position = (sorted.size - 1) * fraction.coerceIn(0.0, 1.0)
        val low = position.toInt()
        val high = min(low + 1, sorted.size - 1)
        val weight = position - low
        return (sorted[low] * (1.0 - weight) + sorted[high] * weight).toFloat()
    }
}
