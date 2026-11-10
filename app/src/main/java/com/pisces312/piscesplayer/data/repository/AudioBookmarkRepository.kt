package com.pisces312.piscesplayer.data.repository

import com.pisces312.piscesplayer.data.database.AudioBookmarkEntity
import kotlinx.coroutines.flow.Flow

interface AudioBookmarkRepository {
    fun getAllBookmarksFlow(): Flow<List<AudioBookmarkEntity>>
    suspend fun getBookmarksForSong(songId: Long): List<AudioBookmarkEntity>
    suspend fun insertBookmark(bookmark: AudioBookmarkEntity)
    suspend fun deleteBookmark(id: Long)
    suspend fun getBookmarkById(id: Long): AudioBookmarkEntity?
}
