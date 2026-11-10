/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 (chart auto-play diagnostics).
 */
package com.pisces312.piscesplayer.data.rhythm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runner for [RhythmAutoPlayTool] — the "build the chart with the phone's logic, then play it"
 * diagnostic. Usage from the repo root (PowerShell):
 *
 * ```
 * $env:RHYTHM_AUTOPLAY_WAV = 'D:\path\song.wav'          # 16-bit mono WAV (see below)
 * $env:RHYTHM_AUTOPLAY_OSU = 'D:\path\timing-reference.osu'   # optional pulse reference
 * $env:RHYTHM_AUTOPLAY_TAP_WAV = 'D:\path\drums.wav'     # optional tap ground truth (e.g. a
 *                                                        # separated drums stem; the virtual
 *                                                        # player taps its onsets instead of
 *                                                        # the master's dense onset stream)
 * $env:RHYTHM_AUTOPLAY_TAP_MODE = 'beat'                 # 'beat' taps the chart beat grid
 *                                                        # (the player who follows the
 *                                                        # rhythm) instead of the nearest
 *                                                        # audible onset per note
 * $env:RHYTHM_AUTOPLAY_KICK_TXT = 'D:\path\kicks.txt'    # clean kick list (tools/rhythm-lab/
 *                                                        # extract_kicks.py) for kickPhase
 * $env:RHYTHM_AUTOPLAY_DUMP_GRID = 'D:\path\grid.txt'    # write the chart grid lines
 * $env:RHYTHM_AUTOPLAY_MODES = 'grid,onset,classic'      # default: every derive mode
 * $env:RHYTHM_AUTOPLAY_DIFFS = 'easy,normal,hard'        # default: all
 * .\gradlew.bat :app:testDebugUnitTest --tests "com.pisces312.piscesplayer.data.rhythm.RhythmAutoPlayToolTest"
 * ```
 *
 * Any FLAC/MP3 can be turned into the expected WAV with the rhythm-lab venv:
 * `tools\rhythm-lab\.venv\Scripts\python.exe -c "import soundfile as sf; d,sr=sf.read(r'<src>'); sf.write(r'<dst>.wav', d.mean(axis=1) if d.ndim>1 else d, sr, subtype='PCM_16')"`
 *
 * Without `RHYTHM_AUTOPLAY_WAV` the test runs a synthetic 120 BPM smoke (known pulse, no
 * reference file needed) so the tool itself stays verified on every test run.
 */
class RhythmAutoPlayToolTest {

