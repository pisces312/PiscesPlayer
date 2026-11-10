package com.pisces312.piscesplayer.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the schema-alignment migration MIGRATION_14_15 (and the 12→15 upgrade chain):
 * drop legacy song columns, snake_case renames, Long song_id rebuilds.
 *
 * `runMigrationsAndValidate` is the real gate — it checks hand-written rebuild DDL against
 * the exported v15 schema. A mismatch here is a startup crash for upgrading users.
 */
@RunWith(AndroidJUnit4::class)
class SchemaV15MigrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = PiscesPlayerDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrationFourteenToFifteenDropsLegacyColumnsAndRenamesSnakeCase() {
        migrationHelper.createDatabase(DATABASE_NAME, 14).apply {
            execSQL(
                "INSERT INTO songs (id, title, artist_name, artist_id, album_name, album_id, " +
                    "content_uri_string, duration, file_path, parent_directory_path, " +
                    "is_favorite, lyrics) " +
                    "VALUES (1, 'T', 'A', 1, 'Al', 1, 'content://1', 100, '/a/1.mp3', '/a', 1, 'embedded-body')"
            )
            execSQL(
                "INSERT INTO songs (id, title, artist_name, artist_id, album_name, album_id, " +
                    "content_uri_string, duration, file_path, parent_directory_path, " +
                    "is_favorite, lyrics) " +
                    "VALUES (2, 'T2', 'A', 1, 'Al', 1, 'content://2', 100, '/a/2.mp3', '/a', 0, 'other')"
            )
            execSQL("INSERT INTO favorites (songId, isFavorite, timestamp, rating) VALUES (1, 1, 11, 5)")
            // Pre-existing lyrics row must win over songs.lyrics backfill.
            execSQL("INSERT INTO lyrics (songId, content, isSynced, source) VALUES (2, 'manual', 1, 'manual')")
            execSQL(
                "INSERT INTO song_engagements (song_id, play_count, total_play_duration_ms, last_played_timestamp) " +
                    "VALUES ('1', 3, 3000, 111)"
            )
            execSQL(
                "INSERT INTO playlist_songs (playlist_id, song_id, sort_order) VALUES ('p1', '1', 0)"
            )
            execSQL(
                "INSERT INTO audio_bookmarks (id, song_id, song_title, artist_name, album_art_uri, title, timestamp_ms, created_time) " +
                    "VALUES (1, '1', 'T', 'A', NULL, 'mark', 50, 1)"
            )
            execSQL(
                "INSERT INTO offline_tracks (download_id, attempt_id, song_id, source_uri, provider, title, mime_type, " +
                    "local_path, state, bytes_downloaded, total_bytes, created_at, updated_at, error_message) " +
                    "VALUES ('d1', 'a1', '1', 'navidrome://1', 'navidrome', 'T', 'audio/mpeg', '/off/1', 'done', 1, 1, 1, 1, NULL)"
            )
            execSQL("INSERT INTO ai_cache (promptHash, responseJson, timestamp) VALUES ('h1', '{}', 1)")
            execSQL(
                "INSERT INTO ai_usage (id, timestamp, provider, model, promptType, promptTokens, outputTokens, thoughtTokens) " +
                    "VALUES (1, 1, 'p', 'm', 'mix', 10, 20, 0)"
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            name = DATABASE_NAME,
            version = 15,
            validateDroppedTables = true,
            MIGRATION_14_15,
        ).use { db ->
            assertThat(db.hasColumn("songs", "is_favorite")).isFalse()
            assertThat(db.hasColumn("songs", "lyrics")).isFalse()

            // songs.lyrics backfilled for the row that had no lyrics-table entry.
            assertThat(db.lyricsContent(1L)).isEqualTo("embedded-body")
            assertThat(db.lyricsSource(1L)).isEqualTo("embedded")
            // Existing manual lyrics row wins.
            assertThat(db.lyricsContent(2L)).isEqualTo("manual")

            // snake_case renames.
            assertThat(db.hasColumn("favorites", "song_id")).isTrue()
            assertThat(db.hasColumn("favorites", "is_favorite")).isTrue()
            assertThat(db.hasColumn("favorites", "songId")).isFalse()
            assertThat(db.hasColumn("lyrics", "song_id")).isTrue()
            assertThat(db.hasColumn("lyrics", "is_synced")).isTrue()
            assertThat(db.hasColumn("lyrics", "songId")).isFalse()
            assertThat(db.hasColumn("ai_cache", "prompt_hash")).isTrue()
            assertThat(db.hasColumn("ai_cache", "response_json")).isTrue()
            assertThat(db.hasColumn("ai_usage", "prompt_type")).isTrue()
            assertThat(db.hasColumn("ai_usage", "prompt_tokens")).isTrue()
            assertThat(db.hasColumn("ai_usage", "output_tokens")).isTrue()
            assertThat(db.hasColumn("ai_usage", "thought_tokens")).isTrue()

            // TEXT song_id rebuilt as INTEGER and data preserved.
            assertThat(db.columnType("song_engagements", "song_id")).isEqualTo("integer")
            assertThat(db.columnType("playlist_songs", "song_id")).isEqualTo("integer")
            assertThat(db.columnType("audio_bookmarks", "song_id")).isEqualTo("integer")
            assertThat(db.columnType("offline_tracks", "song_id")).isEqualTo("integer")
            db.query("SELECT song_id, play_count FROM song_engagements").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(1L)
                assertThat(c.getInt(1)).isEqualTo(3)
            }
            db.query("SELECT COUNT(*) FROM playlist_songs WHERE song_id = 1").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            db.query("SELECT COUNT(*) FROM audio_bookmarks WHERE song_id = 1").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            db.query("SELECT COUNT(*) FROM offline_tracks WHERE song_id = 1").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(1)
            }

