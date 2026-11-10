/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (AudioTrack MODE_STREAM playback clock).
 */
package com.pisces312.piscesplayer.data.rhythm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log

/** Song clock abstraction is defined in RhythmSongClock.kt. */

/**
 * AudioTrack output clock: audible song time is derived from audio output frames
 * (AudioTimestamp, falling back to the playback head), not from a UI progress poll.
 *
 * Real timestamps arrive at limited precision and are missing entirely on some devices, so
 * the clock interpolates with a monotonic clock between reports and is continuously nudged
 * back onto the output position — following the audio rather than drifting away from it.
 * MODE_STATIC cannot hold full-song PCM on common devices, so MODE_STREAM feeds PCM from a
 * dedicated writer thread while play control shares [streamLock]. The buffer is primed
 * before `play()` so the first audible frame is not delayed by a startup underrun.
 */
class RhythmAudioClock(
    private val audio: RhythmPcmAudio,
    override var offsetMs: Double = 0.0,
) : RhythmSongClock {

    private val track: AudioTrack = createTrack().also {
        check(it.state == AudioTrack.STATE_INITIALIZED) {
            "AudioTrack init failed: ${audio.bytes.size} bytes, ${audio.sampleRate} Hz"
        }
    }

    private val estimator = RhythmFrameEstimator(audio.sampleRate, audio.frames)
    private val streamLock = java.lang.Object()

    @Volatile private var streamPlaying = false
    @Volatile private var released = false
    private var writeOffsetBytes = 0

    private val writerThread = Thread(::writerLoop, "PixelRhythmAudioWriter").apply {
        isDaemon = true
        start()
    }

    override val durationMs: Double get() = audio.durationMs
    override val isPlaying: Boolean get() = estimator.isPlaying
    private val timestamp = AudioTimestamp()
    @Volatile private var lastSample = RhythmClockSample(0.0, 0.0, Double.NaN, ClockSource.WALL)

    override fun start() {
        synchronized(streamLock) {
            if (released) return
            streamPlaying = false
            track.pause()
            track.flush()
            track.stop()
            writeOffsetBytes = 0
            // Prime before play(): starting on an empty buffer underruns immediately, so the
            // first audible frame lands tens of ms after the clock anchor and every note jumps.
            primeBuffer()
            estimator.start(SystemClock.elapsedRealtimeNanos())
            streamPlaying = true
            track.play()
            streamLock.notifyAll()
        }
    }

    /** Fill the track buffer while stopped so `play()` starts on real data, not silence. */
    private fun primeBuffer() {
        while (writeOffsetBytes < audio.bytes.size) {
            val count = minOf(WRITE_CHUNK_BYTES, audio.bytes.size - writeOffsetBytes)
            val result = track.write(
                audio.bytes,
                writeOffsetBytes,
                count,
                AudioTrack.WRITE_NON_BLOCKING,
            )
            if (result <= 0) break // buffer full (or not accepting more): enough runway
            writeOffsetBytes += result
        }
    }

    override fun pause() {
        if (!estimator.isPlaying) return
        synchronized(streamLock) {
            estimator.pauseAt(readFrame())
            streamPlaying = false
            track.pause()
            streamLock.notifyAll()
        }
    }

    override fun resume() {
        if (estimator.isPlaying || released) return
        synchronized(streamLock) {
            estimator.resumeFrom(estimator.frozenFrame, SystemClock.elapsedRealtimeNanos())
            streamPlaying = true
            track.play()
            streamLock.notifyAll()
        }
    }

    override fun restart() = start()

    override fun stop() {
        synchronized(streamLock) {
            if (released) return
            streamPlaying = false
            track.pause()
            track.flush()
            track.stop()
            writeOffsetBytes = 0
            estimator.pauseAt(0.0)
            streamLock.notifyAll()
        }
    }

    override val finished: Boolean
        get() = !estimator.isPlaying && estimator.frozenFrame >= audio.frames

    override fun nowMs(): Double = readFrame() / audio.sampleRate * 1000.0 + offsetMs

    override fun release() {
        synchronized(streamLock) {
            if (released) return
            released = true
            streamPlaying = false
            streamLock.notifyAll()
        }
        writerThread.join(500)
        synchronized(streamLock) {
            track.pause()
            track.stop()
            track.release()
        }
    }

    private fun readFrame(): Double {
        val bootNs = SystemClock.elapsedRealtimeNanos()
        // After release() the AudioTrack native pointer is gone; getTimestamp()/
        // getPlaybackHeadPosition() then throw IllegalStateException. The game loop can still
        // read one more frame on the Choreographer callback after exitGame() released the track,
        // so bail onto the pure estimator (no device access) instead of crashing.
        if (released) return estimator.currentFrame(bootNs)
        // Hold streamLock across the device reads so they are mutually exclusive with
        // track.release() in release(); re-check released inside, since it may have flipped
        // between the guard above and acquiring the lock. Reentrant: pause() calls readFrame()
        // while already holding this lock.
        synchronized(streamLock) {
            if (released) return estimator.currentFrame(bootNs)
            var timestampMs = Double.NaN
            var source = ClockSource.WALL
            if (track.getTimestamp(timestamp) && timestamp.nanoTime > 0) {
                timestampMs = timestamp.framePosition.toDouble() / audio.sampleRate * 1000.0
                val accepted = estimator.offerTimestamp(
                    frame = timestamp.framePosition.toDouble(),
                    timestampNs = timestamp.nanoTime,
                    monoNs = System.nanoTime(),
                    bootNs = bootNs,
                )
                if (accepted) source = ClockSource.TIMESTAMP
            }
            // Always sample the playback head too: it is the one position every device reports
            // and it stops advancing on an underrun, so it is what keeps the clock locked to the
            // audio instead of drifting further behind it every second. The estimator ignores it
            // while a precise AudioTimestamp is still fresh.
            val headFrame = (track.playbackHeadPosition.toLong() and HEAD_MASK).toDouble()
            val headMoved = estimator.offerHeadPosition(headFrame, bootNs)
            if (source == ClockSource.WALL && headMoved) source = ClockSource.HEAD
            val frame = estimator.currentFrame(bootNs)
            lastSample = RhythmClockSample(
                songMs = frame / audio.sampleRate * 1000.0 + offsetMs,
                headMs = headFrame / audio.sampleRate * 1000.0,
                timestampMs = timestampMs,
                source = source,
            )
            return frame
        }
    }

    /**
     * The raw clock reading captured by the most recent [nowMs]: what the game clock reported
     * against what the output device actually rendered. Diagnostics only; does not touch the
     * device again, so calling it per frame is free.
     */
    fun diagnosticsSample(): RhythmClockSample = lastSample

    private fun writerLoop() {
        while (true) {
            var wrote = 0
            synchronized(streamLock) {
                while (!released && (!streamPlaying || writeOffsetBytes >= audio.bytes.size)) {
                    streamLock.wait()
                }
                if (released) return

                val count = minOf(WRITE_CHUNK_BYTES, audio.bytes.size - writeOffsetBytes)
                val result = track.write(
                    audio.bytes,
                    writeOffsetBytes,
                    count,
                    AudioTrack.WRITE_NON_BLOCKING,
                )
                if (result > 0) {
                    writeOffsetBytes += result
                    wrote = result
                } else if (result < 0) {
                    Log.e(TAG, "AudioTrack write failed: $result")
                    streamPlaying = false
                }
            }
            if (wrote == 0) {
                Thread.sleep(2)
            }
        }
    }

    private fun createTrack(): AudioTrack {
        val mask = channelMask(audio.channels)
        val minBuffer = AudioTrack.getMinBufferSize(audio.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0) { "AudioTrack params invalid: minBuffer=$minBuffer" }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer * 4, STREAM_BUFFER_MIN_BYTES))
            .build()
    }

    private fun channelMask(channels: Int): Int =
        if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO

    companion object {
        private const val TAG = "RhythmAudioClock"
        private const val STREAM_BUFFER_MIN_BYTES = 64 * 1024
        private const val WRITE_CHUNK_BYTES = 64 * 1024

        /** `playbackHeadPosition` is an unsigned 32-bit frame count. */
        private const val HEAD_MASK = 0xFFFFFFFFL
    }
}
