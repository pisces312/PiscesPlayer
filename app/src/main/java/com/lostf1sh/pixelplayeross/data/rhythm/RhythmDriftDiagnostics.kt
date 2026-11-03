/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS (docs/rhythm-clock-drift-diagnostics.md §4).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import kotlin.math.abs

/** Which device source the clock anchored on for a given frame. */
enum class ClockSource { TIMESTAMP, HEAD, WALL }

/**
 * One frame's raw clock reading, captured inside [RhythmAudioClock] while it already reads the
 * device for `nowMs()`. `timestampMs` is NaN when AudioTimestamp is unavailable this frame.
 *
 * All positions are milliseconds of audio. `headMs` is `playbackHeadPosition`-derived (frames the
 * output rendered, ahead of what is heard by the buffer latency); `songMs` is what the game clock
 * reported and therefore what drove rendering and judgement.
 */
data class RhythmClockSample(
    val songMs: Double,
    val headMs: Double,
    val timestampMs: Double,
    val source: ClockSource,
)

/** A downsampled point of the run's clock trace. `wallMs` is ms since the clock started. */
data class TracePoint(
    val wallMs: Double,
    val songMs: Double,
    val headMs: Double,
    val timestampMs: Double,
    val source: ClockSource,
) {
    /** Clock minus the audio the output actually rendered: the gameplay-relevant drift. */
    val songVsHeadMs: Double get() = songMs - headMs
}

/** One note crossing the judgement line: how far the chart time was from the real audio. */
data class CrossingEvent(
    val noteId: Int,
    val noteTimeMs: Double,
    val songMs: Double,
    val headMs: Double,
    /** noteTimeMs − headMs at the crossing; grows over the song when the clock over-runs. */
    val residualMs: Double,
)

enum class RhythmDriftKind {
    STABLE,
    PROGRESSIVE_OVER_RUN,
    PROGRESSIVE_UNDER_RUN,
    STEP,
    SAWTOOTH,
    INSUFFICIENT_DATA,
}

/** Auto-classified result of one run's clock trace. Numbers, not prose: the UI localises. */
data class RhythmDriftReport(
    val kind: RhythmDriftKind,
    /** d(songMs − headMs)/d(wall second); >0 means the clock runs ahead of the audio. */
    val slopeMsPerSec: Double,
    val driftPpm: Double,
    /** Net growth of the residual from the run's opening to its end, in ms. */
    val endResidualMs: Double,
    val maxAbsResidualMs: Double,
    val largestStepMs: Double,
    val dominantSource: ClockSource,
    val timestampCoverage: Double,
    val crossingSlopeMsPerSec: Double,
    val crossingEndResidualMs: Double,
    val sampleCount: Int,
    val crossingCount: Int,
    val durationMs: Double,
)

/**
 * Collects one run's clock trace and turns it into a [RhythmDriftReport].
 *
 * Pure Kotlin (the caller supplies `nowNanos`) so the whole recorder + analyser is JVM-testable.
 * The trace is downsampled to roughly [SAMPLE_INTERVAL_MS] to keep memory and the export small;
 * note crossings are derived from the trace afterwards, so the hot game loop only appends points.
 */
class RhythmDriftRecorder {

    private val trace = ArrayList<TracePoint>()
    private var runStartNanos = 0L
    private var lastStoredWallMs = Double.NEGATIVE_INFINITY

    fun start(nowNanos: Long) {
        trace.clear()
        runStartNanos = nowNanos
        lastStoredWallMs = Double.NEGATIVE_INFINITY
    }

    /** Feed one frame's clock reading. Cheap enough to call every frame while playing. */
    fun recordFrame(sample: RhythmClockSample, nowNanos: Long) {
        val wallMs = (nowNanos - runStartNanos) / 1_000_000.0
        if (wallMs < 0) return
        if (trace.isNotEmpty() && wallMs - lastStoredWallMs < SAMPLE_INTERVAL_MS) return
        lastStoredWallMs = wallMs
        trace += TracePoint(wallMs, sample.songMs, sample.headMs, sample.timestampMs, sample.source)
    }

    val pointCount: Int get() = trace.size

    fun snapshot(): List<TracePoint> = trace.toList()

