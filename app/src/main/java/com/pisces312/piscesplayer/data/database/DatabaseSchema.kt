package com.pisces312.piscesplayer.data.database

/**
 * Compile-time constants describing the database this build expects on disk.
 *
 * `@Database(version = ...)` only accepts compile-time constants, while
 * [DatabaseVersionGuard] needs the very same number at runtime to compare against the version
 * stored in the file. Defining it once here is what keeps the declaration and the guard from
 * drifting apart — a silent drift would either skip a required migration or block a healthy
 * install.
 */
object DatabaseSchema {

    /** Schema version this build declares; `@Database(version = ...)` points here. */
    const val VERSION = 16

    /** Room database file name, also used to locate the file before Room opens it. */
    const val FILE_NAME = "pixelplayer_database"
}
