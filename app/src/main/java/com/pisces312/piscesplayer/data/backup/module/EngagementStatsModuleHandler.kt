package com.pisces312.piscesplayer.data.backup.module

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pisces312.piscesplayer.data.backup.model.BackupSection
import com.pisces312.piscesplayer.data.backup.restore.PendingSongRef
import com.pisces312.piscesplayer.data.backup.restore.PlaylistSongMatcher
import com.pisces312.piscesplayer.data.database.EngagementDao
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.SongEngagementEntity
import com.pisces312.piscesplayer.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Engagement backup row + identity metadata for cross-device songId remapping. */
data class EngagementBackupEntry(
    val songId: Long,
    val playCount: Int = 0,
    val totalPlayDurationMs: Long = 0L,
    val lastPlayedTimestamp: Long = 0L,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val duration: Long = 0L,
)

@Singleton
class EngagementStatsModuleHandler @Inject constructor(
    private val engagementDao: EngagementDao,
    private val musicDao: MusicDao,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.ENGAGEMENT_STATS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val summaries = musicDao.getAllLocalSongSummaries().associateBy { it.id }
        val payload = engagementDao.getAllEngagements().map { row ->
            val meta = summaries[row.songId]
            EngagementBackupEntry(
                songId = row.songId,
                playCount = row.playCount,
                totalPlayDurationMs = row.totalPlayDurationMs,
                lastPlayedTimestamp = row.lastPlayedTimestamp,
                title = meta?.title,
                artist = meta?.artistName,
                album = meta?.albumName,
                duration = meta?.duration ?: 0L,
            )
        }
        gson.toJson(payload)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        engagementDao.getAllEngagements().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val parsed = JsonParser.parseString(payload)
        require(parsed.isJsonArray) { "Engagement stats payload must be a JSON array." }

        val sourceEntries = parsed.asJsonArray.size()
        val matcher = PlaylistSongMatcher(musicDao.getAllLocalSongSummaries())
        val stats = parseEntries(parsed.asJsonArray, matcher)
        if (sourceEntries > 0 && stats.isEmpty()) {
            throw IllegalArgumentException("Engagement stats backup does not contain any valid entries.")
        }

        // Merge with existing rows so backup restore never drops newer local stats.
        val existing = engagementDao.getAllEngagements().associateBy { it.songId }
        val merged = stats.map { incoming ->
            existing[incoming.songId]?.let { mergeEntries(it, incoming) } ?: incoming
        }
        val untouched = existing.values.filter { it.songId !in stats.map { s -> s.songId }.toSet() }
        engagementDao.replaceAll(merged + untouched)
    }

    /** Full replace so a failed restore can be undone. */
    override suspend fun rollback(snapshot: String) = withContext(Dispatchers.IO) {
        val parsed = JsonParser.parseString(snapshot)
        require(parsed.isJsonArray) { "Engagement stats payload must be a JSON array." }
        val matcher = PlaylistSongMatcher(musicDao.getAllLocalSongSummaries())
        engagementDao.replaceAll(parseEntries(parsed.asJsonArray, matcher))
    }

    private fun parseEntries(
        array: com.google.gson.JsonArray,
        matcher: PlaylistSongMatcher,
    ): List<SongEngagementEntity> {
        val merged = linkedMapOf<Long, SongEngagementEntity>()
        array.forEach { element ->
            val entry = parseEntry(element, matcher) ?: return@forEach
            merged.merge(entry.songId, entry, ::mergeEntries)
        }
        return merged.values.toList()
    }

    private fun parseEntry(element: JsonElement, matcher: PlaylistSongMatcher): SongEngagementEntity? {
        if (!element.isJsonObject) return null

        val obj = element.asJsonObject
        val backupId = readLong(obj, "songId", "song_id") ?: return null

        val meta = buildMeta(obj)
        val resolvedId = matcher.resolve(backupId.toString(), meta)?.toLongOrNull() ?: backupId

        return SongEngagementEntity(
            songId = resolvedId,
            playCount = (readInt(obj, "playCount", "play_count", "score", "plays") ?: 0).coerceAtLeast(0),
            totalPlayDurationMs = (
                readLong(
                    obj,
                    "totalPlayDurationMs",
                    "total_play_duration_ms",
                    "totalDuration",
                    "total_duration",
                    "durationMs",
                    "duration_ms"
                ) ?: 0L
            ).coerceAtLeast(0L),
            lastPlayedTimestamp = (
                readLong(
                    obj,
                    "lastPlayedTimestamp",
                    "last_played_timestamp",
                    "lastPlayedAt",
                    "last_played_at",
                    "timestamp"
                ) ?: 0L
            ).coerceAtLeast(0L)
        )
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

    private fun mergeEntries(
        existing: SongEngagementEntity,
        incoming: SongEngagementEntity
    ): SongEngagementEntity {
        return SongEngagementEntity(
            songId = existing.songId,
            playCount = maxOf(existing.playCount, incoming.playCount),
            totalPlayDurationMs = maxOf(existing.totalPlayDurationMs, incoming.totalPlayDurationMs),
            lastPlayedTimestamp = maxOf(existing.lastPlayedTimestamp, incoming.lastPlayedTimestamp)
        )
    }

    private fun readString(obj: JsonObject, vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { key ->
                obj.get(key)
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
            }
            .firstOrNull()
    }

    private fun readInt(obj: JsonObject, vararg keys: String): Int? {
        return keys.asSequence()
            .mapNotNull { key -> readLongValue(obj.get(key))?.toInt() }
            .firstOrNull()
    }

    private fun readLong(obj: JsonObject, vararg keys: String): Long? {
        return keys.asSequence()
            .mapNotNull { key -> readLongValue(obj.get(key)) }
            .firstOrNull()
    }

    private fun readLongValue(element: JsonElement?): Long? {
        val primitive = element?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            primitive.isNumber -> primitive.asNumber.toLong()
            primitive.isString -> primitive.asString.toLongOrNull()
            else -> null
        }
    }
}
