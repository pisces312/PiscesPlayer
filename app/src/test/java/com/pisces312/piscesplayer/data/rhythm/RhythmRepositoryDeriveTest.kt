/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/rhythm-master-timing-variants-and-derive-profiles-plan.md §5, §6, §10).
 */
package com.pisces312.piscesplayer.data.rhythm

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Repository-level derivation wiring: filters resolve stored cutoffs, the selected timing variant
 * reaches every mode, and derived charts do not keep master-only fields.
 */
class RhythmRepositoryDeriveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = mockk(relaxed = true)
    private lateinit var repository: RhythmRepository

    @Before
    fun setUp() {
        every { context.filesDir } returns temp.newFolder("files")
        repository = RhythmRepository(context)
    }

    private fun master(
        times: List<Int>,
        rms: List<Float> = List(times.size) { 0f },
        timing: List<RhythmTimingPoint> = listOf(RhythmTimingPoint(0, 500.0)),
        variants: List<RhythmTimingVariant> = emptyList(),
        thresholds: List<RhythmRmsThreshold> = emptyList(),
    ): RhythmChart = RhythmChart(
        source = RhythmChart.SOURCE_DSP,
        difficultyName = "DSP Raw",
        timing = timing,
        timingVariants = variants,
        rmsThresholds = thresholds,
        notes = times.mapIndexed { index, time ->
            RhythmNote(
                id = index,
                timeMs = time,
                lane = index % 4,
                strength = 0.5f + index * 0.05f,
                band = index % 8,
                rms = rms[index],
            )
        },
        durationMs = 20_000,
    )

    private fun metaOf(master: RhythmChart): RhythmChartMeta = RhythmChartMeta(
        chartId = "master",
        songId = SONG,
        source = master.source,
        difficultyName = master.difficultyName,
        noteCount = master.noteCount(),
        averageNps = master.averageNps(),
        durationMs = master.durationMs,
        importedAtMs = 1L,
        difficultyKey = RhythmChart.DIFFICULTY_RAW,
    )

    private suspend fun preview(
        master: RhythmChart,
        modes: Set<RhythmDeriveMode>,
        options: RhythmDeriveOptions = RhythmDeriveOptions(),
        difficulties: List<DspDifficultySpec> = listOf(DspDifficulty.EASY.spec),
    ): Map<Pair<DspDifficultySpec, RhythmDeriveMode>, Int> = repository.previewDerivedCounts(
        master = master,
        difficulties = difficulties,
        modes = modes,
        laneStrategy = LaneStrategy.BAND_ORDER,
        maxChord = 1,
        onsetSelection = OnsetSelection.BALANCED,
        options = options,
    )

    @Test
    fun loudnessUsesTheStoredCutoffNotAnOnsetPercentile() = runBlocking {
        val chart = master(
            times = listOf(1_500, 2_000, 2_500, 3_000),
            rms = listOf(0.1f, 0.11f, 0.12f, 0.9f),
            // p25 over the onsets would be ~0.1075 and keep three notes. The stored cutoff comes
            // from the full-song envelope, so only the loud onset survives.
            thresholds = listOf(RhythmRmsThreshold(25, 0.5f)),
        )

        val counts = preview(
            chart,
            modes = setOf(RhythmDeriveMode.CLASSIC),
            options = RhythmDeriveOptions(minRmsPercentile = 25),
        )

        assertEquals(1, counts.getValue(DspDifficulty.EASY.spec to RhythmDeriveMode.CLASSIC))
    }

    @Test
    fun aLegacyMasterWithoutCutoffsTreatsLoudnessAsOff() = runBlocking {
        val chart = master(
            times = listOf(1_500, 2_000, 2_500, 3_000),
            rms = listOf(0.1f, 0.11f, 0.12f, 0.9f),
        )

        // Normal keeps the one-beat cell, so with the filter off all four onsets survive in
        // their own cells. (Easy now uses two-beat cells and would merge them.)
        val counts = preview(
            chart,
            modes = setOf(RhythmDeriveMode.CLASSIC),
            options = RhythmDeriveOptions(minRmsPercentile = 25),
            difficulties = listOf(DspDifficulty.NORMAL.spec),
        )

        assertEquals(4, counts.getValue(DspDifficulty.NORMAL.spec to RhythmDeriveMode.CLASSIC))
    }

    @Test
    fun densityCapIsAppliedBeforeDerivation() = runBlocking {
        val chart = master(times = listOf(1_000, 1_010, 1_500))

        val off = preview(chart, modes = setOf(RhythmDeriveMode.CLASSIC))
        val on = preview(
            chart,
            modes = setOf(RhythmDeriveMode.CLASSIC),
            options = RhythmDeriveOptions(maxLocalNps = 1.0),
        )

        // Without the cap 1000/1010 share a cell but 1500 is a second cell. The cap drops 1500
        // because it sits exactly 500 ms from the kept 1000, leaving a single note.
        assertEquals(2, off.getValue(DspDifficulty.EASY.spec to RhythmDeriveMode.CLASSIC))
        assertEquals(1, on.getValue(DspDifficulty.EASY.spec to RhythmDeriveMode.CLASSIC))
    }

    @Test
    fun selectedTimingVariantReachesTheGridDerivation() = runBlocking {
        val tracked = listOf(RhythmTimingPoint(0, 500.0), RhythmTimingPoint(4_000, 400.0))
        val chart = master(
            times = listOf(5_200),
            timing = tracked,
            variants = listOf(
                RhythmTimingVariant(RhythmTimingVariantKey.TRACKED, tracked),
                RhythmTimingVariant(RhythmTimingVariantKey.CLASSIC, listOf(RhythmTimingPoint(0, 500.0))),
            ),
        )

        val trackedChart = deriveSingle(chart, RhythmDeriveOptions(RhythmTimingVariantKey.TRACKED))
        // Tracked segment (400 ms from 4000) leaves 5200 on the beat; the classic 500 ms grid
        // rounds it to 5000. The two must not be interchangeable.
        assertEquals(listOf(5_200), trackedChart.notes.map { it.timeMs })

        val classicChart = deriveSingle(chart, RhythmDeriveOptions(RhythmTimingVariantKey.CLASSIC))
        assertEquals(listOf(5_000), classicChart.notes.map { it.timeMs })
    }

    @Test
    fun derivedChartsDropMasterOnlyFieldsAndOnsetRms() = runBlocking {
        val tracked = listOf(RhythmTimingPoint(0, 500.0))
        val chart = master(
            times = listOf(5_200),
            rms = listOf(0.4f),
            timing = tracked,
            variants = listOf(
                RhythmTimingVariant(RhythmTimingVariantKey.TRACKED, tracked),
                RhythmTimingVariant(RhythmTimingVariantKey.CLASSIC, tracked),
            ),
            thresholds = listOf(RhythmRmsThreshold(25, 0.5f)),
        )

        val derived = deriveSingle(chart, RhythmDeriveOptions())

        assertTrue(derived.timingVariants.isEmpty())
        assertTrue(derived.rmsThresholds.isEmpty())
        assertEquals(0f, derived.notes.single().rms, 0f)
    }

    @Test
    fun beatFirstRunsThroughTheBeatGate() = runBlocking {
        val chart = master(times = listOf(1_500, 2_500, 3_500)).let { base ->
            base.copy(
                notes = base.notes.mapIndexed { index, note ->
                    note.copy(
                        band = if (index == 0) 0 else 6,
                        lowRatio = if (index == 0) 0.6f else 0.1f,
                    )
                },
            )
        }

        val ungated = preview(
            chart,
            modes = setOf(RhythmDeriveMode.BEAT_FIRST),
            options = RhythmDeriveOptions(beatParams = RhythmBeatFirstParams(drumGate = BeatDrumGate.OFF)),
        )
        assertEquals(3, ungated.getValue(DspDifficulty.EASY.spec to RhythmDeriveMode.BEAT_FIRST))

        val gated = preview(
            chart,
            modes = setOf(RhythmDeriveMode.BEAT_FIRST),
            options = RhythmDeriveOptions(
                beatParams = RhythmBeatFirstParams(
                    drumGate = BeatDrumGate.LOW_RATIO,
                    restThreshold = 0.0,
                    lowRatioMin = 0.45,
                ),
            ),
        )
        // Only the low-ratio-dominated beat survives the share gate.
        assertEquals(1, gated.getValue(DspDifficulty.EASY.spec to RhythmDeriveMode.BEAT_FIRST))
    }

    @Test
    fun customDifficultyDerivesWithItsOwnBudget() = runBlocking {
        val custom = RhythmCustomDifficulty(
            name = "Test",
            gridDiv = 1,
            maxNotesPerSecond = 1.0,
            minGapMs = 0.0,
        )
        val chart = master(times = listOf(1_500, 1_600, 2_500))

        val counts = preview(
            chart,
            modes = setOf(RhythmDeriveMode.ONSET),
            difficulties = listOf(custom.spec("Custom")),
        )
        // 1500/1600 share a 1 s budget window, so the stronger one only survives; 2500 is out
        // of that window and keeps its own slot.
        assertEquals(2, counts.getValue(custom.spec("Custom") to RhythmDeriveMode.ONSET))

        val metas = repository.deriveCharts(
            songId = SONG,
            master = chart,
            masterMeta = metaOf(chart),
            difficulties = listOf(custom.spec("Custom")),
            modes = setOf(RhythmDeriveMode.GRID),
            laneStrategy = LaneStrategy.BAND_ORDER,
            displayNames = emptyMap(),
        )
        assertEquals(DspDifficultySpec.KEY_CUSTOM, metas.single().difficultyKey)
        assertEquals(1, metas.single().difficultyGridDiv)
    }

    @Test
    fun customDifficultySkipsTheFrozenClassicLadder() = runBlocking {
        val custom = RhythmCustomDifficulty(gridDiv = 8)
        val chart = master(times = listOf(1_500, 2_500))

        val counts = preview(
            chart,
            modes = setOf(RhythmDeriveMode.CLASSIC),
            difficulties = listOf(custom.spec("Custom")),
        )

        assertTrue(counts.isEmpty())
    }

    @Test
    fun theTwoRungLadderHalvesEasyThroughTwoBeatSlots() = runBlocking {
        val points = listOf(RhythmTimingPoint(0, 500.0))
        val chart = master(
            times = (1_000..8_000 step 500).toList(),
            variants = listOf(
                RhythmTimingVariant(RhythmTimingVariantKey.TRACKED, points),
                RhythmTimingVariant(RhythmTimingVariantKey.CLASSIC, points),
            ),
        )

        val counts = preview(
            chart,
            modes = setOf(RhythmDeriveMode.GRID),
            difficulties = DspDifficultySpec.BUILT_IN,
        )

        // Hard fills one slot per beat (15 cells at 500 ms), Easy one slot per two beats (8).
        assertEquals(15, counts.getValue(DspDifficultySpec.HARD to RhythmDeriveMode.GRID))
        assertEquals(8, counts.getValue(DspDifficultySpec.EASY to RhythmDeriveMode.GRID))
    }

    @Test
    fun theClassicHardRungIsTheOneBeatReplica() = runBlocking {
        val chart = master(times = listOf(1_500, 2_000, 2_500, 3_000))

        val counts = preview(
            chart,
            modes = setOf(RhythmDeriveMode.CLASSIC),
            difficulties = listOf(DspDifficultySpec.HARD),
        )

        // The 544-note golden density: the ladder's NORMAL rung keeps the one-beat cell.
        assertEquals(4, counts.getValue(DspDifficultySpec.HARD to RhythmDeriveMode.CLASSIC))
    }

    private suspend fun deriveSingle(
        master: RhythmChart,
        options: RhythmDeriveOptions,
    ): RhythmChart {
        val metas = repository.deriveCharts(
            songId = SONG,
            master = master,
            masterMeta = metaOf(master),
            difficulties = listOf(DspDifficulty.EASY.spec),
            modes = setOf(RhythmDeriveMode.GRID),
            laneStrategy = LaneStrategy.BAND_ORDER,
            displayNames = emptyMap(),
            maxChord = 1,
            onsetSelection = OnsetSelection.BALANCED,
            options = options,
        )
        return requireNotNull(repository.loadChart(metas.single().chartId))
    }

    private companion object {
        const val SONG = "song-a"
    }
}
