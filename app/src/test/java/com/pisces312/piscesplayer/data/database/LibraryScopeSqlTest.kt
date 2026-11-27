package com.pisces312.piscesplayer.data.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the single source of truth for "which source types are hidden from library scope".
 *
 * Room needs compile-time constant SQL, so [SourceType.LIBRARY_HIDDEN_SQL_IDS] cannot be
 * derived from [SourceType.APP_PROVIDED] at runtime — these two assertions are what keeps them
 * honest. See design/piscesplayer/docs/library-visibility-scope.md.
 */
class LibraryScopeSqlTest {

    @Test
    fun libraryHiddenSqlIdsMatchesAppProvidedList() {
        assertEquals(
            "SourceType.LIBRARY_HIDDEN_SQL_IDS must list exactly SourceType.APP_PROVIDED, " +
                "in the same order — update both when adding a built-in pack",
            SourceType.APP_PROVIDED.joinToString(","),
            SourceType.LIBRARY_HIDDEN_SQL_IDS
        )
    }

    @Test
    fun musicDaoNeverHardcodesHiddenSourceIds() {
        // JVM unit tests run with the module directory (app/) as the working directory.
        val dao = File("src/main/java/com/pisces312/piscesplayer/data/database/MusicDao.kt")
        assertTrue("MusicDao.kt not found at ${dao.absolutePath}", dao.exists())
        assertFalse(
            "A library-scope query hardcodes the hidden source ids again; use " +
                "LIBRARY_WHERE / LIBRARY_PREDICATE instead of `source_type NOT IN (7)`",
            dao.readText().contains("source_type NOT IN (7)")
        )
    }
}
