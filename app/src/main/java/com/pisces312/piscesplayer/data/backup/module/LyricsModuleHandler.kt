package com.pisces312.piscesplayer.data.backup.module

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pisces312.piscesplayer.data.backup.model.BackupSection
import com.pisces312.piscesplayer.data.backup.restore.PendingSongRef
import com.pisces312.piscesplayer.data.backup.restore.PlaylistSongMatcher
import com.pisces312.piscesplayer.data.database.LyricsDao
import com.pisces312.piscesplayer.data.database.LyricsEntity
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Lyrics backup row + identity metadata for cross-device songId remapping. */
data class LyricsBackupEntry(
    val songId: Long,
    val content: String,
    val isSynced: Boolean = false,
    val source: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val duration: Long = 0L,
)

@Singleton
class LyricsModuleHandler @Inject constructor(
    private val lyricsDao: LyricsDao,
    private val musicDao: MusicDao,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.LYRICS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val summaries = musicDao.getAllLocalSongSummaries().associateBy { it.id }
        val payload = lyricsDao.getAll().map { row ->
            val meta = summaries[row.songId]
            LyricsBackupEntry(
                songId = row.songId,
                content = row.content,
                isSynced = row.isSynced,
                source = row.source,
                title = meta?.title,
                artist = meta?.artistName,
                album = meta?.albumName,
                duration = meta?.duration ?: 0L,
            )
        }
        gson.toJson(payload)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        lyricsDao.getAll().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val array = JsonParser.parseString(payload).asJsonArray
        val matcher = PlaylistSongMatcher(musicDao.getAllLocalSongSummaries())
        val lyrics = array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val backupId = readLong(obj, "songId", "song_id") ?: return@mapNotNull null
            val content = readString(obj, "content") ?: return@mapNotNull null
            val meta = buildMeta(obj)
            val resolvedId = matcher.resolve(backupId.toString(), meta)?.toLongOrNull() ?: backupId
            LyricsEntity(
                songId = resolvedId,
                content = content,
                isSynced = readBoolean(obj, "isSynced", "is_synced") ?: false,
                source = readString(obj, "source"),
            )
        }
        // Merge: upsert incoming rows, keep lyrics for songs not in the backup.
        lyricsDao.insertAll(lyrics)
    }

    /** Full replace so a failed restore can be undone. */
    override suspend fun rollback(snapshot: String) = withContext(Dispatchers.IO) {
        val array = JsonParser.parseString(snapshot).asJsonArray
        val matcher = PlaylistSongMatcher(musicDao.getAllLocalSongSummaries())
        val lyrics = array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val backupId = readLong(obj, "songId", "song_id") ?: return@mapNotNull null
            val content = readString(obj, "content") ?: return@mapNotNull null
            val meta = buildMeta(obj)
            val resolvedId = matcher.resolve(backupId.toString(), meta)?.toLongOrNull() ?: backupId
            LyricsEntity(
                songId = resolvedId,
                content = content,
                isSynced = readBoolean(obj, "isSynced", "is_synced") ?: false,
                source = readString(obj, "source"),
            )
        }
        lyricsDao.replaceAll(lyrics)
    }

    private fun buildMeta(obj: JsonObject): PendingSongRef? {
        val title = readString(obj, "title")
        val artist = readString(obj, "artist", "artistName", "artist_name")
        if (title == null || artist == null) return null
        return PendingSongRef(
            title = title,
            artist = artist,
            album = readString(obj, "album", "albumName", "album_name"),
            duration = readLong(obj, "duration") ?: 0L,
        )
    }

    private fun readString(obj: JsonObject, vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
            .firstOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun readBoolean(obj: JsonObject, vararg keys: String): Boolean? {
        return keys.asSequence()
            .mapNotNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive }
            .firstOrNull()
            ?.let { primitive ->
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isString -> primitive.asString.toBooleanStrictOrNull()
                    else -> null
                }
            }
    }

    private fun readLong(obj: JsonObject, vararg keys: String): Long? {
        return keys.asSequence()
            .mapNotNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive }
            .firstOrNull()
            ?.let { primitive ->
                when {
                    primitive.isNumber -> primitive.asNumber.toLong()
                    primitive.isString -> primitive.asString.toLongOrNull()
                    else -> null
                }
            }
    }
}
