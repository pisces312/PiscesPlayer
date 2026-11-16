package com.pisces312.piscesplayer.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import timber.log.Timber
import java.io.File

/**
 * Builds a throwaway, readable copy of a database written by a **newer** build.
 *
 * The downgrade screen offers to let the user back in so they can save a backup before giving
 * up the data (see `DatabaseDowngradeScreen`). Backups are assembled by reading the library
 * through Room — playlists, favorites, lyrics, play stats — so "let the user export" and "let
 * Room open the file" are the same problem. Room refuses a newer schema outright, therefore the
 * only way in is a copy whose version fields agree with this build.
 *
 * Two header facts are rewritten on the copy, never on the original:
 * - `PRAGMA user_version` back to [DatabaseSchema.VERSION], so Room considers the file current;
 * - `room_master_table.identity_hash` to the hash this build derives from its own schema, so the
 *   integrity check that Room runs right after opening passes.
 *
 * A newer schema is expected to be a superset of this one (tables added, nothing removed or
 * retyped — that is the contract migrations keep), so the older code reads the columns it knows
 * and ignores the rest. Everything the user changes during a temporary session lands in this
 * copy and is discarded; the original file is never opened for writing, which is why this is
 * safe even if the assumption above is wrong.
 */
object DatabaseDowngradeSnapshot {

    /** File name inside the app's database directory; must stay distinct from the real one. */
    const val FILE_NAME = "pixelplayer_downgrade_copy"

    /** `room_master_table` always holds exactly one row under this id. */
    private const val ROOM_MASTER_ROW_ID = 42

    fun isReady(context: Context): Boolean = context.getDatabasePath(FILE_NAME).exists()

    /**
     * Copies the newer database and rewrites the two header values so this build can open it.
     * Returns false when no copy could be produced, in which case the caller must keep the
     * downgrade screen (an unreadable copy is worse than no copy: Room would crash on it).
     */
    fun prepare(context: Context): Boolean {
        val source = context.getDatabasePath(DatabaseSchema.FILE_NAME)
        if (!source.exists()) {
            Timber.w("No database file to copy for a temporary downgrade session")
            return false
        }
        val identityHash = readIdentityHashOfThisBuild(context) ?: return false
        val target = context.getDatabasePath(FILE_NAME)

        return try {
            // deleteDatabase also removes the -wal / -shm siblings of a previous attempt.
            context.deleteDatabase(FILE_NAME)
            source.copyTo(target, overwrite = true)
            copySibling(source, target, "-wal")
            copySibling(source, target, "-shm")

            SQLiteDatabase.openDatabase(
                target.path,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                // Fold any copied WAL back into the main file first: the patch below has to be
                // the newest write in the file, and the copy should stay a single self-contained
                // database that Room can adopt with its own journal settings.
                db.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() }

                val updated = db.compileStatement(
                    "UPDATE room_master_table SET identity_hash = ? WHERE id = $ROOM_MASTER_ROW_ID"
                ).use { statement ->
                    statement.bindString(1, identityHash)
                    statement.executeUpdateDelete()
                }
                if (updated != 1) {
                    Timber.w("Unexpected room_master_table shape in the downgrade copy (%d rows)", updated)
                    context.deleteDatabase(FILE_NAME)
                    return false
                }

                db.version = DatabaseSchema.VERSION
                db.rawQuery("PRAGMA user_version", null).use { cursor ->
                    val version = if (cursor.moveToFirst()) cursor.getInt(0) else -1
                    if (version != DatabaseSchema.VERSION) {
                        Timber.w("Downgrade copy still reports schema %d", version)
                        context.deleteDatabase(FILE_NAME)
                        return false
                    }
                }
            }
            if (!verifyReadable(context)) {
                context.deleteDatabase(FILE_NAME)
                return false
            }
            Timber.i("Prepared a temporary copy of the newer database for read-only browsing")
            true
        } catch (e: Exception) {
            Timber.w(e, "Could not prepare a temporary copy of the newer database")
            runCatching { context.deleteDatabase(FILE_NAME) }
            false
        }
    }

    fun deleteSnapshot(context: Context) {
        runCatching { context.deleteDatabase(FILE_NAME) }
    }

    /**
     * Opens the copy through Room exactly the way the app will at startup. Without this the user
     * would arm the session, the process would restart, and only then would Room reject the file
     * — from a background thread, where nothing can be caught.
     */
    private fun verifyReadable(context: Context): Boolean = try {
        val database = Room.databaseBuilder(
            context.applicationContext,
            PiscesPlayerDatabase::class.java,
            FILE_NAME
        ).build()
        try {
            database.openHelper.readableDatabase
                .query("SELECT count(*) FROM songs")
                .use { it.moveToFirst() }
        } finally {
            database.close()
        }
        true
    } catch (e: Exception) {
        Timber.w(e, "The temporary copy was rejected by Room; keeping the downgrade screen")
        false
    }

    private fun copySibling(source: File, target: File, suffix: String) {
        val from = File(source.path + suffix)
        if (!from.exists()) return
        from.copyTo(File(target.path + suffix), overwrite = true)
    }

    /**
     * Derives this build's schema identity hash by letting Room create it in memory: writing the
     * value down as a constant would silently rot the day a migration changes the schema, and a
     * stale hash makes Room reject the copy with "Room cannot verify the data integrity".
     */
    private fun readIdentityHashOfThisBuild(context: Context): String? = try {
        val database = Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            PiscesPlayerDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        try {
            database.openHelper.readableDatabase
                .query("SELECT identity_hash FROM room_master_table WHERE id = $ROOM_MASTER_ROW_ID")
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } finally {
            database.close()
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not derive this build's schema identity hash")
        null
    }
}