    @Test
    fun autoPlay() {
        val wavPath = System.getenv("RHYTHM_AUTOPLAY_WAV")
        if (wavPath.isNullOrBlank()) {
            smoke()
            return
        }
        val wav = File(wavPath)
        assumeTrue("wav not found: $wav", wav.isFile)

        val osuPath = System.getenv("RHYTHM_AUTOPLAY_OSU")
        val osu = osuPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
        val modes = parseModes(System.getenv("RHYTHM_AUTOPLAY_MODES"))
        val diffs = parseDiffs(System.getenv("RHYTHM_AUTOPLAY_DIFFS"))

        val (pcm, sampleRate) = RhythmTestAudio.readMonoWav16(wav)
        // Long math: a 4-minute song overflows Int at pcm.size * 1000.
        val durationMs = (pcm.size.toLong() * 1000 / sampleRate).toInt()
        println("AUTOPLAY song=${wav.name} ${durationMs / 1000}s ${sampleRate}Hz")

        val analysis = RhythmChartGenerator.analyze(
            pcmMono = pcm,
            sampleRate = sampleRate,
            durationMs = durationMs,
            params = RhythmAnalysisParams(),
        )
        println(RhythmAutoPlayTool.formatTracking(analysis))
        println(RhythmAutoPlayTool.formatStrongPhase(analysis, durationMs))
        RhythmBeatTracker.estimateTiming(pcm, sampleRate, analysis.usedBpm, durationMs).let {
            println(RhythmAutoPlayTool.formatTracked(it.tracked, durationMs))
        }

        val pulse = osu?.let {
            val segments = RhythmAutoPlayTool.parseOsuPulse(it)
            val medianBeat = segments.map { segment -> segment.beatMs }.median()
            val subdivision = Math.round(medianBeat / analysis.beatMs).toInt().coerceIn(1, 8)
            println(
                "pulse reference: ${it.name} segments=${segments.size} " +
                    "osuBeat=${"%.1f".format(medianBeat)}ms subdivision=$subdivision",
            )
            RhythmAutoPlayTool.expandPulse(segments, durationMs.toDouble(), subdivision)
        }

        val master = RhythmChartGenerator.renderMaster(analysis, LaneStrategy.BAND_ORDER)

        // Tap ground truth: what the player hears and taps to. `beat` taps the chart's beat
        // grid (verified against the strong onsets by formatStrongPhase) — the player who
        // follows the rhythm; anything else taps the nearest audible onset per note — the
        // player who follows the hits. The gap between the two verdicts is exactly the
        // "chart type vs play style" mismatch.
        val tapMode = System.getenv("RHYTHM_AUTOPLAY_TAP_MODE")?.lowercase()
        val tapWavPath = System.getenv("RHYTHM_AUTOPLAY_TAP_WAV")
        var tapTimes: List<Double>? = null
        var kickTimes: List<Double>? = null
        if (tapMode == "beat") {
            tapTimes = RhythmAutoPlayTool.gridLines(analysis.timing, durationMs)
            println("tap reference: chart beat grid (${tapTimes.size} lines)")
        }
        tapWavPath?.takeIf { it.isNotBlank() }?.let { path ->
            val tapWav = File(path)
            assumeTrue("tap wav not found: $tapWav", tapWav.isFile)
            val (tapPcm, tapRate) = RhythmTestAudio.readMonoWav16(tapWav)
            val tapAnalysis = RhythmChartGenerator.analyze(
                pcmMono = tapPcm,
                sampleRate = tapRate,
                durationMs = (tapPcm.size.toLong() * 1000 / tapRate).toInt(),
                params = RhythmAnalysisParams(),
            )
            if (tapMode != "beat") tapTimes = tapAnalysis.onsetTimesMs.toList()
            // Low-band onsets of the drums stem are the physical kicks: the beat itself.
            // Keep the strong half only — soft low-band events (bass bleed, flams) blur the
            // phase walk without adding information.
            val lowBand = (0 until tapAnalysis.onsetTimesMs.size).filter {
                tapAnalysis.onsetBands[it] <= 2
            }
            val strengthCut = lowBand.map { tapAnalysis.onsetStrength[it] }.sorted()
                .let { it[it.size / 2] }
            kickTimes = lowBand.filter { tapAnalysis.onsetStrength[it] >= strengthCut }
                .map { tapAnalysis.onsetTimesMs[it] }
            println(
                "tap reference: ${tapWav.name} onsets=${tapAnalysis.onsetTimesMs.size} " +
                    "lowBand=${lowBand.size} strongKicks=${kickTimes?.size}",
            )
        }
        System.getenv("RHYTHM_AUTOPLAY_KICK_TXT")?.takeIf { it.isNotBlank() }?.let { path ->
            val kickFile = File(path)
            assumeTrue("kick list not found: $kickFile", kickFile.isFile)
            kickTimes = kickFile.readLines()
                .mapNotNull { it.trim().toDoubleOrNull() }
            println("kick list: ${kickTimes?.size} kicks from ${kickFile.name}")
        }
        kickTimes?.let {
            println(RhythmAutoPlayTool.formatKickPhase(it, analysis.timing, durationMs))
        }
        System.getenv("RHYTHM_AUTOPLAY_DUMP_GRID")?.takeIf { it.isNotBlank() }?.let { path ->
            val lines = RhythmAutoPlayTool.gridLines(analysis.timing, durationMs)
            File(path).writeText(lines.joinToString("\n") { "%.1f".format(it) })
            println("grid dump: ${lines.size} lines -> $path")
            RhythmBeatTracker.estimateTiming(pcm, sampleRate, analysis.usedBpm, durationMs).let {
                File("$path.tracked").writeText(it.tracked.joinToString("\n") { v -> "%.1f".format(v) })
                println("tracked dump: ${it.tracked.size} beats -> $path.tracked")
            }
            File("$path.onsets").writeText(
                analysis.onsetTimesMs.indices.joinToString("\n") { index ->
                    "%.1f %.4f".format(analysis.onsetTimesMs[index], analysis.onsetStrength[index])
                },
            )
            println("onset dump: ${analysis.onsetTimesMs.size} onsets -> $path.onsets")
        }

        for (mode in modes) {
            for (difficulty in diffs) {
                val chart = when (mode) {
                    RhythmDeriveMode.GRID -> RhythmChartDeriver.derive(
                        master, difficulty, LaneStrategy.BAND_ORDER,
                        maxChord = 2, timing = analysis.timing,
                    )
                    RhythmDeriveMode.ONSET -> RhythmChartDeriver.deriveOnsetNative(master, difficulty)
                    RhythmDeriveMode.CLASSIC -> RhythmClassicGridDeriver.derive(
                        master, difficulty, LaneStrategy.BAND_ORDER, maxChord = 2,
                    )
                }.copy(difficultyName = "${mode.name} ${difficulty.label}")
                val report = RhythmAutoPlayTool.play(chart, master, pulse, tapTimes, kickTimes)
                println(RhythmAutoPlayTool.format(report))
            }
        }
    }

