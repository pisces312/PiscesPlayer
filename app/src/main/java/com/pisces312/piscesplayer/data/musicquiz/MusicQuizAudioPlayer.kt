package com.pisces312.piscesplayer.data.musicquiz

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber

/** A screen-owned player: no service, MediaSession, notification, queue, or listening statistics. */
internal class MusicQuizAudioPlayer(
    context: Context,
    private val callbacks: Callbacks,
) {
    internal interface Callbacks {
        fun onPlaybackStarted(token: Long)
        fun onClipEnded(token: Long)
        fun onPlaybackInterrupted(token: Long)
        fun onPlaybackError(token: Long)
    }

    private var activeToken: Long? = null
    private var hasStarted = false
    private var intentionalPause = false
    private var released = false

    /** True while a clip is loaded and its end/error callback has not yet consumed the token. */
    val hasActiveClip: Boolean get() = activeToken != null

    private val listener: Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val token = activeToken ?: return
            if (isPlaying) {
                hasStarted = true
                callbacks.onPlaybackStarted(token)
                return
            }
            if (!hasStarted || intentionalPause || player.playbackState == Player.STATE_ENDED) {
                return
            }
            // Buffering, focus loss and becoming-noisy all require an explicit user resume.
            // Do not infer an intentional pause from playWhenReady: the system can clear it too.
            intentionalPause = true
            player.pause()
            callbacks.onPlaybackInterrupted(token)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val token = activeToken ?: return
            if (playbackState == Player.STATE_ENDED && hasStarted) {
                activeToken = null
                callbacks.onClipEnded(token)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val token = activeToken ?: return
            activeToken = null
            Timber.e(error, "Music quiz clip playback failed")
            callbacks.onPlaybackError(token)
        }
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_GAME)
                .build(),
            true,
        )
        setHandleAudioBecomingNoisy(true)
        addListener(listener)
    }

    fun play(question: MusicQuizQuestion, token: Long, autoplay: Boolean = true) {
        if (released) return
        stop()
        activeToken = token
        hasStarted = false
        intentionalPause = false
        val clipEndMs = question.clipStartMs + question.clipDurationMs
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(question.track.playbackUri))
            .setMediaId("music_quiz_$token")
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(question.clipStartMs)
                    .setEndPositionMs(clipEndMs)
                    .build(),
            )
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        if (autoplay) {
            player.play()
        } else {
            intentionalPause = true
            player.playWhenReady = false
        }
    }

    fun pause() {
        if (released) return
        intentionalPause = true
        player.pause()
    }

    fun resume() {
        if (released || activeToken == null) return
        intentionalPause = false
        player.play()
    }

    fun stop() {
        if (released) return
        activeToken = null
        hasStarted = false
        intentionalPause = true
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        if (released) return
        stop()
        released = true
        player.removeListener(listener)
        player.release()
    }
}
