package com.pisces312.piscesplayer.data.rhythm

import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Test-only audio helpers: a deterministic drum loop for pipeline tests and a minimal
 * 16-bit mono RIFF reader for the golden fixture (no Android, no third-party decoder).
 */
internal object RhythmTestAudio {

    /** Kick on every beat, noise hi-hat on every half beat. */
    fun drumLoop(
        durationSeconds: Double = 20.0,
        sampleRate: Int = 44100,
        bpm: Double = 120.0,
        seed: Int = 7,
    ): FloatArray {
        val total = (durationSeconds * sampleRate).toInt()
        val output = FloatArray(total)
        val beat = 60.0 / bpm
        val random = Random(seed)

        var index = 0
        while (index * beat < durationSeconds) {
            val start = (index * beat * sampleRate).toInt()
            val length = (0.15 * sampleRate).toInt()
            if (start + length > total) break
            var phase = 0.0
            for (offset in 0 until length) {
                val time = offset.toDouble() / sampleRate
                val frequency = 42.0 + 55.0 * exp(-time / 0.03)
                phase += 2.0 * PI * frequency / sampleRate
                output[start + offset] += (0.9 * sin(phase) * exp(-time / 0.045)).toFloat()
            }
            index++
        }

        index = 0
        while (index * beat / 2 < durationSeconds) {
            val start = (index * beat / 2 * sampleRate).toInt()
            val length = (0.04 * sampleRate).toInt()
            if (start + length <= total) {
                for (offset in 0 until length) {
                    val time = offset.toDouble() / sampleRate
                    val noise = random.nextFloat() * 2f - 1f
                    output[start + offset] += (0.25 * noise * exp(-time / 0.010)).toFloat()
                }
            }
            index++
        }
        return output
    }

    /** Reads a 16-bit mono PCM WAV into [-1, 1] floats. */
    fun readMonoWav16(file: File): Pair<FloatArray, Int> {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "wav too small: ${file.name}" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a RIFF file" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAVE file" }

        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataOffset = -1
        var dataLength = 0
        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val id = String(bytes, cursor, 4, Charsets.US_ASCII)
            val size = readIntLe(bytes, cursor + 4)
            val body = cursor + 8
            when (id) {
                "fmt " -> {
                    channels = readShortLe(bytes, body + 2)
                    sampleRate = readIntLe(bytes, body + 4)
                    bits = readShortLe(bytes, body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataLength = size
                }
            }
            cursor = body + size + (size % 2)
        }
        require(dataOffset > 0) { "wav has no data chunk" }
        require(channels == 1) { "expected mono, got $channels channels" }
        require(bits == 16) { "expected 16-bit, got $bits bits" }

        val count = minOf(dataLength, bytes.size - dataOffset) / 2
        val samples = FloatArray(count)
        for (index in 0 until count) {
            val low = bytes[dataOffset + 2 * index].toInt() and 0xFF
            val high = bytes[dataOffset + 2 * index + 1].toInt()
            val value = ((high shl 8) or low).toShort()
            samples[index] = value / 32768f
        }
        return Pair(samples, sampleRate)
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
