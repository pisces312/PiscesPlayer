package com.pisces312.piscesplayer.data.classical

import android.content.Context
import android.content.SharedPreferences
import com.pisces312.piscesplayer.data.database.AlbumEntity
import com.pisces312.piscesplayer.data.database.ArtistEntity
import com.pisces312.piscesplayer.data.database.EngagementDao
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.SongArtistCrossRef
import com.pisces312.piscesplayer.data.database.SongEntity
import com.pisces312.piscesplayer.data.database.SourceType
import com.pisces312.piscesplayer.data.model.ArtistRef
import com.pisces312.piscesplayer.data.database.serializeArtistRefs
import com.pisces312.piscesplayer.data.stats.PlaybackStatsRepository
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
 * 3. On pack updates (content hash mismatch), removes rows (and files, via the fresh
 *    extract) that are no longer part of the shipped index.
 *
 * Pack identity is self-describing: the first zip entry `classical_pack.json` carries
 * the `packHash` content hash, compared against the hash stored at install time.
 * Updating the resource pack is rebuild + swap assets with zero app-code changes —
 * there is no version constant to bump.
 *
 * Idempotent: `classical_library_pack_hash` is only written after extraction AND
 * injection succeed, so an interrupted run is redone from scratch on next launch.
 */
@Singleton
class ClassicalLibraryInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val engagementDao: EngagementDao,
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val nameCatalog: ClassicalNameCatalog,
    private val workCatalog: ClassicalWorkCatalog
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
        purgeListeningDataOnce(prefs)
        val installedHash = prefs.getString(ClassicalLibrary.PREF_PACK_HASH, null)
        val root = File(context.filesDir, ClassicalLibrary.EXTRACT_DIR_NAME)
        if (installedHash != null && installedHash == peekAssetPackHash() &&
            File(root, ClassicalLibrary.INDEX_ENTRY).exists()
        ) {
            loadNames(root)
            loadWorks(root)
            return
        }
        Timber.d(
            "Installing classical library pack (installed=%s)",
            installedHash?.take(12) ?: "none"
        )

        // ---- 1. extract the resource pack ---------------------------------
        val tmp = File(context.filesDir, ClassicalLibrary.EXTRACT_DIR_NAME + ".tmp")
        tmp.deleteRecursively()
        check(tmp.mkdirs()) { "Could not create $tmp" }
        var indexJson: String? = null
        var packJson: String? = null
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
                        when (name) {
                            ClassicalLibrary.INDEX_ENTRY -> indexJson = out.readText()
                            ClassicalLibrary.PACK_ENTRY -> packJson = out.readText()
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
        val json = indexJson ?: error("classical_index.json missing from ${ClassicalLibrary.ASSET_ZIP}")
        val packHash = packJson?.let { ClassicalLibrary.parsePackHash(it) }
            ?: error("classical_pack.json missing from ${ClassicalLibrary.ASSET_ZIP}")
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

        // ---- 5. done; only now is the pack hash written --------------------
        prefs.edit().putString(ClassicalLibrary.PREF_PACK_HASH, packHash).apply()
        Timber.d(
            "Classical library installed: %d tracks, %d composers (pack %s)",
            tracks.size, composers.size, packHash.take(12)
        )
        loadNames(root)
        loadWorks(root)
    }

    /**
     * Reads only the tiny `classical_pack.json` entry from the shipped zip to learn the
     * pack's content hash — no full extraction needed for the fast-path comparison.
     * Returns null when the entry is missing or unreadable (treated as "needs install").
     */
    private fun peekAssetPackHash(): String? = runCatching {
        context.assets.open(ClassicalLibrary.ASSET_ZIP).use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name == ClassicalLibrary.PACK_ENTRY) {
                        return@runCatching ClassicalLibrary.parsePackHash(
                            zin.readBytes().decodeToString()
                        )
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        }
        null
    }.getOrNull()

    /** 装载运行时曲名目录（每次启动都要装，含快速路径）。 */
    private fun loadNames(root: File) {
        val namesFile = File(root, ClassicalLibrary.NAMES_ENTRY)
        if (!namesFile.exists()) return
        runCatching {
            nameCatalog.load(ClassicalLibrary.parseNames(namesFile.readText()))
        }.onFailure { Timber.e(it, "Failed to load %s", ClassicalLibrary.NAMES_ENTRY) }
    }

    /**
     * 装载作品分组目录（每次启动都要装，含快速路径）。作品分组由
     * [ClassicalWorkDeriver] 从 index 运行时派生（见 plan §B4 P0）。
     */
    private fun loadWorks(root: File) {
        val indexFile = File(root, ClassicalLibrary.INDEX_ENTRY)
        if (!indexFile.exists()) return
        runCatching {
            workCatalog.load(ClassicalLibrary.parseIndex(indexFile.readText()))
        }.onFailure { Timber.e(it, "Failed to load %s work catalog", ClassicalLibrary.INDEX_ENTRY) }
    }

    /**
     * Built-in tracks are excluded from listening stats at record time
     * (MusicService.syncLocalListeningStatsFromPlayer). This one-shot cleanup removes
     * engagement rows and playback-stat events recorded before that exclusion existed
     * (e.g. early verification plays), keeping built-ins out of stats / recently played /
     * AI sampling retroactively. Runs on every code path but is flag-guarded.
     */
    private suspend fun purgeListeningDataOnce(prefs: SharedPreferences) {
        if (prefs.getBoolean(ClassicalLibrary.PREF_STATS_PURGE_DONE, false)) return
        engagementDao.deleteEngagementsAtOrAbove(ClassicalLibrary.SONG_ID_BASE)
        playbackStatsRepository.purgeEventsWithSongIdAtLeast(ClassicalLibrary.SONG_ID_BASE)
        prefs.edit().putBoolean(ClassicalLibrary.PREF_STATS_PURGE_DONE, true).apply()
        Timber.d("Purged pre-exclusion listening stats for built-in classical tracks")
    }
}
