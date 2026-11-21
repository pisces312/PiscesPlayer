package com.pisces312.piscesplayer.data.musicquiz

import android.content.Context
import com.pisces312.piscesplayer.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Two-layer cache for the music-quiz library classification
 * ([MusicQuizLibraryClassifier]): an in-process snapshot plus a small on-disk
 * **per-track evidence** file, so cold starts skip reclassification too.
 *
 * The on-disk layer (format v2) stores the per-track [MusicQuizLibraryClassifier.TrackEvidence]
 * (classical / kids-tierA / kids-keyword) keyed by a digest over the classification-relevant
 * fields of each track — album art is excluded because it never affects flags, while any
 * metadata change that classification reads flips that track's digest and reclassifies
 * **only that track**. The kids decision has a library-wide gate (tierA sufficiency) that
 * is NOT a per-track pure function, so the cache stores evidence and recomputes the gate
 * per build (a cheap set operation) instead of caching final flags.
 *
 * In-process entries are keyed by the source song list itself (`ImmutableList` from
 * LibraryStateHolder): identity (`===`) is the fast path and structural equality keeps
 * re-created-but-unchanged lists cached.
 *
 * Computation is single-flight: concurrent callers share one build. [tracksFor]'s
 * `force` flag (the manual "refresh library" action) skips both cache layers and
 * recomputes all evidence (catalog updates may change flags without metadata changes).
 * The cache is derived data: the file lives in `cacheDir`, is written atomically, and
 * any parse failure falls back to full classification.
 */
