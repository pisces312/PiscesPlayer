/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/plans/manual-tap-chart-mobile-plan.md §3, chart export).
 */
package com.pisces312.piscesplayer.data.rhythm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export a stored chart as a standalone file the user can keep or share.
 *
 * Formats:
 * - [Format.OSU]: osu!mania 4K tap beatmap. Round-trips through [OsuManiaParser]; when the chart
 *   carries no timing the file gets a single 120 BPM point so real osu! can load it (its beat
 *   grid does not affect mania judgement).
 * - [Format.PRC]: RhythmChartLab `.prc` chart bundle (ZIP: `manifest.json` + `<难度>.json`),
 *   accepted by `rhythm_lab.bundle.import_bundle` and by Wavefall's importer.
 * - [Format.CHART_JSON]: the unified chart JSON (schemaVersion 1, camelCase) that `.prc` wraps.
 * - [Format.TAP_JSON]: the raw tap take, field-identical to the PC recorder's output.
 *
 * The `.prc` / chart-JSON writers reproduce the RhythmChartLab canonical-JSON hashes
 * (`chartSha256`, `timingHash`, manifest `bundleId`) byte-for-byte: the canonical serializer
 * mirrors Python `json.dumps(..., sort_keys=True, separators=(",", ":"), ensure_ascii=False)`
 * for the value domain these charts use (ints, plain doubles, strings). Golden vectors come from
 * running the PC `rhythm_lab` code over the same fixture (see `RhythmChartExportTest`).
 */
object RhythmChartExport {

    enum class Format(val extension: String, val mimeType: String) {
        OSU("osu", "text/plain"),
        PRC("prc", "application/octet-stream"),
        CHART_JSON("json", "application/json"),
        TAP_JSON("tap.json", "application/json"),
    }

    /** Everything the writers need from the song; chart data is passed separately. */
    data class SongInfo(
        val title: String = "",
        val artist: String = "",
        val durationMs: Long = 0,
        val audioFileName: String = "",
        /** `rhythm_lab.audio.file_fingerprint` of the audio file; empty when unreadable. */
        val audioFingerprint: String = "",
    )

    private const val GENERATOR_VERSION = "chart-4"
    private const val RECORDER = "piscesplayer-tap"

    /**
     * `generationParams.recorder` provenance label: a hand-tap chart names the phone recorder,
     * anything else names the pipeline whose output it is (`"piscesplayer-dsp"` for a DSP master
     * or a chart derived from it). PC-side analysis treats this field as the producer's mark, so
     * a chart that is not a hand-tap recording must never claim to be one.
     */
    private fun recorderOf(chart: RhythmChart): String =
        if (chart.source == RhythmChart.SOURCE_TAP) RECORDER else "piscesplayer-${chart.source}"

    fun fileName(format: Format, songTitle: String, chartName: String): String {
        val base = listOf(songTitle, chartName)
            .filter { it.isNotBlank() }
            .joinToString("-")
            .ifBlank { "chart" }
        return "${sanitizeFilePart(base)}.${format.extension}"
    }

    /** Build the export payload; [take] is only required for [Format.TAP_JSON]. */
    fun build(format: Format, chart: RhythmChart, take: RhythmTapTake?, info: SongInfo): ByteArray =
        when (format) {
            Format.OSU -> osuText(chart, info).toByteArray(Charsets.UTF_8)
            Format.PRC -> prcBytes(chart, info)
            Format.CHART_JSON -> pretty(chartDict(chart, info)).toByteArray(Charsets.UTF_8)
            Format.TAP_JSON -> (take ?: error("this chart has no tap take to export"))
                .toJson().toByteArray(Charsets.UTF_8)
        }

    // ---------------------------------------------------------------- osu

    private fun osuText(chart: RhythmChart, info: SongInfo): String {
        val title = oneLine(info.title).ifBlank { "Unknown" }
        val artist = oneLine(info.artist).ifBlank { "Unknown" }
        val version = oneLine(chart.difficultyName).ifBlank { "Normal" }
        val audio = oneLine(info.audioFileName).ifBlank { "audio.mp3" }
        val timing = chart.timing.ifEmpty { listOf(RhythmTimingPoint(0, DEFAULT_BEAT_MS)) }
        return buildString {
            appendLine("osu file format v14")
            appendLine()
            appendLine("[General]")
            appendLine("AudioFilename: $audio")
            appendLine("AudioLeadIn: 0")
            appendLine("PreviewTime: -1")
            appendLine("Countdown: 0")
            appendLine("SampleSet: Soft")
            appendLine("StackLeniency: 0.7")
            appendLine("Mode: 3")
            appendLine("LetterboxInBreaks: 0")
            appendLine()
            appendLine("[Metadata]")
            appendLine("Title: $title")
            appendLine("TitleUnicode: $title")
            appendLine("Artist: $artist")
            appendLine("ArtistUnicode: $artist")
            appendLine("Creator: PiscesPlayer")
            appendLine("Version: $version")
            appendLine("BeatmapID: 0")
            appendLine("BeatmapSetID: -1")
            appendLine()
            appendLine("[Difficulty]")
            appendLine("HPDrainRate: 5")
            appendLine("CircleSize: 4")
            appendLine("OverallDifficulty: 5")
            appendLine("ApproachRate: 5")
            appendLine("SliderMultiplier: 1.4")
            appendLine("SliderTickRate: 1")
            appendLine()
            appendLine("[TimingPoints]")
            for (point in timing) {
                appendLine("${point.timeMs},${doubleText(point.beatMs)},4,2,0,60,1,0")
            }
            appendLine()
            appendLine("[HitObjects]")
            for (note in chart.notes) {
                // Column centre for 4K: lane = floor(x * 4 / 512) must map back to the same lane.
                val x = LANE_X0 + LANE_WIDTH * note.lane
                appendLine("$x,192,${note.timeMs},1,0,0:0:0:0:")
            }
        }
    }

