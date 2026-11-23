/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, storage layer designed for PixelPlayerOSS
 * (docs/rhythm-chart-ladder-plan.md §6, §9).
 */
package com.pisces312.piscesplayer.data.rhythm

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

    /**
     * Parse a MIDI file and store one chart per playable track and difficulty
     * ([MidiChartOptions.difficulties], three density-capped rungs by default).
     *
     * Unlike [generateMaster] there is no master/derive two-tier: MIDI carries exact note
     * times and an exact tempo map, so generation is a cheap deterministic function of the
     * file ([RhythmMidiChartGenerator]). Charts are content-addressed like tap charts, so
     * re-importing the same file is idempotent; [uri] must be openable by the content
     * resolver (callers resolve `piscesmidi://` to a file first).
     */
    suspend fun importMidiCharts(
        songId: String,
        uri: Uri,
        options: MidiChartOptions = MidiChartOptions(),
    ): List<RhythmChartMeta> = withContext(Dispatchers.IO) {
        // Built-in classical library: piscesmidi://<songId> -> extracted file
        // (same resolution as DualPlayerEngine's ResolvingDataSource).
        val resolved = if (uri.scheme == "piscesmidi") {
            val id = uri.authority ?: uri.lastPathSegment
            val file = id?.toLongOrNull()?.let { File(context.filesDir, "classical/$it.mid") }
            if (file == null || !file.isFile) {
                throw RhythmGenerationException("Built-in MIDI not extracted yet")
            }
            Uri.fromFile(file)
        } else {
            uri
        }
        val bytes = runCatching {
            context.contentResolver.openInputStream(resolved)?.use { it.readBytes() }
        }.getOrNull() ?: throw RhythmGenerationException("Unable to read the selected MIDI file")

        val score = try {
            RhythmMidiParser.parse(bytes)
        } catch (e: RhythmGenerationException) {
            throw e
        } catch (e: Exception) {
            throw RhythmGenerationException("Unable to parse the MIDI file")
        }
        val charts = RhythmMidiChartGenerator.generate(score, options)
        if (charts.isEmpty()) throw RhythmGenerationException("No playable MIDI tracks found")

        val library = store()
        val now = System.currentTimeMillis()
        charts.mapIndexed { index, chart ->
            library.importChart(songId = songId, chart = chart, nowMs = now + index)
        }
    }

    suspend fun chartsForSong(songId: String): List<RhythmChartMeta> = withContext(Dispatchers.IO) {
        store().chartsForSong(songId)
    }

    /**
     * Save a hand-tapped chart ([RhythmChart.SOURCE_TAP]) and its raw take.
     *
     * A tap chart is a first-class library entry like an osu import: `playChart` sends it
     * straight to the game (no play-logic dialog), scores are recorded against it, and it can
     * be deleted like any other chart. `difficultyKey` stays null so it lands in the "others"
     * group, same as osu imports.
     */
    suspend fun saveTapChart(
        songId: String,
        chart: RhythmChart,
        take: RhythmTapTake? = null,
    ): RhythmChartMeta = withContext(Dispatchers.IO) {
        store().importTapChart(songId = songId, chart = chart, take = take)
    }

    /** The raw take recorded for [chartId], for export/PC analysis; null when none was kept. */
    suspend fun tapTakeFor(chartId: String): RhythmTapTake? = withContext(Dispatchers.IO) {
        store().readTapTake(chartId)
    }

    suspend fun chartCatalog(): Map<String, List<RhythmChartMeta>> = withContext(Dispatchers.IO) {
        store().chartCatalog()
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
     * All modes are pure functions over the master: no audio is decoded. Loudness / density
     * filters run here ([RhythmDeriveFilters]), followed by the timing-variant choice, then the
     * mode rule: [RhythmDeriveMode.GRID] snaps onto the chosen grid ([RhythmTimingRefiner] only
     * as a legacy single-point fallback), [RhythmDeriveMode.ONSET] keeps the onset timestamps,
     * [RhythmDeriveMode.BEAT_FIRST] gates beat cells. Derivation always resolves the tracked
     * timing variant ([selectedTiming]). [displayNames]
     * carries the localized, timestamped difficulty and mode names; it is part of the chart body,
     * which is what gives each derivation its own content hash and therefore its own entry.
     */
    suspend fun deriveCharts(
        songId: String,
        master: RhythmChart,
        masterMeta: RhythmChartMeta,
        difficulties: List<DspDifficultySpec>,
        modes: Set<RhythmDeriveMode>,
        laneStrategy: LaneStrategy,
        displayNames: Map<Pair<DspDifficultySpec, RhythmDeriveMode>, String>,
        maxChord: Int = 1,
        onsetSelection: OnsetSelection = OnsetSelection.BALANCED,
        options: RhythmDeriveOptions = RhythmDeriveOptions(),
    ): List<RhythmChartMeta> {
        val jobs = deriveJobs(difficulties, modes)
        if (jobs.isEmpty()) return emptyList()
        val notes = filteredNotes(master, options)
        if (notes.isEmpty()) return emptyList()
        val source = master.copy(notes = notes)
        val timing = selectedTiming(master)
        val gridTiming = gridTimingFor(master, modes, timing)
        val charts = withContext(Dispatchers.Default) {
            jobs.map { (difficulty, mode) ->
                deriveOne(source, difficulty, mode, laneStrategy, maxChord, gridTiming, timing, onsetSelection, options)
                    // The chart keeps every note; hiding the song's opening is the per-chart
                    // `RhythmChartSettings.leadInMs` play filter (default 0).
                    .copy(
                        difficultyName = displayNames[difficulty to mode]
                            ?: "${difficulty.label} ${mode.name.lowercase()}",
                        // Variants and cutoffs describe the master; a derived chart has one grid
                        // and no filter data, and keeping them would fake a second derivation.
                        timingVariants = emptyList(),
                        rmsThresholds = emptyList(),
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
                    difficultyKey = jobs[index].first.key,
                    difficultyGridDiv = jobs[index].first.gridDiv,
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
        difficulties: List<DspDifficultySpec>,
        modes: Set<RhythmDeriveMode>,
        laneStrategy: LaneStrategy,
        maxChord: Int = 1,
        onsetSelection: OnsetSelection = OnsetSelection.BALANCED,
        options: RhythmDeriveOptions = RhythmDeriveOptions(),
    ): Map<Pair<DspDifficultySpec, RhythmDeriveMode>, Int> = withContext(Dispatchers.Default) {
        val jobs = deriveJobs(difficulties, modes)
        if (jobs.isEmpty()) return@withContext emptyMap()
        val notes = filteredNotes(master, options)
        if (notes.isEmpty()) return@withContext emptyMap()
        val source = master.copy(notes = notes)
        val timing = selectedTiming(master)
        val gridTiming = gridTimingFor(master, modes, timing)
        jobs.map { (difficulty, mode) ->
            (difficulty to mode) to
                deriveOne(source, difficulty, mode, laneStrategy, maxChord, gridTiming, timing, onsetSelection, options)
                    .noteCount()
        }.toMap()
    }

    /**
     * Difficulty x mode pairs in creation order: hardest difficulty first (so the easiest chart
     * lands on top of the newest-first list), modes in reverse declaration order (grid last).
     */
    private fun deriveJobs(
        difficulties: List<DspDifficultySpec>,
        modes: Set<RhythmDeriveMode>,
    ): List<Pair<DspDifficultySpec, RhythmDeriveMode>> = difficulties
        .sortedByDescending { it.slotsPerBeat }
        .flatMap { difficulty ->
            RhythmDeriveMode.entries.reversed()
                .filter { it in modes }
                .map { difficulty to it }
        }

    /**
     * Loudness first, then density — the order the generator used before the filters moved here.
     * A percentile the master has no stored cutoff for is treated as off (legacy master).
     */
    private fun filteredNotes(
        master: RhythmChart,
        options: RhythmDeriveOptions,
    ): List<RhythmNote> {
        val minRms = options.minRmsPercentile?.let(master::rmsThresholdFor)
        return RhythmDeriveFilters.apply(
            notes = master.notes,
            minRms = minRms,
            maxLocalNps = options.maxLocalNps,
        )
    }

    /**
     * The timing derivation works on: always the tracked variant, never the stored `classic`
     * one (its anchor phase is half a beat off the beat the hands tap; see RhythmChartLab
     * docs/experiments/2026-11-20 hand-tap baselines + half-beat verdict). The `classic` variant
     * stays on the master only for export hashes and old-chart compatibility. A master without a
     * tracked entry falls back to the first variant, then to the primary timing — for a legacy
     * single-point master that tracked choice is exactly the original timing.
     */
    private fun selectedTiming(
        master: RhythmChart,
    ): List<RhythmTimingPoint> {
        val variants = master.timingVariantsFor()
        val chosen = variants.firstOrNull { it.key == RhythmTimingVariantKey.TRACKED }
            ?: variants.firstOrNull()
        return chosen?.timing ?: master.timing
    }

    private fun deriveOne(
        master: RhythmChart,
        difficulty: DspDifficultySpec,
        mode: RhythmDeriveMode,
        laneStrategy: LaneStrategy,
        maxChord: Int,
        gridTiming: List<RhythmTimingPoint>,
        timing: List<RhythmTimingPoint>,
        onsetSelection: OnsetSelection,
        options: RhythmDeriveOptions,
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
            timing = timing,
        )
        // The beat-grid skeleton with a gate per beat: the v2-v4 configurations of the hand-tap
        // config fit. Same timing source as ONSET (the tracked variant).
        RhythmDeriveMode.BEAT_FIRST -> RhythmChartDeriver.deriveBeatFirst(
            master = master,
            difficulty = difficulty,
            laneStrategy = laneStrategy,
            maxChord = maxChord,
            timing = timing,
            params = options.beatParams,
        )
    }

    /**
     * Empty unless the grid mode is requested.
     *
     * A current master stores both variants, so the tracked one is used as-is. A legacy master
     * predates them: the default tracked choice keeps the historical behaviour of refining a
     * piecewise grid from its onsets.
     */
    private fun gridTimingFor(
        master: RhythmChart,
        modes: Set<RhythmDeriveMode>,
        timing: List<RhythmTimingPoint>,
    ): List<RhythmTimingPoint> {
        if (RhythmDeriveMode.GRID !in modes) return emptyList()
        if (master.timingVariants.isNotEmpty()) return timing
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
    }
}
