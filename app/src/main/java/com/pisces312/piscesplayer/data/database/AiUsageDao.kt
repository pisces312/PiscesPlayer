package com.pisces312.piscesplayer.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiUsageDao {
    @Insert suspend fun insertUsage(usage: AiUsageEntity)

    @Query("SELECT * FROM ai_usage") suspend fun getAllUsagesOnce(): List<AiUsageEntity>

    @Query("SELECT * FROM ai_usage ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentUsages(limit: Int): Flow<List<AiUsageEntity>>

    @Query("SELECT SUM(prompt_tokens) FROM ai_usage") fun getTotalPromptTokens(): Flow<Int?>

    @Query("SELECT SUM(output_tokens) FROM ai_usage") fun getTotalOutputTokens(): Flow<Int?>

    @Query("SELECT SUM(thought_tokens) FROM ai_usage") fun getTotalThoughtTokens(): Flow<Int?>

    @Query("DELETE FROM ai_usage") suspend fun clearAll()
}
