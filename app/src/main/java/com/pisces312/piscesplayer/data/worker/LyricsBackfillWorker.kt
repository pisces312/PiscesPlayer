package com.pisces312.piscesplayer.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.database.MusicDao
import com.pisces312.piscesplayer.data.database.SongEntity
import com.pisces312.piscesplayer.data.model.Song
import com.pisces312.piscesplayer.data.repository.LyricsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One-shot, user-triggered backfill that indexes local lyrics sources into the `lyrics`
 * table (and therefore `lyrics_fts`, via the LyricsDao chokepoint) so lyrics-content
 * search can match them:
 *
 * 1. sidecar `.lrc` files (`scanAndAssignLocalLrcFiles`),
 * 2. embedded tags (`scanAndPersistEmbeddedLyrics`) — read-only, never writes audio files.
 *
 * Progress is reported both through WorkManager progress (in-app observation) and a
 * low-importance foreground notification. The scan is per-song idempotent: songs that
 * already have persisted lyrics content are skipped, so cancelling mid-run loses nothing
 * and re-running continues where the previous run stopped.
 *
 * Exposed to the user as "Backfill lyrics index" in Settings → Library → Lyrics management.
 */
@HiltWorker
class LyricsBackfillWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val musicDao: MusicDao,
    private val lyricsRepository: LyricsRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(buildForegroundInfo(0, 1))

            val songIds = musicDao.getAllMediaStoreSongIds()
            val total = songIds.size
            if (total == 0) {
                return@withContext Result.success()
            }

            var processed = 0
            var updated = 0
            songIds.chunked(BATCH_SIZE).forEach { batchIds ->
                if (isStopped) throw CancellationException("Lyrics backfill cancelled")

                val batchSongs = musicDao.getSongsByIdsListSimple(batchIds).map { it.toBackfillSong() }

                // .lrc files first (higher source priority), then embedded tags for the
                // songs the LRC scan left without persisted lyrics.
                updated += lyricsRepository.scanAndAssignLocalLrcFiles(batchSongs) { current, _ ->
                    reportProgress(processed + current, total)
                }
                updated += lyricsRepository.scanAndPersistEmbeddedLyrics(batchSongs) { current, _ ->
                    reportProgress(processed + current, total)
                }

                processed += batchIds.size
                reportProgress(processed, total)
            }

            Timber.tag(TAG).i("Lyrics backfill finished: $updated songs indexed out of $total")
            Result.success(
                workDataOf(
                    RESULT_UPDATED_COUNT to updated,
                    RESULT_TOTAL to total
                )
            )
        } catch (e: CancellationException) {
            // WorkManager cancel (user or system). Rows already written stay; the next
            // run skips them, so this is a seamless resume point.
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Lyrics backfill failed")
            if (runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private suspend fun reportProgress(current: Int, total: Int) {
        setProgress(workDataOf(PROGRESS_CURRENT to current, PROGRESS_TOTAL to total))
        // Throttle notification churn: refresh at most ~every 1% plus the final update.
        val step = (total / 100).coerceAtLeast(1)
        if (current == total || current - lastNotifiedProgress >= step) {
            lastNotifiedProgress = current
            setForeground(buildForegroundInfo(current, total))
        }
    }

    private var lastNotifiedProgress = -1

    private fun buildForegroundInfo(current: Int, total: Int): ForegroundInfo {
        ensureChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(applicationContext.getString(R.string.lyrics_backfill_notification_title))
            .setContentText(
                applicationContext.getString(R.string.lyrics_backfill_notification_progress, current, total)
            )
            .setProgress(total, current, total <= 0)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.lyrics_backfill_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun SongEntity.toBackfillSong(): Song = Song(
        id = id.toString(),
        title = title,
        artist = artistName,
        artistId = artistId,
        album = albumName,
        albumId = albumId,
        path = filePath,
        contentUriString = contentUriString,
        albumArtUriString = albumArtUriString,
        duration = duration,
        lyrics = null,
        dateAdded = dateAdded,
        trackNumber = trackNumber,
        year = year,
        mimeType = mimeType,
        bitrate = bitrate,
        sampleRate = sampleRate
    )

    companion object {
        private const val TAG = "LyricsBackfillWorker"
        private const val BATCH_SIZE = 1000
        private const val MAX_RUN_ATTEMPTS = 3
        private const val NOTIFICATION_ID = 4207
        private const val CHANNEL_ID = "lyrics_backfill"

        const val WORK_NAME = "lyrics_backfill_work"
        const val PROGRESS_CURRENT = "lyrics_backfill_current"
        const val PROGRESS_TOTAL = "lyrics_backfill_total"
        const val RESULT_UPDATED_COUNT = "lyrics_backfill_updated"
        const val RESULT_TOTAL = "lyrics_backfill_total_count"

        /** Taps while a backfill is already running are ignored (KEEP). */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresStorageNotLow(true)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LyricsBackfillWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