    /** Tool self-check on a synthetic loop whose pulse is exactly k * 500 ms. */
    private fun smoke() {
        val pcm = RhythmTestAudio.drumLoop(durationSeconds = 20.0, bpm = 120.0)
        val analysis = RhythmChartGenerator.analyze(
            pcmMono = pcm,
            sampleRate = 44100,
            durationMs = 20_000,
            params = RhythmAnalysisParams(),
        )
        val pulse = DoubleArray(40) { it * 500.0 }
        val master = RhythmChartGenerator.renderMaster(analysis, LaneStrategy.BAND_ORDER)
        val chart = RhythmChartDeriver.derive(
            master, DspDifficulty.EASY, LaneStrategy.BAND_ORDER,
            maxChord = 2, timing = analysis.timing,
        ).copy(difficultyName = "SMOKE GRID Easy")
        val report = RhythmAutoPlayTool.play(chart, master, pulse)
        println("AUTOPLAY smoke (synthetic 120 BPM, no env vars set)")
        println(RhythmAutoPlayTool.formatTracking(analysis))
        println(RhythmAutoPlayTool.format(report))
        // The synthetic loop is the tool's own regression net: its grid is exact, so a
        // rhythm-following player must clear it comfortably.
        assertTrue("smoke accuracy ${report.accuracy}", report.accuracy >= 0.8)
    }

    private fun parseModes(raw: String?): List<RhythmDeriveMode> =
        raw?.split(",")?.mapNotNull { token ->
            when (token.trim().lowercase()) {
                "grid" -> RhythmDeriveMode.GRID
                "onset" -> RhythmDeriveMode.ONSET
                "classic" -> RhythmDeriveMode.CLASSIC
                else -> null
            }
        }?.ifEmpty { RhythmDeriveMode.entries } ?: RhythmDeriveMode.entries

    private fun parseDiffs(raw: String?): List<DspDifficulty> =
        raw?.split(",")?.mapNotNull { token ->
            DspDifficulty.entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
        }?.ifEmpty { DspDifficulty.entries.toList() } ?: DspDifficulty.entries.toList()

    private fun List<Double>.median(): Double {
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
