/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS (tools/rhythm-lab/README.md).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracked grid against what is known to be true, not against another estimator:
 * `RhythmTestAudio.drumLoop` puts kicks on an exact 500 ms lattice from t = 0, so the anchors
 * must land on it and the fitted beat length must be 500 ms.
 */
class RhythmBeatTrackerTest {

    private val sampleRate = 44100

    @Test
    fun tracksTheBeatGridOfTheSyntheticLoop() {
        val audio = RhythmTestAudio.drumLoop(20.0, sampleRate)
        val result = RhythmBeatTracker.estimateTiming(
            pcmMono = audio,
            sampleRate = sampleRate,
            chartBpm = 120.0,
            durationMs = 20_000,
        )

        assertTrue("anchors ${result.timing.size}", result.timing.size >= 15)
        for (point in result.timing) {
            val nearestBeat = Math.round(point.timeMs / 500.0) * 500.0
            assertTrue(
                "anchor ${point.timeMs} off the beat lattice ($nearestBeat)",
                abs(point.timeMs - nearestBeat) <= 20.0,
            )
            assertTrue("beatMs ${point.beatMs}", abs(point.beatMs - 500.0) <= 5.0)
        }
    }

    @Test
    fun dropsTrailingBeatsFromTheSilentTail() {
        // The dense fixture loop (drums + bass + pad) zero-padded by 5 s: the gate must not
        // extend the grid into the fade-out where nothing plays — that is what dragged the
        // last segment late in the app. Dense audio matters: the gate compares against the
        // song's median 100 ms RMS, which is zero for a sparse drum synth and disables it.
        val url = requireNotNull(javaClass.classLoader?.getResource("rhythm/dsp-beats-120bpm.wav")) {
            "missing test resource rhythm/dsp-beats-120bpm.wav"
        }
        val (loop, rate) = RhythmTestAudio.readMonoWav16(File(url.toURI()))
        val audio = FloatArray(rate * 25)
        loop.copyInto(audio)
        val result = RhythmBeatTracker.estimateTiming(
            pcmMono = audio,
            sampleRate = rate,
            chartBpm = 120.0,
            durationMs = 25_000,
        )

        assertTrue(result.timing.isNotEmpty())
        assertTrue(
            "last anchor ${result.timing.last().timeMs} inside the silent tail",
            result.timing.last().timeMs < 20_500,
        )
    }

    @Test
    fun fallsBackToAFixedRateGridWhenNothingCanBeTracked() {
        val result = RhythmBeatTracker.estimateTiming(
            pcmMono = FloatArray(sampleRate * 5),
            sampleRate = sampleRate,
            chartBpm = 170.3,
            durationMs = 5_000,
        )

        assertEquals(1, result.timing.size)
        assertEquals(60_000.0 / 170.3, result.timing.single().beatMs, 1e-6)
    }

    @Test
    fun offsetShiftsEveryAnchor() {
        val audio = RhythmTestAudio.drumLoop(20.0, sampleRate)
        val plain = RhythmBeatTracker.estimateTiming(audio, sampleRate, 120.0, 20_000)
        val shifted = RhythmBeatTracker.estimateTiming(audio, sampleRate, 120.0, 20_000, extraOffsetMs = 40)

        assertEquals(plain.timing.size, shifted.timing.size)
        for ((index, point) in plain.timing.withIndex()) {
            assertEquals(point.timeMs + 40, shifted.timing[index].timeMs)
            assertEquals(point.beatMs, shifted.timing[index].beatMs, 0.0)
        }
    }
}
