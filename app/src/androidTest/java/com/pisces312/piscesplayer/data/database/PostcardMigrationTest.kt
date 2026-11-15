package com.pisces312.piscesplayer.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v16 -> v17 for sound_postcard (M0, design §10.3): six fresh tables, no data transform.
 * The migration SQL is copied verbatim from the exported 17.json, so this test mainly guards
 * against future edits of the migration drifting from the schema, and pins the uniqueness
 * invariants the settlement idempotency relies on (journey.attempt_id, card logical key,
 * visit (card_key, journey_id)).
 */
@RunWith(AndroidJUnit4::class)
class PostcardMigrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = PiscesPlayerDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrationSixteenToSeventeenCreatesPostcardTablesAndEnforcesUniqueness() {
        migrationHelper.createDatabase(DATABASE_NAME, 16).use { db ->
            // Pre-feature database: none of the postcard tables exist yet.
            for (table in POSTCARD_TABLES) {
                assertThat(tableExists(db, table)).isFalse()
            }
        }

        migrationHelper.runMigrationsAndValidate(
            name = DATABASE_NAME,
            version = 17,
            validateDroppedTables = true,
            MIGRATION_16_17,
        ).use { db ->
            for (table in POSTCARD_TABLES) {
                assertThat(tableExists(db, table)).isTrue()
            }

            // Insert the single profile row.
            db.execSQL(
                "INSERT INTO postcard_profile (id, traveler_name, world_version, total_distance_km, " +
                    "total_journeys, created_at_epoch_ms) VALUES (1, '旅者', 1, 0.0, 0, 1700000000000)"
            )

            // journey.attempt_id uniqueness: the settlement idempotency seam.
            db.execSQL(
                "INSERT INTO postcard_journey (journey_id, attempt_id, stable_song_key, completed_at_epoch_ms, " +
                    "completed_at_zone_id, distance_km, card_count, deliver_at_epoch_ms, status, created_at_epoch_ms) " +
                    "VALUES ('j1', 'a1', 'local:1', 1700000000000, 'Asia/Shanghai', 4.2, 2, 1700000000000, 'DELIVERED', 1700000000000)"
            )
            insertFailing(db) {
                "INSERT INTO postcard_journey (journey_id, attempt_id, stable_song_key, completed_at_epoch_ms, " +
                    "completed_at_zone_id, distance_km, card_count, deliver_at_epoch_ms, status, created_at_epoch_ms) " +
                    "VALUES ('j2', 'a1', 'local:1', 1700000000000, 'Asia/Shanghai', 4.2, 2, 1700000000000, 'DELIVERED', 1700000000000)"
            }.let { failed -> assertThat(failed).isTrue() }

            // Card logical unique key: same (song, spot, condition) cannot insert twice.
            db.execSQL(
                "INSERT INTO postcard_card (card_key, stable_song_key, library_song_id, song_title_snapshot, " +
                    "artist_snapshot, spot_id, region_id, condition_key, season_key, generator_version, spec_json, " +
                    "distance_km, first_visited_at_epoch_ms, first_visited_at_zone_id, last_visited_at_epoch_ms, visit_count) " +
                    "VALUES ('local:1\u001fold_town_01\u001fmorning_sunny', 'local:1', 1, 'T', 'A', 'old_town_01', 'old_town', " +
                    "'morning_sunny', 'autumn', 1, '{}', 4.2, 1700000000000, 'Asia/Shanghai', 1700000000000, 1)"
            )
            insertFailing(db) {
                "INSERT INTO postcard_card (card_key, stable_song_key, library_song_id, song_title_snapshot, " +
                    "artist_snapshot, spot_id, region_id, condition_key, season_key, generator_version, spec_json, " +
                    "distance_km, first_visited_at_epoch_ms, first_visited_at_zone_id, last_visited_at_epoch_ms, visit_count) " +
                    "VALUES ('dup', 'local:1', 1, 'T', 'A', 'old_town_01', 'old_town', " +
                    "'morning_sunny', 'autumn', 1, '{}', 4.2, 1700000000000, 'Asia/Shanghai', 1700000000000, 1)"
            }.let { failed -> assertThat(failed).isTrue() }

            // visit (card_key, journey_id) uniqueness.
            db.execSQL(
                "INSERT INTO postcard_visit (card_key, journey_id, visited_at_epoch_ms, time_of_day_key, " +
                    "weather_key, season_key, distance_km) VALUES " +
                    "('local:1\u001fold_town_01\u001fmorning_sunny', 'j1', 1700000000000, 'morning', 'sunny', 'autumn', 4.2)"
            )
            insertFailing(db) {
                "INSERT INTO postcard_visit (card_key, journey_id, visited_at_epoch_ms, time_of_day_key, " +
                    "weather_key, season_key, distance_km) VALUES " +
                    "('local:1\u001fold_town_01\u001fmorning_sunny', 'j1', 1700000000001, 'morning', 'sunny', 'autumn', 4.2)"
            }.let { failed -> assertThat(failed).isTrue() }
        }
    }

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Boolean {
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    /** Runs [sql] and returns true when it failed with a constraint violation. */
    private fun insertFailing(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        sql: () -> String,
    ): Boolean = try {
        db.execSQL(sql())
        false
    } catch (_: android.database.SQLException) {
        true
    }

    private companion object {
        const val DATABASE_NAME = "postcard-migration-test"
        val POSTCARD_TABLES = listOf(
            "postcard_profile",
            "postcard_attempt",
            "postcard_song_profile",
            "postcard_journey",
            "postcard_card",
            "postcard_visit",
        )
    }
}
