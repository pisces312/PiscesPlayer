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

/** Which expensive step a quick run is inside, for the progress UI's status line. */
enum class RhythmQuickPhase { ANALYZING, DERIVING }

/** The charts a quick run resolved to; a slot is null when that rung could not be produced. */
data class RhythmQuickCharts(val easy: RhythmChartMeta?, val hard: RhythmChartMeta?) {
    /** The chart to start for [key] ("easy"/"hard"), falling back to the other rung. */
    fun target(key: String): RhythmChartMeta? =
        if (key == DspDifficultySpec.HARD.key) hard ?: easy else easy ?: hard
}

/**
 * The "make this song playable right now" pipeline shared by every quick-start entry: the song
 * picker's game icon, the song-info quick dialog and the setup page's「直接生成」button.
 *
 * It only fills the gaps — a chart that already exists (and its best score) is never
 * regenerated. The master comes from the DSP analysis (or the MIDI note tracks for MIDI songs)
 * and the derived rungs are the built-in Easy/Hard ladder under the default v1 preset
 * ([RhythmDerivePreset.V1_GRID_TRACKED]) — the same charts the derive sheet produces on its
 * defaults.
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

    suspend fun ensure(
        song: Song,
        onPhase: (RhythmQuickPhase, Float) -> Unit = { _, _ -> },
    ): RhythmQuickCharts {
        if (CloudOfflineRepository.isCloudSong(song)) {
            throw IllegalArgumentException(context.getString(R.string.rhythm_cloud_not_supported))
        }
        if (song.isRhythmMidiSong()) return ensureMidi(song, onPhase)
        if (song.duration > RhythmRepository.MAX_ANALYSIS_DURATION_MS) {
            throw IllegalArgumentException(
                context.getString(
                    R.string.rhythm_generate_song_too_long,
                    (RhythmRepository.MAX_ANALYSIS_DURATION_MS / 60_000L).toInt(),
                ),
            )
        }
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
        return RhythmQuickCharts(
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

    private fun playbackUri(song: Song): Uri =
        song.contentUriString.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: Uri.fromFile(File(song.path))
}