    /**
     * Build the note-crossing series and classify the run. `notes` are the chart's notes in
     * ascending time; a crossing is the first trace point whose `songMs` reached the note's time.
     */
    fun analyze(notes: List<RhythmNote>): RhythmDriftReport {
        val crossings = buildCrossings(notes)
        return RhythmDriftAnalyzer.analyze(trace, crossings)
    }

    /** Plain-text dump (header + report + CSV trace + crossings) for the share sheet. */
    fun exportText(meta: String, notes: List<RhythmNote>): String {
        val crossings = buildCrossings(notes)
        val report = RhythmDriftAnalyzer.analyze(trace, crossings)
        return buildString {
            appendLine("PixelPlayerOSS rhythm clock diagnostics")
            appendLine(meta)
            appendLine()
            appendLine("== classification ==")
            appendLine("kind=${report.kind}")
            appendLine("slopeMsPerSec=${"%.4f".format(report.slopeMsPerSec)}")
            appendLine("driftPpm=${"%.1f".format(report.driftPpm)}")
            appendLine("endResidualMs=${"%.2f".format(report.endResidualMs)}")
            appendLine("maxAbsResidualMs=${"%.2f".format(report.maxAbsResidualMs)}")
            appendLine("largestStepMs=${"%.2f".format(report.largestStepMs)}")
            appendLine("dominantSource=${report.dominantSource}")
            appendLine("timestampCoverage=${"%.2f".format(report.timestampCoverage)}")
            appendLine("crossingSlopeMsPerSec=${"%.4f".format(report.crossingSlopeMsPerSec)}")
            appendLine("crossingEndResidualMs=${"%.2f".format(report.crossingEndResidualMs)}")
            appendLine("samples=${report.sampleCount} crossings=${report.crossingCount} durationMs=${"%.0f".format(report.durationMs)}")
            appendLine()
            appendLine("== trace (wallMs,songMs,headMs,timestampMs,source,songVsHeadMs) ==")
            for (p in trace) {
                val ts = if (p.timestampMs.isNaN()) "nan" else "%.3f".format(p.timestampMs)
                appendLine(
                    "%.1f,%.3f,%.3f,%s,%s,%.3f".format(
                        p.wallMs, p.songMs, p.headMs, ts, p.source, p.songVsHeadMs,
                    ),
                )
            }
            appendLine()
            appendLine("== crossings (noteId,noteTimeMs,songMs,headMs,residualMs) ==")
            for (c in crossings) {
                appendLine(
                    "%d,%.1f,%.3f,%.3f,%.3f".format(c.noteId, c.noteTimeMs, c.songMs, c.headMs, c.residualMs),
                )
            }
        }
    }

    private fun buildCrossings(notes: List<RhythmNote>): List<CrossingEvent> {
        if (trace.isEmpty()) return emptyList()
        val out = ArrayList<CrossingEvent>(notes.size)
        var i = 0
        for (note in notes.sortedBy { it.timeMs }) {
            val target = note.timeMs.toDouble()
            while (i < trace.size && trace[i].songMs < target) i++
            if (i >= trace.size) break
            val head = if (i == 0) {
                trace[0].headMs
            } else {
                val a = trace[i - 1]
                val b = trace[i]
                val span = b.songMs - a.songMs
                if (span <= 0) b.headMs else a.headMs + (b.headMs - a.headMs) * ((target - a.songMs) / span)
            }
            val song = trace[i].songMs
            out += CrossingEvent(note.id, target, song, head, target - head)
        }
        return out
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 50.0
    }
}

/** Least-squares classification of a clock trace. Pure and side-effect free. */
object RhythmDriftAnalyzer {

    /** Below this many points a slope is noise, not a verdict. */
    private const val MIN_SAMPLES = 24

    /** A single-frame residual jump above this reads as an underrun/seek step, not drift. */
    private const val STEP_MS = 60.0

    /** Slope (ms of residual per second of wall time) treated as flat. 0.05 ms/s == 50 ppm. */
    private const val STABLE_SLOPE = 0.05

    /** Residual band (ms) that still counts as "no meaningful drift" when the slope is flat. */
    private const val STABLE_BAND_MS = 40.0

