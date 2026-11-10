package com.pisces312.piscesplayer.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pisces312.piscesplayer.data.model.Playlist
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val playlistJson = Json { ignoreUnknownKeys = true }
private val songIdListSerializer = ListSerializer(String.serializer())

/** Encodes the ordered original-song snapshot for the `ai_original_song_ids` TEXT column. */
private fun encodeSongIds(ids: List<String>): String? =
        ids.takeIf { it.isNotEmpty() }?.let { playlistJson.encodeToString(songIdListSerializer, it) }

/** Decodes it back; anything unreadable (manual/legacy rows) degrades to an empty list. */
private fun decodeSongIds(raw: String?): List<String> =
        raw?.let { runCatching { playlistJson.decodeFromString(songIdListSerializer, it) }.getOrNull() }
                ?: emptyList()

@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["last_modified"])
    ]
)
data class PlaylistEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_modified")
    val lastModified: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_queue_generated")
    val isQueueGenerated: Boolean = false,
    @ColumnInfo(name = "cover_image_uri")
    val coverImageUri: String? = null,
    @ColumnInfo(name = "cover_color_argb")
    val coverColorArgb: Int? = null,
    @ColumnInfo(name = "cover_icon_name")
    val coverIconName: String? = null,
    @ColumnInfo(name = "cover_shape_type")
    val coverShapeType: String? = null,
    @ColumnInfo(name = "cover_shape_detail_1")
    val coverShapeDetail1: Float? = null,
    @ColumnInfo(name = "cover_shape_detail_2")
    val coverShapeDetail2: Float? = null,
    @ColumnInfo(name = "cover_shape_detail_3")
    val coverShapeDetail3: Float? = null,
    @ColumnInfo(name = "cover_shape_detail_4")
    val coverShapeDetail4: Float? = null,
    @ColumnInfo(name = "source")
    val source: String = "LOCAL",
    /** Generation prompt for AI playlists; null for everything else. Added in schema v9. */
    @ColumnInfo(name = "ai_prompt")
    val aiPrompt: String? = null,
    /** Sampling mode name used at generation; null for manual/legacy rows. Added in schema v10. */
    @ColumnInfo(name = "ai_sample_mode")
    val aiSampleMode: String? = null,
    /** Context size used at generation; null for manual/legacy rows. Added in schema v10. */
    @ColumnInfo(name = "ai_sample_size")
    val aiSampleSize: Int? = null,
    /** JSON array of the originally generated song ids, in generation order. Schema v10. */
    @ColumnInfo(name = "ai_original_song_ids")
    val aiOriginalSongIds: String? = null,
    /** Chain of thought the model streamed before answering; null when it sent none. Schema v11. */
    @ColumnInfo(name = "ai_thinking")
    val aiThinking: String? = null,
)

fun PlaylistEntity.toPlaylist(songIds: List<String>): Playlist {
    return Playlist(
        id = id,
        name = name,
        songIds = songIds,
        createdAt = createdAt,
        lastModified = lastModified,
        isQueueGenerated = isQueueGenerated,
        coverImageUri = coverImageUri,
        coverColorArgb = coverColorArgb,
        coverIconName = coverIconName,
        coverShapeType = coverShapeType,
        coverShapeDetail1 = coverShapeDetail1,
        coverShapeDetail2 = coverShapeDetail2,
        coverShapeDetail3 = coverShapeDetail3,
        coverShapeDetail4 = coverShapeDetail4,
        source = source,
        aiPrompt = aiPrompt,
        aiSampleMode = aiSampleMode,
        aiSampleSize = aiSampleSize,
        aiOriginalSongIds = decodeSongIds(aiOriginalSongIds),
        aiThinking = aiThinking,
    )
}

fun Playlist.toEntity(): PlaylistEntity {
    return PlaylistEntity(
        id = id,
        name = name,
        createdAt = createdAt,
        lastModified = lastModified,
        isQueueGenerated = isQueueGenerated,
        coverImageUri = coverImageUri,
        coverColorArgb = coverColorArgb,
        coverIconName = coverIconName,
        coverShapeType = coverShapeType,
        coverShapeDetail1 = coverShapeDetail1,
        coverShapeDetail2 = coverShapeDetail2,
        coverShapeDetail3 = coverShapeDetail3,
        coverShapeDetail4 = coverShapeDetail4,
        source = source,
        aiPrompt = aiPrompt,
        aiSampleMode = aiSampleMode,
        aiSampleSize = aiSampleSize,
        aiOriginalSongIds = encodeSongIds(aiOriginalSongIds),
        aiThinking = aiThinking,
    )
}
