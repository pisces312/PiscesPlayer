package com.pisces312.piscesplayer.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per completed AI request, used for the usage report in AI settings.
 *
 * `thoughtTokens` tracks reasoning tokens billed by thinking-capable models; it stays 0 when the
 * provider does not report them.
 */
@Entity(tableName = "ai_usage")
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "prompt_type") val promptType: String,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int,
    @ColumnInfo(name = "thought_tokens") val thoughtTokens: Int
)
