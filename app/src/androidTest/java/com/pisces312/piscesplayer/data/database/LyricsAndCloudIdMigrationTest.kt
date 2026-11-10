package com.pisces312.piscesplayer.data.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke coverage for additive/idempotent migrations (MIGRATION_12_13 and 13_14). */
@RunWith(AndroidJUnit4::class)
class LyricsAndCloudIdMigrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = PiscesPlayerDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrationTwelveToThirteenBackfillsEmbeddedLyrics() {
        migrationHelper.createDatabase(DATABASE_NAME, 12).apply {
            execSQL(
                "INSERT INTO songs (id, title, artist_name, artist_id, album_name, album_id, " +
                    "content_uri_string, duration, file_path, parent_directory_path, lyrics) " +
                    "VALUES (1, 'T', 'A', 1, 'Al', 1, 'content://1', 100, '/a/1.mp3', '/a', 'hello')"
            )
            execSQL(
                "INSERT INTO songs (id, title, artist_name, artist_id, album_name, album_id, " +
                    "content_uri_string, duration, file_path, parent_directory_path, lyrics) " +
                    "VALUES (2, 'T2', 'A', 1, 'Al', 1, 'content://2', 100, '/a/2.mp3', '/a', 'world')"
            )
            execSQL("INSERT INTO lyrics (songId, content, isSynced, source) VALUES (2, 'manual', 1, 'manual')")
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            name = DATABASE_NAME,
            version = 13,
            validateDroppedTables = true,
            MIGRATION_12_13,
        ).use { db ->
            assertThat(lyricsContent(db, 1L)).isEqualTo("hello")
            assertThat(lyricsSource(db, 1L)).isEqualTo("embedded")
            // Existing manual row must win.
            assertThat(lyricsContent(db, 2L)).isEqualTo("manual")
            assertThat(lyricsSource(db, 2L)).isEqualTo("manual")
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationThirteenToFourteenRewritesNavidromeSongId() {
        migrationHelper.createDatabase(DATABASE_NAME, 13).apply {
            execSQL(
                "INSERT INTO songs (id, title, artist_name, artist_id, album_name, album_id, " +
                    "content_uri_string, duration, file_path, parent_directory_path, source_type) " +
                    "VALUES (-9000000000001, 'Cloud', 'Artist', -11000000000001, 'Album', " +
                    "-10000000000001, 'navidrome://ext-1', 100, '/srv/1', '/srv', 5)"
            )
            execSQL("INSERT INTO favorites (songId, isFavorite, timestamp, rating) VALUES (-9000000000001, 1, 1, 5)")
            close()
        }

        // Validate at v15, not v14: 13.json still has songs.artist_id FK SET NULL while 14.json
        // expects NO ACTION, and MIGRATION_13_14 only rewrites ids. MIGRATION_14_15 rebuilds
        // songs and realigns the constraint — that is the schema users actually open.
        migrationHelper.runMigrationsAndValidate(
            name = DATABASE_NAME,
            version = 15,
            validateDroppedTables = true,
            MIGRATION_13_14,
            MIGRATION_14_15,
        ).use { db ->
            val newId = CloudUnifiedIds.unifiedSongId(9_000_000_000_000L, "ext-1")
            db.query("SELECT COUNT(*) FROM songs WHERE id = ?", arrayOf(newId)).use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            db.query("SELECT COUNT(*) FROM favorites WHERE song_id = ?", arrayOf(newId)).use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
        }
    }

    private fun lyricsContent(db: SupportSQLiteDatabase, songId: Long): String? {
        return db.query("SELECT content FROM lyrics WHERE songId = ?", arrayOf(songId)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun lyricsSource(db: SupportSQLiteDatabase, songId: Long): String? {
        return db.query("SELECT source FROM lyrics WHERE songId = ?", arrayOf(songId)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private companion object {
        const val DATABASE_NAME = "lyrics-cloud-id-migration-test"
    }
}
