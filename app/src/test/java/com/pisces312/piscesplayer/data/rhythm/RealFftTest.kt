package com.pisces312.piscesplayer.data.rhythm

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealFftTest {

    @Test
    fun matchesNaiveDft() {
        for (size in intArrayOf(16, 64, 256)) {
            val random = Random(size)
            val input = FloatArray(size) { random.nextFloat() * 2f - 1f }
            val actual = magnitudeOf(input, size)
            val expected = naiveMagnitude(input, size)
            val peak = expected.max()
            for (bin in expected.indices) {
                assertEquals(
                    "size=$size bin=$bin",
                    expected[bin],
                    actual[bin],
                    peak * 1e-4f,
                )
            }
        }
    }

    @Test
    fun matchesNaiveDftForAudioSizedTransform() {
        val size = 2048
        val random = Random(7)
        val input = FloatArray(size) { random.nextFloat() * 2f - 1f }
        val actual = magnitudeOf(input, size)
        val expected = naiveMagnitude(input, size)
        var worst = 0f
        for (bin in expected.indices) {
            worst = maxOf(worst, abs(expected[bin] - actual[bin]))
        }
        assertEquals(0f, worst, 1e-3f)
    }

    @Test
    fun impulseHasFlatSpectrum() {
        val size = 64
        val input = FloatArray(size)
        input[0] = 1f
        val actual = magnitudeOf(input, size)
        for (bin in 0 until size / 2 + 1) {
            assertEquals(1f, actual[bin], 1e-5f)
        }
    }

    @Test
    fun singleSinePutsEnergyInOneBin() {
        val size = 1024
        val binIndex = 37
        val input = FloatArray(size) { index ->
            sin(2.0 * PI * binIndex * index / size).toFloat()
        }
        val actual = magnitudeOf(input, size)
        var peakBin = 0
        for (bin in 1 until size / 2) {
            if (actual[bin] > actual[peakBin]) peakBin = bin
        }
        assertEquals(binIndex, peakBin)
        // A full-scale sine in one bin has magnitude size/2.
        assertEquals(size / 2f, actual[binIndex], size * 1e-3f)
        assertTrue(actual.min() < size * 1e-3f)
    }

    @Test
    fun windowIsAppliedInsideTransform() {
        val size = 64
        val input = FloatArray(size) { 1f }
        val window = FloatArray(size) { 1f }
        val unwindowed = magnitudeOf(input, size, window)
        assertEquals(size.toFloat(), unwindowed[0], 1e-4f)

        val hann = FloatArray(size) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / (size - 1))).toFloat()
        }
        val windowed = magnitudeOf(input, size, hann)
        // The DC bin is the sum of the window, which equals (size - 1) / 2 for a symmetric Hann.
        assertEquals((size - 1) / 2f, windowed[0], 1e-3f)
    }

    @Test
    fun rejectsNonPowerOfTwoSize() {
        val failure = runCatching { RealFft(1000) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun magnitudeOf(
        input: FloatArray,
        size: Int,
        window: FloatArray = FloatArray(size) { 1f },
    ): FloatArray {
        val output = FloatArray(size / 2 + 1)
        RealFft(size).magnitude(input, 0, window, output)
        return output
    }

    private fun naiveMagnitude(input: FloatArray, size: Int): FloatArray {
        val output = FloatArray(size / 2 + 1)
        for (bin in 0..size / 2) {
            var real = 0.0
            var imaginary = 0.0
            for (index in 0 until size) {
                val angle = -2.0 * PI * bin * index / size
                real += input[index] * cos(angle)
                imaginary += input[index] * sin(angle)
            }
            output[bin] = kotlin.math.hypot(real, imaginary).toFloat()
        }
        return output
    }
}
