package com.pisces312.piscesplayer.data.midilibrary

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.SongEntity
import com.pisces312.piscesplayer.data.database.SourceType
import com.pisces312.piscesplayer.data.database.toSongs
import com.pisces312.piscesplayer.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
    private val scanner: MidiScanner
) {
    val directories = store.directories

    val files = store.files

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