            // Favorites row survives rename.
            db.query("SELECT is_favorite, rating FROM favorites WHERE song_id = 1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1)
                assertThat(c.getInt(1)).isEqualTo(5)
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrationTwelveToFifteenFullChainBackfillsThenAlignsSchema() {
        migrationHelper.createDatabase(DATABASE_NAME, 12).apply {
            execSQL(
                "INSERT INTO songs (id, title, artist_name, artist_id, album_name, album_id, " +
                    "content_uri_string, duration, file_path, parent_directory_path, lyrics) " +
                    "VALUES (1, 'T', 'A', 1, 'Al', 1, 'content://1', 100, '/a/1.mp3', '/a', 'from-embed')"
            )
            execSQL(
                "INSERT INTO songs (id, title, artist_name, artist_id, album_name, album_id, " +
                    "content_uri_string, duration, file_path, parent_directory_path) " +
                    "VALUES (2, 'T2', 'A', 1, 'Al', 1, 'content://2', 100, '/a/2.mp3', '/a')"
            )
            execSQL("INSERT INTO lyrics (songId, content, isSynced, source) VALUES (2, 'already', 1, 'manual')")
            execSQL("INSERT INTO favorites (songId, isFavorite, timestamp, rating) VALUES (1, 1, 1, 4)")
            execSQL(
                "INSERT INTO song_engagements (song_id, play_count, total_play_duration_ms, last_played_timestamp) " +
                    "VALUES ('2', 1, 100, 5)"
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            name = DATABASE_NAME,
            version = 15,
            validateDroppedTables = true,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
        ).use { db ->
            assertThat(db.hasColumn("songs", "is_favorite")).isFalse()
            assertThat(db.hasColumn("songs", "lyrics")).isFalse()

            // 12→13 backfill, then 14→15 rename must preserve content under snake_case.
            assertThat(db.lyricsContent(1L)).isEqualTo("from-embed")
            assertThat(db.lyricsSource(1L)).isEqualTo("embedded")
            assertThat(db.lyricsContent(2L)).isEqualTo("already")

            db.query("SELECT rating FROM favorites WHERE song_id = 1").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(4)
            }
            db.query("SELECT song_id, play_count FROM song_engagements").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(2L)
                assertThat(c.getInt(1)).isEqualTo(1)
            }
            assertThat(db.columnType("song_engagements", "song_id")).isEqualTo("integer")
        }
    }

    private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
        query("PRAGMA table_info(`$table`)").use { c ->
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (c.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    private fun SupportSQLiteDatabase.columnType(table: String, column: String): String {
        query("PRAGMA table_info(`$table`)").use { c ->
            val nameIndex = c.getColumnIndex("name")
            val typeIndex = c.getColumnIndex("type")
            while (c.moveToNext()) {
                if (c.getString(nameIndex) == column) {
                    return c.getString(typeIndex).lowercase()
                }
            }
        }
        error("column $table.$column not found")
    }

    private fun SupportSQLiteDatabase.lyricsContent(songId: Long): String? {
        return query("SELECT content FROM lyrics WHERE song_id = ?", arrayOf(songId)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun SupportSQLiteDatabase.lyricsSource(songId: Long): String? {
        return query("SELECT source FROM lyrics WHERE song_id = ?", arrayOf(songId)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private companion object {
        const val DATABASE_NAME = "schema-v15-migration-test"
    }
}
