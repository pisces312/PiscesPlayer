/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §6).
 */
package com.pisces312.piscesplayer.data.noise

import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundscapeMixerTest {

    private val frames = 1_024

    private class ConstantSource(private val value: Float) : TrackSource {
        override fun generate(out: FloatArray, frames: Int) {
            for (i in 0 until frames * 2) {
                out[i] = value
            }
        }
    }

    /** Mixes ~1 s worth of blocks so the master gain converges on its target. */
    private fun converge(mixer: SoundscapeMixer, sources: List<TrackSource>): FloatArray {
        val out = FloatArray(frames * 2)
        repeat(50) { mixer.mix(out, frames, sources) }
        return out
    }

    @Test
    fun transparentWhenTheSumStaysBelowTheLimiterThreshold() {
        val mixer = SoundscapeMixer()
        mixer.setMasterTarget(1f)
        val out = converge(mixer, List(2) { ConstantSource(0.3f) })

        // 0.3 + 0.3 = 0.6 < 0.8 threshold: the limiter must be exactly unity.
        for (v in out) {
            assertEquals(0.6f, v, 1e-4f)
        }
    }

    @Test
    fun softLimitsInsteadOfClippingWhenTracksSumAboveThreshold() {
        val mixer = SoundscapeMixer()
        mixer.setMasterTarget(1f)
        val out = converge(mixer, List(4) { ConstantSource(0.35f) })

        for (v in out) {
            // Sum would be 1.4; limited output must stay in (threshold, 1.0].
            assertTrue("limited value $v out of range", v > SoundscapeMixer.LIMIT_THRESHOLD && v <= 1.0f)
        }
    }

    @Test
    fun emptyTrackListProducesSilence() {
        val mixer = SoundscapeMixer()
        mixer.setMasterTarget(1f)
        val out = converge(mixer, emptyList())
        for (v in out) {
            assertEquals(0f, v, 0f)
        }
    }

    @Test
    fun masterGainFadesInInsteadOfStepping() {
        val mixer = SoundscapeMixer()
        mixer.setMasterTarget(0f)
        val sources = List(2) { ConstantSource(0.5f) }

        // Silence, then open up: the first 100 frames of the next block must stay
        // quiet (a 42 ms exponential, not an instant step).
        converge(mixer, sources)
        mixer.setMasterTarget(1f)
        val out = FloatArray(frames * 2)
        mixer.mix(out, frames, sources)

        var maxAbs = 0f
        for (i in 0 until 100 * 2) maxAbs = max(maxAbs, abs(out[i]))
        assertTrue("master faded in too fast, first 100 frames peak=$maxAbs", maxAbs < 0.05f)
    }

    @Test
    fun realSynthTracksSurviveFullStacking() {
        val mixer = SoundscapeMixer()
        mixer.setMasterTarget(1f)
        val tracks = listOf(
            NoiseSynthesis(seed = 1L).apply {
                setColor(NoiseColor.BROWN); setMotion(0f); setWidth(1f); setTargetGain(1f)
            },
            NoiseSynthesis(seed = 2L).apply {
                setColor(NoiseColor.PINK); setMotion(0f); setWidth(1f); setTargetGain(1f)
            },
            NoiseSynthesis(seed = 3L).apply {
                setColor(NoiseColor.WHITE); setMotion(0f); setWidth(1f); setTargetGain(0.5f)
            },
            NoiseSynthesis(seed = 4L).apply {
                setColor(NoiseColor.DEEP_BROWN); setMotion(0f); setWidth(1f); setTargetGain(0.8f)
            },
        )
        val out = FloatArray(frames * 2)
        // Long run: no state drift, no clipping, stable output across blocks.
        repeat(100) {
            mixer.mix(out, frames, tracks)
            for (v in out) {
                assertTrue("mixed output clipped: $v", abs(v) <= 1f)
            }
        }
        val lastRms = blockRms(out)
        mixer.mix(out, frames, tracks)
        val nowRms = blockRms(out)
        assertTrue(
            "output drifted: last=$lastRms now=$nowRms",
            abs(nowRms - lastRms) / max(lastRms, 1e-6f) < 0.5f,
        )
    }

    private fun blockRms(out: FloatArray): Float {
        var acc = 0.0
        for (v in out) acc += v.toDouble() * v
        return kotlin.math.sqrt(acc / out.size).toFloat()
    }
}
