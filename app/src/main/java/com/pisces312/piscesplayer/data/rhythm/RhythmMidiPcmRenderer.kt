/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (offline MIDI → PCM render for gameplay,
 * docs/../../RhythmChartLab docs/design/rhythm-midi-chart-plan.md §5).
 */
package com.pisces312.piscesplayer.data.rhythm

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.midi.MidiExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * Renders a Standard MIDI File to interleaved PCM offline through Media3's MIDI extractor
 * (the Sonivox synthesizer, the same engine the main player auditions MIDI with — so the
 * chart plays against the sound the user knows from the library). Platform MediaExtractor
 * cannot decode MIDI, which is why [RhythmPcmDecoder] branches here by mime type.
 *
 * Everything is in-memory: the whole file is already a [ByteArray] (chart import read it),
 * and the rendered PCM feeds the same one-shot buffer the DSP path produces.
 */
@OptIn(UnstableApi::class)
internal object RhythmMidiPcmRenderer {

    fun render(bytes: ByteArray): RhythmPcmAudio {
        val trackOutput = CapturingTrackOutput()
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
        return trackOutput.toPcm()
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

    // ---- TrackOutput capturing every rendered sample ------------------------------

    @UnstableApi
    private class CapturingTrackOutput : TrackOutput {

        private val out = ByteArrayOutputStream()
        private var format: Format? = null

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(
            data: androidx.media3.common.DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val buffer = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = data.read(buffer, offset, length - offset)
                if (read == END_OF_INPUT) break
                offset += read
            }
            out.write(buffer, 0, offset)
            return offset
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            out.write(data.data, 0, length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = Unit

        fun toPcm(): RhythmPcmAudio {
            val f = format
                ?: throw RhythmPcmDecoder.DecodeException("MIDI render produced no audio track")
            if (f.sampleMimeType != MimeTypes.AUDIO_RAW || f.pcmEncoding != C.ENCODING_PCM_16BIT) {
                throw RhythmPcmDecoder.DecodeException(
                    "Unsupported MIDI render output: ${f.sampleMimeType}/${f.pcmEncoding}"
                )
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) throw RhythmPcmDecoder.DecodeException("MIDI render produced no data")
            return RhythmPcmAudio(bytes, f.sampleRate, f.channelCount)
        }
    }
}
