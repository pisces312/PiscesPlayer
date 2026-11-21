package com.pisces312.piscesplayer.data.midilibrary

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SAF-based scanning of user MIDI files (plan §C): recursive directory walks and single
 * document adds. Files are **referenced** (persistent URI permission), never copied.
 * Each file gets its two-level identity: L1 file SHA-256 plus L2 [MidiFingerprint] music
 * hash and duration (plan §2-E).
 */
@Singleton
class MidiScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed class ScanOutcome {
        data class Scanned(val entries: List<MidiFileEntry>) : ScanOutcome()
        /** 持久权限失效（被系统/用户回收）：提示重新授权。 */
        object NeedsReauth : ScanOutcome()
    }

    /** 递归扫描 tree 下所有 `.mid`/`.midi`；单个文件读取失败跳过并计数。 */
    suspend fun scanTree(treeUri: Uri, directoryName: String): ScanOutcome =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext ScanOutcome.NeedsReauth
            if (!root.canRead()) {
                return@withContext ScanOutcome.NeedsReauth
            }
            val entries = ArrayList<MidiFileEntry>()
            walk(root, treeUri.toString(), directoryName, entries)
            ScanOutcome.Scanned(entries)
        }

    private fun walk(
        dir: DocumentFile,
        directoryUri: String,
        directoryName: String,
        out: MutableList<MidiFileEntry>
    ) {
        val children = runCatching { dir.listFiles() }.getOrElse {
            Timber.w(it, "MIDI scan: cannot list %s", dir.uri)
            return
        }
        for (child in children) {
            if (child.isDirectory) {
                walk(child, directoryUri, directoryName, out)
            } else if (isMidiName(child.name)) {
                analyzeFile(child, directoryUri, directoryName)?.let(out::add)
            }
        }
    }

    /** 单曲添加（OpenDocument 选择的文档）。 */
    suspend fun analyzeSingle(uri: Uri, directoryName: String): MidiFileEntry? =
        withContext(Dispatchers.IO) {
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return@withContext null
            analyzeFile(doc, directoryUri = "", directoryName = directoryName)
        }

    private fun analyzeFile(
        doc: DocumentFile,
        directoryUri: String,
        directoryName: String
    ): MidiFileEntry? {
        val name = doc.name ?: return null
        if (!isMidiName(name)) return null
        val bytes = runCatching {
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                val out = ByteArrayOutputStream(minOf(doc.length().coerceAtLeast(0), 1 shl 16).toInt())
                input.copyTo(out)
                out.toByteArray()
            }
        }.getOrNull() ?: run {
            Timber.w("MIDI scan: cannot read %s", doc.uri)
            return null
        }
        val analysis = MidiFingerprint.analyze(bytes)
        return MidiFileEntry(
            songId = MidiLibraryStore.stableSongId(doc.uri.toString()),
            uri = doc.uri.toString(),
            displayName = name,
            directoryUri = directoryUri,
            directoryName = directoryName,
            sizeBytes = bytes.size.toLong(),
            fileSha256 = sha256(bytes),
            musicHash = analysis?.musicHash,
            durationMs = analysis?.durationMs ?: 0L,
            addedAt = System.currentTimeMillis()
        )
    }

    private fun isMidiName(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return n.endsWith(".mid") || n.endsWith(".midi")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
