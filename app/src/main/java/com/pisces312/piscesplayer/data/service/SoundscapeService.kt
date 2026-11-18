/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §4.4).
 */
package com.pisces312.piscesplayer.data.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pisces312.piscesplayer.MainActivity
import com.pisces312.piscesplayer.MainActivityIntentContract
import com.pisces312.piscesplayer.R
import com.pisces312.piscesplayer.data.noise.SoundscapeEngine
import com.pisces312.piscesplayer.data.noise.SoundscapeSound
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground host for the sleep soundscape engine. The screen is only a remote
 * control: [SoundscapeEngine] is a @Singleton, so playback survives navigation,
 * and this service keeps the process alive with its own notification ("入梦 ·
 * 播放中") while [SoundscapeEngine.phase] is not IDLE.
 *
 * The soundscape manages its own audio focus ([AudioManager.AUDIOFOCUS_GAIN]):
 * transient losses (a call) fade the engine out and regain resumes it, a
 * permanent loss stops it. Unplugging headphones stops playback outright.
 *
 * The sleep timer itself runs inside the engine's writer loop (so the fade is
 * sample-accurate); the AlarmManager alarm scheduled here is only a backup that
 * makes the service stop foreground even if the writer loop died. Restarting
 * the process never resumes playback (safety default).
 */
@AndroidEntryPoint
class SoundscapeService : android.app.Service() {

    @Inject
    lateinit var engine: SoundscapeEngine

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    private var focusRequest: AudioFocusRequest? = null
    private var running = false
    private var scope: CoroutineScope? = null
    private var phaseJob: Job? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Timber.tag(TAG).d("Becoming noisy: stopping soundscape")
                engine.stop()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.tag(TAG).d("Permanent focus loss: stopping soundscape")
                engine.stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Timber.tag(TAG).d("Transient focus loss: fading out soundscape")
                engine.suspendForFocusLoss()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Timber.tag(TAG).d("Focus regained: resuming soundscape if suspended")
                engine.resumeAfterFocusGain()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk 30 >= O: the channel can be created unconditionally.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.soundscape_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                engine.stop()
                // The phase observer tears the service down; do not start foreground
                // for a stop request if we are not already running.
                return START_NOT_STICKY
            }
            ACTION_TIMER_EXPIRED -> {
                // Backup path: the engine already fades out at the timer end itself.
                engine.stop(SoundscapeEngine.TIMER_FADE_OUT_MS)
                return START_NOT_STICKY
            }
            else -> startHosting()
        }
        return START_NOT_STICKY
    }

    private fun startHosting() {
        startForegroundCompat(buildNotification())
        if (running) return
        running = true

        requestFocus()
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = serviceScope
        phaseJob = serviceScope.launch {
            launch {
                engine.activeSoundIds.collect {
                    if (running) notificationManager.notify(NOTIFICATION_ID, buildNotification())
                }
            }
            engine.phase.collect { phase ->
                if (phase == SoundscapeEngine.Phase.IDLE && running) {
                    Timber.tag(TAG).d("Engine idle: stopping soundscape service")
                    stopHosting()
                }
            }
        }
    }

    private fun stopHosting() {
        running = false
        phaseJob?.cancel()
        phaseJob = null
        scope?.cancel()
        scope = null
        runCatching { unregisterReceiver(noisyReceiver) }
        abandonFocus()
        cancelTimerAlarm(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun buildNotification(): Notification {
        val summary = engine.activeSoundIds.value
            .mapNotNull { SoundscapeSound.fromId(it) }
            .joinToString(" + ") { getString(it.nameRes) }
            .ifEmpty { getString(R.string.app_name) }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivityIntentContract.ACTION_OPEN_SOUNDSCAPE
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SoundscapeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(getString(R.string.soundscape_notification_title))
            .setContentText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.soundscape_notification_stop), stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        // minSdk 30 >= Q: the typed foreground start is always available.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    override fun onDestroy() {
        if (running) stopHosting()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    companion object {
        private const val TAG = "SoundscapeService"
        const val NOTIFICATION_CHANNEL_ID = "soundscape_playback"
        const val NOTIFICATION_ID = 0x51CCE5

        const val ACTION_START = "com.pisces312.piscesplayer.action.SOUNDSCAPE_START"
        const val ACTION_STOP = "com.pisces312.piscesplayer.action.SOUNDSCAPE_STOP"
        const val ACTION_TIMER_EXPIRED = "com.pisces312.piscesplayer.action.SOUNDSCAPE_TIMER_EXPIRED"

        /**
         * Schedules the backup timer alarm. Safe to call repeatedly; always
         * cancels the previous PendingIntent first.
         */
        fun scheduleTimerBackup(context: Context, durationMs: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = timerPendingIntent(context)
            alarmManager.cancel(pendingIntent)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            android.os.SystemClock.elapsedRealtime() + durationMs,
                            pendingIntent,
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            android.os.SystemClock.elapsedRealtime() + durationMs,
                            pendingIntent,
                        )
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + durationMs,
                        pendingIntent,
                    )
                }
            } catch (e: SecurityException) {
                Timber.tag(TAG).w(e, "Failed to schedule soundscape timer backup")
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + durationMs,
                    pendingIntent,
                )
            }
        }

        fun cancelTimerAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(timerPendingIntent(context))
        }

        private fun timerPendingIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context,
                2,
                Intent(context, SoundscapeService::class.java).apply { action = ACTION_TIMER_EXPIRED },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        fun start(context: Context, durationMs: Long?) {
            val intent = Intent(context, SoundscapeService::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(context, intent)
            if (durationMs != null) scheduleTimerBackup(context, durationMs) else cancelTimerAlarm(context)
        }

        fun stop(context: Context) {
            cancelTimerAlarm(context)
            val intent = Intent(context, SoundscapeService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to deliver soundscape stop")
            }
        }
    }
}
