/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (chart auto-play diagnostics).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import com.lostf1sh.pixelplayeross.presentation.rhythm.RhythmJudgement
import com.lostf1sh.pixelplayeross.presentation.rhythm.RhythmJudgementEngine
import com.lostf1sh.pixelplayeross.presentation.rhythm.RhythmJudgementEvent
import java.io.File
import kotlin.math.abs

/**
 * Auto-play diagnostics for real songs: build charts with the phone's own pipeline
 * ([RhythmChartGenerator.analyze] + [RhythmChartDeriver]), then play them with a virtual
 * player who taps exactly what they hear — the master's onsets — and let the real
 * [RhythmJudgementEngine] (the same ±60/90/140 ms windows the game uses) settle every note.
 *
 * A chart that drifts away from the audible hits produces MISS runs exactly where a player
 * following the rhythm would fail, so the report answers "where does it break" directly.
 *
 * Two rulers per note:
 * - `onsetDeltaMs` = note − nearest audible hit. This is the tap error of a rhythm-following
 *   player; |error| > 140 ms is a miss. Legitimate quantization keeps this near zero because
 *   the snap always moves an onset onto its *nearest* grid line.
 * - `pulseDeltaMs` = note − nearest beat of an independent pulse reference (the lab's
 *   `timing-reference.osu`), subdivided to the chart's own grid density. If this grows while
 *   onsetDelta stays calm, the tracker lost the beat itself, not just the snap.
 *
 * Run it through [RhythmAutoPlayToolTest]; it documents the environment variables.
 */
internal object RhythmAutoPlayTool {

    /** Judgement windows of the game; only used to classify, the engine applies them. */
    private const val MISS_MS = 140.0

    // ------------------------------------------------------------------ pulse reference

    /** One osu! red timing point: beat grid starts at `timeMs` and repeats every `beatMs`. */
    data class PulseSegment(val timeMs: Double, val beatMs: Double)

