package com.pisces312.piscesplayer.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface LyricsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntity(lyrics: LyricsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllEntities(lyrics: List<LyricsEntity>)

    /**
     * Public insert entry point. Keeps `lyrics_fts` in sync; blank lyrics are treated
     * as "no lyrics" and removed from the index (matching `getSongIdsWithLyrics`'s
     * `content != ''` semantics).
     */
    @Transaction
    suspend fun insert(lyrics: LyricsEntity) {
        insertEntity(lyrics)
        upsertFtsRow(lyrics)
    }

    /**
     * Batch variant of [insert]; single transaction so a backup restore cannot leave a
     * partially indexed table.
     */
    @Transaction
    suspend fun insertAll(lyrics: List<LyricsEntity>) {
        insertAllEntities(lyrics)
        lyrics.forEach { upsertFtsRow(it) }
    }

    @Query("SELECT * FROM lyrics WHERE song_id = :songId")
    suspend fun getLyrics(songId: Long): LyricsEntity?

    @Query("DELETE FROM lyrics WHERE song_id = :songId")
    suspend fun deleteLyricsEntity(songId: Long)

    @Transaction
    suspend fun deleteLyrics(songId: Long) {
        deleteLyricsEntity(songId)
        deleteFtsRow(songId)
    }

    @Query("DELETE FROM lyrics")
    suspend fun deleteAllEntities()

    @Transaction
    suspend fun deleteAll() {
        deleteAllEntities()
        clearFts()
    }

    @Query("SELECT * FROM lyrics")
    suspend fun getAll(): List<LyricsEntity>

    @Query("SELECT song_id FROM lyrics WHERE song_id IN (:songIds) AND content != ''")
    suspend fun getSongIdsWithLyrics(songIds: List<Long>): List<Long>

    @Transaction
    suspend fun replaceAll(lyrics: List<LyricsEntity>) {
        deleteAllEntities()
        clearFts()
        if (lyrics.isNotEmpty()) {
            insertAllEntities(lyrics)
            lyrics.forEach { upsertFtsRow(it) }
        }
    }

    @Query("INSERT OR REPLACE INTO lyrics_fts(rowid, content) VALUES (:songId, :content)")
    suspend fun upsertFtsRowRaw(songId: Long, content: String)

    @Query("DELETE FROM lyrics_fts WHERE rowid = :songId")
    suspend fun deleteFtsRow(songId: Long)

    @Query("DELETE FROM lyrics_fts")
    suspend fun clearFts()

    private suspend fun upsertFtsRow(lyrics: LyricsEntity) {
        if (lyrics.content.isBlank()) {
            deleteFtsRow(lyrics.songId)
        } else {
            upsertFtsRowRaw(lyrics.songId, lyrics.content)
        }
    }
}
