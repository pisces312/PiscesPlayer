/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (AudioTrack MODE_STREAM playback clock).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

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
        if (track.getTimestamp(timestamp) && timestamp.nanoTime > 0) {
            estimator.offerTimestamp(
                frame = timestamp.framePosition.toDouble(),
                timestampNs = timestamp.nanoTime,
                monoNs = System.nanoTime(),
                bootNs = bootNs,
            )
        }
        // Always sample the playback head too: it is the one position every device reports and
        // it stops advancing on an underrun, so it is what keeps the clock locked to the audio
        // instead of drifting further behind it every second. The estimator ignores it while a
        // precise AudioTimestamp is still fresh.
        estimator.offerHeadPosition(
            frame = (track.playbackHeadPosition.toLong() and HEAD_MASK).toDouble(),
            bootNs = bootNs,
        )
        return estimator.currentFrame(bootNs)
    }

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
