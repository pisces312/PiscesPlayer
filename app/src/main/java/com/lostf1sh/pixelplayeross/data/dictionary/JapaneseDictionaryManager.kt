package com.lostf1sh.pixelplayeross.data.dictionary

import android.content.Context
import com.atilika.kuromoji.ipadic.Tokenizer
import com.lostf1sh.pixelplayeross.data.preferences.UserPreferencesRepository
import com.lostf1sh.pixelplayeross.utils.MultiLangRomanizer
import com.lostf1sh.pixelplayeross.utils.japanese.ExternalDictionaryTokenizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the externally downloaded kuromoji/ipadic dictionary.
 *
 * The dictionary is not shipped in the APK. When the user enables Japanese lyrics
 * romanization (or taps the dictionary row in Settings), the archive is downloaded from this
 * fork's GitHub release, verified against [EXPECTED_SHA256], and extracted into app storage.
 * [MultiLangRomanizer] only receives a tokenizer while the preference is enabled and the files
 * are present, so disabling the option drops the tokenizer and its heap footprint.
 */
@Singleton
class JapaneseDictionaryManager @Inject constructor(
    @ApplicationContext context: Context,
    baseClient: OkHttpClient,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    sealed interface State {
        data object NotInstalled : State
        data class Downloading(val bytesRead: Long, val totalBytes: Long?) : State {
            val progress: Float?
                get() = totalBytes?.takeIf { it > 0L }
                    ?.let { (bytesRead.toFloat() / it).coerceIn(0f, 1f) }
        }
        data object Installed : State
        data class Failed(val message: String?) : State
    }

    private val appContext = context.applicationContext
    private val dictionaryDirectory = File(
        appContext.filesDir,
        "dictionaries/${ExternalDictionaryTokenizer.DICTIONARY_VERSION}"
    )
    private val downloadClient = baseClient.newBuilder()
        .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadLock = Mutex()

    private val _state = MutableStateFlow<State>(
        if (ExternalDictionaryTokenizer.isComplete(dictionaryDirectory)) {
            State.Installed
        } else {
            State.NotInstalled
        }
    )
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var started = false

    @Volatile
    private var enabled = false

    @Volatile
    private var tokenizer: Tokenizer? = null
    private val tokenizerLock = Any()
    private var downloadJob: Job? = null

    /** Wires the romanizer provider and starts mirroring the preference. Safe to call twice. */
    fun start() {
        if (started) return
        started = true
        MultiLangRomanizer.setJapaneseTokenizerProvider(::tokenizerOrNull)
        scope.launch {
            userPreferencesRepository.japaneseRomanizationEnabledFlow.collect { isEnabled ->
                enabled = isEnabled
                if (!isEnabled) {
                    clearTokenizer()
                } else if (_state.value is State.Installed) {
                    launch { tokenizerOrNull() }
                }
            }
        }
    }

    /** Starts the download when needed; no-op while downloading or after installation. */
    fun ensureDownloaded() {
        if (_state.value is State.Installed) return
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch { download() }
    }

    /**
     * Returns the tokenizer only when the option is enabled and the dictionary exists.
     * The first call after installation builds it; callers run on a background dispatcher.
     */
    fun tokenizerOrNull(): Tokenizer? {
        if (!enabled || _state.value !is State.Installed) return null
        tokenizer?.let { return it }
        return synchronized(tokenizerLock) {
            tokenizer ?: runCatching { ExternalDictionaryTokenizer.build(dictionaryDirectory) }
                .onFailure { Timber.e(it, "Failed to load Japanese dictionary") }
                .getOrNull()
                ?.also { tokenizer = it }
        }
    }

    private fun clearTokenizer() {
        synchronized(tokenizerLock) { tokenizer = null }
    }

    private suspend fun download() = downloadLock.withLock {
        withContext(Dispatchers.IO) {
            val tempArchive = File(appContext.cacheDir, "$ARCHIVE_NAME.part")
            val tempDirectory = File(appContext.cacheDir, "kuromoji-ipadic-tmp")
            _state.value = State.Downloading(0L, EXPECTED_BYTES)
            try {
                tempArchive.delete()
                tempDirectory.deleteRecursively()

                val request = Request.Builder().url(DOWNLOAD_URL).get().build()
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0L } ?: EXPECTED_BYTES
                    body.byteStream().use { input ->
                        tempArchive.outputStream().use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            var written = 0L
                            var lastPercent = -1
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                written += count
                                val percent = (written * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    _state.value = State.Downloading(written, total)
                                }
                            }
                        }
                    }
                }

                if (tempArchive.length() != EXPECTED_BYTES) {
                    throw IOException("Unexpected archive size: ${tempArchive.length()}")
                }
                if (!sha256(tempArchive).equals(EXPECTED_SHA256, ignoreCase = true)) {
                    throw IOException("Dictionary checksum mismatch")
                }

                tempDirectory.mkdirs()
                extractRequiredFiles(tempArchive, tempDirectory)
                if (!ExternalDictionaryTokenizer.isComplete(tempDirectory)) {
                    throw IOException("Dictionary archive is incomplete")
                }

                dictionaryDirectory.deleteRecursively()
                dictionaryDirectory.parentFile?.mkdirs()
                if (!tempDirectory.renameTo(dictionaryDirectory)) {
                    tempDirectory.copyRecursively(dictionaryDirectory, overwrite = true)
                }
                if (!ExternalDictionaryTokenizer.isComplete(dictionaryDirectory)) {
                    dictionaryDirectory.deleteRecursively()
                    throw IOException("Could not install dictionary")
                }

                clearTokenizer()
                _state.value = State.Installed
                if (enabled) scope.launch { tokenizerOrNull() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!ExternalDictionaryTokenizer.isComplete(dictionaryDirectory)) {
                    dictionaryDirectory.deleteRecursively()
                }
                Timber.w(e, "Japanese dictionary download failed")
                _state.value = State.Failed(e.message)
            } finally {
                tempArchive.delete()
                tempDirectory.deleteRecursively()
            }
        }
    }

    private fun extractRequiredFiles(archive: File, targetDirectory: File) {
        ZipFile(archive).use { zip ->
            ExternalDictionaryTokenizer.REQUIRED_FILES.forEach { name ->
                val entry = zip.getEntry(name) ?: throw IOException("Missing $name in archive")
                zip.getInputStream(entry).use { input ->
                    File(targetDirectory, name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ARCHIVE_NAME = "kuromoji-ipadic-0.9.0.zip"
        const val DOWNLOAD_URL =
            "https://github.com/pisces312/PixelPlayerOSS/releases/download/" +
                "dictionary-kuromoji-0.9.0/kuromoji-ipadic-0.9.0.zip"
        const val EXPECTED_SHA256 =
            "dbcecc13e5ec410b58f401b8286ff82c3f8eae2d0789f798e07ae7acc826624e"
        const val EXPECTED_BYTES = 13_331_021L
        const val DOWNLOAD_READ_TIMEOUT_SECONDS = 120L
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }
}
