package com.pisces312.piscesplayer.data.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmLatencyCalibrationTest {

    private val tickStepNs = RhythmCalibrationTrack.PERIOD_MS * 1_000_000L

    /** Tick wall times at the real metronome spacing (one every PERIOD_MS from 1 s). */
    private fun walls(count: Int = RhythmCalibrationTrack.TICK_COUNT): List<Long> =
        (0 until count).map { RhythmCalibrationTrack.tickSongMs(it).toLong() * 1_000_000L }

    /** Wall times of the measured ticks only, in order. */
    private fun measuredWalls(): List<Long> =
        walls().drop(RhythmCalibrationTrack.LEAD_IN_TICKS)

    private fun readPcm(audio: RhythmPcmAudio, frame: Int, channel: Int = 0): Short {
        val offset = (frame * audio.channels + channel) * 2
        val low = audio.bytes[offset].toInt() and 0xFF
        val high = audio.bytes[offset + 1].toInt()
        return ((high shl 8) or low).toShort()
    }

    private fun isSilent(audio: RhythmPcmAudio, frame: Int): Boolean =
        (0 until audio.channels).all { readPcm(audio, frame, it).toInt() == 0 }

    @Test
    fun trackIsSplitIntoLeadInAndMeasuredTicks() {
        assertEquals(4, RhythmCalibrationTrack.LEAD_IN_TICKS)
        assertEquals(8, RhythmCalibrationTrack.MEASURED_TICKS)
        assertEquals(12, RhythmCalibrationTrack.TICK_COUNT)
        assertFalse(RhythmCalibrationTrack.isMeasured(0))
        assertFalse(RhythmCalibrationTrack.isMeasured(RhythmCalibrationTrack.LEAD_IN_TICKS - 1))
        assertTrue(RhythmCalibrationTrack.isMeasured(RhythmCalibrationTrack.LEAD_IN_TICKS))
        assertTrue(RhythmCalibrationTrack.isMeasured(RhythmCalibrationTrack.TICK_COUNT - 1))
    }

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
    }

    @Test
    fun cleanRunMeasuresThePerceivedLatency() {
        val latencyNs = 180_000_000L
        val tickWalls = walls()
        val taps = measuredWalls().map { it + latencyNs }

        val stats = analyzeCalibration(taps, tickWalls)
        assertNotNull(stats)
        assertEquals(180.0, stats!!.offsetMs, 0.5)
        assertEquals(0.0, stats.sigmaMs, 0.5)
        assertEquals(8, stats.matchedTicks)
        assertEquals(0, stats.rejectedTicks)
        assertFalse(stats.isLowConfidence)
        assertEquals(-180, stats.appliedOffsetMs)
    }

    @Test
    fun bluetoothLatencyIsNotAliasedOntoTheNextTick() {
        // Regression for the real-device case: a classic Bluetooth link measures 400-500 ms
        // end to end. With the old 500 ms period, a tap 463 ms after its tick's wall was
        // NEARER to the next tick's wall and measured as -37 ms — whose absolute value the
        // user read as a "37 ms" low-latency link, and which applied the offset in the wrong
        // direction. At the 1200 ms period the same tap must measure as ~463 ms.
        val tickWalls = walls()
        val taps = measuredWalls().map { it + 463_000_000L }

        val stats = analyzeCalibration(taps, tickWalls)
        assertNotNull(stats)
        assertEquals(463.0, stats!!.offsetMs, 0.5)
        assertEquals(-463, stats.appliedOffsetMs)
    }

    @Test
    fun latencyOfFiveHundredMillisecondsStillMatchesItsOwnTick() {
        val tickWalls = walls()
        val taps = measuredWalls().map { it + 520_000_000L }

        val stats = analyzeCalibration(taps, tickWalls)
        assertNotNull(stats)
        assertEquals(520.0, stats!!.offsetMs, 0.5)
    }

    @Test
    fun aSingleMistimedTapIsRejectedInsteadOfDraggingTheMedian() {
        val tickWalls = walls()
        val measured = measuredWalls()
        // Seven ticks at 180 ms plus one blunder 300 ms off: the old mean moved by ~37 ms.
        val taps = measured.mapIndexed { index, wall ->
            wall + if (index == 5) 480_000_000L else 180_000_000L
        }

        val stats = analyzeCalibration(taps, tickWalls)
        assertNotNull(stats)
        assertEquals(180.0, stats!!.offsetMs, 0.5)
        assertEquals(7, stats.matchedTicks)
        assertEquals(1, stats.rejectedTicks)
        assertTrue(stats.samples.single { it.rejected }.index == RhythmCalibrationTrack.LEAD_IN_TICKS + 5)
    }

    @Test
    fun anAllOverThePlaceRunIsFlaggedLowConfidence() {
        val tickWalls = walls()
        val measured = measuredWalls()
        // Alternating 100 / 300 ms: the median is 200 ms but the spread is ±100 ms.
        val taps = measured.mapIndexed { index, wall ->
            wall + if (index % 2 == 0) 100_000_000L else 300_000_000L
        }

        val stats = analyzeCalibration(taps, tickWalls)
        assertNotNull(stats)
        assertEquals(200.0, stats!!.offsetMs, 0.5)
        assertTrue(stats.sigmaMs > CalibrationStats.LOW_CONFIDENCE_SIGMA_MS)
        assertTrue(stats.isLowConfidence)
    }

    @Test
    fun leadInTapsAreNeverMeasured() {
        val tickWalls = walls()
        val leadInOnly = tickWalls
            .take(RhythmCalibrationTrack.LEAD_IN_TICKS)
            .map { it + 180_000_000L }

        assertNull(analyzeCalibration(leadInOnly, tickWalls))
    }

    @Test
    fun tooFewMatchedTicksProduceNoResult() {
        val tickWalls = walls()
        val fourTaps = measuredWalls().take(4).map { it + 180_000_000L }

        assertNull(analyzeCalibration(fourTaps, tickWalls))
    }

    @Test
    fun twoTapsOnOneTickCountOnce() {
        val tickWalls = walls()
        val measured = measuredWalls()
        val taps = measured.mapIndexed { index, wall ->
            wall + if (index == 2) 160_000_000L else 180_000_000L
        } + (measured[2] + 200_000_000L)

        val stats = analyzeCalibration(taps, tickWalls)
        assertNotNull(stats)
        // The per-tick median of 160 / 200 is 180: the extra tap must not weigh twice.
        assertEquals(180.0, stats!!.offsetMs, 0.5)
        assertEquals(8, stats.matchedTicks)
    }

    @Test
    fun tapsArrivingOutOfOrderAreStillMatched() {
        val tickWalls = walls()
        val taps = measuredWalls().map { it + 180_000_000L }.reversed()

        val stats = analyzeCalibration(taps, tickWalls)
        assertNotNull(stats)
        assertEquals(180.0, stats!!.offsetMs, 0.5)
    }

    @Test
    fun uncrossedTicksCarryNoWallAndAreSkipped() {
        // A pathological run where the clock never reached the last ticks: nulls carry no wall.
        val partial = walls().mapIndexed { index, wall ->
            if (index < RhythmCalibrationTrack.LEAD_IN_TICKS + 3) wall else null
        }
        val taps = (RhythmCalibrationTrack.LEAD_IN_TICKS until
            RhythmCalibrationTrack.LEAD_IN_TICKS + 3).map { partial[it]!! + 150_000_000L }

        assertNull(analyzeCalibration(taps, partial))
    }

    @Test
    fun appliedOffsetIsClampedToAPlausibleRange() {
        val stats = CalibrationStats(
            offsetMs = 900.0,
            sigmaMs = 5.0,
            matchedTicks = 8,
            rejectedTicks = 0,
            measuredTicks = 8,
            taps = 8,
            samples = emptyList(),
        )

        assertEquals(-CalibrationStats.MAX_OFFSET_MS, stats.appliedOffsetMs)
    }
}
