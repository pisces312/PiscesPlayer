/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS (tools/rhythm-lab/README.md).
 */
package com.lostf1sh.pixelplayeross.data.rhythm

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * PC-lab harness, not a CI test: it runs the real pipeline over the lab's baseline WAV and
 * exports every chart variant so `tools/rhythm-lab/run_lab.py` can measure them against an
 * independent beat reference.
 *
 * The test is skipped unless the baseline WAV exists; `run_lab.py decode` produces it.
 * Output: `app/build/rhythm-lab/out/`.
 */
class RhythmLabExportTest {

    private val repoRoot = File("..").canonicalFile
    private val baselineFile = File(repoRoot, "tools/rhythm-lab/baseline.json")
    private val prettyJson = Json { prettyPrint = true }

    @Test
    fun exportBaselineCharts() {
        assumeTrue("baseline.json missing", baselineFile.isFile)
        val baseline = Json.parseToJsonElement(baselineFile.readText()).jsonObject
        val wav = File(repoRoot, baseline.getValue("decodedWav").jsonPrimitive.content)
        assumeTrue(
            "baseline WAV missing (${wav.path}); run tools/rhythm-lab/run_lab.py decode",
            wav.isFile,
        )

        val outDir = File("build/rhythm-lab/out").apply { mkdirs() }
        val (pcm, sampleRate) = RhythmTestAudio.readMonoWav16(wav)
        val durationMs = (pcm.size.toLong() * 1000L / sampleRate).toInt()
        val analysis = RhythmChartGenerator.analyze(
            pcmMono = pcm,
            sampleRate = sampleRate,
            durationMs = durationMs,
        )
        val master = RhythmChartGenerator.renderMaster(analysis)
        writeJson(outDir, "master.json", master)

        writeAnalysis(outDir, analysis)

        val refinedTiming = RhythmTimingRefiner.refine(
            onsets = master.notes.map { RhythmTimingRefiner.Onset(it.timeMs, it.strength) },
            global = master.timing.first(),
            durationMs = master.durationMs,
        )

        for (difficulty in DspDifficulty.entries) {
            writeJson(
                outDir,
                "grid_global_${difficulty.name}.json",
                RhythmChartDeriver.derive(master, difficulty, timing = master.timing),
            )
            writeJson(
                outDir,
                "grid_refined_${difficulty.name}.json",
                RhythmChartDeriver.derive(master, difficulty, timing = refinedTiming),
            )
            writeJson(
                outDir,
                "onset_${difficulty.name}.json",
                RhythmChartDeriver.deriveOnsetNative(master, difficulty),
            )
        }
    }

    private fun writeJson(dir: File, name: String, chart: RhythmChart) {
        File(dir, name).writeText(prettyJson.encodeToString(RhythmChart.serializer(), chart))
    }

    private fun writeAnalysis(dir: File, analysis: DspAnalysis) {
        File(dir, "analysis.json").writeText(
            """
            {
             "detectedBpm": ${analysis.detectedBpm},
             "usedBpm": ${analysis.usedBpm},
             "beatMs": ${analysis.beatMs},
             "timingPoints": ${analysis.timing.size},
             "octaveCorrection": ${analysis.octaveCorrection},
             "onsetCount": ${analysis.onsetTimesMs.size},
             "peakCount": ${analysis.peakCount},
             "durationMs": ${analysis.durationMs}
            }
            """.trimIndent(),
        )
    }
}
