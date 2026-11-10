/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * and the osu!mania beatmap file format (docs/rhythm-game-integration-plan.md §5).
 */
package com.pisces312.piscesplayer.data.rhythm

import java.io.InputStream

/** Parse failure with a user-presentable reason; no chart is produced. */
class OsuParseException(message: String) : Exception(message)

/**
 * Minimal osu!mania 4K tap parser.
 *
 * Only `[General] Mode`, `[Difficulty] CircleSize`, `[Metadata] Version`,
 * uninherited `[TimingPoints]` and tap `[HitObjects]` are consumed. Hold objects
 * and non-4K keymodes are rejected explicitly instead of being silently mapped.
 */
object OsuManiaParser {

    private const val TYPE_HOLD = 1 shl 7
    private const val LANE_COUNT = 4
    private const val PLAYFIELD_WIDTH = 512

    fun parse(stream: InputStream): RhythmChart = parse(stream.readBytes().decodeToString())

    fun parse(text: String): RhythmChart {
        val lines = text
            .removePrefix("\uFEFF")
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val sections = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        for (line in lines) {
            if (line.startsWith("osu file format")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                current = line.substring(1, line.length - 1)
                sections.getOrPut(current) { mutableListOf() }
                continue
            }
            current?.let { key -> sections.getOrPut(key) { mutableListOf() }.add(line) }
        }

        val general = keyValue(sections["General"])
        val metadata = keyValue(sections["Metadata"])
        val difficulty = keyValue(sections["Difficulty"])

        val mode = general["Mode"]?.trim()?.toIntOrNull()
            ?: throw OsuParseException("Missing osu Mode; only mania charts are supported")
        if (mode != 3) throw OsuParseException("Only osu!mania charts are supported (Mode=3)")

        val keyCount = difficulty["CircleSize"]?.trim()?.toDoubleOrNull()
            ?: throw OsuParseException("Missing osu CircleSize")
        if (keyCount != LANE_COUNT.toDouble()) {
            throw OsuParseException("Only 4-key mania charts are supported (CircleSize=4)")
        }

        val difficultyName = metadata["Version"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "Normal"

        val timing = parseTiming(sections["TimingPoints"] ?: emptyList())
        val notes = parseNotes(sections["HitObjects"] ?: emptyList())
            ?: throw OsuParseException("No osu HitObjects found")

        val sorted = notes
            .sortedWith(compareBy({ it.second }, { it.first }))
            .distinctBy { it.first to it.second } // same lane, same time -> one note
            .mapIndexed { index, (lane, timeMs) -> RhythmNote(id = index, timeMs = timeMs, lane = lane) }

        if (sorted.isEmpty()) throw OsuParseException("Chart has no playable notes")
        if (sorted.first().timeMs < 0) throw OsuParseException("Note time is negative")

        val durationMs = sorted.last().timeMs
        return RhythmChart(
            source = RhythmChart.SOURCE_OSU,
            difficultyName = difficultyName,
            timing = timing,
            notes = sorted,
            durationMs = durationMs,
        )
    }

    private fun keyValue(lines: List<String>?): Map<String, String> {
        if (lines == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (line in lines) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            out[line.substring(0, separator).trim()] = line.substring(separator + 1)
        }
        return out
    }

    private fun parseTiming(lines: List<String>): List<RhythmTimingPoint> {
        val out = mutableListOf<RhythmTimingPoint>()
        for (line in lines) {
            val parts = line.split(',')
            val timeMs = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val beatMs = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: continue
            // Uninherited points have positive beatMs; inherited points are negative SV factors.
            if (beatMs > 0.0) {
                out += RhythmTimingPoint(timeMs = timeMs, beatMs = beatMs)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Returns lane/time pairs, or null when the section is absent. */
    private fun parseNotes(lines: List<String>): List<Pair<Int, Int>>? {
        val out = mutableListOf<Pair<Int, Int>>()
        for (line in lines) {
            val parts = line.split(',')
            if (parts.size < 4) throw OsuParseException("Malformed HitObjects line: $line")
            val x = parts[0].trim().toDoubleOrNull()
                ?: throw OsuParseException("Malformed HitObjects x: ${parts[0]}")
            val timeMs = parts[2].trim().toIntOrNull()
                ?: throw OsuParseException("Malformed HitObjects time: ${parts[2]}")
            val type = parts[3].trim().toIntOrNull()
                ?: throw OsuParseException("Malformed HitObjects type: ${parts[3]}")
            if (type and TYPE_HOLD != 0) {
                throw OsuParseException("Hold notes are not supported yet")
            }
            if (timeMs < 0) throw OsuParseException("Negative note time: $timeMs")
            val lane = (x * LANE_COUNT / PLAYFIELD_WIDTH).toInt().coerceIn(0, LANE_COUNT - 1)
            out += lane to timeMs
        }
        return if (lines.isEmpty()) null else out
    }
}
