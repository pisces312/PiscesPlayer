package com.pisces312.piscesplayer.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import timber.log.Timber

/**
 * Compares the schema version stored in the database file against the version this build
 * declares — **before** Room opens it.
 *
 * Room treats a higher on-disk version as a downgrade and throws
 * `IllegalStateException("A migration from X to Y was required but not found")` from
 * `SQLiteOpenHelper.onDowngrade`. There is no hook between that throw and process death: the
 * exception surfaces on whichever thread first touches the database, and at startup that is a
 * background dispatcher, so the app dies right after the first frame paints and the user only
 * sees a flash back to the launcher.
 *
 * This happened in practice — a `main` build (schema 16) installed over a device that had run a
 * `feature/sound-postcard` build (schema 17). Because no catchable point exists, the only safe
 * move is to inspect the file first and refuse to bootstrap when it belongs to a newer schema;
 * the UI then explains the situation instead of crashing.
 *
 * Failure to read the file is *not* treated as a downgrade: a guess-based block would be worse
 * than letting Room raise its own (equally fatal, but truthful) error.
 */
object DatabaseVersionGuard {

    /**
     * @param onDiskVersion schema version found in the database file, or 0 when the file does
     *   not exist yet or could not be read.
     * @param appVersion schema version declared by this build.
     */
    data class State(val onDiskVersion: Int, val appVersion: Int) {
        val isBlocked: Boolean get() = onDiskVersion > appVersion
    }

    @Volatile
    var state: State? = null
        private set

    val isBlocked: Boolean get() = state?.isBlocked == true

    /** Reads the on-disk version once and caches the verdict for the process lifetime. */
    fun inspect(context: Context): State {
        val appVersion = DatabaseSchema.VERSION
        val onDiskVersion = readOnDiskVersion(context)
        val result = State(onDiskVersion = onDiskVersion, appVersion = appVersion)
        state = result
        if (result.isBlocked) {
            Timber.e(
                "Database schema %d on disk is newer than this build's %d",
                onDiskVersion,
                appVersion
            )
        }
        return result
    }

    /**
     * Deletes the database (plus its `-wal` / `-shm` siblings) so the next launch recreates an
     * empty one. Only reachable from the downgrade screen, after an explicit confirmation.
     */
    fun deleteDatabase(context: Context): Boolean =
        context.deleteDatabase(DatabaseSchema.FILE_NAME)

    private fun readOnDiskVersion(context: Context): Int {
        val file = context.getDatabasePath(DatabaseSchema.FILE_NAME)
        if (!file.exists()) return 0
        return try {
            // Read-only open never triggers Room and never rewrites the file; it only reports
            // the `user_version` header field.
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
                it.version
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not read the on-disk database version")
            0
        }
    }
}
