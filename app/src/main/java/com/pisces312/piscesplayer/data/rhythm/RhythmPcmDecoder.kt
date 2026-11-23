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

/** Decoded PCM audio (16-bit little-endian interleaved). */
data class RhythmPcmAudio(
    val bytes: ByteArray,
    val sampleRate: Int,
    val channels: Int,
) {
    val frames: Long get() = bytes.size.toLong() / 2 / channels
    val durationMs: Double get() = frames * 1000.0 / sampleRate

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

    private fun decodeMidi(context: Context, uri: Uri): RhythmPcmAudio {
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
        val bytes = try {
            when (resolved.scheme) {
                "content" -> context.contentResolver.openInputStream(resolved)?.use { it.readBytes() }
                else -> resolved.path?.let { java.io.File(it).readBytes() }
            }
        } catch (e: Exception) {
            throw DecodeException("Unable to read the MIDI file: ${e.message}", e)
        } ?: throw DecodeException("Unable to read the MIDI file")
        try {
            return RhythmMidiPcmRenderer.render(context, bytes)
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("MIDI render failed: ${e.message}", e)
        }
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
