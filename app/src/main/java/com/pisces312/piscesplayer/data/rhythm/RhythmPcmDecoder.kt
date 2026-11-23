/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work, adapted in 2026 from the author's local RhythmChartLab prototype
 * (MediaExtractor + MediaCodec one-shot PCM decode).
 */
package com.pisces312.piscesplayer.data.rhythm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Progressive-fill state of a streaming decode ([RhythmPcmDecoder.decodeStreaming]).
 *
 * The owning [RhythmPcmAudio.bytes] is preallocated at full size and filled by a background
 * render thread; readers must stay at or below [readyBytes]. Completion (success or failure)
 * publishes the whole buffer so a consumer never waits on bytes that will never come.
 */
class RhythmPcmFill {
    private val lock = Object()

    /** Bytes of the buffer that currently hold rendered audio. */
    @Volatile
    var readyBytes: Int = 0
        private set

    @Volatile
    var done: Boolean = false
        private set

    /** Set when the render died mid-way; the tail past the failure point is silence. */
    @Volatile
    var failure: Exception? = null
        private set

    @Volatile
    var cancelled: Boolean = false

    /** Publish newly rendered bytes and wake readers. */
    fun publish(ready: Int) {
        synchronized(lock) {
            readyBytes = ready
            lock.notifyAll()
        }
    }

    /** Mark the render finished ([error] = null on success) and wake readers. */
    fun complete(error: Exception?) {
        synchronized(lock) {
            failure = error
            done = true
            lock.notifyAll()
        }
    }

    /** Block until at least [minBytes] are rendered or the render finished. */
    fun await(minBytes: Int) {
        synchronized(lock) {
            while (!done && readyBytes < minBytes) lock.wait()
        }
    }

    /** Ask the render thread to stop early (screen exit). */
    fun cancel() {
        cancelled = true
    }
}

/** Decoded PCM audio (16-bit little-endian interleaved). */
data class RhythmPcmAudio(
    val bytes: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    /**
     * Effective frame count when [bytes] carries trailing capacity slack (streaming MIDI
     * render); -1 derives it from the buffer size as usual.
     */
    val frameCount: Long = -1L,
    /** Non-null while a background render is still filling [bytes]; null = complete. */
    val fill: RhythmPcmFill? = null,
) {
    val frames: Long
        get() = if (frameCount >= 0) frameCount else bytes.size.toLong() / 2 / channels

    val durationMs: Double get() = frames * 1000.0 / sampleRate

    /**
     * Bytes of [bytes] that may be consumed right now. While a render is running this is its
     * watermark; once it completes (success or failure) the whole buffer is consumable — the
     * tail past a failure point is silence and must still drain so playback can end cleanly.
     */
    fun renderedBytes(): Int {
        val fill = fill ?: return bytes.size
        return if (fill.done) bytes.size else fill.readyBytes
    }

    /** Block until at least [minBytes] (clamped to the buffer) are rendered; no-op when complete. */
    fun awaitRendered(minBytes: Int) {
        fill?.await(minOf(minBytes, bytes.size))
    }

    override fun equals(other: Any?): Boolean =
        other is RhythmPcmAudio &&
            other.sampleRate == sampleRate &&
            other.channels == channels &&
            other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + sampleRate

    /**
     * AudioTrack is opened as mono/stereo only, so surround sources must be folded
     * down here; otherwise the interleaved data plays at the wrong speed.
     */
    /** Mono floats in [-1, 1] for spectrum analysis; matches `ffmpeg -ac 1` downmixing. */
    fun toMonoFloat(): FloatArray {
        val frameCount = frames.toInt()
        val output = FloatArray(frameCount)
        val scale = 1f / (channels * 32768f)
        var source = 0
        for (frame in 0 until frameCount) {
            var sum = 0
            for (channel in 0 until channels) {
                val low = bytes[source].toInt() and 0xFF
                val high = bytes[source + 1].toInt()
                sum += ((high shl 8) or low).toShort()
                source += 2
            }
            output[frame] = sum * scale
        }
        return output
    }

    fun downmixToStereo(): RhythmPcmAudio {
        if (channels <= 2) return this
        val frameCount = frames.toInt()
        val out = ByteArray(frameCount * 4)
        var src = 0
        var dst = 0
        repeat(frameCount) {
            out[dst] = bytes[src]
            out[dst + 1] = bytes[src + 1]
            out[dst + 2] = bytes[src + 2]
            out[dst + 3] = bytes[src + 3]
            dst += 4
            src += 2 * channels
        }
        return RhythmPcmAudio(out, sampleRate, 2)
    }
}

