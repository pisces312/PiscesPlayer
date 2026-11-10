package com.pisces312.piscesplayer.data.backup.module

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pisces312.piscesplayer.data.backup.model.BackupSection
import com.pisces312.piscesplayer.data.backup.restore.PlaylistSongMatcher
import com.pisces312.piscesplayer.data.backup.restore.PendingSongRef
import com.pisces312.piscesplayer.data.database.FavoritesDao
import com.pisces312.piscesplayer.data.database.FavoritesEntity
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Backup row: favorite state + identity metadata for cross-device songId remapping. */
data class FavoritesBackupEntry(
    val songId: Long,
    val isFavorite: Boolean = true,
    val timestamp: Long = 0L,
    val rating: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val duration: Long = 0L,
)

@Singleton
class FavoritesModuleHandler @Inject constructor(
    private val favoritesDao: FavoritesDao,
    private val musicDao: MusicDao,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.FAVORITES

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val summaries = musicDao.getAllLocalSongSummaries().associateBy { it.id }
        val payload = favoritesDao.getAllFavoritesOnce().map { fav ->
            val meta = summaries[fav.songId]
            FavoritesBackupEntry(
                songId = fav.songId,
                isFavorite = fav.isFavorite,
                timestamp = fav.timestamp,
                rating = fav.rating,
                title = meta?.title,
                artist = meta?.artistName,
                album = meta?.albumName,
                duration = meta?.duration ?: 0L,
            )
        }
        gson.toJson(payload)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        favoritesDao.getAllFavoritesOnce().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val array = JsonParser.parseString(payload).asJsonArray
        val matcher = PlaylistSongMatcher(musicDao.getAllLocalSongSummaries())
        val favorites = array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val backupId = readLong(obj, "songId", "song_id") ?: return@mapNotNull null
            val title = readString(obj, "title")
            val artist = readString(obj, "artist", "artistName", "artist_name")
            val meta = if (title != null && artist != null) {
                PendingSongRef(
                    title = title,
                    artist = artist,
                    album = readString(obj, "album", "albumName", "album_name"),
                    duration = readLong(obj, "duration") ?: 0L,
                )
            } else {
                null
            }
            val resolvedId = matcher.resolve(backupId.toString(), meta)?.toLongOrNull()
                ?: backupId // keep verbatim when library empty / deferred
            FavoritesEntity(
                songId = resolvedId,
                isFavorite = readBoolean(obj, "isFavorite", "is_favorite") ?: true,
                timestamp = readLong(obj, "timestamp", "addedAt", "added_at")
                    ?: System.currentTimeMillis(),
                rating = readInt(obj, "rating") ?: 0,
            )
        }
        // Merge: upsert incoming rows, keep favorites not present in the backup.
        favoritesDao.insertAll(favorites)
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

    private fun readInt(obj: JsonObject, vararg keys: String): Int? =
        readLong(obj, *keys)?.toInt()

    /** Full replace so a failed restore can be undone. */
    override suspend fun rollback(snapshot: String) = withContext(Dispatchers.IO) {
        val array = JsonParser.parseString(snapshot).asJsonArray
        val favorites = array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val songId = readLong(obj, "songId", "song_id") ?: return@mapNotNull null
            FavoritesEntity(
                songId = songId,
                isFavorite = readBoolean(obj, "isFavorite", "is_favorite") ?: true,
                timestamp = readLong(obj, "timestamp", "addedAt", "added_at") ?: 0L,
                rating = readInt(obj, "rating") ?: 0,
            )
        }
        favoritesDao.replaceAll(favorites)
    }
}
