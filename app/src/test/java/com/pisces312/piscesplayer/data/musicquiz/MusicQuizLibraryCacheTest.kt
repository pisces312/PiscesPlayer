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
        val evidenceCalls = java.util.concurrent.atomic.AtomicInteger()
        val cache = newCache { evidenceCalls.incrementAndGet(); NO_EVIDENCE }
        val songs = listOf(song("1"), song("2"))

        val first = cache.tracksFor(songs)
        val second = cache.tracksFor(songs)

        assertThat(evidenceCalls.get()).isEqualTo(2)
        assertThat(second === first).isTrue()
    }

    @Test
    fun `memory layer reuses the classification for an equal rebuilt list`() = runTest {
        val evidenceCalls = java.util.concurrent.atomic.AtomicInteger()
        val cache = newCache { evidenceCalls.incrementAndGet(); NO_EVIDENCE }

        cache.tracksFor(listOf(song("1")))
        val rebuilt = cache.tracksFor(listOf(song("1"))) // equal content, new instance

        assertThat(evidenceCalls.get()).isEqualTo(1)
        assertThat(rebuilt).hasSize(1)
    }

    @Test
    fun `reclassifies only tracks whose metadata changed`() = runTest {
        val evidenceCalls = java.util.concurrent.atomic.AtomicInteger()
        val cache = newCache { evidenceCalls.incrementAndGet(); NO_EVIDENCE }

        cache.tracksFor(listOf(song("1")))
        cache.tracksFor(listOf(song("1", title = "Renamed")))
        cache.tracksFor(listOf(song("1", title = "Renamed"), song("2")))

        assertThat(evidenceCalls.get()).isEqualTo(3)
    }

    @Test
    fun `adding songs classifies only the new tracks`() = runTest {
        val evidenceCalls = java.util.concurrent.atomic.AtomicInteger()
        val cache = newCache { evidenceCalls.incrementAndGet(); NO_EVIDENCE }
        val first = listOf(song("1"), song("2"))

        cache.tracksFor(first)
        cache.tracksFor(listOf(song("2"), song("1"))) // reordered: same evidence keys
        cache.tracksFor(listOf(song("1"), song("2"), song("3")))

        assertThat(evidenceCalls.get()).isEqualTo(3)
    }

    @Test
    fun `disk layer survives a new cache instance without reclassification`() = runTest {
        val evidenceCalls = java.util.concurrent.atomic.AtomicInteger()
        val evidence: (MusicQuizTrack) -> MusicQuizLibraryClassifier.TrackEvidence = { track ->
            evidenceCalls.incrementAndGet()
            when (track.id) {
                "1" -> EVIDENCE_CLASSICAL
                "2" -> EVIDENCE_KIDS
                else -> NO_EVIDENCE
            }
        }
        val songs = listOf(song("1"), song("2"))

        val first = newCache(evidence).tracksFor(songs)
        assertThat(evidenceCalls.get()).isEqualTo(2)

        val restored = newCache(evidence).tracksFor(songs)

        assertThat(evidenceCalls.get()).isEqualTo(2) // second instance restored from disk
        assertThat(restored.map { Triple(it.id, it.isClassical, it.isKidsSong) })
            .isEqualTo(first.map { Triple(it.id, it.isClassical, it.isKidsSong) })
        assertThat(restored.first { it.id == "1" }.isClassical).isTrue()
        assertThat(restored.first { it.id == "2" }.isKidsSong).isTrue()
    }

    @Test
    fun `disk layer reclassifies when metadata changes`() = runTest {
        val evidenceCalls = java.util.concurrent.atomic.AtomicInteger()
        val evidence: (MusicQuizTrack) -> MusicQuizLibraryClassifier.TrackEvidence = {
            evidenceCalls.incrementAndGet(); NO_EVIDENCE
        }
        newCache(evidence).tracksFor(listOf(song("1", title = "Original")))

        newCache(evidence).tracksFor(listOf(song("1", title = "Renamed")))

        assertThat(evidenceCalls.get()).isEqualTo(2)
    }

    @Test
    fun `force bypasses both cache layers`() = runTest {
        val evidenceCalls = java.util.concurrent.atomic.AtomicInteger()
        val cache = newCache { evidenceCalls.incrementAndGet(); NO_EVIDENCE }
        val songs = listOf(song("1"))

        cache.tracksFor(songs) // cold build
        cache.tracksFor(songs) // memory hit
        cache.tracksFor(songs, force = true) // forced rebuild

        assertThat(evidenceCalls.get()).isEqualTo(2)

        // The forced run refreshed the disk file, so a fresh instance still skips a build.
        val fresh = newCache { evidenceCalls.incrementAndGet(); NO_EVIDENCE }
        fresh.tracksFor(songs)

        assertThat(evidenceCalls.get()).isEqualTo(2)
    }

    @Test
    fun `a corrupt cache file falls back to classification`() = runTest {
        val evidenceCalls = java.util.concurrent.atomic.AtomicInteger()
        cacheFile.writeBytes(byteArrayOf(1, 2, 3))

        val tracks = newCache { evidenceCalls.incrementAndGet(); NO_EVIDENCE }.tracksFor(listOf(song("1")))

        assertThat(evidenceCalls.get()).isEqualTo(1)
        assertThat(tracks).hasSize(1)
    }

    @Test
    fun `concurrent callers share a single build`() = runTest {
        val evidenceCalls = java.util.concurrent.atomic.AtomicInteger()
        val cache = newCache { evidenceCalls.incrementAndGet(); NO_EVIDENCE }
        val songs = listOf(song("1"))

        val callers = List(4) {
            launch { cache.tracksFor(songs) }
        }
        callers.forEach { it.join() }

        assertThat(evidenceCalls.get()).isEqualTo(1)
    }

    @Test
    fun `kids gate is recomputed from restored evidence when the library grows`() = runTest {
        val evidenceCalls = java.util.concurrent.atomic.AtomicInteger()
        val evidence: (MusicQuizTrack) -> MusicQuizLibraryClassifier.TrackEvidence = { track ->
            evidenceCalls.incrementAndGet()
            when {
                track.id == "kw" -> EVIDENCE_KIDS_KEYWORD_ONLY
                track.id == "extra" -> NO_EVIDENCE
                else -> EVIDENCE_KIDS_TIER_A
            }
        }
        val tierAIds = (1..10).map { it.toString() }
        val initial = tierAIds.map { song(it) } + song("kw")

        // tierA pool (10 distinct titles) fills the pool by itself -> keyword tier excluded.
        val first = newCache(evidence).tracksFor(initial)
        assertThat(first.count { it.isKidsSong }).isEqualTo(10)
        assertThat(first.first { it.id == "kw" }.isKidsSong).isFalse()

        // New instance restores evidence from disk (no evidence calls) and the gate is
        // recomputed against the grown library: restored flags must not leak.
        val grown = initial + song("extra")
        val restored = newCache(evidence).tracksFor(grown)
        assertThat(evidenceCalls.get()).isEqualTo(12) // only "extra" needed new evidence
        assertThat(restored.count { it.isKidsSong }).isEqualTo(10)
        assertThat(restored.first { it.id == "kw" }.isKidsSong).isFalse()
        assertThat(restored.first { it.id == "extra" }.isKidsSong).isFalse()
    }

    @Test
    fun `keyword fallback fills the kids pool when tierA is small`() = runTest {
        val evidence: (MusicQuizTrack) -> MusicQuizLibraryClassifier.TrackEvidence = { track ->
            when (track.id) {
                "a" -> EVIDENCE_KIDS_TIER_A
                "b" -> EVIDENCE_KIDS_KEYWORD_ONLY
                else -> NO_EVIDENCE
            }
        }
        val tracks = newCache(evidence).tracksFor(listOf(song("a"), song("b")))

        assertThat(tracks.first { it.id == "a" }.isKidsSong).isTrue()
        assertThat(tracks.first { it.id == "b" }.isKidsSong).isTrue()
    }

    @Test
    fun `cached flags match a full classification pass`() = runTest {
        val realEvidence = MusicQuizLibraryClassifier::evidenceOf
        val songs = listOf(
            song("1", artist = "Mozart", title = "Symphony No. 40"),
            song("2", artist = "贝瓦儿歌", title = "小星星"),
            song("3", artist = "Some Pop Band", title = "Friday Night"),
        )

        val cached = newCache(realEvidence).tracksFor(songs)
        val direct = MusicQuizLibraryClassifier.classify(MusicQuizLibraryClassifier.base(songs))

        assertThat(cached.map { Triple(it.id, it.isClassical, it.isKidsSong) })
            .isEqualTo(direct.map { Triple(it.id, it.isClassical, it.isKidsSong) })
    }

    private fun newCache(
        evidenceOf: (MusicQuizTrack) -> MusicQuizLibraryClassifier.TrackEvidence,
    ): MusicQuizLibraryCache = MusicQuizLibraryCache(cacheFile, evidenceOf)

    private fun song(id: String, title: String = "Title $id", artist: String = "Artist"): Song =
        Song.emptySong().copy(
            id = id,
            title = title,
            artist = artist,
            duration = 200_000L,
            contentUriString = "content://media/$id",
        )

    private companion object {
        val NO_EVIDENCE = MusicQuizLibraryClassifier.TrackEvidence(
            isClassical = false,
            kidsTierA = false,
            kidsKeyword = false,
        )
        val EVIDENCE_CLASSICAL = NO_EVIDENCE.copy(isClassical = true)
        val EVIDENCE_KIDS = NO_EVIDENCE.copy(kidsTierA = true, kidsKeyword = true)
        val EVIDENCE_KIDS_TIER_A = NO_EVIDENCE.copy(kidsTierA = true)
        val EVIDENCE_KIDS_KEYWORD_ONLY = NO_EVIDENCE.copy(kidsKeyword = true)
    }
}

