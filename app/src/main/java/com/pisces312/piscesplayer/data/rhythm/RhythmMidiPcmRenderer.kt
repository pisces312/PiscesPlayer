/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (offline MIDI → PCM render for gameplay,
 * docs/../../RhythmChartLab docs/design/rhythm-midi-chart-plan.md §5).
 */
package com.pisces312.piscesplayer.data.rhythm

import android.content.Context
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.midi.MidiExtractor
import androidx.media3.decoder.midi.PiscesMidiOfflineDecoder
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Renders a Standard MIDI File to interleaved 16-bit PCM offline.
 *
 * Media3's MIDI extractor does **not** produce PCM: it emits raw MIDI messages tagged
 * `audio/x-exoplayer-midi`, and [androidx.media3.decoder.midi.MidiDecoder] is what turns those
 * messages into float PCM using the Sonivox voices. The decoder is package-private, so
 * [PiscesMidiOfflineDecoder] drives it from the same package at CPU speed — the resulting audio
 * is the same voice set the main player auditions MIDI with, without waiting out a real-time
 * playthrough.
 *
 * Everything is in-memory: the whole file is already a [ByteArray] (chart import read it),
 * and the rendered PCM feeds the same one-shot buffer the DSP path produces.
 */
@UnstableApi
internal object RhythmMidiPcmRenderer {

    /** MidiDecoder's fixed output format (float PCM, two channels, 44.1 kHz). */
    private const val OUTPUT_SAMPLE_RATE = 44100
    private const val OUTPUT_CHANNELS = 2
    private const val BYTES_PER_SECOND = OUTPUT_SAMPLE_RATE * OUTPUT_CHANNELS * 2
    private const val BYTES_PER_FRAME = OUTPUT_CHANNELS * 2

    /** Extra rendered time so the final note's release tail is not cut off. */
    private const val RELEASE_TAIL_MS = 250L

    /** Preallocation headroom for per-chunk frame rounding (clamped away on overflow). */
    private const val RENDER_SLACK_BYTES = 8 * 1024
    fun render(context: Context, bytes: ByteArray): RhythmPcmAudio {
        val stream = renderStreaming(context, bytes)
        val fill = stream.fill!!
        fill.await(Int.MAX_VALUE)
        fill.failure?.let {
            throw RhythmPcmDecoder.DecodeException("MIDI render failed: ${it.message}", it)
        }
        // readyBytes (not renderedBytes) is the actual rendered length here: trailing
        // allocation slack is silence and is trimmed so a blocking render is byte-identical
        // to the old ByteArrayOutputStream output.
        val ready = fill.readyBytes
        if (ready == 0) throw RhythmPcmDecoder.DecodeException("MIDI render produced no audio")
        return RhythmPcmAudio(
            bytes = stream.bytes.copyOf(ready),
            sampleRate = OUTPUT_SAMPLE_RATE,
            channels = OUTPUT_CHANNELS,
        )
    }

    /**
     * Start the synthesis on a background thread and return immediately.
     *
     * The buffer is preallocated from the render estimate, so [RhythmPcmAudio.frames] (the
     * game clock's total) is known up front while [RhythmPcmFill.readyBytes] grows as the
     * Sonivox pass produces PCM — this is what lets the game start after a short runway
     * instead of waiting out a full-song synthesis (see `RhythmPcmFill`). The allocation
     * carries a small slack: per-chunk frame rounding can exceed the length estimate by a
     * frame or two per sample, and the overflow is clamped away (it is silence past the
     * 250 ms release tail anyway).
     */
    fun renderStreaming(context: Context, bytes: ByteArray): RhythmPcmAudio {
        val samples = extractSamples(bytes)
        val parsedDurationUs = try {
            // The chart generator's parser knows the musical end; padding the render to it
            // covers a last note whose note-off is missing from the file. Playback must not
            // fail just because the optional duration pass could not parse this file.
            RhythmMidiParser.parse(bytes).durationMs.toLong() * 1000L
        } catch (e: Exception) {
            null
        }
        val lastSampleUs = samples.lastOrNull()?.timeUs ?: 0L
        val endUs = maxOf(lastSampleUs, parsedDurationUs ?: 0L) + RELEASE_TAIL_MS * 1000L
        val estimateBytes = (endUs * BYTES_PER_SECOND / 1_000_000L).toInt()
        val totalBytes = ((estimateBytes + RENDER_SLACK_BYTES + BYTES_PER_FRAME - 1) /
            BYTES_PER_FRAME) * BYTES_PER_FRAME
        val out = ByteArray(totalBytes)
        val fill = RhythmPcmFill()
        val audio = RhythmPcmAudio(
            bytes = out,
            sampleRate = OUTPUT_SAMPLE_RATE,
            channels = OUTPUT_CHANNELS,
            frameCount = (totalBytes / BYTES_PER_FRAME).toLong(),
            fill = fill,
        )
        val thread = Thread(
            {
                var error: Exception? = null
                try {
                    renderInto(out, samples, endUs, context, fill)
                } catch (e: Exception) {
                    error = e
                } finally {
                    // Publish whatever was rendered (the tail past a failure is silence) so
                    // consumers never block on bytes that will never come.
                    fill.complete(error)
                }
            },
            "PixelRhythmMidiRender",
        )
        thread.isDaemon = true
        thread.start()
        return audio
    }