    fun analyze(trace: List<TracePoint>, crossings: List<CrossingEvent>): RhythmDriftReport {
        if (trace.size < MIN_SAMPLES) {
            return RhythmDriftReport(
                kind = RhythmDriftKind.INSUFFICIENT_DATA,
                slopeMsPerSec = 0.0, driftPpm = 0.0, endResidualMs = 0.0, maxAbsResidualMs = 0.0,
                largestStepMs = 0.0, dominantSource = ClockSource.WALL, timestampCoverage = 0.0,
                crossingSlopeMsPerSec = 0.0, crossingEndResidualMs = 0.0,
                sampleCount = trace.size, crossingCount = crossings.size,
                durationMs = trace.lastOrNull()?.wallMs ?: 0.0,
            )
        }

        val xs = DoubleArray(trace.size) { trace[it].wallMs / 1000.0 }
        val ys = DoubleArray(trace.size) { trace[it].songVsHeadMs }
        val slope = regressionSlope(xs, ys)

        // Growth is measured against the run's opening offset, so a constant latency between the
        // clock and the rendered audio does not masquerade as drift.
        val baseline = median(window(ys, 0, ys.size / 10))
        var maxAbsGrowth = 0.0
        var largestStep = 0.0
        var bigJumps = 0
        for (i in ys.indices) {
            maxAbsGrowth = maxOf(maxAbsGrowth, abs(ys[i] - baseline))
            if (i > 0) {
                val step = abs(ys[i] - ys[i - 1])
                largestStep = maxOf(largestStep, step)
                if (step > STEP_MS) bigJumps++
            }
        }
        val endResidual = median(window(ys, ys.size - ys.size / 10, ys.size)) - baseline

        val kind = when {
            // One (or two) discrete jumps explain the whole excursion: an underrun/seek, not drift.
            // Gradual drift has tiny per-frame steps; a sawtooth has many large ones.
            bigJumps in 1..2 && largestStep > STEP_MS && largestStep >= 0.5 * maxAbsGrowth ->
                RhythmDriftKind.STEP
            abs(slope) < STABLE_SLOPE && maxAbsGrowth < STABLE_BAND_MS -> RhythmDriftKind.STABLE
            abs(slope) < STABLE_SLOPE -> RhythmDriftKind.SAWTOOTH
            slope > 0 -> RhythmDriftKind.PROGRESSIVE_OVER_RUN
            else -> RhythmDriftKind.PROGRESSIVE_UNDER_RUN
        }

        val timestampPoints = trace.count { !it.timestampMs.isNaN() }
        val dominant = trace.groupingBy { it.source }.eachCount().maxByOrNull { it.value }?.key
            ?: ClockSource.WALL

        val crossingSlope = if (crossings.size >= MIN_SAMPLES) {
            regressionSlope(
                DoubleArray(crossings.size) { crossings[it].noteTimeMs / 1000.0 },
                DoubleArray(crossings.size) { crossings[it].residualMs },
            )
        } else 0.0
        val crossingEnd = if (crossings.isNotEmpty()) {
            val tail = crossings.subList(maxOf(0, crossings.size - crossings.size / 10), crossings.size)
            tail.map { it.residualMs }.average()
        } else 0.0

        return RhythmDriftReport(
            kind = kind,
            slopeMsPerSec = slope,
            driftPpm = slope * 1000.0,
            endResidualMs = endResidual,
            maxAbsResidualMs = maxAbsGrowth,
            largestStepMs = largestStep,
            dominantSource = dominant,
            timestampCoverage = timestampPoints.toDouble() / trace.size,
            crossingSlopeMsPerSec = crossingSlope,
            crossingEndResidualMs = crossingEnd,
            sampleCount = trace.size,
            crossingCount = crossings.size,
            durationMs = trace.last().wallMs,
        )
    }

    private fun regressionSlope(x: DoubleArray, y: DoubleArray): Double {
        val n = x.size
        if (n < 2) return 0.0
        var meanX = 0.0
        var meanY = 0.0
        for (i in 0 until n) { meanX += x[i]; meanY += y[i] }
        meanX /= n; meanY /= n
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = x[i] - meanX
            num += dx * (y[i] - meanY)
            den += dx * dx
        }
        return if (den <= 0.0) 0.0 else num / den
    }

    private fun window(values: DoubleArray, from: Int, to: Int): DoubleArray {
        val lo = from.coerceIn(0, values.size)
        val hi = to.coerceIn(lo, values.size)
        return if (hi <= lo) values.copyOfRange(0, minOf(1, values.size)) else values.copyOfRange(lo, hi)
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf().also { it.sort() }
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
