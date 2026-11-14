package com.pisces312.piscesplayer.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Fts4(tokenizer = "unicode61")
@Entity(tableName = "songs_fts")
data class SongSearchFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist_name")
    val artistName: String
)

/**
 * FTS index over `lyrics.content`, keyed by `lyrics.song_id`, powering lyrics-content
 * search (`SearchMode.LYRICS`).
 *
 * Unlike `songs_fts` (kept in sync by triggers), this table is maintained in the
 * `LyricsDao` chokepoint so every writer — repository, `.lrc` bulk scan, backup restore —
 * keeps the index consistent, and empty lyrics are removed from the index.
 */
@Fts4(tokenizer = "unicode61")
@Entity(tableName = "lyrics_fts")
data class LyricsSearchFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "content")
    val content: String
)
