package com.pisces312.piscesplayer.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.pisces312.piscesplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostic log collector.
 *
 * Background: [ReleaseTree] only forwards WARN and above, so DEBUG/INFO logs
 * (e.g. importer progress) are invisible in release builds. This collector
 * plants its own Timber Tree and captures everything at or above
 * [minimumPriority] (release default WARN, debug default VERBOSE, adjustable
 * at runtime). Logs are stored in two places:
 * 1. In-memory ring buffer ([MAX_MEMORY_LINES]) - fast to export;
 * 2. Rolling file ([MAX_FILE_BYTES] with one .1 backup) - survives restarts.
 *
 * Export attaches a logcat tail as fallback for logs produced before the
 * collector was planted and for native/system output.
 *
 * Note: exported logs may contain song titles and file paths. Files are only
 * created when the user explicitly requests export/sharing.
 */
object AppLogCollector {

    private const val MAX_MEMORY_LINES = 3_000
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024
    private const val SIZE_CHECK_INTERVAL_BYTES = 32 * 1024
    private const val LOGCAT_LINES = 3_000
    private const val EXPORT_KEEP_MS = 60 * 60 * 1000L

    private const val LOG_DIR = "logs"
    private const val LOG_FILE_NAME = "piscesplayer.log"
    private const val EXPORT_DIR = "log_export"

    private const val PREFS_NAME = "log_collector"
    private const val KEY_MIN_PRIORITY = "minimum_priority"

    private val lock = Any()
    private val ring = ArrayDeque<String>()

    private var appContext: Context? = null
    private var logFile: File? = null
    private var fileStream: FileOutputStream? = null
    private var bytesSinceCheck = 0L
    private var planted = false

    @Volatile
    private var minimumPriority: Int = if (BuildConfig.DEBUG) Log.VERBOSE else Log.WARN

