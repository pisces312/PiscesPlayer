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

@RunWith(AndroidJUnit4::class)
class PlaylistMigrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = PiscesPlayerDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrationFromFiveToSixPreservesRowsAndAllowsRepeatedSongs() {
        migrationHelper.createDatabase(DATABASE_NAME, 5).apply {
            execSQL(
                "INSERT INTO playlist_songs (playlist_id, song_id, sort_order) VALUES " +
                    "('playlist-1', 'intro', 0), " +
                    "('playlist-1', 'chorus', 1), " +
                    "('playlist-1', 'outro', 2)"
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            name = DATABASE_NAME,
            version = 6,
            validateDroppedTables = true,
            MIGRATION_5_6,
        ).use { database ->
            database.execSQL(
                "INSERT INTO playlist_songs (playlist_id, song_id, sort_order) " +
                    "VALUES ('playlist-1', 'chorus', 3)"
            )

            assertThat(database.playlistSongIds("playlist-1"))
                .containsExactly("intro", "chorus", "outro", "chorus")
                .inOrder()
        }
    }

    /**
     * v11 adds `ai_thinking`. A playlist generated before it existed keeps its row and simply has no
     * thought process, which is what both the result phase and the prompt details dialog key off.
     */
    @Test
    @Throws(IOException::class)
    fun migrationFromTenToElevenAddsThinkingAndLeavesExistingRowsAlone() {
        migrationHelper.createDatabase(DATABASE_NAME, 10).apply {
            execSQL(
                "INSERT INTO playlists " +
                    "(id, name, created_at, last_modified, is_queue_generated, source, ai_prompt) " +
                    "VALUES ('playlist-1', 'AI Mix', 1, 1, 0, 'AI', 'rainy evening')"
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            name = DATABASE_NAME,
            version = 11,
            validateDroppedTables = true,
            MIGRATION_10_11,
        ).use { database ->
            assertThat(database.playlistText("playlist-1", "ai_prompt")).isEqualTo("rainy evening")
            assertThat(database.playlistText("playlist-1", "ai_thinking")).isNull()

            database.execSQL(
                "UPDATE playlists SET ai_thinking = 'weighed the mood' WHERE id = 'playlist-1'"
            )

            assertThat(database.playlistText("playlist-1", "ai_thinking"))
                .isEqualTo("weighed the mood")
        }
    }

    private fun SupportSQLiteDatabase.playlistText(playlistId: String, column: String): String? {
        return query(
            "SELECT $column FROM playlists WHERE id = ? LIMIT 1",
            arrayOf(playlistId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun SupportSQLiteDatabase.playlistSongIds(playlistId: String): List<String> {
        return query(
            "SELECT song_id FROM playlist_songs WHERE playlist_id = ? ORDER BY sort_order",
            arrayOf(playlistId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "playlist-migration-test"
    }
}
