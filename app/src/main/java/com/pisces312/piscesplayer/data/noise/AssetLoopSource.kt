/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PiscesPlayer
 * (design/piscesplayer docs/white-noise-implementation-plan.md §4.2).
 */
package com.pisces312.piscesplayer.data.noise

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.RawRes
import java.util.Arrays
import kotlin.concurrent.thread
import timber.log.Timber

/**
 * A [TrackSource] that loops a natural-sound recording from a raw-resource
 * OGG file: decodes it once to PCM (on a background thread, so registering a
 * track never janks the UI), hands the buffer to [PcmLoopPlayer] for the
 * seamless loop, and outputs silence until decoding finishes. The engine's
 * 3-second fade-in masks the (normally sub-second) decode gap.
 *
 * Decoding happens at construction on a dedicated thread; [generate] runs on
 * the audio thread only. A decode failure leaves the source outputting
 * silence rather than crashing the engine.
 */
class AssetLoopSource(
    context: Context,
    @RawRes val resId: Int,
    sampleRate: Int = SoundscapeEngine.SAMPLE_RATE,
) : GainControlledTrackSource {

    @Volatile private var player: PcmLoopPlayer? = null

    /** Gain set before decoding finishes; applied to the player once ready. */
    @Volatile private var pendingGain: Float = 0f

    init {
        val appContext = context.applicationContext
        thread(name = "soundscape-decode", priority = Thread.NORM_PRIORITY) {
            runCatching { decodePcm(appContext, resId, sampleRate) }
                .onSuccess { pcm ->
                    if (pcm != null) {
                        val p = PcmLoopPlayer(pcm, sampleRate)
                        p.setTargetGain(pendingGain)
                        player = p
                    } else {
                        Timber.w("Soundscape asset %s decoded to empty PCM", resId)
                    }
                }
                .onFailure { Timber.w(it, "Soundscape asset %s failed to decode", resId) }
        }
    }

    override fun setTargetGain(value: Float) {
        val g = value.coerceIn(0f, 1f)
        pendingGain = g
        player?.setTargetGain(g)
    }

    override fun generate(out: FloatArray, frames: Int) {
        val p = player
        if (p == null) {
            Arrays.fill(out, 0, frames * 2, 0f)
            return
        }
        p.generate(out, frames)
    }

    /**
     * Decodes the resource to an interleaved stereo float buffer at
     * [targetRate] via MediaExtractor + MediaCodec; returns null when the
     * stream yields less than [PcmLoopPlayer.MIN_SECONDS] of audio.
     */
    private fun decodePcm(context: Context, @RawRes resId: Int, targetRate: Int): FloatArray? {
        val afd = context.resources.openRawResourceFd(resId)
        val extractor = MediaExtractor()
        extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()

        var codec: MediaCodec? = null
        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null

            extractor.selectTrack(trackIndex)
            val inRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                targetRate
            }
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: return null

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val samples = ArrayList<Float>(inRate * (channels + 1))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)
                        if (buf == null) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = decoder.getOutputBuffer(outIdx)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            while (buf.remaining() >= 2) {
                                samples.add(buf.short / 32768f)
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }

            val stereo = toStereoTargetRate(samples.toFloatArray(), inRate, channels, targetRate)
            return if (stereo.size >= PcmLoopPlayer.MIN_SECONDS * 2 * targetRate) stereo else null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Channel-maps (mono→dual mono) and linear-resamples to the target rate. */
    private fun toStereoTargetRate(
        samples: FloatArray,
        inRate: Int,
        inChannels: Int,
        targetRate: Int,
    ): FloatArray {
        val inFrames = samples.size / inChannels
        if (inFrames == 0) return FloatArray(0)
        val outFrames = (inFrames.toLong() * targetRate / inRate).toInt()
        val out = FloatArray(outFrames * 2)
        if (inRate == targetRate) {
            when (inChannels) {
                1 -> {
                    var i = 0
                    var o = 0
                    while (i < samples.size) {
                        val v = samples[i]
                        out[o] = v
                        out[o + 1] = v
                        i++
                        o += 2
                    }
                }
                2 -> System.arraycopy(samples, 0, out, 0, minOf(samples.size, out.size))
                else -> {
                    var i = 0
                    var o = 0
                    while (i + inChannels <= samples.size && o + 1 < out.size) {
                        out[o] = samples[i]
                        out[o + 1] = samples[i + 1]
                        i += inChannels
                        o += 2
                    }
                }
            }
            return out
        }
        val ratio = inRate.toDouble() / targetRate
        for (o in 0 until outFrames) {
            val src = o * ratio
            val i0 = src.toInt().coerceAtMost(inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = (src - i0).toFloat()
            if (inChannels == 1) {
                val s0 = samples[i0]
                val v = s0 + (samples[i1] - s0) * frac
                out[o * 2] = v
                out[o * 2 + 1] = v
            } else {
                val b0 = i0 * inChannels
                val b1 = i1 * inChannels
                out[o * 2] = samples[b0] + (samples[b1] - samples[b0]) * frac
                out[o * 2 + 1] = samples[b0 + 1] + (samples[b1 + 1] - samples[b0 + 1]) * frac
            }
        }
        return out
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
