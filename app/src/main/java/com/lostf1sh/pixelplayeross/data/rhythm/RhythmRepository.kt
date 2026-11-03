/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, storage layer designed for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §6, §9).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The freshly written master chart plus what the analysis found, for the result dialog. */
data class MasterGenerationResult(
    val meta: RhythmChartMeta,
    val chart: RhythmChart,
    val diagnostics: DspDiagnostics,
)

/**
 * Rhythm chart library stored in `filesDir/rhythm`. No Room schema change is
 * involved; chart content is hashed, so importing the same `.osu` twice is
 * idempotent and only updates the song binding.
 *
 * Charts come in two tiers: one master per song (every detected onset, overwritten on
 * regeneration) and any number of derived difficulties. Deriving is a pure function over the
 * stored master, so switching lane layout or adding another Hard version costs no decoding.
 */
@Singleton
class RhythmRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val filesRoot: File get() = context.filesDir

    private fun store(): RhythmLibraryStore =
        RhythmLibraryStore(File(filesRoot, RhythmLibraryStore.ROOT_DIR))

    /** Import an osu!mania 4K chart from a SAF document and bind it to [songId]. */
    suspend fun importOsu(songId: String, uri: Uri): RhythmChartMeta = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: throw OsuParseException("Unable to read the selected .osu file")

        store().importText(songId = songId, text = bytes.toString(Charsets.UTF_8))
    }

    suspend fun chartsForSong(songId: String): List<RhythmChartMeta> = withContext(Dispatchers.IO) {
        store().chartsForSong(songId)
    }

    suspend fun loadChart(chartId: String): RhythmChart? = withContext(Dispatchers.IO) {
        store().readChart(chartId)
    }

    suspend fun deleteChart(songId: String, chartId: String): Boolean = withContext(Dispatchers.IO) {
        store().delete(songId, chartId)
    }

    suspend fun recordScore(chartId: String, score: Int): Boolean = withContext(Dispatchers.IO) {
        store().recordScore(chartId, score)
    }

    suspend fun loadSettings(chartId: String): RhythmChartSettings = withContext(Dispatchers.IO) {
        store().readSettings(chartId)
    }

    suspend fun saveSettings(
        chartId: String,
        settings: RhythmChartSettings,
    ): RhythmChartSettings = withContext(Dispatchers.IO) {
        store().writeSettings(chartId, settings)
    }

    /** Settings for a list of charts, loaded in one IO hop for the list screen. */
    suspend fun settingsFor(chartIds: List<String>): Map<String, RhythmChartSettings> =
        withContext(Dispatchers.IO) {
            val library = store()
            chartIds.associateWith { library.readSettings(it) }
        }

    /**
     * Decode the song, analyse it, and store the result as this song's one master chart.
     *
     * Replaces any previous master. Gameplay stays untouched: the master is a normal chart, it
     * simply happens to contain every onset the detector found.
     */
    suspend fun generateMaster(
        songId: String,
        uri: Uri,
        params: RhythmAnalysisParams,
        laneStrategy: LaneStrategy = LaneStrategy.BAND_ORDER,
        onProgress: (Float) -> Unit = {},
    ): MasterGenerationResult = withContext(Dispatchers.Default) {
        val decoded = withContext(Dispatchers.IO) {
            val pcm = RhythmPcmDecoder.decode(context, uri)
            val durationMs = pcm.durationMs.roundToInt()
            if (durationMs > MAX_ANALYSIS_DURATION_MS) {
                throw RhythmGenerationException(
                    "Songs longer than ${MAX_ANALYSIS_DURATION_MS / 60_000} minutes are not supported",
                )
            }
            DecodedAudio(pcm.toMonoFloat(), pcm.sampleRate, durationMs)
        }

        val analysis = RhythmChartGenerator.analyze(
            pcmMono = decoded.mono,
            sampleRate = decoded.sampleRate,
            durationMs = decoded.durationMs,
            params = params,
            onProgress = onProgress,
        )
        val chart = RhythmChartGenerator.renderMaster(analysis, laneStrategy)
        onProgress(1f)

        val meta = withContext(Dispatchers.IO) { store().saveMaster(songId, chart) }
        MasterGenerationResult(
            meta = meta,
            chart = chart,
            diagnostics = analysis.diagnostics(chart.noteCount()),
        )
    }

    /**
     * Derive the requested difficulties from the stored master and keep every derivation.
     *
     * Both modes are pure functions over the master: no audio is decoded. [RhythmDeriveMode.GRID]
     * snaps onto the master's tracked piecewise grid ([RhythmTimingRefiner] only for a legacy
     * single-point master); [RhythmDeriveMode.ONSET] keeps the onset timestamps. [displayNames]
     * carries the localized, timestamped difficulty and mode names; it is part of the chart body,
     * which is what gives each derivation its own content hash and therefore its own entry.
     */
    suspend fun deriveCharts(
        songId: String,
        master: RhythmChart,
        masterMeta: RhythmChartMeta,
        difficulties: List<DspDifficulty>,
        modes: Set<RhythmDeriveMode>,
        laneStrategy: LaneStrategy,
        displayNames: Map<Pair<DspDifficulty, RhythmDeriveMode>, String>,
        maxChord: Int = 1,
        onsetSelection: OnsetSelection = OnsetSelection.BALANCED,
    ): List<RhythmChartMeta> {
        if (difficulties.isEmpty() || modes.isEmpty()) return emptyList()
        val gridTiming = gridTimingFor(master, modes)
        val jobs = difficulties.flatMap { difficulty ->
            // Grid last, so it is created last and lands on top of the newest-first list.
            RhythmDeriveMode.entries.reversed()
                .filter { it in modes }
                .map { difficulty to it }
        }
        val charts = withContext(Dispatchers.Default) {
            jobs.map { (difficulty, mode) ->
                deriveOne(master, difficulty, mode, laneStrategy, maxChord, gridTiming, onsetSelection)
                    // Derived charts are stored playable: the first lead-in stays only in the
                    // master, so a difficulty starts exactly where the run has runway.
                    .withoutLeadIn()
                    .copy(
                        difficultyName = displayNames[difficulty to mode]
                            ?: "${difficulty.label} ${mode.name.lowercase()}",
                    )
            }
        }
        return withContext(Dispatchers.IO) {
            val library = store()
            val now = System.currentTimeMillis()
            charts.mapIndexed { index, chart ->
                library.importChart(
                    songId = songId,
                    chart = chart,
                    // Stagger by a millisecond so a batch keeps a stable display order without
                    // relying on the filesystem clock.
                    nowMs = now + index,
                    difficultyKey = difficultyKeyOf(jobs[index].first),
                    parentChartId = masterMeta.chartId,
                    masterGeneratedAtMs = masterMeta.importedAtMs,
                )
            }
        }
    }

    /**
     * Preview note counts for the derive sheet. Same code path as [deriveCharts], so the number
     * shown before pressing the button is the number that lands on disk.
     */
    suspend fun previewDerivedCounts(
        master: RhythmChart,
        difficulties: List<DspDifficulty>,
        modes: Set<RhythmDeriveMode>,
        laneStrategy: LaneStrategy,
        maxChord: Int = 1,
        onsetSelection: OnsetSelection = OnsetSelection.BALANCED,
    ): Map<Pair<DspDifficulty, RhythmDeriveMode>, Int> = withContext(Dispatchers.Default) {
        if (difficulties.isEmpty() || modes.isEmpty()) return@withContext emptyMap()
        val gridTiming = gridTimingFor(master, modes)
        difficulties.flatMap { difficulty ->
            RhythmDeriveMode.entries.filter { it in modes }.map { mode ->
                (difficulty to mode) to
                    deriveOne(master, difficulty, mode, laneStrategy, maxChord, gridTiming, onsetSelection)
                        .withoutLeadIn()
                        .noteCount()
            }
        }.toMap()
    }

    private fun deriveOne(
        master: RhythmChart,
        difficulty: DspDifficulty,
        mode: RhythmDeriveMode,
        laneStrategy: LaneStrategy,
        maxChord: Int,
        gridTiming: List<RhythmTimingPoint>,
        onsetSelection: OnsetSelection,
    ): RhythmChart = when (mode) {
        RhythmDeriveMode.GRID -> RhythmChartDeriver.derive(
            master = master,
            difficulty = difficulty,
            laneStrategy = laneStrategy,
            maxChord = maxChord,
            timing = gridTiming,
        )
        RhythmDeriveMode.ONSET -> RhythmChartDeriver.deriveOnsetNative(
            master = master,
            difficulty = difficulty,
            laneStrategy = laneStrategy,
            maxChord = maxChord,
            selection = onsetSelection,
        )
    }

    /**
     * Empty unless the grid mode is requested. A current master carries the tracked piecewise
     * grid; a legacy single-point master predates it, so refine one from its onsets instead.
     */
    private fun gridTimingFor(
        master: RhythmChart,
        modes: Set<RhythmDeriveMode>,
    ): List<RhythmTimingPoint> {
        if (RhythmDeriveMode.GRID !in modes) return emptyList()
        if (master.timing.size > 1) return master.timing
        val global = master.timing.firstOrNull() ?: RhythmTimingPoint(0, FALLBACK_BEAT_MS)
        return RhythmTimingRefiner.refine(
            onsets = master.notes.map { RhythmTimingRefiner.Onset(it.timeMs, it.strength) },
            global = global,
            durationMs = master.durationMs,
        )
    }

    private class DecodedAudio(
        val mono: FloatArray,
        val sampleRate: Int,
        val durationMs: Int,
    )

    companion object {
        /** Above this the one-shot PCM buffer becomes a real OOM risk on low-end devices. */
        const val MAX_ANALYSIS_DURATION_MS = 12 * 60 * 1000L

        /** 120 BPM, only for a master that somehow carries no timing point at all. */
        private const val FALLBACK_BEAT_MS = 500.0

        fun difficultyKeyOf(difficulty: DspDifficulty): String = difficulty.name.lowercase()
    }
}
