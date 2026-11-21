package com.pisces312.piscesplayer.data

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.pisces312.piscesplayer.data.database.EngagementDao
import com.pisces312.piscesplayer.data.database.SongEngagementEntity
import com.pisces312.piscesplayer.data.model.MixKind
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
 * Covers the merged daily mix [DailyMixManager.generateDailyMix]:
 * - one list without duplicates, split into familiar (2/3) and discovery (1/3) buckets;
 * - discovery picks must rank undiscovered (never played) songs above worn-out favorites;
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
    fun `merged mix has no duplicates and splits familiar and discovery buckets`() = runTest {
        // 40 songs, 30 of them heavily played. The familiar bucket (20) gravitates toward
        // the played ones; the discovery bucket (10) fills from the remaining candidates.
        val played = (1L..30L).map { song(it, artistId = 1000 + it, genre = "Rock") }
        val fresh = (31L..40L).map { song(it, artistId = 2000 + it, genre = "Jazz") }
        val all = played + fresh
        val manager = managerWith(played.map { engagement(it.id.toLong(), playCount = 20) })

        val mix = manager.generateDailyMix(all, limit = 30)

        assertThat(mix).hasSize(30)
        assertThat(mix.map { it.song.id }.distinct()).hasSize(30)
        assertThat(mix.count { it.kind == MixKind.FAMILIAR }).isEqualTo(20)
        assertThat(mix.count { it.kind == MixKind.DISCOVERY }).isEqualTo(10)
    }

    @Test
    fun `discovery picks prefer never played songs over worn out favorites`() = runTest {
        // 5 worn-out favorites + 15 untouched tracks. The familiar bucket (10) absorbs the
        // favorites first; whatever lands in the discovery bucket must come from the
        // never-played half: high affinity pushes the favorites' discovery scores far
        // below the untouched tracks.
        val played = (1L..5L).map { song(it, artistId = 1000 + it, genre = "Pop") }
        val unplayed = (6L..20L).map { song(it, artistId = 2000 + it, genre = "Pop") }
        val all = played + unplayed
        val manager = managerWith(played.map { engagement(it.id.toLong(), playCount = 50) })

        val mix = manager.generateDailyMix(
            allSongs = all,
            favoriteSongIds = played.map { it.id }.toSet(),
            limit = 15
        )

        val discovery = mix.filter { it.kind == MixKind.DISCOVERY }
        assertThat(discovery).isNotEmpty()
        val unplayedIds = unplayed.map { it.id }.toSet()
        assertThat(discovery.map { it.song.id }.all { it in unplayedIds }).isTrue()
    }

    @Test
    fun `same day generation is deterministic`() = runTest {
        val all = (1L..20L).map { song(it, artistId = 1000 + it) }
        val manager = managerWith((1L..20L).map { engagement(it, playCount = (it % 4).toInt()) })

        val first = manager.generateDailyMix(all, limit = 10)
        val second = manager.generateDailyMix(all, limit = 10)

        assertThat(first.map { it.song.id }).isEqualTo(second.map { it.song.id })
    }

    @Test
    fun `empty library returns empty mix`() = runTest {
        assertThat(manager.generateDailyMix(emptyList())).isEmpty()
    }

    @Test
    fun `small library returns every song once`() = runTest {
        val all = (1L..5L).map { song(it) }

        val mix = manager.generateDailyMix(all, limit = 30)

        assertThat(mix.map { it.song.id }).containsExactlyElementsIn(all.map { it.id })
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
        override suspend fun deleteEngagementsAtOrAbove(minSongId: Long) = Unit
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
