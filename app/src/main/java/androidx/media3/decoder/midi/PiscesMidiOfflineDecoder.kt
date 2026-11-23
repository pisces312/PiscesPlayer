/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (offline MIDI synthesis for rhythm gameplay).
 *
 * This file deliberately lives in Media3's MIDI decoder package: `MidiDecoder` is
 * package-private, and its only public entry point (`MidiRenderer`) needs an AudioSink plus a
 * playback clock, i.e. it can only render in real time. Calling the decoder directly (and thus
 * the same Sonivox/JSyn voice set the main player uses) is what makes the offline render
 * possible at CPU speed. Do not move this file; same-package access is the point.
 *
 * Pinned against androidx.media3:media3-exoplayer-midi 1.11.1. If that dependency is upgraded,
 * re-check `MidiDecoder.decode`'s contract (float output, 2 channels, 44.1 kHz) before assuming
 * this still works.
 */
package androidx.media3.decoder.midi

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.DecoderOutputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer

/**
 * Renders Media3 MIDI extractor samples to PCM without an ExoPlayer/AudioSink.
 *
 * Each [renderSample] call corresponds to one extractor sample: the decoder synthesizes the
 * time elapsed since the previous sample, then applies that sample's MIDI message bytes. Empty
 * samples are pure time advances (the extractor emits them every 100 ms, which also bounds the
 * size of every PCM chunk).
 */
@UnstableApi
internal class PiscesMidiOfflineDecoder(context: Context) : AutoCloseable {

    private val decoder = MidiDecoder(context)
    private val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val output = SimpleDecoderOutputBuffer(NoOpOwner)
    private var firstSample = true

    /**
     * Renders the PCM up to [timeUs] and passes the interleaved float samples (2 channels)
     * to [consumer]. [midiBytes] may be empty; the sample is still needed to advance the
     * synthesizer clock.
     */
    fun renderSample(timeUs: Long, midiBytes: ByteArray, consumer: (FloatArray, Int) -> Unit) {
        input.clear()
        input.timeUs = timeUs
        input.ensureSpaceForWrite(midiBytes.size)
        input.data!!.put(midiBytes)
        input.flip()
        val error = decoder.decode(input, output, firstSample)
        firstSample = false
        if (error != null) throw IllegalStateException("MIDI synthesis failed: ${error.message}", error)
        val buffer = output.data ?: return
        val sampleCount = buffer.remaining() / 4
        if (sampleCount <= 0) return
        val samples = FloatArray(sampleCount)
        buffer.asFloatBuffer().get(samples)
        consumer(samples, sampleCount)
    }

    override fun close() {
        decoder.release()
    }

    private companion object {
        val NoOpOwner = object : DecoderOutputBuffer.Owner<SimpleDecoderOutputBuffer> {
            override fun releaseOutputBuffer(outputBuffer: SimpleDecoderOutputBuffer) = Unit
        }
    }
}
