/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, storage layer designed for PixelPlayerOSS
 * (docs/rhythm-game-integration-plan.md §4.2).
 */
package com.pisces312.piscesplayer.data.rhythm

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * File-backed chart library under [rootDir]:
 * `library.json` (songId -> chart metadata), immutable `charts/<chartId>.json`, and per-chart
 * playback settings in `settings/<chartId>.json` (same base name, sibling directory).
 *
 * The master chart is the one deliberate exception to content addressing: its id is derived from
 * the song, so regenerating rewrites the same file instead of piling up near-identical copies.
 * See `docs/rhythm-chart-ladder-plan.md` §6.2.
 *
 * Kept free of Android types so the identity/idempotency rules are JVM-testable
 * ([RhythmRepository] only adds SAF reading on top).
 */
internal class RhythmLibraryStore(private val rootDir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val chartsDir: File
        get() = File(rootDir, CHARTS_DIR).also { it.mkdirs() }

    private val settingsDir: File
        get() = File(rootDir, SETTINGS_DIR)

    private val indexFile: File
        get() = File(rootDir, INDEX_FILE)

    /** Parse and store an osu!mania 4K chart; re-importing the same text is idempotent. */
    fun importText(
        songId: String,
        text: String,
        nowMs: Long = System.currentTimeMillis(),
    ): RhythmChartMeta {
        val chart = OsuManiaParser.parse(text)
        // Id stays derived from the .osu text on purpose: switching it to the serialized chart
        // would re-key every already imported chart and drop its best score.
        return persist(songId, chart, contentId(text), nowMs)
    }

    /**
     * Store a chart produced in memory: a derived difficulty or, before the ladder existed, a
     * whole generated chart.
     *
     * Unlike [importText] the id is derived from the serialized chart, so re-deriving with
     * different parameters yields a new chart while an identical run stays idempotent. Derived
     * charts carry a second-precision timestamp in their name, so repeated derivations always
     * produce distinct entries — several charts for the same song are kept on purpose.
     */
    fun importChart(
        songId: String,
        chart: RhythmChart,
        nowMs: Long = System.currentTimeMillis(),
        difficultyKey: String? = null,
        parentChartId: String? = null,
        masterGeneratedAtMs: Long? = null,
    ): RhythmChartMeta {
        require(chart.source != RhythmChart.SOURCE_OSU) {
            "osu charts must be imported through importText"
        }
        val body = json.encodeToString(RhythmChart.serializer(), chart)
        return persist(
            songId = songId,
            chart = chart,
            chartId = contentId(body),
            nowMs = nowMs,
            difficultyKey = difficultyKey,
            parentChartId = parentChartId,
            masterGeneratedAtMs = masterGeneratedAtMs,
        )
    }

    /**
     * Write the one master chart of [songId], replacing whatever was stored before.
     *
     * Two consequences are deliberate: the id is derived from the song (so an osu import and a
     * master can never collide), and the old best score is dropped — its notes are gone, so
     * keeping the score would advertise a result nobody can reproduce.
     */
    fun saveMaster(
        songId: String,
        chart: RhythmChart,
        nowMs: Long = System.currentTimeMillis(),
    ): RhythmChartMeta {
        require(chart.source != RhythmChart.SOURCE_OSU) {
            "osu charts must be imported through importText"
        }
        val chartId = masterChartId(songId)
        writeAtomic(
            File(chartsDir, "$chartId.json"),
            json.encodeToString(RhythmChart.serializer(), chart),
        )

        val library = readLibrary()
        val songCharts = library.songs.getOrPut(songId) { mutableListOf() }
        val meta = RhythmChartMeta(
            chartId = chartId,
            songId = songId,
            source = chart.source,
            difficultyName = chart.difficultyName,
            noteCount = chart.noteCount(),
            averageNps = chart.averageNps(),
            durationMs = chart.durationMs,
            importedAtMs = nowMs,
            difficultyKey = RhythmChart.DIFFICULTY_RAW,
        )
        val index = songCharts.indexOfFirst { it.chartId == chartId }
        if (index >= 0) songCharts[index] = meta else songCharts += meta
        writeLibrary(library)
        return meta
    }

    private fun persist(
        songId: String,
        chart: RhythmChart,
        chartId: String,
        nowMs: Long,
        difficultyKey: String? = null,
        parentChartId: String? = null,
        masterGeneratedAtMs: Long? = null,
    ): RhythmChartMeta {
        val chartFile = File(chartsDir, "$chartId.json")
        if (!chartFile.isFile) {
            writeAtomic(chartFile, json.encodeToString(RhythmChart.serializer(), chart))
        }

        val library = readLibrary()
        val songCharts = library.songs.getOrPut(songId) { mutableListOf() }
        val existing = songCharts.firstOrNull { it.chartId == chartId }
        if (existing == null) {
            songCharts += RhythmChartMeta(
                chartId = chartId,
                songId = songId,
                source = chart.source,
                difficultyName = chart.difficultyName,
                noteCount = chart.noteCount(),
                averageNps = chart.averageNps(),
                durationMs = chart.durationMs,
                importedAtMs = nowMs,
                difficultyKey = difficultyKey,
                parentChartId = parentChartId,
                masterGeneratedAtMs = masterGeneratedAtMs,
            )
        } else {
            // Re-import: keep best score, refresh the song binding.
            existing.songId = songId
        }
        writeLibrary(library)
        return songCharts.first { it.chartId == chartId }
    }

    fun chartsForSong(songId: String): List<RhythmChartMeta> =
        readLibrary().songs[songId].orEmpty().sortedBy { it.importedAtMs }

    /** Lightweight metadata for song pickers; chart bodies stay on disk. */
    fun chartCatalog(): Map<String, List<RhythmChartMeta>> =
        readLibrary().songs.mapValues { (_, charts) -> charts.toList() }

    fun readChart(chartId: String): RhythmChart? {
        val file = File(chartsDir, "$chartId.json")
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(RhythmChart.serializer(), file.readText())
        }.getOrNull()
    }

    /** Delete the imported copy and its score; the original .osu file is untouched. */
    fun delete(songId: String, chartId: String): Boolean {
        val library = readLibrary()
        val songCharts = library.songs[songId] ?: return false
        val removed = songCharts.removeAll { it.chartId == chartId }
        if (!removed) return false
        if (songCharts.isEmpty()) library.songs.remove(songId)
        writeLibrary(library)
        if (!isChartReferenced(library, chartId)) {
            File(chartsDir, "$chartId.json").delete()
        }
        // Settings are per chart, so they go with it. A failed delete must not block removing
        // the chart — a corrupt sidecar would otherwise make the entry undeletable.
        File(settingsDir, "$chartId.json").delete()
        return true
    }

    /** Playback settings for [chartId]; missing or unreadable files fall back to defaults. */
    fun readSettings(chartId: String): RhythmChartSettings {
        val file = File(settingsDir, "$chartId.json")
        if (!file.isFile) return RhythmChartSettings.DEFAULT
        return runCatching {
            json.decodeFromString(RhythmChartSettings.serializer(), file.readText()).clamped()
        }.getOrDefault(RhythmChartSettings.DEFAULT)
    }

    /** Persist playback settings; returns what actually landed on disk. */
    fun writeSettings(
        chartId: String,
        settings: RhythmChartSettings,
        nowMs: Long = System.currentTimeMillis(),
    ): RhythmChartSettings {
        val stored = settings.clamped().copy(updatedAtMs = nowMs)
        writeAtomic(
            File(settingsDir, "$chartId.json"),
            json.encodeToString(RhythmChartSettings.serializer(), stored),
        )
        return stored
    }

    /** Best score only ever increases; returns true when a new record was stored. */
    fun recordScore(chartId: String, score: Int): Boolean {
        if (score < 0) return false
        val library = readLibrary()
        val meta = library.songs.values.asSequence()
            .flatten()
            .firstOrNull { it.chartId == chartId }
            ?: return false
        if (score <= meta.bestScore) return false
        meta.bestScore = score
        writeLibrary(library)
        return true
    }

    private fun readLibrary(): RhythmLibraryData {
        if (!indexFile.isFile) return RhythmLibraryData()
        // A corrupt index must not take the chart files down with it.
        return runCatching {
            json.decodeFromString(RhythmLibraryData.serializer(), indexFile.readText())
        }.getOrElse { RhythmLibraryData() }
    }

    private fun writeLibrary(library: RhythmLibraryData) {
        writeAtomic(indexFile, json.encodeToString(RhythmLibraryData.serializer(), library))
    }

    private fun isChartReferenced(library: RhythmLibraryData, chartId: String): Boolean =
        library.songs.values.any { songCharts -> songCharts.any { it.chartId == chartId } }

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            target.writeText(content)
            temp.delete()
        }
    }

    /** Content hash: the same chart bound to two songs shares one file. */
    private fun contentId(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * The master is keyed by song, not by content: it must stay the same file across
     * regenerations, and it shares `charts/` with every other song's charts.
     */
    fun masterChartId(songId: String): String = "raw-" + contentId(songId).take(12)

    companion object {
        const val ROOT_DIR = "rhythm"
        private const val CHARTS_DIR = "charts"
        private const val SETTINGS_DIR = "settings"
        private const val INDEX_FILE = "library.json"
    }
}
