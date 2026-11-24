/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS (rhythm game quick-start pipeline).
 */
package com.pisces312.piscesplayer.presentation.rhythm

import android.content.Context
import android.net.Uri
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.offline.CloudOfflineRepository
import com.pisces312.piscesplayer.data.rhythm.DspDifficultySpec
import com.pisces312.piscesplayer.data.rhythm.MidiChartOptions
import com.pisces312.piscesplayer.data.rhythm.RhythmAnalysisParams
import com.pisces312.piscesplayer.data.rhythm.RhythmChart
import com.pisces312.piscesplayer.data.rhythm.RhythmChartMeta
import com.pisces312.piscesplayer.data.rhythm.RhythmDerivePreset
import com.pisces312.piscesplayer.data.rhythm.RhythmDeriveSettings
import com.pisces312.piscesplayer.data.rhythm.RhythmRepository
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Which expensive step a quick run is inside, for the progress UI's status line. */
enum class RhythmQuickPhase { ANALYZING, DERIVING }

/** The charts a quick run resolved to; a slot is null when that rung could not be produced. */
data class RhythmQuickCharts(val easy: RhythmChartMeta?, val hard: RhythmChartMeta?) {
    /**
     * The chart for [key] ("easy"/"hard"), strictly. No fallback to the other rung: a missing
     * target is an error the caller reports, never a silent difficulty swap.
     */
    fun target(key: String): RhythmChartMeta? =
        if (key == DspDifficultySpec.HARD.key) hard else easy
}

/**
 * The "make this song playable right now" pipeline shared by every quick-start entry: the song
 * picker's game icon, the song-info quick dialog and the setup page's「生成谱面」entry.
 *
 * It only fills the gaps — a chart that already exists (and its best score) is never
 * regenerated. The master comes from the DSP analysis (or the MIDI note tracks for MIDI songs)
 * and the derived rungs are the built-in Easy/Hard ladder under the default v1 preset
 * ([RhythmDerivePreset.V1_GRID_TRACKED]) — the same charts the derive sheet produces on its
 * defaults.
 *
 * **Cancellation cleans up**: when the caller cancels mid-run (the "cancel" button), every
 * chart this run created is deleted again — a cancelled quick run leaves the library exactly as
 * it found it (2026-11-24 decision).
 *
 * @throws IllegalArgumentException with a user-readable message when the song cannot be
 * analysed at all (cloud stream, too long); other failures surface as their own messages.
 */
