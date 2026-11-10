package com.pisces312.piscesplayer.data.rhythm

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
    fun usedTempoMatchesTheLoopPeriod() {
        // A minute of audio makes a period error visible: 0.3% is already 100 ms of phase slip
        // by the end, so the phase-coherence refinement must land well inside 0.05 BPM.
        val bpm = 120.0
        val result = RhythmChartGenerator.generate(
            pcmMono = RhythmTestAudio.drumLoop(60.0, sampleRate, bpm),
            sampleRate = sampleRate,
            durationMs = 60_000,
        )
        assertEquals(bpm, result.diagnostics.usedBpm, 0.05)
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
        val timing = result.chart.timing
        for (note in result.chart.notes) {
            // The grid is piecewise: every note sits on its own timing segment's lattice.
            val point = timing.lastOrNull { it.timeMs <= note.timeMs } ?: timing.first()
            val grid = point.beatMs / DspDifficulty.NORMAL.gridDiv
            val nearest = point.timeMs + Math.round((note.timeMs - point.timeMs) / grid) * grid
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
    fun producesPiecewiseTimingAndDspSource() {
        val chart = generate().chart
        assertEquals(RhythmChart.SOURCE_DSP, chart.source)
        // The tracked grid is piecewise: one anchor per music beat, all on the same lattice.
        assertTrue("timing ${chart.timing.size}", chart.timing.size > 1)
        var previous = Int.MIN_VALUE
        for (point in chart.timing) {
            assertTrue("beatMs ${point.beatMs}", point.beatMs > 0.0)
            assertTrue("anchors out of order", point.timeMs > previous)
            previous = point.timeMs
        }
        assertTrue(chart.difficultyName.startsWith("DSP "))
        assertEquals((durationSeconds * 1000).toInt(), chart.durationMs)
    }

    @Test
    fun manualBpmOverrideSkipsDetection() {
        val result = generate(RhythmChartGenParams(analysis = RhythmAnalysisParams(bpmOverride = 97.5)))
        assertEquals(97.5, result.diagnostics.usedBpm, 0.0)
        assertEquals(1.0, result.diagnostics.octaveCorrection, 0.0)
        // The override is the contract for the grid rate: the tracked fit may only refine it
        // within the rate guard (RhythmBeatTracker rejects a fit that strays further and falls
        // back to the requested rate), never replace it with a different tempo.
        val nominal = 60_000.0 / 97.5
        assertTrue(
            result.chart.timing.joinToString { "%.2f".format(it.beatMs) },
            result.chart.timing.all { abs(it.beatMs - nominal) <= nominal * 0.05 },
        )
    }

    @Test
    fun extraOffsetShiftsEveryNote() {
        val plain = generate(RhythmChartGenParams(difficulty = DspDifficulty.EASY)).chart
        val shifted = generate(
            RhythmChartGenParams(
                difficulty = DspDifficulty.EASY,
                analysis = RhythmAnalysisParams(extraOffsetMs = 40),
            ),
        ).chart

        // The grid moves by exactly 40 ms and the notes move with it. Quantisation adds two
        // wrinkles: an onset within 40 ms of a cell boundary flips to the neighbouring cell
        // (a whole grid step away), and a flip can merge or split a note. So the timing must
        // shift exactly while the notes only have to move by 40 ms minus a couple of flippers.
        plain.timing.zip(shifted.timing).forEach { (before, after) ->
            assertEquals(before.timeMs + 40, after.timeMs)
            assertEquals(before.beatMs, after.beatMs, 0.0)
        }
        assertTrue("notes ${plain.noteCount()} -> ${shifted.noteCount()}", abs(plain.noteCount() - shifted.noteCount()) <= 2)
        val moved = plain.notes.count { note ->
            shifted.notes.any { abs(it.timeMs - (note.timeMs + 40)) <= 1 }
        }
        assertTrue("moved $moved of ${plain.noteCount()}", moved >= plain.noteCount() - 2)
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
    fun masterHoldsEveryDetectedOnset() {
        val audio = RhythmTestAudio.drumLoop(durationSeconds, sampleRate)
        val analysis = RhythmChartGenerator.analyze(
            pcmMono = audio,
            sampleRate = sampleRate,
            durationMs = (durationSeconds * 1000).toInt(),
            params = RhythmAnalysisParams(),
        )
        val master = RhythmChartGenerator.renderMaster(analysis)

        assertEquals(analysis.onsetTimesMs.size, master.noteCount())
        assertTrue("master is empty", master.noteCount() > 0)
        assertTrue("strength missing", master.notes.all { it.strength > 0f })
        assertTrue("band missing", master.notes.all { it.band in 0..7 })
        assertEquals(analysis.timing, master.timing)
        // Notes are stored as whole milliseconds, so two onsets must not collapse.
        assertEquals(master.noteCount(), master.notes.map { it.timeMs }.distinct().size)
    }

    @Test
    fun masterIsAtLeastAsDenseAsAnyDifficulty() {
        val audio = RhythmTestAudio.drumLoop(durationSeconds, sampleRate)
        val analysis = RhythmChartGenerator.analyze(
            pcmMono = audio,
            sampleRate = sampleRate,
            durationMs = (durationSeconds * 1000).toInt(),
            params = RhythmAnalysisParams(),
        )
        val master = RhythmChartGenerator.renderMaster(analysis)
        for (difficulty in DspDifficulty.entries) {
            val chart = RhythmChartGenerator.render(analysis, difficulty)
            assertTrue(
                "${difficulty.name} ${chart.noteCount()} > master ${master.noteCount()}",
                chart.noteCount() <= master.noteCount(),
            )
        }
    }

    @Test
    fun masterKeepsTheSameBeatGridAsEveryDifficulty() {
        val audio = RhythmTestAudio.drumLoop(durationSeconds, sampleRate)
        val analysis = RhythmChartGenerator.analyze(
            pcmMono = audio,
            sampleRate = sampleRate,
            durationMs = (durationSeconds * 1000).toInt(),
            params = RhythmAnalysisParams(),
        )
        val masterTiming = RhythmChartGenerator.renderMaster(analysis).timing
        for (difficulty in DspDifficulty.entries) {
            // Sharing one analysis is the whole point of the ladder: a difficulty derived from
            // the master must not drift against it.
            assertEquals(masterTiming, RhythmChartGenerator.render(analysis, difficulty).timing)
        }
    }

    @Test
    fun analysisCarriesBothTrackedAndClassicTiming() {
        val audio = RhythmTestAudio.drumLoop(durationSeconds, sampleRate)
        val analysis = RhythmChartGenerator.analyze(
            pcmMono = audio,
            sampleRate = sampleRate,
            durationMs = (durationSeconds * 1000).toInt(),
            params = RhythmAnalysisParams(),
        )
        // The 0.4.3-pisces.1 contract: one analysis now carries both grids. Classic is a
        // single fixed-rate point, tracked is the piecewise lattice.
        assertEquals(1, analysis.classicTiming.size)
        assertTrue("tracked ${analysis.timing.size}", analysis.timing.size > 1)
        assertEquals(60_000.0 / analysis.usedBpm, analysis.classicTiming[0].beatMs, 0.0)
        assertTrue("anchor ${analysis.classicTiming[0].timeMs}", analysis.classicTiming[0].timeMs >= 0)
    }

    @Test
    fun classicTimingHonorsExtraOffset() {
        val plain = RhythmChartGenerator.analyze(
            pcmMono = RhythmTestAudio.drumLoop(durationSeconds, sampleRate),
            sampleRate = sampleRate,
            durationMs = (durationSeconds * 1000).toInt(),
            params = RhythmAnalysisParams(),
        )
        val shifted = RhythmChartGenerator.analyze(
            pcmMono = RhythmTestAudio.drumLoop(durationSeconds, sampleRate),
            sampleRate = sampleRate,
            durationMs = (durationSeconds * 1000).toInt(),
            params = RhythmAnalysisParams(extraOffsetMs = 40),
        )
        assertEquals(plain.classicTiming[0].timeMs + 40, shifted.classicTiming[0].timeMs)
        assertEquals(plain.classicTiming[0].beatMs, shifted.classicTiming[0].beatMs, 0.0)
    }

    @Test
    fun classicTimingMatchesTheFrozenGoldenValues() {
        // Pinned from the 0.4.3-pisces.1 phone build (refineTempo + single-point grid) on the
        // 20 s / 120 BPM drum loop. A failure here means the replica moved, not that the pin is old.
        val analysis = RhythmChartGenerator.analyze(
            pcmMono = RhythmTestAudio.drumLoop(durationSeconds, sampleRate),
            sampleRate = sampleRate,
            durationMs = (durationSeconds * 1000).toInt(),
            params = RhythmAnalysisParams(),
        )
        assertEquals(1, analysis.classicTiming.size)
        assertEquals(19, analysis.classicTiming[0].timeMs)
        assertEquals(500.01270187424916, analysis.classicTiming[0].beatMs, 1e-9)
        assertEquals(119.99695162762029, analysis.usedBpm, 1e-9)
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
