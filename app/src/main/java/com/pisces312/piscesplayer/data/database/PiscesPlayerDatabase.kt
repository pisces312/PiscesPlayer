package com.pisces312.piscesplayer.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlbumArtThemeEntity::class,
        SearchHistoryEntity::class,
        SongEntity::class,
        SongSearchFtsEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        TransitionRuleEntity::class,
        SongArtistCrossRef::class,
        SongEngagementEntity::class,
        FavoritesEntity::class,
        LyricsEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        NavidromeSongEntity::class,
        NavidromePlaylistEntity::class,
        JellyfinSongEntity::class,
        JellyfinPlaylistEntity::class,
        ListenBrainzPendingListenEntity::class,
        AudioBookmarkEntity::class,
        OfflineTrackEntity::class,
        AiCacheEntity::class,
        AiUsageEntity::class
    ],
    version = 15,
    exportSchema = true
)
abstract class PiscesPlayerDatabase : RoomDatabase() {
    abstract fun albumArtThemeDao(): AlbumArtThemeDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun musicDao(): MusicDao
    abstract fun transitionDao(): TransitionDao
    abstract fun engagementDao(): EngagementDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun localPlaylistDao(): LocalPlaylistDao
    abstract fun navidromeDao(): NavidromeDao
    abstract fun jellyfinDao(): JellyfinDao
    abstract fun listenBrainzDao(): ListenBrainzDao
    abstract fun audioBookmarkDao(): AudioBookmarkDao
    abstract fun offlineTrackDao(): OfflineTrackDao
    abstract fun aiCacheDao(): AiCacheDao
    abstract fun aiUsageDao(): AiUsageDao

    companion object {
        private fun createSongsSearchVirtualTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                    CREATE VIRTUAL TABLE IF NOT EXISTS songs_fts
                    USING fts4(
                        title,
                        artist_name,
                        tokenize=unicode61
                    )
                """.trimIndent()
            )
        }

        fun installSongsSearchSyncTriggers(db: SupportSQLiteDatabase) {
            createSongsSearchVirtualTable(db)

            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_insert")
            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_update")
            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_delete")

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_insert
                    AFTER INSERT ON songs
                    BEGIN
                        INSERT INTO songs_fts(rowid, title, artist_name)
                        VALUES (NEW.id, NEW.title, NEW.artist_name);
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_update
                    AFTER UPDATE OF title, artist_name ON songs
                    WHEN OLD.title != NEW.title OR OLD.artist_name != NEW.artist_name
                    BEGIN
                        DELETE FROM songs_fts WHERE rowid = OLD.id;
                        INSERT INTO songs_fts(rowid, title, artist_name)
                        VALUES (NEW.id, NEW.title, NEW.artist_name);
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_delete
                    AFTER DELETE ON songs
                    BEGIN
                        DELETE FROM songs_fts WHERE rowid = OLD.id;
                    END
                """.trimIndent()
            )
        }

        /**
         * Installs FTS sync triggers.
         *
         * Called from `onOpen` (not only `onCreate`) so upgrades and Auto Backup restores — which
         * never fire `onCreate` — still end up with a complete trigger set. The installer is
         * idempotent (`DROP TRIGGER IF EXISTS` + `CREATE`), so running it on every open is safe
         * and keeps `songs_fts` recoverable after drift.
         */
        fun createRuntimeArtifactsCallback(): RoomDatabase.Callback {
            return object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    installSongsSearchSyncTriggers(db)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    installSongsSearchSyncTriggers(db)
                }
            }
        }
    }
}
