package com.pisces312.piscesplayer.data.musicquiz

import com.pisces312.piscesplayer.data.database.LyricsDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Batch lookup of persisted lyrics for kids-song keyword matching. The `lyrics` table is the
 * source of truth (the legacy `songs.lyrics` column was dropped in migration P1d); songs
 * without cached lyrics simply return nothing and are matched on metadata alone.
 */
@Singleton
class MusicQuizLyricsProvider @Inject constructor(
    private val lyricsDao: LyricsDao,
) {
    suspend fun lyricsFor(songIds: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext emptyMap()
        val idMap = songIds.mapNotNull { raw ->
            raw.toLongOrNull()?.let { it to raw }
        }.toMap()
        if (idMap.isEmpty()) return@withContext emptyMap()
        val withLyrics = idMap.keys
            .chunked(SQLITE_BIND_LIMIT)
            .flatMap { chunk -> lyricsDao.getSongIdsWithLyrics(chunk) }
            .toSet()
        withLyrics.mapNotNull { longId ->
            val content = lyricsDao.getLyrics(longId)?.content
            if (content.isNullOrBlank()) {
                null
            } else {
                idMap[longId]?.let { rawId -> rawId to content }
            }
        }.toMap()
    }

    private companion object {
        const val SQLITE_BIND_LIMIT = 500
    }
}
