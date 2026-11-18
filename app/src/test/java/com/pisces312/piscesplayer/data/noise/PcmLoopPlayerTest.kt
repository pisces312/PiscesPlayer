/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §4.2).
 */
package com.pisces312.piscesplayer.data.noise

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmLoopPlayerTest {

    private val sampleRate = 48_000

    /** Three seconds of a slow ramp: distinctive per-frame values, gentle slope. */
    private fun rampBuffer(seconds: Int = 3): FloatArray {
        val frames = sampleRate * seconds
        val buf = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = 0.1f + 0.5f * i / frames
            buf[i * 2] = v
            buf[i * 2 + 1] = v
        }
        return buf
    }

    @Test
    fun gainConvergesToTargetOnAConstantBuffer() {
        val buf = FloatArray(sampleRate * 3 * 2) { 0.5f }
        val player = PcmLoopPlayer(buf, sampleRate)
        player.setTargetGain(1f)

        val out = FloatArray(sampleRate * 2 * 2) // 2 s * stereo
        player.generate(out, sampleRate * 2)

        // After 1 s of per-sample smoothing the gain is within ~1% of unity.
        assertEquals(0.5, out[out.size - 2].toDouble(), 0.01)
        assertEquals(0.5, out[out.size - 1].toDouble(), 0.01)
    }

    @Test
    fun zeroGainOutputsSilence() {
        val buf = rampBuffer()
        val player = PcmLoopPlayer(buf, sampleRate)
        player.setTargetGain(0f)
        val out = FloatArray(4_096)
        player.generate(out, 2_048)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun loopWrapLandsOnTheCrossfadeContinuation() {
        val seconds = 3
        val buf = rampBuffer(seconds)
        val total = buf.size / 2
        val player = PcmLoopPlayer(buf, sampleRate)
        player.setTargetGain(1f)

        // Warm up so the gain is converged, consuming exactly `total` frames:
        // the read head is now parked on `total`, one step before the wrap.
        val discard = FloatArray(total * 2)
        player.generate(discard, total)

        val out = FloatArray(2)
        player.generate(out, 1)

        // The read head wrapped to `crossfadeFrames`, not to 0: the head
        // segment was already heard inside the previous pass's crossfade.
        val expected = buf[player.crossfadeFrames * 2]
        assertEquals(expected.toDouble(), out[0].toDouble(), 0.01)
    }

    @Test
    fun seamIsClickFreeAcrossTheLoopBoundary() {
        val buf = rampBuffer(3)
        val player = PcmLoopPlayer(buf, sampleRate)
        player.setTargetGain(1f)

        // Render well past one full loop and check adjacent-frame continuity.
        val block = 4_096
        val out = FloatArray(block * 2)
        var maxDelta = 0f
        var prevL: Float? = null
        var prevR: Float? = null
        repeat(40) {
            player.generate(out, block)
            for (i in 0 until block) {
                val l = out[i * 2]
                val r = out[i * 2 + 1]
                if (prevL != null) {
                    maxDelta = maxOf(maxDelta, abs(l - prevL))
                    maxDelta = maxOf(maxDelta, abs(r - prevR!!))
                }
                prevL = l
                prevR = r
            }
        }
        // Ramp slope is 0.5/total per frame and the crossfade region adds its
        // own gentle slope; a seam discontinuity would jump by ~0.5 instead.
        assertTrue("seam jump $maxDelta", maxDelta < 0.01)
    }

    @Test
    fun rejectsAssetsShorterThanTwoSeconds() {
        val short = FloatArray(sampleRate) // 0.5 s stereo
        assertThrows(IllegalArgumentException::class.java) {
            PcmLoopPlayer(short, sampleRate)
        }
    }
}