class RhythmQuickPlay(
    private val context: Context,
    private val repository: RhythmRepository,
) {
    /** Same compact stamp the derive sheet puts into derived chart names. */
    private val nameFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMdd HH:mm").withZone(ZoneId.systemDefault())

    /** Master only — the setup page's first step; its derive step runs in the derive sheet. */
    suspend fun ensureMaster(
        song: Song,
        onPhase: (RhythmQuickPhase, Float) -> Unit = { _, _ -> },
    ): RhythmChartMeta = withCreatedCleanup(song) {
        requireUsable(song)
        requireAnalysisLength(song)
        val uri = playbackUri(song)
        var charts = repository.chartsForSong(song.id)
        var masterMeta = charts.firstOrNull { it.difficultyKey == RhythmChart.DIFFICULTY_RAW }
        if (masterMeta == null) {
            repository.generateMaster(
                songId = song.id,
                uri = uri,
                params = RhythmAnalysisParams(),
                onProgress = { onPhase(RhythmQuickPhase.ANALYZING, it) },
            )
            charts = repository.chartsForSong(song.id)
            masterMeta = charts.firstOrNull { it.difficultyKey == RhythmChart.DIFFICULTY_RAW }
        }
        masterMeta ?: error("master generation produced no chart")
    }

    /** Master + Easy/Hard rungs: the full quick pipeline. */
    suspend fun ensure(
        song: Song,
        onPhase: (RhythmQuickPhase, Float) -> Unit = { _, _ -> },
    ): RhythmQuickCharts = withCreatedCleanup(song) {
        requireUsable(song)
        if (song.isRhythmMidiSong()) return@withCreatedCleanup ensureMidi(song, onPhase)
        requireAnalysisLength(song)
        val uri = playbackUri(song)
        var charts = repository.chartsForSong(song.id)
        if (charts.none { it.difficultyKey == RhythmChart.DIFFICULTY_RAW }) {
            repository.generateMaster(
                songId = song.id,
                uri = uri,
                params = RhythmAnalysisParams(),
                onProgress = { onPhase(RhythmQuickPhase.ANALYZING, it) },
            )
            charts = repository.chartsForSong(song.id)
        }
        val masterMeta = charts.firstOrNull { it.difficultyKey == RhythmChart.DIFFICULTY_RAW }
        val master = masterMeta?.let { repository.loadChart(it.chartId) }
        if (masterMeta != null && master != null) {
            val missing = listOf(DspDifficultySpec.EASY, DspDifficultySpec.HARD)
                .filter { spec -> charts.none { it.difficultyKey == spec.key } }
            if (missing.isNotEmpty()) {
                onPhase(RhythmQuickPhase.DERIVING, 0f)
                val settings = RhythmDerivePreset.V1_GRID_TRACKED.applyTo(RhythmDeriveSettings())
                val stamp = nameFormatter.format(Instant.now())
                val modeLabel = context.getString(R.string.rhythm_derive_mode_grid)
                val names = buildMap {
                    for (spec in missing) {
                        for (mode in settings.modes) {
                            put(spec to mode, "${spec.label} · $modeLabel · $stamp")
                        }
                    }
                }
                repository.deriveCharts(
                    songId = song.id,
                    master = master,
                    masterMeta = masterMeta,
                    // Creation order: hardest first, like the derive sheet does.
                    difficulties = missing.sortedByDescending { it.slotsPerBeat },
                    modes = settings.modes,
                    laneStrategy = settings.laneStrategy,
                    displayNames = names,
                    maxChord = settings.maxChord,
                    onsetSelection = settings.selection,
                    options = settings.options(),
                )
                charts = repository.chartsForSong(song.id)
            }
        }
        RhythmQuickCharts(
            easy = charts.lastOrNull { it.difficultyKey == DspDifficultySpec.EASY.key },
            hard = charts.lastOrNull { it.difficultyKey == DspDifficultySpec.HARD.key },
        )
    }

    /** MIDI songs skip the DSP master entirely: the note tracks are the chart source. */
    private suspend fun ensureMidi(
        song: Song,
        onPhase: (RhythmQuickPhase, Float) -> Unit,
    ): RhythmQuickCharts {
        var charts = repository.chartsForSong(song.id)
        if (charts.isEmpty()) {
            onPhase(RhythmQuickPhase.DERIVING, 0f)
            repository.importMidiCharts(
                songId = song.id,
                uri = playbackUri(song),
                options = MidiChartOptions(difficulties = defaultMidiDifficulties(context)),
            )
            charts = repository.chartsForSong(song.id)
        }
        // One chart per playable track per rung, named "<track> · <difficulty label>".
        val easyLabel = context.getString(R.string.rhythm_midi_diff_easy)
        val hardLabel = context.getString(R.string.rhythm_midi_diff_hard)
        fun byLabel(label: String): RhythmChartMeta? =
            charts.lastOrNull { it.difficultyName.endsWith("· $label") }
                ?: charts.lastOrNull { it.difficultyName == label }
        return RhythmQuickCharts(easy = byLabel(easyLabel), hard = byLabel(hardLabel))
    }

    /** Songs the analysis path cannot handle at all (cloud streams). */
    private suspend fun requireUsable(song: Song) {
        if (CloudOfflineRepository.isCloudSong(song)) {
            throw IllegalArgumentException(context.getString(R.string.rhythm_cloud_not_supported))
        }
    }

    /** The DSP analysis has a duration ceiling; MIDI songs skip it (note tracks are cheap). */
    private fun requireAnalysisLength(song: Song) {
        if (song.duration > RhythmRepository.MAX_ANALYSIS_DURATION_MS) {
            throw IllegalArgumentException(
                context.getString(
                    R.string.rhythm_generate_song_too_long,
                    (RhythmRepository.MAX_ANALYSIS_DURATION_MS / 60_000L).toInt(),
                ),
            )
        }
    }

    /**
     * Runs [block], and when the caller cancels mid-run deletes every chart the run created —
     * the diff against the chart list captured before it started. Cleanup itself runs under
     * [NonCancellable] so a cancelled scope cannot abort it halfway.
     */
    private suspend fun <T> withCreatedCleanup(song: Song, block: suspend () -> T): T {
        val before = repository.chartsForSong(song.id).map { it.chartId }.toSet()
        try {
            return block()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val created = repository.chartsForSong(song.id).filter { it.chartId !in before }
                for (meta in created) {
                    repository.deleteChart(song.id, meta.chartId)
                }
            }
            throw cancelled
        }
    }

    private fun playbackUri(song: Song): Uri =
        song.contentUriString.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: Uri.fromFile(File(song.path))
}
