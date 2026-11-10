package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RhythmPcmAudioTest {

    private fun pcm16(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun stereoIsLeftUntouched() {
        val audio = RhythmPcmAudio(pcm16(1, 2, 3, 4), sampleRate = 44_100, channels = 2)

        assertSame(audio, audio.downmixToStereo())
    }

    @Test
    fun surroundKeepsFrameCountAndTakesFirstTwoChannels() {
        // Two frames of 4 channels: (10,20,30,40) and (50,60,70,80).
        val audio = RhythmPcmAudio(
            bytes = pcm16(10, 20, 30, 40, 50, 60, 70, 80),
            sampleRate = 44_100,
            channels = 4,
        )

        val stereo = audio.downmixToStereo()

        assertEquals(2, stereo.channels)
        assertEquals(audio.frames, stereo.frames)
        assertEquals(audio.durationMs, stereo.durationMs, 1e-9)
        assertEquals(pcm16(10, 20, 50, 60).toList(), stereo.bytes.toList())
    }

    @Test
    fun monoConversionAveragesEveryChannel() {
        // Two stereo frames: (1000, -1000) and (2000, 0).
        val audio = RhythmPcmAudio(pcm16(1000, -1000, 2000, 0), sampleRate = 44_100, channels = 2)

        val mono = audio.toMonoFloat()

        assertEquals(2, mono.size)
        assertEquals(0f, mono[0], 1e-6f)
        assertEquals(1000f / 32768f, mono[1], 1e-6f)
    }

    @Test
    fun monoConversionAlreadyMonoIsIdentityScaled() {
        val audio = RhythmPcmAudio(pcm16(-32768, 32767), sampleRate = 48_000, channels = 1)

        val mono = audio.toMonoFloat()

        assertEquals(2, mono.size)
        assertEquals(-1f, mono[0], 1e-6f)
        assertEquals(32767f / 32768f, mono[1], 1e-6f)
    }
}