@Singleton
class MusicQuizLibraryCache internal constructor(
    private val cacheFile: File,
    private val evidenceOf: (MusicQuizTrack) -> MusicQuizLibraryClassifier.TrackEvidence =
        MusicQuizLibraryClassifier::evidenceOf,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        cacheFile = File(context.cacheDir, CACHE_FILE_NAME),
    )

    private class Entry(val source: List<Song>, val tracks: List<MusicQuizTrack>)

    private val mutex = Mutex()

    @Volatile
    private var entry: Entry? = null

    /** In-memory evidence map (hex digest -> bits); written through to the disk file. */
    @Volatile
    private var evidenceBits: Map<String, Byte>? = null

    /**
     * Classified tracks for [songs]: in-memory snapshot -> on-disk per-track evidence ->
     * fresh classification of the missing tracks. [force] bypasses both cache layers.
     */
    internal suspend fun tracksFor(songs: List<Song>, force: Boolean = false): List<MusicQuizTrack> {
        if (!force) memoryHit(songs)?.let { return it }
        return mutex.withLock {
            if (!force) memoryHit(songs)?.let { return@withLock it }
            val tracks = buildTracks(songs, force)
            entry = Entry(songs, tracks)
            tracks
        }
    }

    /** Read-only check for [com.pisces312.piscesplayer.presentation.musicquiz.MusicQuizViewModel]
     *  to skip the classifying state flash when the snapshot is already current. */
    internal fun matchesCurrentSource(songs: List<Song>): Boolean {
        val current = entry ?: return false
        return current.source === songs || current.source == songs
    }

    private fun memoryHit(songs: List<Song>): List<MusicQuizTrack>? {
        val current = entry ?: return null
        if (current.source !== songs) {
            if (current.source != songs) return null
            entry = Entry(songs, current.tracks)
        }
        return current.tracks
    }

    private suspend fun buildTracks(songs: List<Song>, force: Boolean): List<MusicQuizTrack> =
        withContext(Dispatchers.Default) {
            val base = MusicQuizLibraryClassifier.base(songs)
            val cached = if (force) emptyMap() else evidenceMap()
            val computed = computeMissingEvidence(base, cached)
            val merged = if (computed.isEmpty()) {
                cached
            } else {
                val grown = HashMap(cached).apply { putAll(computed) }
                evidenceBits = grown
                withContext(Dispatchers.IO) { writeEvidence(grown) }
                grown
            }
            val evidence = base.map { track ->
                bitsToEvidence(merged.getValue(trackKey(track)))
            }
            MusicQuizLibraryClassifier.applyEvidence(base, evidence)
        }

    /** 只给 catalog 里没有证据的曲目算证据（新入库/元数据改动），按核数并行。 */
    private suspend fun computeMissingEvidence(
        base: List<MusicQuizTrack>,
        cached: Map<String, Byte>,
    ): Map<String, Byte> {
        val missing = base.mapIndexed { index, track -> index to trackKey(track) }
            .filter { (_, key) -> key !in cached }
        if (missing.isEmpty()) return emptyMap()

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val chunkSize = ceil(missing.size.toDouble() / cores).toInt().coerceAtLeast(1)
        return coroutineScope {
            missing.chunked(chunkSize)
                .map { chunk ->
                    async {
                        chunk.map { (index, key) -> key to evidenceToBits(evidenceOf(base[index])) }
                    }
                }
                .awaitAll()
                .flatten()
                .toMap()
        }
    }

    private suspend fun evidenceMap(): Map<String, Byte> {
        evidenceBits?.let { return it }
        val loaded = withContext(Dispatchers.IO) { readEvidence() }.orEmpty()
        evidenceBits = loaded
        return loaded
    }

    /** Digest sink; OutputStream.nullOutputStream() only exists from API 33. */
    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    /**
     * Per-track digest over exactly the fields classification consumes: id, title,
     * artist, album, genre, year, duration and playback uri. Length-prefixed (via
     * writeUTF/writeInt) so field content can never forge a different concatenation.
     */
    private fun trackKey(track: MusicQuizTrack): String {
        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        DataOutputStream(DigestOutputStream(NullOutputStream, digest)).use { out ->
            out.writeUTF(track.id)
            out.writeUTF(track.title)
            out.writeUTF(track.artist)
            out.writeUTF(track.album)
            out.writeUTF(track.genre.orEmpty())
            out.writeInt(track.year)
            out.writeLong(track.durationMs)
            out.writeUTF(track.playbackUri)
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun evidenceToBits(evidence: MusicQuizLibraryClassifier.TrackEvidence): Byte =
        ((if (evidence.isClassical) FLAG_CLASSICAL else 0) or
            (if (evidence.kidsTierA) FLAG_KIDS_TIER_A else 0) or
            (if (evidence.kidsKeyword) FLAG_KIDS_KEYWORD else 0)).toByte()

    private fun bitsToEvidence(bits: Byte): MusicQuizLibraryClassifier.TrackEvidence {
        val value = bits.toInt()
        return MusicQuizLibraryClassifier.TrackEvidence(
            isClassical = value and FLAG_CLASSICAL != 0,
            kidsTierA = value and FLAG_KIDS_TIER_A != 0,
            kidsKeyword = value and FLAG_KIDS_KEYWORD != 0,
        )
    }

    /**
     * Format v2: MAGIC, VERSION(2), entry count, then per entry a fixed-length digest
     * (SHA-256 = 32 bytes) plus one evidence bits byte. A v1 file (whole-library digest
     * + final flags) fails the version check and falls back to full classification.
     */
    private fun readEvidence(): Map<String, Byte>? = runCatching {
        if (!cacheFile.isFile) return@runCatching null
        DataInputStream(cacheFile.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) return@runCatching null
            if (input.readInt() != VERSION) return@runCatching null
            val count = input.readInt()
            if (count !in 0..MAX_TRACKS) return@runCatching null
            val entries = HashMap<String, Byte>(count)
            repeat(count) {
                val digest = ByteArray(DIGEST_BYTES)
                input.readFully(digest)
                entries[hexOf(digest)] = input.readByte()
            }
            entries
        }
    }.getOrNull()

    private fun writeEvidence(entries: Map<String, Byte>) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(entries.size)
                for ((key, bits) in entries) {
                    out.write(hexToBytes(key))
                    out.writeByte(bits.toInt())
                }
            }
            if (!temp.renameTo(cacheFile)) {
                temp.delete()
            }
        }.onFailure { error -> Timber.e(error, "Unable to persist music quiz library cache") }
    }

    private fun hexOf(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    private companion object {
        const val CACHE_FILE_NAME = "music_quiz_library_cache.bin"
        const val DIGEST_ALGORITHM = "SHA-256"
        const val DIGEST_BYTES = 32

        /** "MQC1" — bumped when the file layout changes. */
        const val MAGIC = 0x4D51_4331
        const val VERSION = 2

        const val FLAG_CLASSICAL = 0x01
        const val FLAG_KIDS_TIER_A = 0x02
        const val FLAG_KIDS_KEYWORD = 0x04

        /** Corruption guards for the on-disk reader. */
        const val MAX_TRACKS = 1_000_000
    }
}
