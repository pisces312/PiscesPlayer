package com.pisces312.piscesplayer.data.midilibrary

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 已注册的扫描目录（SAF tree URI，引用原文件）。 */
data class MidiDirectory(
    val uri: String,
    val displayName: String,
    val lastScanAt: Long = 0L,
    val trackCount: Int = 0,
    val needsReauth: Boolean = false
)

/** 一条用户 MIDI 文件记录（含两级 hash，见 plan §2-E）。 */
data class MidiFileEntry(
    val songId: String,
    val uri: String,
    val displayName: String,
    val directoryUri: String,
    val directoryName: String,
    val sizeBytes: Long = 0L,
    val fileSha256: String = "",
    val musicHash: String? = null,
    val durationMs: Long = 0L,
    val addedAt: Long = 0L
)

/**
 * Persistent registry for the user MIDI library (plan §C): which directories are
 * registered for scanning and what each file's identity/hashes are. Backed by
 * `SharedPreferences` JSON (like [com.pisces312.piscesplayer.data.classical.ClassicalFavoritesStore])
 * so no Room migration is needed — songs table only gains rows.
 */
@Singleton
class MidiLibraryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _directories = MutableStateFlow(readDirectories())
    val directories: StateFlow<List<MidiDirectory>> = _directories.asStateFlow()

    private val _files = MutableStateFlow(readFiles())
    val files: StateFlow<List<MidiFileEntry>> = _files.asStateFlow()

    /** 单曲添加：追加一条记录（同 songId 覆盖）。 */
    fun upsertFile(entry: MidiFileEntry) {
        val next = _files.value.filterNot { it.songId == entry.songId } + entry
        _files.value = next
        writeFiles(next)
    }

    /**
     * 整目录扫描结果落库：替换该目录下的文件集合，并更新目录元信息。
     * 返回被移除的旧条目（调用方负责清 DB 行）。
     */
    fun replaceDirectoryFiles(
        directory: MidiDirectory,
        entries: List<MidiFileEntry>
    ): List<MidiFileEntry> {
        val removed = _files.value.filter { it.directoryUri == directory.uri }
            .filterNot { old -> entries.any { it.songId == old.songId } }
        val others = _files.value.filter { it.directoryUri != directory.uri }
        val next = others + entries
        _files.value = next
        writeFiles(next)
        _directories.value = _directories.value.map {
            if (it.uri == directory.uri) directory.copy(trackCount = entries.size) else it
        }
        writeDirectories(_directories.value)
        return removed
    }

    fun upsertDirectory(directory: MidiDirectory) {
        val next = if (_directories.value.any { it.uri == directory.uri }) {
            _directories.value.map { if (it.uri == directory.uri) directory else it }
        } else {
            _directories.value + directory
        }
        _directories.value = next
        writeDirectories(next)
    }

    /** 移除目录（及其文件记录），返回被移除的文件条目供调用方清 DB。 */
    fun removeDirectory(uri: String): List<MidiFileEntry> {
        val removed = _files.value.filter { it.directoryUri == uri }
        val nextFiles = _files.value.filterNot { it.directoryUri == uri }
        _files.value = nextFiles
        writeFiles(nextFiles)
        _directories.value = _directories.value.filterNot { it.uri == uri }
        writeDirectories(_directories.value)
        return removed
    }

    /** 移除单条（单曲添加的文件），返回是否找到。 */
    fun removeFile(songId: String): MidiFileEntry? {
        val target = _files.value.firstOrNull { it.songId == songId } ?: return null
        val next = _files.value.filterNot { it.songId == songId }
        _files.value = next
        writeFiles(next)
        return target
    }

    // ---- persistence -----------------------------------------------------------

    private fun readDirectories(): List<MidiDirectory> {
        val raw = prefs.getString(KEY_DIRS, null) ?: return emptyList()
        return runCatching {
            val obj = JSONObject(raw)
            buildList {
                for (uri in obj.keys()) {
                    val d = obj.getJSONObject(uri)
                    add(
                        MidiDirectory(
                            uri = uri,
                            displayName = d.optString("name", uri),
                            lastScanAt = d.optLong("lastScanAt"),
                            trackCount = d.optInt("trackCount"),
                            needsReauth = d.optBoolean("needsReauth")
                        )
                    )
                }
            }
        }.getOrElse {
            Timber.w(it, "Unreadable MIDI directory registry")
            emptyList()
        }
    }

    private fun writeDirectories(list: List<MidiDirectory>) {
        val obj = JSONObject()
        list.forEach { d ->
            obj.put(
                d.uri,
                JSONObject()
                    .put("name", d.displayName)
                    .put("lastScanAt", d.lastScanAt)
                    .put("trackCount", d.trackCount)
                    .put("needsReauth", d.needsReauth)
            )
        }
        prefs.edit().putString(KEY_DIRS, obj.toString()).apply()
    }

    private fun readFiles(): List<MidiFileEntry> {
        val raw = prefs.getString(KEY_FILES, null) ?: return emptyList()
        return runCatching {
            val obj = JSONObject(raw)
            buildList {
                for (id in obj.keys()) {
                    val f = obj.getJSONObject(id)
                    add(
                        MidiFileEntry(
                            songId = id,
                            uri = f.optString("uri"),
                            displayName = f.optString("name"),
                            directoryUri = f.optString("dirUri"),
                            directoryName = f.optString("dirName"),
                            sizeBytes = f.optLong("size"),
                            fileSha256 = f.optString("sha256"),
                            musicHash = f.optString("musicHash").takeIf { it.isNotBlank() },
                            durationMs = f.optLong("duration"),
                            addedAt = f.optLong("addedAt")
                        )
                    )
                }
            }
        }.getOrElse {
            Timber.w(it, "Unreadable MIDI file registry")
            emptyList()
        }
    }

    private fun writeFiles(list: List<MidiFileEntry>) {
        val obj = JSONObject()
        list.forEach { f ->
            obj.put(
                f.songId,
                JSONObject()
                    .put("uri", f.uri)
                    .put("name", f.displayName)
                    .put("dirUri", f.directoryUri)
                    .put("dirName", f.directoryName)
                    .put("size", f.sizeBytes)
                    .put("sha256", f.fileSha256)
                    .put("musicHash", f.musicHash ?: "")
                    .put("duration", f.durationMs)
                    .put("addedAt", f.addedAt)
            )
        }
        prefs.edit().putString(KEY_FILES, obj.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "midi_library"
        private const val KEY_DIRS = "midi_directories"
        private const val KEY_FILES = "midi_files"

        /**
         * 稳定 song id：document URI 字符串的 FNV-1a 64 位哈希（去掉符号位）。
         * id 只需稳定与唯一（63 位碰撞概率可忽略），不需要可逆。
         */
        fun stableSongId(uri: String): String {
            var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
            for (b in uri.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            return (h and Long.MAX_VALUE).toString()
        }
    }
}