    /** Parses the uninherited `[TimingPoints]` of an osu! file. */
    fun parseOsuPulse(file: File): List<PulseSegment> {
        val segments = ArrayList<PulseSegment>()
        var section = ""
        for (rawLine in file.readLines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("//")) continue
            if (line.startsWith("[")) {
                section = line
                continue
            }
            if (section != "[TimingPoints]") continue
            val parts = line.split(",")
            if (parts.size < 2) continue
            val time = parts[0].toDoubleOrNull() ?: continue
            val beatLength = parts[1].toDoubleOrNull() ?: continue
            if (beatLength > 0.0) segments += PulseSegment(time, beatLength)
        }
        return segments.sortedBy { it.timeMs }
    }

    /**
     * Expands [segments] into pulse times within `0..durationMs`, subdividing each beat into
     * `subdivision` equal parts so the pulse reaches the chart grid's density (an osu! beat is
     * a music beat while the chart beat is half of it).
     */
    fun expandPulse(
        segments: List<PulseSegment>,
        durationMs: Double,
        subdivision: Int,
    ): DoubleArray {
        val sub = subdivision.coerceIn(1, 8)
        val out = ArrayList<Double>()
        for ((index, segment) in segments.withIndex()) {
            val end = segments.getOrNull(index + 1)?.timeMs ?: (durationMs + segment.beatMs)
            val step = segment.beatMs / sub
            var time = segment.timeMs
            while (time < minOf(end, durationMs)) {
                if (time >= 0.0) out += time
                time += step
            }
        }
        return out.toDoubleArray()
    }

    // ------------------------------------------------------------------ simulation

    /** One judged note: what the player taps (the audible hit) versus where the chart puts it. */
    data class NoteVerdict(
        val noteId: Int,
        val timeMs: Int,
        val lane: Int,
        val onsetDeltaMs: Double,
        val kickDeltaMs: Double?,
        val pulseDeltaMs: Double?,
        val judgement: RhythmJudgement,
    )

    /** Everything one chart produced, in report-ready form. */
    data class ChartReport(
        val label: String,
        val noteCount: Int,
        val masterOnsets: Int,
        val verdicts: List<NoteVerdict>,
    ) {
        val counts: Map<RhythmJudgement, Int> =
            RhythmJudgement.entries.associateWith { judgement ->
                verdicts.count { it.judgement == judgement }
            }

        val accuracy: Double
            get() {
                val hit = verdicts.count { it.judgement != RhythmJudgement.MISS }
                return if (verdicts.isEmpty()) 1.0 else hit.toDouble() / verdicts.size
            }
    }

    /**
     * Plays [chart] with a virtual player: one tap per note, at the tap reference nearest to
     * the note, in the note's lane. The tap reference is what the player hears and taps to —
     * the master's onsets by default, or an external ground truth (e.g. the onsets of a
     * separated drums stem) when the onset stream is too dense to be a beat ruler.
     * The engine sees exactly the input stream the game would.
     */
    fun play(
        chart: RhythmChart,
        master: RhythmChart,
        pulse: DoubleArray?,
        tapTimes: List<Double>? = null,
        kickTimes: List<Double>? = null,
    ): ChartReport {
        val hitTimes = (tapTimes ?: master.notes.map { it.timeMs.toDouble() }).sorted()
        // The beat carrier: low-band onsets (kick/bass). Hats fire on offbeats too, so the
        // nearest onset of any kind can be a hat even when the note sits half a beat off the
        // pulse; the delta to the nearest low-band onset is what tells beat-on placement from
        // off-beat placement. An external kick list (e.g. a separated drums stem) is the
        // physical beat of the song and beats the full-mix bands as a ruler.
        val kicks = (kickTimes ?: master.notes.filter { it.band <= 2 }.map { it.timeMs.toDouble() })
            .sorted()
        val taps = chart.notes
            .map { it to nearestTo(hitTimes, it.timeMs.toDouble()) }
            .sortedWith(compareBy({ it.second }, { it.first.lane }))

        val engine = RhythmJudgementEngine(chart.notes)
        val events = HashMap<Int, RhythmJudgementEvent>()
        for ((note, tapMs) in taps) {
            for (event in engine.advance(tapMs)) events[event.noteId] = event
            val event = engine.hit(note.lane, tapMs)
            if (event.noteId >= 0) events[event.noteId] = event
        }
        for (event in engine.finish()) events[event.noteId] = event

        val verdicts = chart.notes.map { note ->
            val tapMs = nearestTo(hitTimes, note.timeMs.toDouble())
            NoteVerdict(
                noteId = note.id,
                timeMs = note.timeMs,
                lane = note.lane,
                onsetDeltaMs = note.timeMs - tapMs,
                kickDeltaMs = kicks.takeIf { it.isNotEmpty() }
                    ?.let { note.timeMs - nearestTo(it, note.timeMs.toDouble()) },
                pulseDeltaMs = pulse?.let { note.timeMs - nearestTo(it.toList(), note.timeMs.toDouble()) },
                judgement = events[note.id]?.judgement ?: RhythmJudgement.MISS,
            )
        }
        return ChartReport(
            label = chart.difficultyName,
            noteCount = chart.notes.size,
            masterOnsets = master.notes.size,
            verdicts = verdicts,
        )
    }

    // ------------------------------------------------------------------ reporting

    /** The chart-independent part: what the tracker did to the song. */
    fun formatTracking(analysis: DspAnalysis): String = buildString {
        appendLine(
            "tracking: detectedBpm=${fmt(analysis.detectedBpm)} usedBpm=${fmt(analysis.usedBpm)} " +
                "octaveCorrection=${fmt(analysis.octaveCorrection)} " +
                "chartBeat=${fmt(analysis.beatMs)}ms anchors=${analysis.timing.size}",
        )
        val beatJumps = analysis.timing.zipWithNext().filter { (a, b) ->
            abs(b.beatMs - a.beatMs) > 0.02 * a.beatMs
        }
        if (beatJumps.isEmpty()) {
            appendLine("anchors: beatMs stable, no seam above 2%")
        } else {
            appendLine("anchors: ${beatJumps.size} beatMs seam(s) above 2%:")
            for ((a, b) in beatJumps) {
                appendLine(
                    "  at ${b.timeMs}ms: beatMs ${fmt(a.beatMs)} -> ${fmt(b.beatMs)} " +
                        "(${fmt((b.beatMs - a.beatMs) / a.beatMs * 100.0)}%)",
                )
            }
        }
        // Per-30s anchor statistics: an octave flip or a drifting fit shows up here directly.
        val span = 30_000
        val buckets = analysis.timing.groupBy { it.timeMs / span }
        for ((bucket, points) in buckets.toSortedMap()) {
            val beats = points.map { it.beatMs }
            appendLine(
                "  ${bucket * span / 1000}s-${(bucket + 1) * span / 1000}s: " +
                    "beatMs min=${fmt(beats.min())} med=${fmt(beats.median())} " +
                    "max=${fmt(beats.max())} (n=${points.size})",
            )
        }
    }

    fun format(report: ChartReport): String = buildString {
        appendLine(
            "== ${report.label} == notes=${report.noteCount} masterOnsets=${report.masterOnsets} " +
                "accuracy=${fmt(report.accuracy * 100.0)}%",
        )
        appendLine(
            "verdicts: " + RhythmJudgement.entries.joinToString("  ") {
                "${it.name} ${report.counts[it] ?: 0}"
            },
        )
        val deltas = report.verdicts.map { abs(it.onsetDeltaMs) }
        appendLine(
            "onsetDelta: median=${fmt(deltas.median())}ms p90=${fmt(deltas.percentile(0.9))}ms " +
                "max=${fmt(deltas.max())}ms",
        )
        val kicks = report.verdicts.mapNotNull { it.kickDeltaMs?.let(::abs) }
        if (kicks.isNotEmpty()) {
            appendLine(
                "kickDelta: median=${fmt(kicks.median())}ms p90=${fmt(kicks.percentile(0.9))}ms " +
                    "max=${fmt(kicks.max())}ms  (low-band onsets; ~half a beat = off-beat)",
            )
        }
        val pulse = report.verdicts.mapNotNull { it.pulseDeltaMs?.let(::abs) }
        if (pulse.isNotEmpty()) {
            appendLine(
                "pulseDelta: median=${fmt(pulse.median())}ms p90=${fmt(pulse.percentile(0.9))}ms " +
                    "max=${fmt(pulse.max())}ms",
            )
        }

        appendLine("10s buckets: (P/G/G/M  onsetDelta med/p90  kickDelta med  pulseDelta med)")
        for ((bucket, notes) in report.verdicts.groupBy { it.timeMs / 10_000 }.toSortedMap()) {
            val counts = RhythmJudgement.entries.joinToString("/") { judgement ->
                "${notes.count { it.judgement == judgement }}"
            }
            val onset = notes.map { abs(it.onsetDeltaMs) }
            val kickMed = notes.mapNotNull { it.kickDeltaMs?.let(::abs) }.medianOrNull()
            val pulseMed = notes.mapNotNull { it.pulseDeltaMs?.let(::abs) }.medianOrNull()
            appendLine(
                "  ${bucket * 10}-${(bucket + 1) * 10}s: $counts  " +
                    "${fmt(onset.median())}/${fmt(onset.percentile(0.9))}ms  " +
                    "${kickMed?.let { "${fmt(it)}ms" } ?: "-"}  " +
                    "${pulseMed?.let { "${fmt(it)}ms" } ?: "-"}",
            )
        }

        val firstMiss = report.verdicts.firstOrNull { it.judgement == RhythmJudgement.MISS }
        appendLine("first MISS: ${firstMiss?.let { "${it.timeMs}ms (note ${it.noteId})" } ?: "none"}")
        longestMissRun(report)?.let { run ->
            appendLine(
                "longest MISS run: ${run.size} notes from ${run.first().timeMs}ms to ${run.last().timeMs}ms " +
                    "(onsetDelta med ${fmt(run.map { abs(it.onsetDeltaMs) }.median())}ms)",
            )
        }
    }

    /**
     * The kick-phase walk: for every beat-carrier onset, the signed distance to the nearest
     * chart grid line, bucketed over time. A grid locked to the beat keeps the medians near 0;
     * a grid that loses the beat walks towards half a beat — which is exactly what a player
     * following the rhythm experiences as "was fine, now everything misses".
     */
    fun formatKickPhase(
        kickTimes: List<Double>,
        timing: List<RhythmTimingPoint>,
        durationMs: Int,
    ): String = buildString {
        val lines = gridLines(timing, durationMs)
        if (lines.isEmpty() || kickTimes.isEmpty()) return@buildString
        appendLine("kickPhase (signed kick - nearest grid line, 30s buckets):")
        for ((bucket, kicks) in kickTimes.filter { it >= 5_000.0 && it < durationMs - 5_000.0 }
            .groupBy { (it / 30_000).toInt() }.toSortedMap()) {
            val phases = kicks.map { it - nearestTo(lines, it) }.sorted()
            val med = phases.median()
            appendLine(
                "  ${bucket * 30}s-${(bucket + 1) * 30}s: med=${fmtSigned(med)}ms " +
                    "p10=${fmtSigned(phases.percentile(0.1))}ms p90=${fmtSigned(phases.percentile(0.9))}ms " +
                    "(n=${kicks.size})",
            )
        }
    }

    /**
     * The strong-onset phase walk: the strongest onsets of the mix are the beat transients
     * (kick/snare), so their signed distance to the nearest chart grid line is the grid's
     * phase against the beat — no stems or references needed. A grid locked to the beat keeps
     * every window near 0; a grid that loses the beat walks towards half a beat, which is what
     * a player following the rhythm experiences as "was fine, now everything misses".
     */
    fun formatStrongPhase(
        analysis: DspAnalysis,
        durationMs: Int,
        fraction: Double = 0.15,
    ): String = buildString {
        val lines = gridLines(analysis.timing, durationMs)
        if (lines.isEmpty()) return@buildString
        val strengthSorted = analysis.onsetStrength.sorted()
        val cut = strengthSorted[((strengthSorted.size - 1) * (1.0 - fraction)).toInt()]
        val strong = analysis.onsetTimesMs.filterIndexed { index, _ ->
            analysis.onsetStrength[index] >= cut
        }
        appendLine("strongPhase (signed strong-onset - nearest grid line, 30s buckets, n=${strong.size}):")
        for ((bucket, times) in strong.filter { it >= 5_000.0 && it < durationMs - 5_000.0 }
            .groupBy { (it / 30_000).toInt() }.toSortedMap()) {
            val phases = times.map { it - nearestTo(lines, it) }.sorted()
            val med = phases.median()
            appendLine(
                "  ${bucket * 30}s-${(bucket + 1) * 30}s: med=${fmtSigned(med)}ms " +
                    "p10=${fmtSigned(phases.percentile(0.1))}ms p90=${fmtSigned(phases.percentile(0.9))}ms " +
                    "(n=${times.size})",
            )
        }
    }

    /**
     * Tracked-beat diagnostics: the DP chain's own spacing per 30s. A sustained run of long
     * or short intervals is the beat tracker leaving the song's true period — the piecewise
     * fits follow it, and the chart grid drifts with them.
     */
    fun formatTracked(tracked: DoubleArray, durationMs: Int): String = buildString {
        if (tracked.size < 2) return@buildString
        val intervals = tracked.toList().zipWithNext { a, b -> b - a }
        appendLine("tracked beats: n=${tracked.size} spacing med=${fmt(intervals.median())}ms")
        for ((bucket, starts) in tracked.filter { it in 0.0..durationMs.toDouble() }
            .groupBy { (it / 30_000).toInt() }.toSortedMap()) {
            val gaps = starts.zipWithNext { a, b -> b - a }.filter { it > 0 }
            if (gaps.isEmpty()) continue
            appendLine(
                "  ${bucket * 30}s-${(bucket + 1) * 30}s: n=${starts.size} " +
                    "gap min=${fmt(gaps.min())} med=${fmt(gaps.median())} max=${fmt(gaps.max())}",
            )
        }
    }

    /** Chart grid lines: `anchor + k * beatMs` inside each timing segment. */
    fun gridLines(timing: List<RhythmTimingPoint>, durationMs: Int): List<Double> {
        val out = ArrayList<Double>()
        for ((index, point) in timing.sortedBy { it.timeMs }.withIndex()) {
            val end = timing.getOrNull(index + 1)?.timeMs?.toDouble() ?: (durationMs + point.beatMs)
            var time = point.timeMs.toDouble()
            while (time < minOf(end, durationMs + 1.0)) {
                if (time >= 0.0) out += time
                time += point.beatMs
            }
        }
        return out.distinct().sorted()
    }

    // ------------------------------------------------------------------ helpers

    private fun longestMissRun(report: ChartReport): List<NoteVerdict>? {
        var best = emptyList<NoteVerdict>()
        var current = emptyList<NoteVerdict>()
        for (verdict in report.verdicts.sortedBy { it.timeMs }) {
            current = if (verdict.judgement == RhythmJudgement.MISS) current + verdict else emptyList()
            if (current.size > best.size) best = current
        }
        return best.ifEmpty { null }
    }

    private fun nearestTo(sorted: List<Double>, value: Double): Double {
        require(sorted.isNotEmpty()) { "no reference times" }
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) / 2
            if (sorted[mid] < value) low = mid + 1 else high = mid
        }
        val after = sorted[low.coerceAtMost(sorted.size - 1)]
        val before = sorted[(low - 1).coerceAtLeast(0)]
        return if (abs(after - value) < abs(before - value)) after else before
    }

    private fun List<Double>.median(): Double {
        require(isNotEmpty()) { "no values" }
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun List<Double>.medianOrNull(): Double? = if (isEmpty()) null else median()

    private fun List<Double>.percentile(fraction: Double): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val index = (fraction * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun fmt(value: Double): String = "%.1f".format(value)

    private fun fmtSigned(value: Double): String = "%+.1f".format(value)
}
