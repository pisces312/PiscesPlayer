package com.pisces312.piscesplayer.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached response for a single AI prompt.
 *
 * The primary key is the SHA-256 hash of the prompt text, so an identical request always
 * overwrites the previous answer instead of accumulating duplicates.
 */
@Entity(tableName = "ai_cache")
data class AiCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "prompt_hash")
    val promptHash: String,
    @ColumnInfo(name = "response_json")
    val responseJson: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long
)
