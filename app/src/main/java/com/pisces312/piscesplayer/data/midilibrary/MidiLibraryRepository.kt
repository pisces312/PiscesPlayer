package com.pisces312.piscesplayer.data.midilibrary

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pisces312.piscesplayer.data.classical.ClassicalWorkCatalog
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.SongEntity
import com.pisces312.piscesplayer.data.database.SourceType
import com.pisces312.piscesplayer.data.database.toSongs
import com.pisces312.piscesplayer.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 遮蔽索引（plan §2-E）：被用户文件遮蔽的内置曲目 id，及 built-in id → 用户条目映射。
 */
data class MidiShadowIndex(
    val shadowedBuiltInIds: Set<Long> = emptySet(),
    val userEntryByBuiltInId: Map<Long, MidiFileEntry> = emptyMap()
) {
    fun substituteFor(builtInId: Long): MidiFileEntry? = userEntryByBuiltInId[builtInId]

    companion object {
        val EMPTY = MidiShadowIndex()
    }
}

/**
 * User MIDI library orchestration (plan §C): registers SAF directories (persistent read
 * permission, files referenced not copied), scans them into [MidiLibraryStore], and mirrors
 * the registry into Room as `SourceType.USER_MIDI` rows so playback / playlists / search /
 * stats work exactly like local songs. Rows are inserted once (dateAdded kept) and removed
 * when their file disappears from the registry.
 */
