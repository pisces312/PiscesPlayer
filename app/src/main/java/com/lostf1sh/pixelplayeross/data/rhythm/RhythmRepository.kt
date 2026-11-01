/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, storage layer designed for PixelPlayerOSS
 * (docs/rhythm-game-integration-plan.md §4.2).
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

/** A generated chart and the facts behind it, for the "generated" confirmation card. */
data class DspGenerationResult(
    val meta: RhythmChartMeta,
    val diagnostics: DspDiagnostics,
)

/**
 * Rhythm chart library stored in `filesDir/rhythm`. No Room schema change is
 * involved; chart content is hashed, so importing the same `.osu` twice is
 * idempotent and only updates the song binding.
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

    /**
     * Decode the song, run the local DSP analysis, and store the result bound to [songId].
     *
     * [displayName] ends up as the chart's difficulty name — callers add a timestamp so that
     * repeated generations stay distinguishable in the list (several charts per song are kept).
     *
     * Decoding uses a whole-song PCM buffer (the same one gameplay uses); [mono] is kept for
     * analysis and the interleaved buffer is dropped before the STFT starts.
     */
    suspend fun generateDsp(
        songId: String,
        uri: Uri,
        params: RhythmChartGenParams,
        displayName: String,
        onProgress: (Float) -> Unit = {},
    ): DspGenerationResult = withContext(Dispatchers.Default) {
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

        val generated = RhythmChartGenerator.generate(
            pcmMono = decoded.mono,
            sampleRate = decoded.sampleRate,
            durationMs = decoded.durationMs,
            params = params,
            onProgress = onProgress,
        )
        val chart = generated.chart.copy(difficultyName = displayName)
        val meta = withContext(Dispatchers.IO) { store().importChart(songId, chart) }
        DspGenerationResult(meta = meta, diagnostics = generated.diagnostics)
    }

    private class DecodedAudio(
        val mono: FloatArray,
        val sampleRate: Int,
        val durationMs: Int,
    )

    companion object {
        /** Above this the one-shot PCM buffer becomes a real OOM risk on low-end devices. */
        const val MAX_ANALYSIS_DURATION_MS = 12 * 60 * 1000L
    }
}
