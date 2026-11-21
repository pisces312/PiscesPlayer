package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmLatencyCalibrationTest {

    private val tickStepNs = RhythmCalibrationTrack.PERIOD_MS * 1_000_000L

    /** Tick wall times at the real metronome spacing (one every PERIOD_MS from 1 s). */
    private fun walls(count: Int = RhythmCalibrationTrack.TICK_COUNT): List<Long> =
        (0 until count).map {
            (RhythmCalibrationTrack.FIRST_TICK_MS * 1_000_000L) + it * tickStepNs
        }

    private fun readPcm(audio: RhythmPcmAudio, frame: Int, channel: Int = 0): Short {
        val offset = (frame * audio.channels + channel) * 2
        val low = audio.bytes[offset].toInt() and 0xFF
        val high = audio.bytes[offset + 1].toInt()
        return ((high shl 8) or low).toShort()
    }

    private fun isSilent(audio: RhythmPcmAudio, frame: Int): Boolean =
        (0 until audio.channels).all { readPcm(audio, frame, it).toInt() == 0 }

    @Test
    fun calibrationTrackPlacesTicksAtTheDeclaredSongTimes() {
        val sampleRate = 8000
        val audio = RhythmCalibrationTrack.build(sampleRate = sampleRate, channels = 2)

        // Tail is PERIOD_MS + 100 of silence after the last tick (the final tap lands inside).
        val tailMs = RhythmCalibrationTrack.PERIOD_MS + 100
        val expectedFrames = (
            RhythmCalibrationTrack.FIRST_TICK_MS +
                (RhythmCalibrationTrack.TICK_COUNT - 1) * RhythmCalibrationTrack.PERIOD_MS +
                tailMs
            ) * sampleRate / 1000
        assertEquals(expectedFrames.toLong(), audio.frames)
        assertEquals((expectedFrames * 2 * 2).toLong(), audio.bytes.size.toLong())

        for (tick in 0 until RhythmCalibrationTrack.TICK_COUNT) {
            val tickFrame = (
                RhythmCalibrationTrack.tickSongMs(tick) * sampleRate / 1000 +
                    RhythmCalibrationTrack.TICK_MS * sampleRate / 2000
                ).toInt()
            assertTrue("tick $tick should be audible at its midpoint", !isSilent(audio, tickFrame))
        }

        // Between ticks the track is silent (midpoint of the first gap).
        val gapFrame =
            ((RhythmCalibrationTrack.FIRST_TICK_MS + RhythmCalibrationTrack.PERIOD_MS / 2) *
                sampleRate / 1000)
        assertTrue(isSilent(audio, gapFrame))
        // And the tail after the last tick is silent.
        val tailFrame = (
            (RhythmCalibrationTrack.TICK_COUNT - 1) * RhythmCalibrationTrack.PERIOD_MS +
                RhythmCalibrationTrack.FIRST_TICK_MS + RhythmCalibrationTrack.PERIOD_MS
            ) * sampleRate / 1000
        assertTrue(isSilent(audio, tailFrame))
    }

    @Test
    fun analyzeTapOffsetsAveragesThePerceivedLatency() {
        val latencyNs = 180_000_000L
        val tickWalls = walls()
        val taps = tickWalls.map { it + latencyNs }

        val mean = analyzeTapOffsets(taps, tickWalls)
        assertNotNull(mean)
        assertEquals(180.0, mean!!, 0.5)
    }

    @Test
    fun bluetoothLatencyIsNotAliasedOntoTheNextTick() {
        // Regression for the real-device case: a classic Bluetooth link measures 400-500 ms
        // end to end. With the old 500 ms period, a tap 463 ms after its tick's wall was
        // NEARER to the next tick's wall and measured as -37 ms — whose absolute value the
        // user read as a "37 ms" low-latency link, and which applied the offset in the wrong
        // direction. At the 1200 ms period the same tap must measure as ~463 ms.
        val tickWalls = walls()
        val taps = tickWalls.map { it + 463_000_000L }

        val mean = analyzeTapOffsets(taps, tickWalls)
        assertNotNull(mean)
        assertEquals(463.0, mean!!, 0.5)
    }

    @Test
    fun latencyOfFiveHundredMillisecondsStillMatchesItsOwnTick() {
        val tickWalls = walls()
        val taps = tickWalls.map { it + 520_000_000L }

        val mean = analyzeTapOffsets(taps, tickWalls)
        assertNotNull(mean)
        assertEquals(520.0, mean!!, 0.5)
    }

    @Test
    fun analyzeTapOffsetsIgnoresTheWarmupTicksAndStrayTaps() {
        val tickWalls = walls()
        // Taps only on the first two (warm-up) ticks: nothing to learn from.
        val warmupOnly = tickWalls.take(2).map { it + 180_000_000L }
        assertNull(analyzeTapOffsets(warmupOnly, tickWalls))

        // Taps on steady-state ticks plus a stray tap beyond the match window of every wall
        // (half a period): stray is unmatched, the mean survives.
        val taps = tickWalls.drop(2).map { it + 180_000_000L } +
            (tickWalls[9] + tickStepNs / 2 + 100_000_000L)
        val mean = analyzeTapOffsets(taps, tickWalls)
        assertNotNull(mean)
        assertEquals(180.0, mean!!, 0.5)
    }

    @Test
    fun analyzeTapOffsetsAveragesMultipleTapsOnOneTick() {
        val tickWalls = walls()
        // Tick 4 gets two taps bracketing 180 ms (160 and 200): averaged per tick first,
        // so the across-tick mean still lands on 180.
        val taps = tickWalls.drop(2).mapIndexed { index, wall ->
            val tick = index + 2
            if (tick == 4) wall + 160_000_000L else wall + 180_000_000L
        } + (tickWalls[4] + 200_000_000L)

        val mean = analyzeTapOffsets(taps, tickWalls)
        assertNotNull(mean)
        assertEquals(180.0, mean!!, 0.5)
    }

    @Test
    fun analyzeTapOffsetsRequiresEnoughMatchedTicks() {
        val tickWalls = walls()
        val twoTaps = listOf(tickWalls[2] + 180_000_000L, tickWalls[3] + 180_000_000L)
        assertNull(analyzeTapOffsets(twoTaps, tickWalls))
    }

    @Test
    fun analyzeTapOffsetsSkipsUncrossedTicks() {
        // A pathological run where the clock never reached the last ticks: nulls carry no wall.
        val partial = walls().mapIndexed { index, wall -> if (index < 5) wall else null }
        val taps = (2 until 5).map { partial[it]!! + 150_000_000L }
        val mean = analyzeTapOffsets(taps, partial)
        assertNotNull(mean)
        assertEquals(150.0, mean!!, 0.5)
    }
}