    // ---------------------------------------------------------------- prc + chart json

    private fun prcBytes(chart: RhythmChart, info: SongInfo): ByteArray {        val timingHash = timingHash(chart.timing)
        val chartDict = chartDict(chart, info, timingHash)
        val chartSha256 = sha256Hex(canonical(chartDict))
        val diffKey = sanitizeFilePart(chart.difficultyName).ifBlank { "manual" }
        val manifestDurationMs = if (info.durationMs > 0) info.durationMs else chart.durationMs.toLong()
        val generationParams = mapOf("recorder" to recorderOf(chart))
        val stats = mapOf(
            "noteCount" to chart.notes.size,
            // The app chart model has no hold notes (a tap is always a tap).
            "holdCount" to 0,
            "nps" to chartNps(chart),
            "seed" to null,
            "conditioning" to 0.0,
            "chartSha256" to chartSha256,
        )
        val semantic = mapOf(
            "schemaVersion" to 1,
            "aiGenerated" to false,
            "audioFingerprint" to info.audioFingerprint,
            "durationMs" to manifestDurationMs,
            "modelRevision" to "",
            "sourceCommit" to "",
            "seed" to null,
            "generationParams" to generationParams,
            "timingHash" to timingHash,
            "postProcessorVersion" to GENERATOR_VERSION,
            "difficulties" to mapOf(diffKey to stats),
        )
        val bundleId = sha256Hex(canonical(semantic))
        val manifest = mapOf(
            "schemaVersion" to 1,
            "aiGenerated" to false,
            "bundleId" to bundleId,
            "audioFingerprint" to info.audioFingerprint,
            "durationMs" to manifestDurationMs,
            "modelRevision" to "",
            "sourceCommit" to "",
            "seed" to null,
            "generationParams" to generationParams,
            "timingHash" to timingHash,
            "postProcessorVersion" to GENERATOR_VERSION,
            "difficulties" to mapOf(diffKey to stats),
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(pretty(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$diffKey.json"))
            zip.write(pretty(chartDict).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    internal fun chartDict(chart: RhythmChart, info: SongInfo, timingHash: String): Map<String, Any?> =
        mapOf(
            "schemaVersion" to 1,
            "generatorVersion" to GENERATOR_VERSION,
            "aiGenerated" to false,
            "mode" to 3,
            "keyCount" to 4,
            "difficultyName" to chart.difficultyName,
            "difficultyStars" to 0.0,
            "durationMs" to chart.durationMs,
            "timing" to chart.timing.map {
                mapOf("timeMs" to it.timeMs, "beatMs" to it.beatMs)
            },
            "notes" to chart.notes.map {
                mapOf("timeMs" to it.timeMs, "lane" to it.lane, "id" to it.id, "endTimeMs" to null)
            },
            "source" to mapOf(
                "audioFingerprint" to info.audioFingerprint,
                "osuSha256" to "",
                "seed" to null,
                "modelRevision" to "",
                "sourceCommit" to "",
                "generationParams" to mapOf("recorder" to recorderOf(chart)),
                "timingHash" to timingHash,
                "postProcessorVersion" to GENERATOR_VERSION,
            ),
        )

    internal fun chartDict(chart: RhythmChart, info: SongInfo): Map<String, Any?> =
        chartDict(chart, info, timingHash(chart.timing))

    /**
     * RhythmChartLab `chart.py` timing hash: sha256 over the snake_case timing entries,
     * keys sorted, compact separators (Python `json.dumps` defaults, numeric values only).
     */
    private fun timingHash(timing: List<RhythmTimingPoint>): String {
        val entries = timing.map { mapOf("time_ms" to it.timeMs, "beat_ms" to it.beatMs) }
        return sha256Hex(canonical(entries))
    }

    /** RhythmChartLab `_chart_stats` nps: note count over the first-to-last note span. */
    private fun chartNps(chart: RhythmChart): Double {
        val notes = chart.notes
        if (notes.size < 2) return 0.0
        val spanS = (notes.last().timeMs - notes.first().timeMs) / 1000.0
        if (spanS <= 0.0) return 0.0
        return roundHalfEven(notes.size / spanS, 3)
    }

    /** Python `round(x, digits)`: half-even on the exact binary value of [value]. */
    private fun roundHalfEven(value: Double, digits: Int): Double =
        BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

    // ---------------------------------------------------------------- canonical json

    /**
     * Python `json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))`
     * for the value domain these charts use. Keys are sorted recursively; doubles print with
     * shortest round-trip decimal notation ("500.0", "461.53846153846155").
     */
    internal fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Double -> doubleText(value)
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString(",", "{", "}") { "\"${escape(it.key.toString())}\":${canonical(it.value)}" }
        is List<*> -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> error("unsupported export value type: ${value::class}")
    }

    /** Same value model as [canonical], in the lab's `json.dumps(indent=2)` shape. */
    internal fun pretty(value: Any?): String = when (value) {
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries
            .sortedBy { it.key.toString() }
            .joinToString(",\n", "{\n", "\n}") { "  \"${escape(it.key.toString())}\": ${prettyAt(it.value, 1)}" }
        is List<*> -> if (value.isEmpty()) "[]" else value
            .joinToString(",\n", "[\n", "\n]") { "  ${prettyAt(it, 1)}" }
        else -> canonical(value)
    }

    private fun prettyAt(value: Any?, depth: Int): String {
        val pad = "  ".repeat(depth)
        return when (value) {
            is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries
                .sortedBy { it.key.toString() }
                .joinToString(",\n", "{\n", "\n$pad}") {
                    "$pad  \"${escape(it.key.toString())}\": ${prettyAt(it.value, depth + 1)}"
                }
            is List<*> -> if (value.isEmpty()) "[]" else value
                .joinToString(",\n", "[\n", "\n$pad]") { "$pad  ${prettyAt(it, depth + 1)}" }
            else -> canonical(value)
        }
    }

    /** Python `json.dumps` string escaping with `ensure_ascii=False`. */
    private fun escape(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\t' -> out.append("\\t")
                c == '\n' -> out.append("\\n")
                c == '\u000C' -> out.append("\\f")
                c == '\r' -> out.append("\\r")
                c < ' ' -> {
                    out.append("\\u")
                    val hex = c.code.toString(16).padStart(4, '0')
                    out.append(hex)
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * Python float repr for the plain-decimal domain these exports use. Scientific notation
     * (values below 1e-4 / above 1e16) is expanded to plain digits; charts never need those,
     * and [canonical] documents the domain.
     */
    private fun doubleText(value: Double): String {
        val text = java.lang.Double.toString(value)
        return if (text.indexOf('E') >= 0) BigDecimal(text).toPlainString() else text
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- audio fingerprint

    /**
     * `rhythm_lab.audio.file_fingerprint`: sha256 over the decimal byte size (UTF-8) followed
     * by the file content, truncated to 32 hex chars. Empty when the audio cannot be read.
     */
    fun audioFingerprint(context: Context, song: com.pisces312.piscesplayer.data.model.Song): String =
        runCatching {
            val path = song.path
            val file = if (path.isNotBlank()) java.io.File(path) else null
            if (file != null && file.isFile) {
                fingerprint(file.length(), file.inputStream())
            } else {
                val uri = runCatching { Uri.parse(song.contentUriString) }.getOrNull() ?: return ""
                val size = querySize(context, uri) ?: return ""
                context.contentResolver.openInputStream(uri)
                    ?.use { fingerprint(size, it) }
                    ?: ""
            }
        }.getOrDefault("")

    internal fun fingerprint(size: Long, stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(size.toString().toByteArray(Charsets.UTF_8))
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        val hex = "0123456789abcdef"
        val out = StringBuilder(32)
        for (b in digest.digest().take(16)) {
            val v = b.toInt() and 0xFF
            out.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return out.toString()
    }

    private fun querySize(context: Context, uri: Uri): Long? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()

    // ---------------------------------------------------------------- misc

    private fun oneLine(text: String): String = text.replace('\r', ' ').replace('\n', ' ').trim()

    /** Keep a name usable on every file system; the extension is appended by [fileName]. */
    private fun sanitizeFilePart(text: String): String {
        val cleaned = text
            .map { if (it < ' ' || it in "\\/:*?\"<>|") '_' else it }
            .joinToString("")
            .trim()
            .trimEnd('.')
        return cleaned.take(80)
    }

    private const val DEFAULT_BEAT_MS = 500.0
    private const val LANE_X0 = 64
    private const val LANE_WIDTH = 128
}
