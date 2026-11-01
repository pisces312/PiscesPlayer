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
     * Pure note shuffling: no audio is decoded. [displayNames] carries the localized, timestamped
     * difficulty name; it is part of the chart body, which is what gives each derivation its own
     * content hash and therefore its own entry.
     */
    suspend fun deriveCharts(
        songId: String,
        master: RhythmChart,
        masterMeta: RhythmChartMeta,
        difficulties: List<DspDifficulty>,
        laneStrategy: LaneStrategy,
        displayNames: Map<DspDifficulty, String>,
    ): List<RhythmChartMeta> {
        if (difficulties.isEmpty()) return emptyList()
        val charts = withContext(Dispatchers.Default) {
            difficulties.map { difficulty ->
                RhythmChartDeriver.derive(master, difficulty, laneStrategy).copy(
                    difficultyName = displayNames[difficulty] ?: difficulty.label,
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
                    difficultyKey = difficultyKeyOf(difficulties[index]),
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
        laneStrategy: LaneStrategy,
    ): Map<DspDifficulty, Int> = withContext(Dispatchers.Default) {
        difficulties.associateWith {
            RhythmChartDeriver.derive(master, it, laneStrategy).noteCount()
        }
    }

    private class DecodedAudio(
        val mono: FloatArray,
        val sampleRate: Int,
        val durationMs: Int,
    )

    companion object {
        /** Above this the one-shot PCM buffer becomes a real OOM risk on low-end devices. */
        const val MAX_ANALYSIS_DURATION_MS = 12 * 60 * 1000L

        fun difficultyKeyOf(difficulty: DspDifficulty): String = difficulty.name.lowercase()
    }
}
