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

    private const val PREFS_NAME = "db_downgrade_guard"
    private const val KEY_TEMPORARY_SESSION = "temporary_session"

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

    /**
     * True while the user browses a copy of the newer database instead of being blocked
     * (see [DatabaseDowngradeSnapshot]). Resolved once per process in [inspect] — the flag alone
     * is not enough, the copy has to be present too, otherwise Room would be pointed at a file
     * this build cannot open and the process would die again.
     */
    @Volatile
    var isTemporarySession = false
        private set

    /** Reads the on-disk version once and caches the verdict for the process lifetime. */
    fun inspect(context: Context): State {
        val appVersion = DatabaseSchema.VERSION
        val onDiskVersion = readOnDiskVersion(context)
        val result = State(onDiskVersion = onDiskVersion, appVersion = appVersion)
        state = result
        isTemporarySession = result.isBlocked &&
            temporarySessionRequested(context) &&
            DatabaseDowngradeSnapshot.isReady(context)
        if (result.isBlocked && !isTemporarySession) {
            Timber.e(
                "Database schema %d on disk is newer than this build's %d",
                onDiskVersion,
                appVersion
            )
        }
        return result
    }

    /**
     * Arms the temporary session. The caller must restart the process afterwards: Hilt has
     * already handed out a database instance (an in-memory one) and [state] is cached.
     */
    fun requestTemporarySession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TEMPORARY_SESSION, true)
            .commit()
    }

    fun clearTemporarySession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TEMPORARY_SESSION, false)
            .commit()
        isTemporarySession = false
    }

    fun temporarySessionRequested(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TEMPORARY_SESSION, false)

    /**
     * Deletes the database (plus its `-wal` / `-shm` siblings) so the next launch recreates an
     * empty one. Only reachable from the downgrade screen, after an explicit confirmation.
     */
    fun deleteDatabase(context: Context): Boolean {
        // The temporary copy belongs to the same decision; leaving it behind would make the
        // next launch believe a session is still available.
        DatabaseDowngradeSnapshot.deleteSnapshot(context)
        clearTemporarySession(context)
        return context.deleteDatabase(DatabaseSchema.FILE_NAME)
    }

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
