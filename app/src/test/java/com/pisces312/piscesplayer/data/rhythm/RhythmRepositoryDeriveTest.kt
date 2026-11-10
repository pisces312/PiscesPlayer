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
    ): Map<Pair<DspDifficulty, RhythmDeriveMode>, Int> = repository.previewDerivedCounts(
        master = master,
        difficulties = listOf(DspDifficulty.EASY),
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

        assertEquals(1, counts.getValue(DspDifficulty.EASY to RhythmDeriveMode.CLASSIC))
    }

    @Test
    fun aLegacyMasterWithoutCutoffsTreatsLoudnessAsOff() = runBlocking {
        val chart = master(
            times = listOf(1_500, 2_000, 2_500, 3_000),
            rms = listOf(0.1f, 0.11f, 0.12f, 0.9f),
        )

        val counts = preview(
            chart,
            modes = setOf(RhythmDeriveMode.CLASSIC),
            options = RhythmDeriveOptions(minRmsPercentile = 25),
        )

        assertEquals(4, counts.getValue(DspDifficulty.EASY to RhythmDeriveMode.CLASSIC))
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
        assertEquals(2, off.getValue(DspDifficulty.EASY to RhythmDeriveMode.CLASSIC))
        assertEquals(1, on.getValue(DspDifficulty.EASY to RhythmDeriveMode.CLASSIC))
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

    private suspend fun deriveSingle(
        master: RhythmChart,
        options: RhythmDeriveOptions,
    ): RhythmChart {
        val metas = repository.deriveCharts(
            songId = SONG,
            master = master,
            masterMeta = metaOf(master),
            difficulties = listOf(DspDifficulty.EASY),
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
