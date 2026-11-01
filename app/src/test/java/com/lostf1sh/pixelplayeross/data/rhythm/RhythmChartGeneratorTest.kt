package com.lostf1sh.pixelplayeross.data.rhythm

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmChartGeneratorTest {

    private val sampleRate = 44100
    private val durationSeconds = 20.0

    private fun generate(
        params: RhythmChartGenParams = RhythmChartGenParams(),
        audio: FloatArray = RhythmTestAudio.drumLoop(durationSeconds, sampleRate),
    ): GeneratedChart = RhythmChartGenerator.generate(
        pcmMono = audio,
        sampleRate = sampleRate,
        durationMs = (durationSeconds * 1000).toInt(),
        params = params,
    )

    @Test
    fun detectsTempoOfSyntheticLoop() {
        val result = generate()
        val bpm = result.diagnostics.usedBpm
        assertTrue("estimated $bpm", abs(bpm - 120.0) <= 1.0)
        assertEquals(1.0, result.diagnostics.octaveCorrection, 0.0)
    }

    @Test
    fun handlesFortyEightKilohertzDevices() {
        // Real devices decode at 48 kHz far more often than 44.1 kHz; every stage is
        // sample-rate driven (hop duration, lag range, frame-to-ms), so exercise it.
        val rate = 48_000
        val audio = RhythmTestAudio.drumLoop(durationSeconds, rate)
        val result = RhythmChartGenerator.generate(
            pcmMono = audio,
            sampleRate = rate,
            durationMs = (durationSeconds * 1000).toInt(),
            params = RhythmChartGenParams(),
        )
        assertTrue(
            "estimated ${result.diagnostics.usedBpm} at 48 kHz",
            abs(result.diagnostics.usedBpm - 120.0) <= 1.5,
        )
        assertEquals(1.0, result.diagnostics.octaveCorrection, 0.0)
        assertTrue(result.chart.notes.isNotEmpty())
        assertTrue(result.chart.notes.all { it.timeMs in 0..(durationSeconds * 1000).toInt() })
    }

    @Test
    fun difficultyGridControlsDensity() {
        val easy = generate(RhythmChartGenParams(difficulty = DspDifficulty.EASY)).chart
        val normal = generate(RhythmChartGenParams(difficulty = DspDifficulty.NORMAL)).chart
        val hard = generate(RhythmChartGenParams(difficulty = DspDifficulty.HARD)).chart

        assertTrue("easy ${easy.noteCount()}", easy.noteCount() in 30..50)
        assertTrue("normal ${normal.noteCount()}", normal.noteCount() > easy.noteCount())
        assertTrue("hard ${hard.noteCount()}", hard.noteCount() >= normal.noteCount())
    }

    @Test
    fun everyNoteSitsOnTheQuantizationGrid() {
        val result = generate(RhythmChartGenParams(difficulty = DspDifficulty.NORMAL))
        val timing = result.chart.timing.single()
        val grid = timing.beatMs / DspDifficulty.NORMAL.gridDiv
        for (note in result.chart.notes) {
            val nearest = timing.timeMs + Math.round((note.timeMs - timing.timeMs) / grid) * grid
            assertTrue(
                "note ${note.timeMs} off grid ($nearest)",
                abs(note.timeMs - nearest) <= 1.0,
            )
        }
    }

    @Test
    fun notesAreOrderedUniqueAndInsideLaneRange() {
        val chart = generate(RhythmChartGenParams(difficulty = DspDifficulty.HARD)).chart
        assertTrue(chart.notes.isNotEmpty())
        var previousTime = -1
        for ((index, note) in chart.notes.withIndex()) {
            assertEquals(index, note.id)
            assertTrue("$note out of order", note.timeMs >= previousTime)
            assertTrue("$note lane", note.lane in 0..3)
            assertTrue("$note range", note.timeMs in 0..chart.durationMs)
            previousTime = note.timeMs
        }
        // A cell holds at most one note, so identical timestamps are only possible across lanes.
        val collisions = chart.notes.groupBy { it.timeMs }.filterValues { group ->
            group.map { it.lane }.distinct().size != group.size
        }
        assertTrue("same lane at the same time: $collisions", collisions.isEmpty())
    }

    @Test
    fun producesSingleSegmentTimingAndDspSource() {
        val chart = generate().chart
        assertEquals(RhythmChart.SOURCE_DSP, chart.source)
        assertEquals(1, chart.timing.size)
        assertTrue(chart.difficultyName.startsWith("DSP "))
        assertEquals((durationSeconds * 1000).toInt(), chart.durationMs)
    }

    @Test
    fun manualBpmOverrideSkipsDetection() {
        val result = generate(RhythmChartGenParams(bpmOverride = 97.5))
        assertEquals(97.5, result.diagnostics.usedBpm, 0.0)
        assertEquals(1.0, result.diagnostics.octaveCorrection, 0.0)
        assertEquals(60_000.0 / 97.5, result.chart.timing.single().beatMs, 1e-6)
    }

    @Test
    fun extraOffsetShiftsEveryNote() {
        val plain = generate(RhythmChartGenParams(difficulty = DspDifficulty.EASY)).chart
        val shifted = generate(
            RhythmChartGenParams(difficulty = DspDifficulty.EASY, extraOffsetMs = 40),
        ).chart
        assertEquals(plain.noteCount(), shifted.noteCount())
        val delta = shifted.notes.first().timeMs - plain.notes.first().timeMs
        assertTrue("delta $delta", abs(delta - 40) <= 1)
    }

    @Test
    fun laneStrategiesBothStayInRange() {
        for (strategy in LaneStrategy.entries) {
            val chart = generate(
                RhythmChartGenParams(difficulty = DspDifficulty.NORMAL, laneStrategy = strategy),
            ).chart
            assertTrue(chart.notes.all { it.lane in 0..3 })
        }
    }

    @Test
    fun rejectsAudioShorterThanOneWindow() {
        val failure = runCatching {
            RhythmChartGenerator.generate(FloatArray(100), sampleRate, 10, RhythmChartGenParams())
        }.exceptionOrNull()
        assertTrue("$failure", failure is RhythmGenerationException)
    }

    @Test
    fun rejectsSilentAudio() {
        val failure = runCatching {
            RhythmChartGenerator.generate(
                FloatArray(sampleRate * 5),
                sampleRate,
                5000,
                RhythmChartGenParams(),
            )
        }.exceptionOrNull()
        assertTrue("$failure", failure is RhythmGenerationException)
    }

    @Test
    fun reportsProgressMonotonically() {
        val progress = ArrayList<Float>()
        RhythmChartGenerator.generate(
            pcmMono = RhythmTestAudio.drumLoop(durationSeconds, sampleRate),
            sampleRate = sampleRate,
            durationMs = (durationSeconds * 1000).toInt(),
            params = RhythmChartGenParams(),
            onProgress = { progress += it },
        )
        assertTrue(progress.isNotEmpty())
        assertEquals(1f, progress.last(), 0f)
        for (index in 1 until progress.size) {
            assertTrue("progress went backwards at $index", progress[index] >= progress[index - 1])
        }
    }
}
