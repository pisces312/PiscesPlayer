package com.pisces312.piscesplayer.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["timestamp"], unique = false)
    ]
)
data class FavoritesEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id")
    @SerializedName(value = "songId", alternate = ["song_id"])
    val songId: Long,
    @ColumnInfo(name = "is_favorite")
    @SerializedName(value = "isFavorite", alternate = ["is_favorite"])
    val isFavorite: Boolean = true,
    @ColumnInfo(name = "timestamp")
    @SerializedName(value = "timestamp", alternate = ["addedAt", "added_at"])
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "rating")
    @SerializedName("rating")
    val rating: Int = 0
)
