/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026, sound_postcard feature
 * (RhythmChartLab docs/design/sound-postcard-journey-design.md §5, §5.1, §10).
 */
package com.pisces312.piscesplayer.data.postcard.generate

import androidx.media3.common.MediaItem
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.SourceType
import com.pisces312.piscesplayer.data.media.TrackBpmRepository
import com.pisces312.piscesplayer.data.rhythm.RhythmChart
import com.pisces312.piscesplayer.data.rhythm.RhythmRepository
import com.pisces312.piscesplayer.utils.MediaItemBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only loader assembling the [SongFacts] snapshot for settlement (design §5). Everything
 * here degrades: a missing BPM tag, a missing DSP master, or a missing cover all just narrow
 * the inputs — no path ever starts DSP analysis or full-song decoding, and read failures are
 * treated as absent rather than retried on the playback callback.
 *
 * Identity (M0): `stableSongKey = "local:<songId>"` plus the SHA-256 alias of the content URI
 * stored in the frozen profile for a future rescan remap. Cloud songs (Navidrome/Jellyfin),
 * Quick Play previews and deleted rows resolve to null — no journey for them, by design.
 */
@Singleton
class SongFactsLoader @Inject constructor(
    private val musicDao: MusicDao,
    private val bpmRepository: TrackBpmRepository,
    private val rhythmRepository: RhythmRepository,
) {

    /** null when [mediaItem] cannot resolve to a LOCAL library row (cloud, quick play, deleted). */
    suspend fun load(mediaItem: MediaItem): SongFacts? = withContext(Dispatchers.IO) {
        if (isQuickPlay(mediaItem)) return@withContext null
        val songId = mediaItem.mediaId.toLongOrNull() ?: return@withContext null
        val entity = musicDao.getSongByIdOnce(songId) ?: return@withContext null
        if (entity.sourceType != SourceType.LOCAL) return@withContext null

        val bpm = runCatching { bpmRepository.bpmFor(mediaItem) }.getOrNull()
            ?.let { SongFactsAggregation.quantize(it) }

        val master = loadDspMaster(songId, entity.duration)

        val features = master?.let { loaded ->
            runCatching { SongFactsAggregation.dspFeatures(loaded.chart.notes) }.getOrNull()
        }

        SongFacts(
            stableSongKey = stableSongKeyFor(songId),
            librarySongId = songId,
            title = entity.title,
            artist = entity.artistName,
            durationMs = entity.duration,
            bpm = bpm,
            genreKey = SongFactsAggregation.normalizeGenre(entity.genre),
            energy = features?.energy,
            lowRatio = features?.lowRatio,
            midRatio = features?.midRatio,
            highRatio = features?.highRatio,
            // Cover palette extraction is M1 (design §12.1); the shape is final.
            artworkPaletteArgb = emptyList(),
            artworkBrightness = null,
            masterGeneratedAtMs = master?.masterGeneratedAtMs,
        )
    }

    /** SHA-256 alias of the content URI, persisted for the M1 media-library rescan remap. */
    fun uriHashAlias(contentUriString: String): String =
        StableHash.hex(StableHash.sha256(contentUriString))

    fun stableSongKeyFor(songId: Long): String = "local:$songId"

    /**
     * The raw DSP master for [songId], accepted only when `source == SOURCE_DSP`,
     * `difficultyKey == "raw"` and its duration matches the song within 2 s (design §5.1).
     * osu imports and stale-length masters are treated as absent.
     */
    private suspend fun loadDspMaster(songId: Long, songDurationMs: Long): LoadedMaster? {
        val metas = runCatching { rhythmRepository.chartsForSong(songId.toString()) }
            .getOrNull()
            ?: return null
        val rawMeta = metas.firstOrNull { it.difficultyKey == RhythmChart.DIFFICULTY_RAW }
            ?: return null
        if (rawMeta.source != RhythmChart.SOURCE_DSP) return null
        val chart = runCatching { rhythmRepository.loadChart(rawMeta.chartId) }.getOrNull()
            ?: return null
        val chartDuration = chart.durationMs.toLong()
        if (chartDuration <= 0L || kotlin.math.abs(chartDuration - songDurationMs) > 2_000L) {
            return null
        }
        return LoadedMaster(chart = chart, masterGeneratedAtMs = rawMeta.masterGeneratedAtMs)
    }

    private class LoadedMaster(
        val chart: RhythmChart,
        val masterGeneratedAtMs: Long?,
    )

    private fun isQuickPlay(mediaItem: MediaItem): Boolean =
        mediaItem.mediaMetadata.extras?.getBoolean(MediaItemBuilder.EXTRA_QUICK_PLAY, false) == true
}
