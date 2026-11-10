package com.pisces312.piscesplayer.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import com.pisces312.piscesplayer.BuildConfig
import com.pisces312.piscesplayer.data.preferences.UserPreferencesRepository
import com.pisces312.piscesplayer.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus

    /**
     * [requiresReinstall] means the release cannot be installed over this build: Android refuses
     * to replace an app whose package name or signing key differs, as with a debug install
     * (which carries an applicationId suffix) or a build signed by another key.
     */
    data class Available(val release: AppRelease, val requiresReinstall: Boolean) : UpdateStatus
    data class Downloading(val release: AppRelease, val progress: Float?) : UpdateStatus
    data class AwaitingInstallPermission(val release: AppRelease) : UpdateStatus
    data class AwaitingConfirmation(val release: AppRelease, val confirmIntent: Intent) : UpdateStatus
    data class Installing(val release: AppRelease) : UpdateStatus
    data class Failed(val error: UpdateError, val release: AppRelease?) : UpdateStatus
}

enum class UpdateError {
    CHECK_FAILED,
    NOTHING_PUBLISHED,
    DOWNLOAD_FAILED,
    INVALID_APK,
    NOT_NEWER,
    INSTALL_FAILED,
}

/**
 * Checks GitHub for a newer build and installs it through [PackageInstaller]. A release that
 * cannot replace this build - a debug install carries an applicationId suffix, and a build signed
 * with another key is refused outright - is only reported, so the user can fetch it manually.
 */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val releaseSource: AppReleaseSource,
    private val preferences: UserPreferencesRepository,
    baseClient: OkHttpClient,
    @AppScope private val scope: CoroutineScope,
) {
    val installedVersionName: String = BuildConfig.VERSION_NAME

    /**
     * Opt-in: this fork reaches GitHub's API from networks where it is often unreachable, so a
     * daily check nobody asked for would only spend battery on retries.
     */
    val autoCheckEnabled: Flow<Boolean> =
        preferences.updateAutoCheckFlow.map { it ?: false }

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val downloadClient = baseClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var job: Job? = null

    /** The in-flight APK download; cancelled directly because OkHttp reads ignore coroutine cancellation. */
    @Volatile
    private var activeDownload: Call? = null

    private var downloadJob: Job? = null

    /**
     * The latest deletion of the updates directory. Cleanups chain onto each other and every
     * download waits for the last one, so a cleanup from an earlier cancel can't delete the files
     * of a retry. A fresh process starts with one: no install is in flight, so whatever a finished
     * or abandoned update left behind can go.
     */
    @Volatile
    private var apkCleanup: Job = scope.launch { updatesDir().deleteRecursively() }

    private fun scheduleApkCleanup() {
        val previous = apkCleanup
        apkCleanup = scope.launch(Dispatchers.IO) {
            previous.join()
            updatesDir().deleteRecursively()
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        scope.launch { preferences.setUpdateAutoCheck(enabled) }
    }

    fun checkNow() {
        job?.cancel()
        job = scope.launch { runCheck() }
    }

    private suspend fun runCheck() {
        _status.value = UpdateStatus.Checking
        _status.value = try {
            findUpdate()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Timber.w(error, "Update check failed")
            UpdateStatus.Failed(UpdateError.CHECK_FAILED, release = null)
        }
    }

    /** One lookup of the published release, without touching [status]; used by the background worker. */
    suspend fun findUpdate(): UpdateStatus {
        val release = releaseSource.latest()
            ?: return UpdateStatus.Failed(UpdateError.NOTHING_PUBLISHED, release = null)
        return when {
            release.isNewerThan(installedVersionName) -> UpdateStatus.Available(
                release,
                // Debug builds carry an applicationId suffix; the published APK installs beside them.
                requiresReinstall = context.packageName != AppReleaseSource.PUBLISHED_PACKAGE,
            )
            else -> UpdateStatus.UpToDate
        }
    }

    fun downloadAndInstall() {
        val available = _status.value as? UpdateStatus.Available ?: return
        if (available.requiresReinstall) return
        val release = available.release
        // A cancelled download may still be unwinding (even if a check ran since); let it
        // finish before this one reuses its files.
        val previousDownload = downloadJob
        job?.cancel()
        _status.value = UpdateStatus.Downloading(release, progress = null)
        UpdateDownloadService.start(context)
        job = scope.launch {
            previousDownload?.join()
            val apk = try {
                download(release)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // cancel() aborts the call, which surfaces here as an IOException; Idle stands.
                ensureActive()
                Timber.w(error, "Update download failed")
                _status.value = UpdateStatus.Failed(UpdateError.DOWNLOAD_FAILED, release)
                return@launch
            }
            val check = verify(apk)
            ensureActive()
            when (check) {
                ApkCheck.OK -> install(release, apk)
                ApkCheck.SIGNER_MISMATCH -> {
                    apk.delete()
                    _status.value = UpdateStatus.Available(release, requiresReinstall = true)
                }
                ApkCheck.NOT_NEWER -> {
                    apk.delete()
                    _status.value = UpdateStatus.Failed(UpdateError.NOT_NEWER, release)
                }
                ApkCheck.INVALID -> {
                    apk.delete()
                    _status.value = UpdateStatus.Failed(UpdateError.INVALID_APK, release)
                }
            }
        }
        downloadJob = job
    }

    /**
     * Stops a download or an install still waiting for the "install unknown apps" grant. Once a
     * session is committed, the system prompt owns the decision and reports back on its own.
     */
    fun cancel() {
        val current = _status.value
        if (current !is UpdateStatus.Downloading && current !is UpdateStatus.AwaitingInstallPermission) return
        job?.cancel()
        activeDownload?.cancel()
        _status.value = UpdateStatus.Idle
        scheduleApkCleanup()
    }

    /** Resumes an install that waited for the "install unknown apps" grant. */
    fun resumeInstall() {
        val waiting = _status.value as? UpdateStatus.AwaitingInstallPermission ?: return
        val apk = downloadedApk(waiting.release)
        if (!apk.isFile) {
            _status.value = UpdateStatus.Available(waiting.release, requiresReinstall = false)
            return
        }
        job = scope.launch { install(waiting.release, apk) }
    }

    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    internal fun onInstallStatus(status: Int, confirmIntent: Intent?, message: String?) {
        val release = when (val current = _status.value) {
            is UpdateStatus.Installing -> current.release
            is UpdateStatus.AwaitingConfirmation -> current.release
            else -> null
        }
        _status.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (release == null || confirmIntent == null) return
                UpdateStatus.AwaitingConfirmation(release, confirmIntent)
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateStatus.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                release?.let { UpdateStatus.Available(it, requiresReinstall = false) } ?: UpdateStatus.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                release?.let { UpdateStatus.Available(it, requiresReinstall = true) }
                    ?: UpdateStatus.Failed(UpdateError.INSTALL_FAILED, null)
            else -> {
                Timber.w("Update install failed: status=$status message=$message")
                UpdateStatus.Failed(UpdateError.INSTALL_FAILED, release)
            }
        }
    }

    fun dismiss() {
        if (_status.value is UpdateStatus.Failed) _status.value = UpdateStatus.Idle
    }

    private suspend fun download(release: AppRelease): File = withContext(Dispatchers.IO) {
        apkCleanup.join()
        val dir = updatesDir()
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("Could not create $dir")
        val target = downloadedApk(release)
        val partial = File(dir, target.name + ".part")

        val request = Request.Builder().url(release.apkUrl).get().build()
        val call = downloadClient.newCall(request).also { activeDownload = it }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSizeBytes
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        var written = 0L
                        var lastReported = -1
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count
                            if (total != null) {
                                val percent = (written * 100 / total).toInt()
                                if (percent != lastReported && isActive) {
                                    lastReported = percent
                                    _status.value = UpdateStatus.Downloading(
                                        release,
                                        (written.toFloat() / total).coerceIn(0f, 1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            activeDownload = null
        }
        if (!partial.renameTo(target)) throw IOException("Could not finalize $target")
        target
    }

    private enum class ApkCheck { OK, SIGNER_MISMATCH, NOT_NEWER, INVALID }

    private fun verify(apk: File): ApkCheck {
        val packageManager = context.packageManager
        val archive = archiveInfo(packageManager, apk) ?: return ApkCheck.INVALID
        // Debug builds carry an applicationId suffix; a release APK would install beside them.
        if (archive.packageName != context.packageName) return ApkCheck.INVALID
        val installed = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= installed.longVersionCode) return ApkCheck.NOT_NEWER
        val installedSigners = installed.signingInfo?.signers().orEmpty()
        val archiveSigners = archive.signingInfo?.signers().orEmpty()
        // Without signer info, PackageInstaller still enforces the signature; let it decide.
        if (installedSigners.isEmpty() || archiveSigners.isEmpty()) return ApkCheck.OK
        return if (archiveSigners.any { it in installedSigners }) ApkCheck.OK else ApkCheck.SIGNER_MISMATCH
    }

    private fun archiveInfo(packageManager: PackageManager, apk: File) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apk.path,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    /** Current signers plus the rotation history, which a rotated key still accepts. */
    private fun SigningInfo.signers(): List<Signature> =
        if (hasMultipleSigners()) apkContentsSigners.toList() else signingCertificateHistory.toList()

    private suspend fun install(release: AppRelease, apk: File) {
        if (!canRequestPackageInstalls()) {
            _status.value = UpdateStatus.AwaitingInstallPermission(release)
            return
        }
        _status.value = UpdateStatus.Installing(release)
        try {
            withContext(Dispatchers.IO) { commitSession(apk) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Timber.w(error, "Could not start the update install session")
            _status.value = UpdateStatus.Failed(UpdateError.INSTALL_FAILED, release)
        }
    }

    private fun commitSession(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            installer.abandonSession(sessionId)
            throw error
        }
    }

    private fun updatesDir() = File(context.cacheDir, UPDATES_DIR)

    private fun downloadedApk(release: AppRelease) =
        File(updatesDir(), "pixelplayeross-${release.versionName}.apk")

    private companion object {
        const val UPDATES_DIR = "updates"
    }
}
