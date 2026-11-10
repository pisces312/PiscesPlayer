package com.pisces312.piscesplayer.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id")
    @SerializedName(value = "songId", alternate = ["song_id"])
    val songId: Long,
    @ColumnInfo(name = "content")
    @SerializedName("content")
    val content: String,
    @ColumnInfo(name = "is_synced")
    @SerializedName(value = "isSynced", alternate = ["is_synced"])
    val isSynced: Boolean = false,
    @ColumnInfo(name = "source")
    @SerializedName("source")
    val source: String? = null
)
