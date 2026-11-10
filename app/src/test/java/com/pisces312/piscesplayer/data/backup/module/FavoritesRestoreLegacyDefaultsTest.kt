package com.pisces312.piscesplayer.data.backup.module

import com.google.gson.GsonBuilder
import com.pisces312.piscesplayer.data.database.FavoritesDao
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesRestoreLegacyDefaultsTest {

    private val favoritesDao: FavoritesDao = mockk(relaxed = true)
    private val musicDao: com.pisces312.piscesplayer.data.database.MusicDao = mockk(relaxed = true)
    private val handler = FavoritesModuleHandler(
        favoritesDao = favoritesDao,
        musicDao = musicDao,
        gson = GsonBuilder().serializeNulls().create()
    )

    @Test
    fun `missing isFavorite defaults to true`() = runTest {
        val payload = """
            [
              {"song_id": 42, "added_at": 1700000000000, "rating": 5}
            ]
        """.trimIndent()

        handler.restore(payload)

        coVerify(exactly = 1) {
            favoritesDao.insertAll(match { list ->
                list.size == 1 &&
                    list[0].songId == 42L &&
                    list[0].isFavorite &&
                    list[0].timestamp == 1700000000000L &&
                    list[0].rating == 5
            })
        }
    }

    @Test
    fun `explicit isFavorite false is preserved`() = runTest {
        val payload = """
            [
              {"songId": 7, "isFavorite": false, "timestamp": 1, "rating": 0}
            ]
        """.trimIndent()

        handler.restore(payload)

        coVerify(exactly = 1) {
            favoritesDao.insertAll(match { list ->
                list.size == 1 && !list[0].isFavorite && list[0].songId == 7L
            })
        }
    }

    @Test
    fun `rating missing defaults to zero`() = runTest {
        val payload = """
            [
              {"songId": 9, "isFavorite": true, "timestamp": 2}
            ]
        """.trimIndent()

        handler.restore(payload)

        coVerify(exactly = 1) {
            favoritesDao.insertAll(match { list ->
                list.size == 1 && list[0].rating == 0 && list[0].songId == 9L
            })
        }
    }

    @Test
    fun `snake_case and camelCase both accepted`() = runTest {
        val payload = """
            [
              {"song_id": 1, "is_favorite": true, "added_at": 10},
              {"songId": 2, "isFavorite": true, "timestamp": 20}
            ]
        """.trimIndent()

        handler.restore(payload)

        coVerify(exactly = 1) {
            favoritesDao.insertAll(match { list ->
                list.map { it.songId } == listOf(1L, 2L) &&
                    list.all { it.isFavorite }
            })
        }
    }

    @Test
    fun `cloud negative songId is accepted`() = runTest {
        val payload = """
            [
              {"songId": -123456789, "isFavorite": true, "timestamp": 3, "rating": 1}
            ]
        """.trimIndent()

        handler.restore(payload)

        coVerify(exactly = 1) {
            favoritesDao.insertAll(match { list ->
                list.size == 1 && list[0].songId == -123456789L && list[0].rating == 1
            })
        }
    }
}
