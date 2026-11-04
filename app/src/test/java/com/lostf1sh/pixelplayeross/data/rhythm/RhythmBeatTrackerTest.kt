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

    @Test
    fun midSongChainSlipStaysOnTheBeatLattice() {
        // The DP chain can re-lock onto the offbeat after a jittery stretch and stay there
        // (Far Away from Home). Beats on a 1000 ms lattice slip a quarter period from beat 8
        // on — the slipped camp is even the majority, so only the onset evidence can keep the
        // grid on the beat.
        val beats = DoubleArray(20) { it * 1000.0 + if (it < 8) 0.0 else 250.0 }
        // Strong onsets on the true beat, weak ones on the slipped chain's offbeat.
        val evidenceTimes = DoubleArray(80) { it * 250.0 }
        val evidenceStrengths = FloatArray(80) { if (it % 2 == 0) 1.0f else 0.3f }
        val timing = RhythmBeatTracker.piecewiseTiming(
            beatTimesMs = beats,
            durationMs = 30_000,
            evidenceTimesMs = evidenceTimes,
            evidenceStrengths = evidenceStrengths,
        )

        assertTrue("anchors ${timing.size}", timing.size >= 15)
        for (point in timing) {
            assertTrue("beatMs ${point.beatMs}", abs(point.beatMs - 500.0) <= 2.0)
            assertTrue(
                "anchor ${point.timeMs} followed the slipped chain",
                abs(point.timeMs % 1000.0) <= 2.0,
            )
        }
        for (index in 0 until timing.size - 1) {
            val spacing = timing[index + 1].timeMs - timing[index].timeMs
            assertTrue("spacing at anchor $index is $spacing", abs(spacing - 1000) <= 2.0)
        }
    }

    @Test
    fun gradualTempoChangeIsFollowed() {
        // A ritardando: the period grows 1000 -> 1100 ms over 30 beats. The lattice must
        // follow the onsets instead of holding a fixed period and walking off the beat.
        val beats = DoubleArray(30)
        var time = 0.0
        for (index in beats.indices) {
            beats[index] = time
            time += 1000.0 + index * (100.0 / 29.0)
        }
        val onsetTimes = ArrayList<Double>()
        val onsetStrengths = ArrayList<Float>()
        for (beat in beats) {
            onsetTimes += beat
            onsetStrengths += 1.0f
            onsetTimes += beat + 250.0
            onsetStrengths += 0.3f
        }
        val timing = RhythmBeatTracker.piecewiseTiming(
            beatTimesMs = beats,
            durationMs = 40_000,
            evidenceTimesMs = onsetTimes.toDoubleArray(),
            evidenceStrengths = onsetStrengths.toFloatArray(),
        )

        assertTrue("anchors ${timing.size}", timing.size >= 25)
        // The spacing follows the ramp smoothly (the chart beat grows 500 -> 550) and the
        // phase stays bounded: the smoothed period lags the ramp a little, the drift builds,
        // and the evidence-gated re-snap pulls it back every dozen-odd beats.
        val first = timing.first()
        val last = timing.last()
        assertTrue("beatMs ${first.beatMs} -> ${last.beatMs}", last.beatMs > first.beatMs + 15.0)
        assertTrue("beatMs ${last.beatMs}", last.beatMs in 480.0..570.0)
        val outliers = timing.count { point ->
            val nearestBeat = beats.minByOrNull { abs(it - point.timeMs) }!!
            abs(point.timeMs - nearestBeat) > 135.0
        }
        assertTrue("outliers $outliers of ${timing.size}", outliers == 0)
        for (index in 1 until timing.size) {
            val spacing = timing[index].timeMs - timing[index - 1].timeMs
            // Smooth spacing plus the occasional evidence-gated re-snap (~±150 ms).
            assertTrue("spacing $spacing", spacing >= 880 && spacing <= 1250)
        }
    }

    @Test
    fun tempoStepSnapsToTheNewRate() {
        // 120 BPM for 14 beats, then 90 BPM (the tempo_shift sample's step). The smoothed
        // period lags a step by design, so the evidence-gated re-snap has to realign the
        // lattice onto the chain's new rate within a couple of beats.
        val beats = DoubleArray(24) { if (it < 14) it * 1000.0 else 13 * 1000.0 + (it - 13) * 1333.333 }
        val onsetTimes = ArrayList<Double>()
        val onsetStrengths = ArrayList<Float>()
        for (beat in beats) {
            onsetTimes += beat
            onsetStrengths += 1.0f
        }
        val timing = RhythmBeatTracker.piecewiseTiming(
            beatTimesMs = beats,
            durationMs = 40_000,
            evidenceTimesMs = onsetTimes.toDoubleArray(),
            evidenceStrengths = onsetStrengths.toFloatArray(),
        )

        assertTrue("anchors ${timing.size}", timing.size >= 20)
        val outliers = timing.count { point ->
            val nearestBeat = beats.minByOrNull { abs(it - point.timeMs) }!!
            abs(point.timeMs - nearestBeat) > 80.0
        }
        assertTrue("outliers $outliers of ${timing.size}", outliers <= 5)
        val tail = timing.takeLast(3).map { it.timeMs }
        for (index in 1 until tail.size) {
            val spacing = tail[index] - tail[index - 1]
            assertTrue("tail spacing $spacing", spacing >= 1250 && spacing <= 1420)
        }
    }

    @Test
    fun introBeforeLockInIsRebuiltAtTheSongTempo() {
        // First five beats tracked at a corrupted 940 ms rate, the rest at 1000 ms. The
        // anchors before the lock-in point must be rebuilt on the whole-song period instead
        // of following the corrupted beats.
        val beats = DoubleArray(20) { if (it < 5) it * 940.0 else 4 * 940.0 + (it - 4) * 1000.0 }
        val timing = RhythmBeatTracker.piecewiseTiming(beats, durationMs = 30_000)

        assertTrue("anchors ${timing.size}", timing.size >= 15)
        for (index in 0 until timing.size - 1) {
            val spacing = timing[index + 1].timeMs - timing[index].timeMs
            // Near the lock-in seam the fitted anchor carries its own fit noise (~20 ms),
            // but no spacing may follow the corrupted 940 ms rate.
            assertTrue("spacing at anchor $index is $spacing", abs(spacing - 1000) <= 30)
        }
        // The pure extrapolation prefix is exact up to rounding.
        val prefix = timing[1].timeMs - timing[0].timeMs
        assertEquals(1000.0, prefix.toDouble(), 2.0)
        assertEquals(500.0, timing.first().beatMs, 5.0)
    }
}
