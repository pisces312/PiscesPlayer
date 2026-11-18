package com.pisces312.piscesplayer.data.musicquiz

import com.google.common.truth.Truth.assertThat
import com.pisces312.piscesplayer.data.model.Song
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MusicQuizLibraryCacheTest {

    @TempDir
    lateinit var directory: File

    private val cacheFile: File
        get() = File(directory, "quiz_cache.bin")

    @Test
    fun `memory layer reuses the classification for the same source instance`() = runTest {
        var builds = 0
        val cache = newCache { builds++; it }
        val songs = listOf(song("1"), song("2"))

        val first = cache.tracksFor(songs)
        val second = cache.tracksFor(songs)

        assertThat(builds).isEqualTo(1)
        assertThat(second === first).isTrue()
    }

    @Test
    fun `memory layer reuses the classification for an equal rebuilt list`() = runTest {
        var builds = 0
        val cache = newCache { builds++; it }

        cache.tracksFor(listOf(song("1")))
        val rebuilt = cache.tracksFor(listOf(song("1"))) // equal content, new instance

        assertThat(builds).isEqualTo(1)
        assertThat(rebuilt).hasSize(1)
    }

    @Test
    fun `reclassifies when the library changes`() = runTest {
        var builds = 0
        val cache = newCache { builds++; it }

        cache.tracksFor(listOf(song("1")))
        cache.tracksFor(listOf(song("1", title = "Renamed")))
        cache.tracksFor(listOf(song("1"), song("2")))

        assertThat(builds).isEqualTo(3)
    }

    @Test
    fun `disk layer survives a new cache instance without reclassification`() = runTest {
        var builds = 0
        val flags: (List<MusicQuizTrack>) -> List<MusicQuizTrack> = { tracks ->
            tracks.map { it.copy(isClassical = it.id == "1", isKidsSong = it.id == "2") }
        }
        val songs = listOf(song("1"), song("2"))

        val first = newCache { builds++; flags(it) }.tracksFor(songs)
        assertThat(builds).isEqualTo(1)

        val restored = newCache { builds++; flags(it) }.tracksFor(songs)

        assertThat(builds).isEqualTo(1) // second instance restored from disk
        assertThat(restored.map { Triple(it.id, it.isClassical, it.isKidsSong) })
            .isEqualTo(first.map { Triple(it.id, it.isClassical, it.isKidsSong) })
    }

    @Test
    fun `disk layer reclassifies when metadata changes`() = runTest {
        var builds = 0
        val cacheA = newCache { builds++; it }
        cacheA.tracksFor(listOf(song("1", title = "Original")))

        val cacheB = newCache { builds++; it }
        cacheB.tracksFor(listOf(song("1", title = "Renamed")))

        assertThat(builds).isEqualTo(2)
    }

    @Test
    fun `force bypasses both cache layers`() = runTest {
        var builds = 0
        val cache = newCache { builds++; it }
        val songs = listOf(song("1"))

        cache.tracksFor(songs) // cold build
        cache.tracksFor(songs) // memory hit
        cache.tracksFor(songs, force = true) // forced rebuild

        assertThat(builds).isEqualTo(2)

        // The forced run refreshed the disk file, so a fresh instance still skips a build.
        val fresh = newCache { builds++; it }
        fresh.tracksFor(songs)

        assertThat(builds).isEqualTo(2)
    }

    @Test
    fun `a corrupt cache file falls back to classification`() = runTest {
        var builds = 0
        cacheFile.writeBytes(byteArrayOf(1, 2, 3))

        val tracks = newCache { builds++; it }.tracksFor(listOf(song("1")))

        assertThat(builds).isEqualTo(1)
        assertThat(tracks).hasSize(1)
    }

    @Test
    fun `concurrent callers share a single build`() = runTest {
        var builds = 0
        val cache = newCache { builds++; it }
        val songs = listOf(song("1"))

        val callers = List(4) {
            launch { cache.tracksFor(songs) }
        }
        callers.forEach { it.join() }

        assertThat(builds).isEqualTo(1)
    }

    private fun newCache(
        classify: (List<MusicQuizTrack>) -> List<MusicQuizTrack>,
    ): MusicQuizLibraryCache = MusicQuizLibraryCache(cacheFile, classify)

    private fun song(id: String, title: String = "Title $id"): Song =
        Song.emptySong().copy(
            id = id,
            title = title,
            duration = 200_000L,
            contentUriString = "content://media/$id",
        )
}
