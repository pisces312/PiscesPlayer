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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Two-layer cache for the music-quiz library classification
 * ([MusicQuizLibraryClassifier]): an in-process snapshot plus a small on-disk flags
 * file, so cold starts skip reclassification too.
 *
 * In-process entries are keyed by the source song list itself (`ImmutableList` from
 * LibraryStateHolder): identity (`===`) is the fast path and structural equality keeps
 * re-created-but-unchanged lists cached. The on-disk layer stores only the derived
 * flags (`isClassical` / `isKidsSong`) keyed by a digest over the classification-relevant
 * fields of the eligible tracks — album art is excluded because it never affects flags,
 * while any metadata change that classification reads flips the digest and triggers a
 * rebuild. The digest is the correctness anchor; MediaStore-driven library updates flow
 * into `allSongs` and are picked up automatically.
 *
 * Computation is single-flight: concurrent callers share one build. [tracksFor]'s
 * `force` flag (the manual "refresh library" action) skips both layers and recomputes.
 * The cache is derived data: the file lives in `cacheDir`, is written atomically, and
 * any parse failure falls back to reclassification.
 */
@Singleton
class MusicQuizLibraryCache internal constructor(
    private val cacheFile: File,
    private val classify: (List<MusicQuizTrack>) -> List<MusicQuizTrack> = MusicQuizLibraryClassifier::classify,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        cacheFile = File(context.cacheDir, CACHE_FILE_NAME),
    )

    private class Entry(val source: List<Song>, val tracks: List<MusicQuizTrack>)

    private class TrackFlags(val isClassical: Boolean, val isKidsSong: Boolean)

    private val mutex = Mutex()

    @Volatile
    private var entry: Entry? = null

    /**
     * Classified tracks for [songs]: in-memory snapshot -> on-disk flags -> fresh
     * classification. [force] bypasses both cache layers and recomputes.
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
            val digest = digestOf(base)
            if (!force) {
                val restored = withContext(Dispatchers.IO) { readFlags(digest) }
                    ?.let { applyFlags(base, it) }
                if (restored != null) return@withContext restored
            }
            val tracks = classify(base)
            withContext(Dispatchers.IO) { writeFlags(digest, tracks) }
            tracks
        }

    private fun applyFlags(
        base: List<MusicQuizTrack>,
        flags: Map<String, TrackFlags>,
    ): List<MusicQuizTrack>? {
        if (flags.size != base.size) return null
        return base.map { track ->
            val flag = flags[track.id] ?: return null
            track.copy(isClassical = flag.isClassical, isKidsSong = flag.isKidsSong)
        }
    }

    /**
     * Digest over exactly the fields classification consumes: id, title, artist, album,
     * genre, year, duration and playback uri. Length-prefixed (via writeUTF/writeInt) so
     * field content can never forge a different concatenation.
     */
    private fun digestOf(tracks: List<MusicQuizTrack>): ByteArray {
        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use { out ->
            out.writeInt(tracks.size)
            for (track in tracks) {
                out.writeUTF(track.id)
                out.writeUTF(track.title)
                out.writeUTF(track.artist)
                out.writeUTF(track.album)
                out.writeUTF(track.genre.orEmpty())
                out.writeInt(track.year)
                out.writeLong(track.durationMs)
                out.writeUTF(track.playbackUri)
            }
        }
        return digest.digest()
    }

    private fun readFlags(expectedDigest: ByteArray): Map<String, TrackFlags>? = runCatching {
        if (!cacheFile.isFile) return@runCatching null
        DataInputStream(cacheFile.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) return@runCatching null
            if (input.readInt() != VERSION) return@runCatching null
            val storedDigest = ByteArray(input.readInt().coerceIn(0, MAX_DIGEST_BYTES))
                .also { input.readFully(it) }
            if (!MessageDigest.isEqual(storedDigest, expectedDigest)) return@runCatching null
            val count = input.readInt()
            if (count !in 0..MAX_TRACKS) return@runCatching null
            val flags = HashMap<String, TrackFlags>(count)
            repeat(count) {
                val id = input.readUTF()
                val bits = input.readByte().toInt()
                flags[id] = TrackFlags(
                    isClassical = bits and FLAG_CLASSICAL != 0,
                    isKidsSong = bits and FLAG_KIDS != 0,
                )
            }
            flags
        }
    }.getOrNull()

    private fun writeFlags(digest: ByteArray, tracks: List<MusicQuizTrack>) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(digest.size)
                out.write(digest)
                out.writeInt(tracks.size)
                for (track in tracks) {
                    out.writeUTF(track.id)
                    val bits = (if (track.isClassical) FLAG_CLASSICAL else 0) or
                        (if (track.isKidsSong) FLAG_KIDS else 0)
                    out.writeByte(bits)
                }
            }
            if (!temp.renameTo(cacheFile)) {
                temp.delete()
            }
        }.onFailure { error -> Timber.e(error, "Unable to persist music quiz library cache") }
    }

    private companion object {
        const val CACHE_FILE_NAME = "music_quiz_library_cache.bin"
        const val DIGEST_ALGORITHM = "SHA-256"

        /** "MQC1" — bumped when the file layout changes. */
        const val MAGIC = 0x4D51_4331
        const val VERSION = 1

        const val FLAG_CLASSICAL = 0x01
        const val FLAG_KIDS = 0x02

        /** Corruption guards for the on-disk reader. */
        const val MAX_DIGEST_BYTES = 64
        const val MAX_TRACKS = 1_000_000
    }
}
