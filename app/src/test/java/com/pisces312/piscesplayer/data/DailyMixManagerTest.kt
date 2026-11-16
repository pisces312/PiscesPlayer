package com.pisces312.piscesplayer.data

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.pisces312.piscesplayer.data.database.EngagementDao
import com.pisces312.piscesplayer.data.database.SongEngagementEntity
import com.pisces312.piscesplayer.data.model.Song
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Covers the discovery-oriented [DailyMixManager.generateYourMix]:
 * - it must never contain songs already picked by [DailyMixManager.generateDailyMix];
 * - it must rank undiscovered (never played) songs above worn-out favorites;
 * - same-day generation must be deterministic (seeded shuffle + stable sort).
 */
class DailyMixManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var manager: DailyMixManager

    @BeforeEach
    fun setUp() {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns File(tempDir.toFile(), "files")
        manager = DailyMixManager(context, FakeEngagementDao(emptyList()))
    }

    private fun song(
        id: Long,
        artistId: Long = id,
        genre: String? = null,
        dateAdded: Long = 0L
    ) = Song(
        id = id.toString(),
        title = "Song $id",
        artist = "Artist $artistId",
        artistId = artistId,
        album = "Album $id",
        albumId = id,
        path = "/dev/null/$id.mp3",
        contentUriString = "content://song/$id",
        albumArtUriString = null,
        duration = 180_000L,
        genre = genre,
        dateAdded = dateAdded,
        mimeType = "audio/mpeg",
        bitrate = 320,
        sampleRate = 44_100
    )

    private fun engagement(songId: Long, playCount: Int) = SongEngagementEntity(
        songId = songId,
        playCount = playCount,
        totalPlayDurationMs = playCount * 180_000L,
        lastPlayedTimestamp = System.currentTimeMillis()
    )

    private fun managerWith(engagements: List<SongEngagementEntity>): DailyMixManager {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns File(tempDir.toFile(), "files")
        return DailyMixManager(context, FakeEngagementDao(engagements))
    }

    @Test
    fun `your mix excludes songs picked for the daily mix`() = runTest {
        // 40 songs, 30 of them heavily played. The daily mix (limit 30) gravitates
        // toward the played ones; whatever it picks must be absent from your mix.
        val played = (1L..30L).map { song(it, artistId = 1000 + it, genre = "Rock") }
        val fresh = (31L..40L).map { song(it, artistId = 2000 + it, genre = "Jazz") }
        val all = played + fresh
        val manager = managerWith(played.map { engagement(it.id.toLong(), playCount = 20) })

        val dailyMix = manager.generateDailyMix(all, limit = 30)
        val yourMix = manager.generateYourMix(
            allSongs = all,
            excludeSongIds = dailyMix.map { it.id }.toSet(),
            limit = 60
        )

        assertThat(dailyMix).isNotEmpty()
        assertThat(yourMix).isNotEmpty()
        val dailyIds = dailyMix.map { it.id }.toSet()
        assertThat(yourMix.map { it.id }.none { it in dailyIds }).isTrue()
    }

    @Test
    fun `discovery ranking prefers never played songs over worn out favorites`() = runTest {
        // Single genre + unique artists: isolates the discovery ordering from the
        // per-genre diversity quota (limit 5 caps the genre at 2 picks in the first
        // pass; the quota-free second pass fills the rest in candidate order).
        val played = (1L..5L).map { song(it, artistId = 1000 + it, genre = "Pop") }
        val unplayed = (6L..10L).map { song(it, artistId = 2000 + it, genre = "Pop") }
        val all = played + unplayed
        val manager = managerWith(played.map { engagement(it.id.toLong(), playCount = 50) })

        val yourMix = manager.generateYourMix(all, favoriteSongIds = played.map { it.id }.toSet(), limit = 5)

        assertThat(yourMix).hasSize(5)
        val unplayedIds = unplayed.map { it.id }.toSet()
        // Every pick must come from the never-played half: high affinity pushed the
        // worn-out favorites' discovery scores far below the untouched tracks.
        assertThat(yourMix.map { it.id }.all { it in unplayedIds }).isTrue()
    }

    @Test
    fun `same day generation is deterministic`() = runTest {
        val all = (1L..20L).map { song(it, artistId = 1000 + it) }
        val manager = managerWith((1L..20L).map { engagement(it, playCount = (it % 4).toInt()) })

        val first = manager.generateYourMix(all, limit = 10)
        val second = manager.generateYourMix(all, limit = 10)

        assertThat(first.map { it.id }).isEqualTo(second.map { it.id })
    }

    @Test
    fun `empty library returns empty mix`() = runTest {
        assertThat(manager.generateYourMix(emptyList())).isEmpty()
    }

    @Test
    fun `excluding every song returns empty mix`() = runTest {
        val all = (1L..5L).map { song(it) }
        val excludeIds = all.map { it.id }.toSet()
        assertThat(manager.generateYourMix(all, excludeSongIds = excludeIds)).isEmpty()
    }

    private class FakeEngagementDao(
        private val engagements: List<SongEngagementEntity>
    ) : EngagementDao {
        override suspend fun upsertEngagement(engagement: SongEngagementEntity) = Unit
        override suspend fun upsertEngagements(engagements: List<SongEngagementEntity>) = Unit
        override suspend fun getEngagement(songId: Long) =
            engagements.firstOrNull { it.songId == songId }

        override suspend fun getAllEngagements() = engagements
        override fun getAllEngagementsFlow(): Flow<List<SongEngagementEntity>> =
            kotlinx.coroutines.flow.flowOf(engagements)
        override suspend fun getPlayCount(songId: Long) =
            engagements.firstOrNull { it.songId == songId }?.playCount

        override suspend fun deleteEngagement(songId: Long) = Unit
        override suspend fun clearAllEngagements() = Unit
        override suspend fun recordPlay(songId: Long, durationMs: Long, timestamp: Long) = Unit
        override suspend fun getTopPlayedSongs(limit: Int) =
            engagements.sortedByDescending { it.playCount }.take(limit)

        override suspend fun getRecentlyPlayedSongs(limit: Int) =
            engagements.filter { it.lastPlayedTimestamp > 0 }
                .sortedByDescending { it.lastPlayedTimestamp }
                .take(limit)
    }
}
