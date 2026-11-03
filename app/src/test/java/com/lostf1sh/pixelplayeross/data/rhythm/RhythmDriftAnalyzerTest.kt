package com.lostf1sh.pixelplayeross.data.rhythm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Synthetic clock traces with a known drift, so each [RhythmDriftKind] is exercised on data whose
 * answer is decided by construction rather than by a real device.
 */
class RhythmDriftAnalyzerTest {

    private fun trace(
        count: Int,
        intervalMs: Double = 50.0,
        song: (wallMs: Double) -> Double,
        head: (wallMs: Double) -> Double,
    ): List<TracePoint> = (0 until count).map { index ->
        val wall = index * intervalMs
        TracePoint(wall, song(wall), head(wall), Double.NaN, ClockSource.HEAD)
    }

    @Test
    fun stableWhenTheClockTracksTheAudio() {
        // A constant 20ms lead (output latency) is an offset, not drift.
        val report = RhythmDriftAnalyzer.analyze(
            trace(2000, song = { it }, head = { it - 20.0 }),
            emptyList(),
        )
        assertEquals(RhythmDriftKind.STABLE, report.kind)
        assertTrue(abs(report.slopeMsPerSec) < 0.05, "slope=${report.slopeMsPerSec}")
        assertTrue(report.maxAbsResidualMs < 1.0, "maxAbs=${report.maxAbsResidualMs}")
    }

    @Test
    fun overRunWhenTheClockPullsAheadOfTheAudio() {
        // songMs gains 0.1% over headMs: +1ms of residual per second of wall time.
        val report = RhythmDriftAnalyzer.analyze(
            trace(2000, song = { it * 1.001 }, head = { it - 20.0 }),
            emptyList(),
        )
        assertEquals(RhythmDriftKind.PROGRESSIVE_OVER_RUN, report.kind)
        assertTrue(report.slopeMsPerSec > 0.5, "slope=${report.slopeMsPerSec}")
        assertTrue(report.driftPpm > 500.0, "ppm=${report.driftPpm}")
        assertTrue(report.endResidualMs > 50.0, "end=${report.endResidualMs}")
    }

    @Test
    fun underRunWhenTheClockFallsBehindTheAudio() {
        val report = RhythmDriftAnalyzer.analyze(
            trace(2000, song = { it * 0.999 }, head = { it - 20.0 }),
            emptyList(),
        )
        assertEquals(RhythmDriftKind.PROGRESSIVE_UNDER_RUN, report.kind)
        assertTrue(report.slopeMsPerSec < -0.5, "slope=${report.slopeMsPerSec}")
        assertTrue(report.endResidualMs < -50.0, "end=${report.endResidualMs}")
    }

    @Test
    fun stepWhenASingleJumpExplainsTheExcursion() {
        // Flat, then one 100ms jump at the halfway point: an underrun, not gradual drift.
        val report = RhythmDriftAnalyzer.analyze(
            trace(2000, song = { if (it < 50_000) it else it + 100.0 }, head = { it - 20.0 }),
            emptyList(),
        )
        assertEquals(RhythmDriftKind.STEP, report.kind)
        assertTrue(report.largestStepMs > 60.0, "step=${report.largestStepMs}")
    }

    @Test
    fun sawtoothWhenTheResidualOscillatesWithNoNetTrend() {
        // ±60ms oscillation, zero net slope: sparse device samples, not accumulating drift.
        val report = RhythmDriftAnalyzer.analyze(
            trace(
                2000,
                song = { it + 60.0 * sin(2 * PI * it / 2000.0) },
                head = { it - 20.0 },
            ),
            emptyList(),
        )
        assertEquals(RhythmDriftKind.SAWTOOTH, report.kind)
        assertTrue(abs(report.slopeMsPerSec) < 0.05, "slope=${report.slopeMsPerSec}")
        assertTrue(report.maxAbsResidualMs > 40.0, "maxAbs=${report.maxAbsResidualMs}")
    }

    @Test
    fun insufficientDataWhenTheTraceIsTooShort() {
        val report = RhythmDriftAnalyzer.analyze(
            trace(10, song = { it * 1.001 }, head = { it - 20.0 }),
            emptyList(),
        )
        assertEquals(RhythmDriftKind.INSUFFICIENT_DATA, report.kind)
    }

    @Test
    fun recorderBuildsCrossingsAndFlagsTheOverRun() {
        val recorder = RhythmDriftRecorder()
        recorder.start(0L)
        // One note every 500ms across a 100s run; the clock over-runs the audio by 0.1%.
        val notes = (0 until 200).map { RhythmNote(it, it * 500, 0) }
        var nanos = 0L
        repeat(2000) { index ->
            val wall = index * 50.0
            recorder.recordFrame(
                RhythmClockSample(wall * 1.001, wall - 20.0, Double.NaN, ClockSource.HEAD),
                nanos,
            )
            nanos += 50L * 1_000_000L
        }

        val report = recorder.analyze(notes)
        assertEquals(RhythmDriftKind.PROGRESSIVE_OVER_RUN, report.kind)
        assertTrue(report.crossingCount > 100, "crossings=${report.crossingCount}")
        // The note-vs-audio residual grows toward the end of the song: the player's exact symptom.
        assertTrue(report.crossingSlopeMsPerSec > 0.5, "crossingSlope=${report.crossingSlopeMsPerSec}")
        assertTrue(report.crossingEndResidualMs > 50.0, "crossingEnd=${report.crossingEndResidualMs}")
    }

    @Test
    fun exportTextContainsTheTraceAndClassification() {
        val recorder = RhythmDriftRecorder()
        recorder.start(0L)
        var nanos = 0L
        repeat(100) { index ->
            val wall = index * 50.0
            recorder.recordFrame(RhythmClockSample(wall, wall - 20.0, Double.NaN, ClockSource.HEAD), nanos)
            nanos += 50L * 1_000_000L
        }
        val text = recorder.exportText("songId=test", (0 until 10).map { RhythmNote(it, it * 500, 0) })
        assertTrue(text.contains("kind="), text)
        assertTrue(text.contains("== trace"), text)
        assertTrue(text.contains("== crossings"), text)
    }
}