@Singleton
class MidiLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao,
    private val store: MidiLibraryStore,
    private val scanner: MidiScanner,
    private val workCatalog: ClassicalWorkCatalog
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val musicHashMutex = Mutex()

    @Volatile
    private var musicHashCache: Map<Long, String>? = null

    val directories = store.directories

    val files = store.files

    /** 用户文件变化时重算遮蔽索引（L1 立即，L2 需要时才解析内置文件并缓存）。 */
    val shadowIndex: StateFlow<MidiShadowIndex> = store.files
        .map { computeShadowIndex(it) }
        .stateIn(scope, SharingStarted.Eagerly, MidiShadowIndex.EMPTY)

    fun userMidiSongs(): Flow<List<Song>> = musicDao.getUserMidiSongs().map { it.toSongs() }

    /** 注册并扫描一个目录。false = 需要重新授权。 */
    suspend fun addDirectory(uri: Uri): Boolean {
        val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "MIDI"
        val base = MidiDirectory(
            uri = uri.toString(),
            displayName = name,
            lastScanAt = System.currentTimeMillis()
        )
        takeReadPermission(uri)
        store.upsertDirectory(base)
        return applyScan(base)
    }

    /** 重新扫描全部已注册目录。 */
    suspend fun rescanAll() {
        store.directories.value.forEach { dir -> applyScan(dir) }
        syncDb()
    }

    /** 移除目录注册（并移除其曲目行）。 */
    suspend fun removeDirectory(uri: String) {
        store.removeDirectory(uri)
        syncDb()
    }

    /** 单曲添加（OpenDocument 文档）。false = 读取失败。 */
    suspend fun addSingleFile(uri: Uri): Boolean {
        takeReadPermission(uri)
        val entry = scanner.analyzeSingle(uri, directoryName = "") ?: return false
        store.upsertFile(entry)
        syncDb()
        return true
    }

    /** 移除一条单曲添加的文件（目录内文件随目录移除/重扫管理）。 */
    suspend fun removeFile(songId: String) {
        store.removeFile(songId)
        syncDb()
    }

    private suspend fun applyScan(directory: MidiDirectory): Boolean {
        return when (val outcome = scanner.scanTree(Uri.parse(directory.uri), directory.displayName)) {
            is MidiScanner.ScanOutcome.Scanned -> {
                store.replaceDirectoryFiles(
                    directory.copy(lastScanAt = System.currentTimeMillis(), needsReauth = false),
                    outcome.entries
                )
                syncDb()
                true
            }
            MidiScanner.ScanOutcome.NeedsReauth -> {
                store.upsertDirectory(directory.copy(needsReauth = true))
                false
            }
        }
    }

    private fun takeReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { Timber.w(it, "takePersistableUriPermission failed for %s", uri) }
    }

    /** store ↔ Room 差异同步：新文件入库、消失文件下线（含收藏/播放列表引用清理）。 */
    private suspend fun syncDb() {
        val entries = store.files.value
        val wantIds = entries.mapNotNull { it.songId.toLongOrNull() }.toSet()
        val haveIds = musicDao.getUserMidiSongIds().toSet()

        val stale = haveIds - wantIds
        if (stale.isNotEmpty()) {
            musicDao.deleteSongsAndRelatedData(stale.toList())
        }
        val fresh = entries.filter { it.songId.toLongOrNull() !in haveIds }
        if (fresh.isNotEmpty()) {
            musicDao.insertMusicData(fresh.map { it.toSongEntity() }, emptyList(), emptyList())
        }
    }

    private suspend fun computeShadowIndex(files: List<MidiFileEntry>): MidiShadowIndex {
        if (files.isEmpty()) return MidiShadowIndex.EMPTY
        val byFileHash = workCatalog.fileHashes()
        val l1 = MidiShadowMatcher.match(files, byFileHash, emptyMap())
        if (l1.size == files.size) return MidiShadowIndex(l1.keys, l1)

        // L1 未命中的走 L2：需要内置侧音乐指纹（一次性解析 + 缓存）。
        val musicHashes = builtinMusicHashes()
        val full = MidiShadowMatcher.match(files, byFileHash, musicHashes)
        return MidiShadowIndex(full.keys, full)
    }

    /**
     * 内置曲目 id → 音乐指纹（L2）。首次需要时解析 1032 个内置文件（很小、亚秒级、
     * 后台一次），结果落盘缓存（`filesDir` JSON，随资源包重建自动重算）。
     */
    private suspend fun builtinMusicHashes(): Map<Long, String> = musicHashMutex.withLock {
        musicHashCache?.let { return it }
        val expected = workCatalog.fileHashes().keys
        val cacheFile = File(context.filesDir, MUSIC_HASH_CACHE)
        val cached = runCatching {
            val obj = JSONObject(cacheFile.readText())
            buildMap {
                for (k in obj.keys()) {
                    k.toLongOrNull()?.let { put(it, obj.getString(k)) }
                }
            }
        }.getOrNull()
        if (cached != null && cached.keys.containsAll(expected)) {
            musicHashCache = cached
            return cached
        }
        val computed = expected.mapNotNull { id ->
            val bytes = runCatching { builtinMidiFile(context, id).readBytes() }.getOrNull()
                ?: return@mapNotNull null
            MidiFingerprint.musicHash(bytes)?.let { id to it }
        }.toMap()
        musicHashCache = computed
        runCatching {
            val obj = JSONObject()
            computed.forEach { (id, hash) -> obj.put(id.toString(), hash) }
            cacheFile.writeText(obj.toString())
        }.onFailure { Timber.w(it, "Cannot cache built-in music hashes") }
        return computed
    }

    private fun MidiFileEntry.toSongEntity(): SongEntity = SongEntity(
        id = songId.toLongOrNull() ?: 0L,
        title = displayName.substringBeforeLast('.'),
        artistName = directoryName,
        artistId = 0L,
        albumName = directoryName,
        albumId = 0L,
        contentUriString = uri,
        albumArtUriString = null,
        duration = durationMs,
        genre = null,
        filePath = "",
        parentDirectoryPath = directoryUri,
        dateAdded = addedAt,
        mimeType = "audio/midi",
        sourceType = SourceType.USER_MIDI
    )
}

/** 内置曲目文件路径（解压目录），与 DualPlayerEngine 的 piscesmidi 解析一致。 */
internal fun builtinMidiFile(context: Context, trackId: Long): File =
    File(File(context.filesDir, "classical"), "$trackId.mid")

private const val MUSIC_HASH_CACHE = "midi_library_music_hashes.json"