    private val lineDateFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * Installs the collector. Call in Application.onCreate(), before other
     * Timber Trees, so every Timber log is captured. Idempotent.
     */
    fun install(context: Context) {
        val ctx = context.applicationContext
        minimumPriority = runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_MIN_PRIORITY, minimumPriority)
        }.getOrDefault(minimumPriority)
        synchronized(lock) {
            appContext = ctx
            val dir = File(ctx.filesDir, LOG_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                // Directory creation failure is non-fatal; keep in-memory buffer.
            }
            logFile = File(dir, LOG_FILE_NAME)
        }
        val shouldPlant = synchronized(lock) {
            if (planted) false else { planted = true; true }
        }
        if (shouldPlant) {
            Timber.plant(
                CollectorTree(
                    minPriorityProvider = { minimumPriority },
                    onLog = { priority, tag, message, throwable ->
                        append(formatLine(priority, tag, message, throwable))
                    }
                )
            )
        }
    }

    /** Current minimum recorded priority ([Log.VERBOSE]..[Log.ERROR]). */
    fun getMinimumPriority(): Int = minimumPriority

    /** Adjust minimum priority at runtime; persisted for next launch. */
    fun setMinimumPriority(priority: Int) {
        val clamped = priority.coerceIn(Log.VERBOSE, Log.ERROR)
        minimumPriority = clamped
        runCatching {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putInt(KEY_MIN_PRIORITY, clamped)?.apply()
        }
    }

    /** Snapshot of the in-memory ring buffer (oldest first). */
    fun snapshot(): List<String> = synchronized(lock) { ring.toList() }

    /** Clears the in-memory buffer and persisted files (including rotation backup). */
    fun clear() {
        synchronized(lock) {
            ring.clear()
            bytesSinceCheck = 0
            closeStreamLocked()
            runCatching {
                logFile?.parentFile?.listFiles()?.forEach { it.delete() }
            }
        }
    }

    /** Dumps the last [lines] of app-visible logcat. */
    fun dumpLogcat(lines: Int = LOGCAT_LINES): String = try {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "threadtime", "-t", lines.toString())
        )
        val text = process.inputStream.bufferedReader().readText()
        runCatching { process.destroy() }
        text
    } catch (e: Exception) {
        "<logcat unavailable: ${e.message}>"
    }

    /** Exports diagnostic logs to cacheDir and returns a FileProvider-shareable file. */
    suspend fun exportLogs(): File = withContext(Dispatchers.IO) {
        val ctx = synchronized(lock) { appContext }
            ?: error("AppLogCollector.install() not called")
        val exportDir = File(ctx.cacheDir, EXPORT_DIR)
        if (!exportDir.exists()) exportDir.mkdirs()
        cleanupOldExports(exportDir)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(exportDir, "piscesplayer-log-$stamp.txt")
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(buildHeader(ctx))
            writer.write("\n")

            val persisted = readPersistedLogs()
            writer.write("---- persisted log (${persisted.fileCount} file(s), ${persisted.bytes} bytes) ----\n")
            writer.write(persisted.text)
            if (persisted.text.isNotEmpty() && !persisted.text.endsWith("\n")) writer.write("\n")

            val buffered = snapshot()
            writer.write("\n---- in-memory buffer (${buffered.size} lines, current session) ----\n")
            buffered.forEach { writer.write(it); writer.write("\n") }

            writer.write("\n---- logcat (last $LOGCAT_LINES) ----\n")
            writer.write(dumpLogcat())
        }
        out
    }

    /**
     * Collects a crash-time attachment: persisted logs + in-memory buffer.
     * Synchronous, called from the uncaught exception handler; must not throw.
     */
    fun collectCrashAttachment(): String = try {
        val persisted = readPersistedLogs()
        buildString {
            append("---- persisted log (${persisted.fileCount} file(s), ${persisted.bytes} bytes) ----\n")
            append(persisted.text)
            if (persisted.text.isNotEmpty() && !persisted.text.endsWith("\n")) append("\n")
            val buffered = snapshot()
            append("\n---- in-memory buffer (${buffered.size} lines) ----\n")
            buffered.forEach { append(it); append("\n") }
        }
    } catch (_: Exception) {
        "<diagnostic log unavailable>"
    }

    /** Shares an exported log file via the system share sheet. */
    fun shareLogFile(file: File) {
        val ctx = synchronized(lock) { appContext } ?: return
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PiscesPlayer diagnostic log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "PiscesPlayer log")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    // region internal

    private fun append(line: String) {
        synchronized(lock) {
            while (ring.size >= MAX_MEMORY_LINES) ring.removeFirst()
            ring.addLast(line)
            writeToFileLocked(line)
        }
    }

    private fun writeToFileLocked(line: String) {
        val file = logFile ?: return
        try {
            if (bytesSinceCheck >= SIZE_CHECK_INTERVAL_BYTES) {
                bytesSinceCheck = 0
                if (file.length() > MAX_FILE_BYTES) rotateLocked(file)
            }
            val stream = fileStream ?: FileOutputStream(file, true).also { fileStream = it }
            val payload = (line + "\n").toByteArray(Charsets.UTF_8)
            stream.write(payload)
            bytesSinceCheck += payload.size
        } catch (_: Exception) {
            closeStreamLocked()
        }
    }

    private fun rotateLocked(file: File) {
        closeStreamLocked()
        try {
            val backup = File(file.parentFile, "$LOG_FILE_NAME.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        } catch (_: Exception) {
        }
    }

    private fun closeStreamLocked() {
        runCatching { fileStream?.close() }
        fileStream = null
    }

    private fun formatLine(priority: Int, tag: String?, message: String, t: Throwable?): String {
        val time = lineDateFormat.get().format(Date())
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> priority.toString()
        }
        val head = "$time $level/${tag ?: "PiscesPlayer"} [${Thread.currentThread().name}] $message"
        return if (t != null) "$head\n${Log.getStackTraceString(t)}" else head
    }

    private fun buildHeader(context: Context): String = buildString {
        appendLine("=== PiscesPlayer Diagnostic Log ===")
        appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) buildType=${BuildConfig.BUILD_TYPE}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
        val file = synchronized(lock) { logFile }
        appendLine("Log file: ${file?.absolutePath} (${file?.length() ?: 0} bytes)")
        appendLine("NOTE: may contain song titles and file paths - review before sharing.")
    }

    private data class PersistedLogs(val text: String, val bytes: Long, val fileCount: Int)

    private fun readPersistedLogs(): PersistedLogs {
        val file = synchronized(lock) { logFile } ?: return PersistedLogs("", 0L, 0)
        var fileCount = 0
        var bytes = 0L
        val text = StringBuilder()
        for (f in listOf(File(file.parentFile, "$LOG_FILE_NAME.1"), file)) {
            if (!f.exists() || f.length() == 0L) continue
            runCatching {
                fileCount++
                bytes += f.length()
                text.append(f.readText(Charsets.UTF_8))
            }
        }
        return PersistedLogs(text.toString(), bytes, fileCount)
    }

    private fun cleanupOldExports(dir: File) {
        val cutoff = System.currentTimeMillis() - EXPORT_KEEP_MS
        runCatching {
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    private class CollectorTree(
        private val minPriorityProvider: () -> Int,
        private val onLog: (Int, String?, String, Throwable?) -> Unit
    ) : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean {
            return priority >= minPriorityProvider()
        }

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            onLog(priority, tag, message, t)
        }
    }

    // endregion
}
