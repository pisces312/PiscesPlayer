package com.pisces312.piscesplayer.data.classical

import android.content.Context
import com.pisces312.piscesplayer.data.database.AlbumEntity
import com.pisces312.piscesplayer.data.database.ArtistEntity
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.SongArtistCrossRef
import com.pisces312.piscesplayer.data.database.SongEntity
import com.pisces312.piscesplayer.data.database.SourceType
import com.pisces312.piscesplayer.data.model.ArtistRef
import com.pisces312.piscesplayer.data.database.serializeArtistRefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs the built-in classical MIDI library on first launch:
 *
 * 1. Streams `assets/classical.zip` into `filesDir/classical/` (temp dir + rename so a
 *    crash mid-extract never leaves a half-written library in place);
 * 2. Injects the tracks into Room as `SourceType.CLASSICAL` rows with the stable ids
 *    baked into `classical_index.json`;
 * 3. On resource version bumps, removes rows (and files, via the fresh extract) that
 *    are no longer part of the shipped index.
 *
 * Idempotent: `classical_library_version` is only written after extraction AND
 * injection succeed, so an interrupted run is redone from scratch on next launch.
 */
@Singleton
class ClassicalLibraryInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao
) {
    private val mutex = Mutex()

    /** Fire-and-forget entry point for app startup. */
    fun start(scope: CoroutineScope) {
        scope.launch { initialize() }
    }

    suspend fun initialize() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                doInitialize()
            } catch (e: Exception) {
                Timber.e(e, "Classical library installation failed")
            }
        }
    }

    private suspend fun doInitialize() {
        val prefs = context.getSharedPreferences(ClassicalLibrary.PREFS_NAME, Context.MODE_PRIVATE)
        val installedVersion = prefs.getInt(ClassicalLibrary.PREF_VERSION, 0)
        val root = File(context.filesDir, ClassicalLibrary.EXTRACT_DIR_NAME)
        if (installedVersion == ClassicalLibrary.RESOURCE_VERSION &&
            File(root, ClassicalLibrary.INDEX_ENTRY).exists()
        ) {
            return
        }
        Timber.d(
            "Installing classical library v%d (installed=%d)",
            ClassicalLibrary.RESOURCE_VERSION,
            installedVersion
        )

        // ---- 1. extract the resource pack ---------------------------------
        val tmp = File(context.filesDir, ClassicalLibrary.EXTRACT_DIR_NAME + ".tmp")
        tmp.deleteRecursively()
        check(tmp.mkdirs()) { "Could not create $tmp" }
        var indexJson: String? = null
        context.assets.open(ClassicalLibrary.ASSET_ZIP).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory) {
                        // flat namespace only; guard against zip-slip just in case
                        check(!name.contains('/') && !name.contains('\\') && !name.contains("..")) {
                            "Unexpected zip entry: $name"
                        }
                        val out = File(tmp, name)
                        out.outputStream().use { zin.copyTo(it) }
                        if (name == ClassicalLibrary.INDEX_ENTRY) {
                            indexJson = out.readText()
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
        val json = indexJson ?: error("classical_index.json missing from ${ClassicalLibrary.ASSET_ZIP}")
        val tracks = ClassicalLibrary.parseIndex(json)
        check(tracks.isNotEmpty()) { "classical_index.json contains no tracks" }

        // ---- 2. swap the extract dir --------------------------------------
        root.deleteRecursively()
        check(tmp.renameTo(root)) { "Could not move $tmp to $root" }

        // ---- 3. inject into Room ------------------------------------------
        val now = System.currentTimeMillis()
        val composers = LinkedHashMap<String, MutableList<ClassicalTrack>>()
        tracks.forEach { composers.getOrPut(it.composer) { mutableListOf() }.add(it) }

        val artists = composers.map { (composer, group) ->
            ArtistEntity(
                id = group.first().artistId,
                name = composer,
                trackCount = group.size
            )
        }
        val albums = composers.map { (composer, group) ->
            AlbumEntity(
                id = group.first().albumId,
                title = group.first().albumName,
                artistName = composer,
                artistId = group.first().artistId,
                albumArtUriString = null,
                songCount = group.size,
                dateAdded = now,
                year = 0,
                albumArtist = composer
            )
        }
        val songs = tracks.map { t ->
            SongEntity(
                id = t.id,
                title = t.titleZh.ifBlank { t.title },
                artistName = t.composer,
                artistId = t.artistId,
                albumArtist = t.composer,
                albumArtistId = t.artistId,
                albumName = t.albumName,
                albumId = t.albumId,
                contentUriString = ClassicalLibrary.contentUri(t.id),
                albumArtUriString = null,
                duration = t.durationMs,
                genre = null,
                filePath = File(root, t.file).absolutePath,
                parentDirectoryPath = root.absolutePath,
                dateAdded = now,
                mimeType = "audio/midi",
                sourceType = SourceType.CLASSICAL,
                artistsJson = serializeArtistRefs(
                    listOf(ArtistRef(id = t.artistId, name = t.composer, isPrimary = true))
                )
            )
        }
        val crossRefs = tracks.map {
            SongArtistCrossRef(songId = it.id, artistId = it.artistId, isPrimary = true)
        }

        // ---- 4. drop stale rows from older resource versions ---------------
        val expectedIds = tracks.map { it.id }.toSet()
        val staleIds = musicDao.getClassicalSongIds().filter { it !in expectedIds }
        if (staleIds.isNotEmpty()) {
            Timber.d("Removing %d stale classical rows", staleIds.size)
            musicDao.deleteSongsAndRelatedData(staleIds)
        }
        musicDao.insertMusicData(songs, albums, artists)
        musicDao.insertSongArtistCrossRefs(crossRefs)
        musicDao.refreshAlbumSongCounts()
        musicDao.refreshArtistTrackCounts()

        // ---- 5. done; only now is the version marker written ---------------
        prefs.edit().putInt(ClassicalLibrary.PREF_VERSION, ClassicalLibrary.RESOURCE_VERSION).apply()
        Timber.d("Classical library installed: %d tracks, %d composers", tracks.size, composers.size)
    }
}