    /** Synthesize [samples] into [out], publishing progress through [fill] as chunks land. */
    private fun renderInto(
        out: ByteArray,
        samples: List<MidiSample>,
        endUs: Long,
        context: Context,
        fill: RhythmPcmFill,
    ) {
        val synth = PiscesMidiOfflineDecoder(context)
        var writePos = 0
        try {
            for (sample in samples) {
                if (fill.cancelled) return
                synth.renderSample(sample.timeUs, sample.data) { floats, count ->
                    writePos = appendPcm16(out, writePos, floats, count)
                }
                fill.publish(writePos)
            }
            // Advance the synth to the musical end so the final voices can release.
            val lastUs = samples.lastOrNull()?.timeUs ?: 0L
            if (endUs > lastUs && !fill.cancelled) {
                synth.renderSample(endUs, EMPTY_BYTES) { floats, count ->
                    writePos = appendPcm16(out, writePos, floats, count)
                }
                fill.publish(writePos)
            }
        } finally {
            synth.close()
        }
    }

    // ---- Extractor pass: raw MIDI message samples with their timestamps -------------

    private data class MidiSample(val timeUs: Long, val data: ByteArray)

    private val EMPTY_BYTES = ByteArray(0)

    private fun extractSamples(bytes: ByteArray): List<MidiSample> {
        val samples = ArrayList<MidiSample>()
        val trackOutput = CapturingTrackOutput(samples)
        val extractor = MidiExtractor()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = trackOutput

            override fun endTracks() = Unit

            override fun seekMap(seekMap: SeekMap) = Unit
        })
        try {
            val input = BytesExtractorInput(bytes)
            if (!extractor.sniff(input)) {
                throw RhythmPcmDecoder.DecodeException("Not a MIDI file")
            }
            extractor.seek(0, 0)
            val positionHolder = PositionHolder()
            while (true) {
                when (extractor.read(input, positionHolder)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_END_OF_INPUT -> break
                    Extractor.RESULT_SEEK -> input.seekTo(positionHolder.position)
                    else -> throw RhythmPcmDecoder.DecodeException("Unexpected MIDI extractor result")
                }
            }
        } finally {
            extractor.release()
        }
        return samples
    }

    /**
     * Converts MidiDecoder's float PCM into the little-endian 16-bit format AudioTrack wants,
     * appending at [writePos] in [out]. Overflow past the buffer (only possible by the render
     * slack) is dropped: it sits past the release tail and is inaudible either way.
     */
    private fun appendPcm16(out: ByteArray, writePos: Int, floats: FloatArray, count: Int): Int {
        var pos = writePos
        for (i in 0 until count) {
            if (pos + 1 >= out.size) return pos
            val value = (floats[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            out[pos++] = (value and 0xFF).toByte()
            out[pos++] = ((value shr 8) and 0xFF).toByte()
        }
        return pos
    }

    // ---- ExtractorInput over an in-memory file -----------------------------------

    /** [ExtractorInput.read]/[ExtractorInput.skip]/[ExtractorInput.peek] end-of-data marker. */
    private const val END_OF_INPUT = -1

    @UnstableApi
    private class BytesExtractorInput(private val data: ByteArray) : ExtractorInput {

        private var position = 0
        private var peekPosition = 0

        fun seekTo(newPosition: Long) {
            position = newPosition.toInt().coerceIn(0, data.size)
            peekPosition = position
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return END_OF_INPUT
            val count = minOf(length, data.size - position)
            System.arraycopy(data, position, buffer, offset, count)
            position += count
            return count
        }

        @Throws(EOFException::class)
        override fun readFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (data.size - position < length) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            System.arraycopy(data, position, buffer, offset, length)
            position += length
            return true
        }

        override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
            readFully(buffer, offset, length, false)
        }

        override fun skip(length: Int): Int {
            if (position >= data.size) return END_OF_INPUT
            val count = minOf(length, data.size - position)
            position += count
            return count
        }

        @Throws(EOFException::class)
        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (data.size - position < length) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            position += length
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(buffer: ByteArray, offset: Int, length: Int): Int {
            if (peekPosition >= data.size) return END_OF_INPUT
            val count = minOf(length, data.size - peekPosition)
            System.arraycopy(data, peekPosition, buffer, offset, count)
            peekPosition += count
            return count
        }

        @Throws(EOFException::class)
        override fun peekFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (data.size - peekPosition < length) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            System.arraycopy(data, peekPosition, buffer, offset, length)
            peekPosition += length
            return true
        }

        override fun peekFully(buffer: ByteArray, offset: Int, length: Int) {
            peekFully(buffer, offset, length, false)
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (data.size - peekPosition < length) {
                if (allowEndOfInput) return false
                throw EOFException()
            }
            peekPosition += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun resetPeekPosition() {
            peekPosition = position
        }

        override fun getPeekPosition(): Long = peekPosition.toLong()

        override fun getPosition(): Long = position.toLong()

        override fun getLength(): Long = data.size.toLong()

        override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
            seekTo(position)
            throw e
        }
    }

    // ---- TrackOutput capturing every MIDI sample ------------------------------

    @UnstableApi
    private class CapturingTrackOutput(private val samples: MutableList<MidiSample>) : TrackOutput {

        private val pending = ByteArrayOutputStream()

        override fun format(format: Format) = Unit

        override fun sampleData(
            data: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val buffer = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = data.read(buffer, offset, length - offset)
                if (read == END_OF_INPUT) {
                    if (offset == 0 && allowEndOfInput) return END_OF_INPUT
                    break
                }
                offset += read
            }
            pending.write(buffer, 0, offset)
            return offset
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (length <= 0) return
            // TrackOutput's contract: read from the current position and advance past it.
            // MidiExtractor relies on that advance for meta events.
            pending.write(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            samples += MidiSample(timeUs, pending.toByteArray())
            pending.reset()
        }
    }
}