/**
 * Compressed audio -> PCM one-shot decode.
 *
 * MVP trade-off: decode the whole song into memory (~40MB per 5 minutes stereo
 * 44.1kHz) to keep a low-latency AudioTrack and a reliable output frame clock.
 * Streaming decode is a later performance milestone.
 */
object RhythmPcmDecoder {
    class DecodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private const val SCHEME_PISCESMIDI = "piscesmidi"

    /**
     * One-shot decode for gameplay.
     *
     * [mimeType] is the song's declared mime (nullable); MIDI is not decodable through
     * MediaExtractor, so MIDI uris (by mime, extension or the `piscesmidi` scheme of the
     * built-in classical library) branch into [RhythmMidiPcmRenderer], which renders the
     * file through the same Media3/Sonivox engine the main player uses.
     */
    fun decode(context: Context, uri: Uri, mimeType: String? = null): RhythmPcmAudio {
        if (isMidi(uri, mimeType)) return decodeMidi(context, uri)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw DecodeException("Unable to open the song for gameplay: ${e.message}", e)
        }
        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            val audioFormat = format ?: throw DecodeException("The song has no audio track")
            extractor.selectTrack(trackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()
            return drain(decoder, extractor)
        } finally {
            extractor.release()
        }
    }

    private fun isMidi(uri: Uri, mimeType: String?): Boolean {
        if (mimeType?.contains("midi", ignoreCase = true) == true) return true
        val last = uri.lastPathSegment ?: return uri.scheme == SCHEME_PISCESMIDI
        return last.endsWith(".mid", ignoreCase = true) ||
            last.endsWith(".midi", ignoreCase = true) ||
            uri.scheme == SCHEME_PISCESMIDI
    }

    /**
     * Streaming decode for the game start path.
     *
     * MIDI synthesis is the slow half of a game start (the whole song is software-rendered,
     * roughly 10x real time), so [RhythmMidiPcmRenderer.renderStreaming] runs it on a
     * background thread and this returns once [minReadyMs] of PCM is buffered — the clock
     * then streams from the buffer while the render keeps filling it (see [RhythmPcmFill]).
     * The MediaCodec path stays one-shot (hardware decode, seconds at most).
     */
    fun decodeStreaming(
        context: Context,
        uri: Uri,
        mimeType: String? = null,
        minReadyMs: Long = 8_000,
    ): RhythmPcmAudio {
        if (!isMidi(uri, mimeType)) return decode(context, uri, mimeType)
        val audio = try {
            RhythmMidiPcmRenderer.renderStreaming(context, readMidiBytes(context, uri))
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("MIDI render failed: ${e.message}", e)
        }
        val fill = audio.fill!!
        audio.awaitRendered((minReadyMs * audio.sampleRate * audio.channels * 2 / 1000).toInt())
        if (fill.readyBytes == 0) {
            fill.failure?.let { throw DecodeException("MIDI render failed: ${it.message}", it) }
            throw DecodeException("MIDI render produced no audio")
        }
        return audio
    }

    private fun decodeMidi(context: Context, uri: Uri): RhythmPcmAudio {
        val bytes = readMidiBytes(context, uri)
        try {
            return RhythmMidiPcmRenderer.render(context, bytes)
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("MIDI render failed: ${e.message}", e)
        }
    }

    /** Resolve a MIDI uri (including the `piscesmidi` built-in scheme) to file bytes. */
    private fun readMidiBytes(context: Context, uri: Uri): ByteArray {
        // Built-in classical library: piscesmidi://<songId> -> extracted file
        // (same resolution as DualPlayerEngine's ResolvingDataSource).
        val resolved = if (uri.scheme == SCHEME_PISCESMIDI) {
            val id = uri.authority ?: uri.lastPathSegment
            val file = id?.toLongOrNull()?.let { java.io.File(context.filesDir, "classical/$it.mid") }
            if (file == null || !file.isFile) {
                throw DecodeException("Built-in MIDI not extracted yet: $uri")
            }
            Uri.fromFile(file)
        } else {
            uri
        }
        return try {
            when (resolved.scheme) {
                "content" -> context.contentResolver.openInputStream(resolved)?.use { it.readBytes() }
                else -> resolved.path?.let { java.io.File(it).readBytes() }
            }
        } catch (e: Exception) {
            throw DecodeException("Unable to read the MIDI file: ${e.message}", e)
        } ?: throw DecodeException("Unable to read the MIDI file")
    }

    private fun drain(decoder: MediaCodec, extractor: MediaExtractor): RhythmPcmAudio {
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sampleRate = 44100
        var channels = 2
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val buffer = decoder.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            val chunk = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(chunk)
                            out.write(chunk)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val decoded = decoder.outputFormat
                        sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) throw DecodeException("Audio decode produced no data")
        return RhythmPcmAudio(bytes, sampleRate, channels).downmixToStereo()
    }
}
