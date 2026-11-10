package com.pisces312.piscesplayer.data.rhythm

import java.io.File
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Judges the Kotlin DSP pipeline on the synthetic fixtures from
 * `tools/rhythm-lab/gen_dsp_fixture.py`, which synthesises 20 s / 120 BPM drum-bass loops:
 * - `dsp-beats-120bpm` — hats on eighths, so the master (every onset) has the same note count
 *   as Hard: it pins the difficulty pipeline but cannot show the ladder;
 * - `dsp-dense-120bpm` — hats on thirty-seconds, so the master is roughly twice Hard: it is the
 *   one that proves raw > hard > normal > easy.
 *
 * Two judges, deliberately different:
 *
 * - **Master (onset) times** are compared against the PC reference's peak picking
 *   (`expected.json` raw): the detector's time base, latency and peak quirks are
 *   implementation semantics shared with the reference, so strict equivalence is the
 *   regression pin — both implementations still produce the same peaks.
 * - **Quantized difficulties** (EASY/NORMAL/HARD) are judged against the *construction
 *   truth* of the synth: kick lands exactly on `k * 500.0 ms`, so every quantized note must
 *   sit on `k * (500.0 / gridDiv)` ms. The reference's own grid is ~25 ms off that truth
 *   (its autocorrelation tempo/phase estimate, −0.05% rate), and since the piecewise beat
 *   grid landed, the Kotlin grid is the more accurate one (measured max deviation 9–10 ms
 *   here). Matching against the reference's quantization would judge accuracy downward, so
 *   the truth is the only fair ruler. Cell fill counts and lanes stay reference-compared:
 *   which of two near-boundary onsets fills a cell is detector jitter, not a musical
 *   difference, and the band-to-lane table is undisputed.
 *
 * Matching is deliberately tolerant: Python `round` is half-to-even and Kotlin `roundToInt`
 * is half-up, and float32/float64 accumulation differs across the two runtimes. A 15 ms
 * two-way match rate above 95% still catches any real divergence (a wrong band layout, a
 * wrong peak threshold or a wrong phase would collapse it immediately).
 */
class RhythmChartGeneratorGoldenTest {

    private val toleranceMs = 15

    /**
     * Quantized positions against the construction grid. 20 ms rather than the 15 ms of the
     * reference comparison: the tracker's period estimate is good to ~0.05%, which drifts the
     * lattice up to ~20 ms across a 20 s fixture. A wrong phase or period is a half-beat
     * (330 ms) error, so this still catches any real divergence decisively.
     */
    private val truthToleranceMs = 20
    private val minimumMatchRate = 0.95
    private val toleranceBpm = 0.5

    /** The synth's construction: one beat = 500.0 ms at 120 BPM (gen_dsp_fixture.py). */
    private val truthBeatMs = 500.0

    private data class Fixture(
        val audio: FloatArray,
        val sampleRate: Int,
        val durationMs: Int,
        val bpm: Double,
        val raw: List<Pair<Double, Int>>,
        val difficulties: Map<DspDifficulty, List<Pair<Double, Int>>>,
    )

    private fun resource(name: String): File {
        val url = requireNotNull(javaClass.classLoader?.getResource("rhythm/$name")) {
            "missing test resource rhythm/$name — run tools/rhythm-lab/gen_dsp_fixture.py"
        }
        return File(url.toURI())
    }

    private fun load(stem: String): Fixture {
        val (audio, sampleRate) = RhythmTestAudio.readMonoWav16(resource("$stem.wav"))
        val root = Json.parseToJsonElement(resource("$stem.expected.json").readText()).jsonObject
        val durationMs = root.getValue("durationMs").jsonPrimitive.int

        val difficulties = root.getValue("difficulties").jsonObject.mapKeys { (key, _) ->
            DspDifficulty.valueOf(key)
        }.mapValues { (_, block) -> block.jsonObject.notePairs() }

        return Fixture(
            audio = audio,
            sampleRate = sampleRate,
            durationMs = durationMs,
            bpm = root.getValue("bpm").jsonPrimitive.double,
            raw = root.getValue("raw").jsonObject.notePairs(),
            difficulties = difficulties,
        )
    }

    private fun JsonObject.notePairs(): List<Pair<Double, Int>> =
        getValue("notes").jsonArray.map {
            val note = it.jsonObject
            note.getValue("t").jsonPrimitive.double to note.getValue("lane").jsonPrimitive.int
        }

    private fun analysisOf(fixture: Fixture): DspAnalysis = RhythmChartGenerator.analyze(
        pcmMono = fixture.audio,
        sampleRate = fixture.sampleRate,
        durationMs = fixture.durationMs,
        params = RhythmAnalysisParams(),
    )

    /** Two-way match rate plus lane agreement, reported in the failure message. */
    private fun assertMatches(
        label: String,
        actual: List<Pair<Double, Int>>,
        expected: List<Pair<Double, Int>>,
        expectedNoteCount: Int = expected.size,
    ): Double {
        val actualTimes = actual.map { it.first }
        val expectedTimes = expected.map { it.first }.sorted()

        val sizeRatio = actual.size.toDouble() / expectedNoteCount
        assertTrue(
            "$label — note count ${actual.size} vs $expectedNoteCount (${(sizeRatio - 1) * 100}%)",
            abs(1 - sizeRatio) <= 0.05,
        )

        val forward = matchRate(actualTimes, expectedTimes, toleranceMs)
        val backward = matchRate(expectedTimes, actualTimes, toleranceMs)
        assertTrue(
            "$label — match forward=${forward * 100}% backward=${backward * 100}%",
            forward >= minimumMatchRate && backward >= minimumMatchRate,
        )

        val laneAgreement = actual.count { note ->
            expected.any { (time, lane) -> abs(time - note.first) <= toleranceMs && lane == note.second }
        }
        println(
            "GOLDEN $label: notes=${actual.size}/$expectedNoteCount " +
                "forward=${forward * 100}% backward=${backward * 100}% " +
                "lane=$laneAgreement/${actual.size}",
        )
        return laneAgreement.toDouble() / actual.size
    }

    /**
     * Quantized notes must sit on the construction grid `k * (500 / gridDiv)` ms; density and
     * lanes stay reference-compared (see the class doc for why the judge is split).
     */
    private fun assertOnTruthGrid(
        label: String,
        actual: List<Pair<Double, Int>>,
        expected: List<Pair<Double, Int>>,
        gridDiv: Int,
    ): Double {
        val stepMs = truthBeatMs / gridDiv

        var maxDeviation = 0.0
        for ((time, _) in actual) {
            val deviation = abs(time - Math.round(time / stepMs) * stepMs)
            if (deviation > maxDeviation) maxDeviation = deviation
        }
        assertTrue(
            "$label — max deviation $maxDeviation ms from the $stepMs ms construction grid",
            maxDeviation <= truthToleranceMs,
        )

        val sizeRatio = actual.size.toDouble() / expected.size
        assertTrue(
            "$label — note count ${actual.size} vs ${expected.size} (${(sizeRatio - 1) * 100}%)",
            // Cell fill of near-boundary onsets flips with the grid phase (two onsets 70 ms
            // apart share a cell or not depending on where the lines fall), so the count only
            // anchors the density; positions and the ladder carry the real checks.
            abs(1 - sizeRatio) <= 0.10,
        )

        // Lanes are matched by grid line, not raw time: the two grids disagree by up to
        // ~25 ms, but a note's lane is a property of the line it quantizes to.
        val expectedByLine = expected.associate { (time, lane) ->
            Math.round(time / stepMs).toInt() to lane
        }
        val laneAgreement = actual.count { (time, lane) ->
            expectedByLine[Math.round(time / stepMs).toInt()] == lane
        }
        println(
            "TRUTH $label: notes=${actual.size}/${expected.size} " +
                "maxDev=${maxDeviation}ms step=$stepMs lane=$laneAgreement/${actual.size}",
        )
        return laneAgreement.toDouble() / actual.size
    }

    @Test
    fun masterMatchesReferenceOnEveryFixturesOnsets() {
        // The master is every detected onset, so its lane is an unambiguous argmax and must
        // agree with the reference implementation (same band -> lane table).
        for (stem in listOf("dsp-beats-120bpm", "dsp-dense-120bpm")) {
            val fixture = load(stem)
            val analysis = analysisOf(fixture)
            val master = RhythmChartGenerator.renderMaster(analysis, LaneStrategy.BAND_ORDER)
            assertEquals(
                "$stem — bpm ${analysis.usedBpm} vs ${fixture.bpm}",
                fixture.bpm,
                analysis.usedBpm,
                toleranceBpm,
            )
            val laneAgreement = assertMatches(
                label = "$stem RAW",
                actual = master.notes.map { it.timeMs.toDouble() to it.lane },
                expected = fixture.raw,
            )
            assertTrue("$stem RAW — lane agreement $laneAgreement", laneAgreement >= 0.95)
        }
    }

    @Test
    fun difficultiesSnapToTheConstructionGridOnBeatsFixture() {
        val fixture = load("dsp-beats-120bpm")
        val analysis = analysisOf(fixture)
        val master = RhythmChartGenerator.renderMaster(analysis, LaneStrategy.BAND_ORDER)

        assertEquals(fixture.bpm, analysis.usedBpm, toleranceBpm)

        for ((difficulty, expectedNotes) in fixture.difficulties) {
            val chart = RhythmChartGenerator.render(analysis, difficulty, LaneStrategy.BAND_ORDER)
            val laneAgreement = assertOnTruthGrid(
                label = "BEATS ${difficulty.name}",
                actual = chart.notes.map { it.timeMs.toDouble() to it.lane },
                expected = expectedNotes,
                gridDiv = difficulty.gridDiv,
            )

            // Lanes are intentionally not asserted for the coarse grids: `argmax` over whitened
            // bands is genuinely ambiguous when several bands peak on one frame (a kick and a
            // hi-hat sharing a cell), so the reference itself flips lanes between neighbours.
            // Hard is unambiguous because each onset owns its own cell.
            if (difficulty == DspDifficulty.HARD) {
                assertTrue(
                    "BEATS HARD — lane agreement $laneAgreement",
                    laneAgreement >= 0.95,
                )
            }

            // Deriving from the master must reproduce the direct render note for note.
            val derived = RhythmChartDeriver.derive(master, difficulty, LaneStrategy.BAND_ORDER)
            assertEquals(
                "BEATS ${difficulty.name} — derived note count",
                chart.noteCount(),
                derived.noteCount(),
            )
            chart.notes.zip(derived.notes).forEach { (direct, thin) ->
                assertEquals("BEATS ${difficulty.name} — derived time", direct.timeMs, thin.timeMs)
                assertEquals("BEATS ${difficulty.name} — derived lane", direct.lane, thin.lane)
            }
        }
    }

    @Test
    fun denseFixtureSeparatesTheLadder() {
        // The whole point of the master: on the thirty-second-note fixture the master must hold
        // noticeably more notes than Hard, which the eighth-note fixture cannot show.
        val fixture = load("dsp-dense-120bpm")
        val analysis = analysisOf(fixture)
        val master = RhythmChartGenerator.renderMaster(analysis, LaneStrategy.BAND_ORDER)

        assertEquals(fixture.bpm, analysis.usedBpm, toleranceBpm)
        assertMatches(
            label = "DENSE RAW",
            actual = master.notes.map { it.timeMs.toDouble() to it.lane },
            expected = fixture.raw,
        )

        val counts = fixture.difficulties.mapValues { (difficulty, expectedNotes) ->
            val chart = RhythmChartGenerator.render(analysis, difficulty, LaneStrategy.BAND_ORDER)
            assertOnTruthGrid(
                label = "DENSE ${difficulty.name}",
                actual = chart.notes.map { it.timeMs.toDouble() to it.lane },
                expected = expectedNotes,
                gridDiv = difficulty.gridDiv,
            )
            chart.noteCount()
        }

        val easy = counts.getValue(DspDifficulty.EASY)
        val normal = counts.getValue(DspDifficulty.NORMAL)
        val hard = counts.getValue(DspDifficulty.HARD)

        assertTrue("dense: easy $easy should be < normal $normal", easy < normal)
        assertTrue("dense: normal $normal should be < hard $hard", normal < hard)
        assertTrue(
            "dense: master ${master.noteCount()} should be well above hard $hard",
            master.noteCount() >= hard * 1.5,
        )
    }

    @Test
    fun chordsExpandOnDetectedOnsetsWithoutBreakingTheLadder() {
        // The detector runs on one summed onset function, so nothing here produces a chord on
        // its own; these numbers show what the cell-level expansion recovers on real onsets.
        for (stem in listOf("dsp-beats-120bpm", "dsp-dense-120bpm")) {
            val fixture = load(stem)
            val analysis = analysisOf(fixture)
            val master = RhythmChartGenerator.renderMaster(analysis, LaneStrategy.BAND_ORDER)

            for (difficulty in DspDifficulty.entries) {
                val single = RhythmChartGenerator.render(
                    analysis, difficulty, LaneStrategy.BAND_ORDER, maxChord = 1,
                )
                val triple = RhythmChartGenerator.render(
                    analysis, difficulty, LaneStrategy.BAND_ORDER, maxChord = 3,
                )
                println(
                    "CHORD $stem ${difficulty.name}: " +
                        "single=${single.noteCount()} triple=${triple.noteCount()} " +
                        "master=${master.noteCount()}",
                )

                assertTrue(
                    "$stem ${difficulty.name}: $ ${triple.noteCount()} < ${single.noteCount()}",
                    triple.noteCount() >= single.noteCount(),
                )
                assertTrue(
                    "$stem ${difficulty.name}: chords exceeded the master",
                    triple.noteCount() <= master.noteCount(),
                )

                // Deriving the same chart must land on exactly the same notes.
                val derived = RhythmChartDeriver.derive(
                    master, difficulty, LaneStrategy.BAND_ORDER, maxChord = 3,
                )
                assertEquals(
                    "$stem ${difficulty.name}: declared size",
                    triple.noteCount(),
                    derived.noteCount(),
                )
                triple.notes.zip(derived.notes).forEach { (direct, thin) ->
                    assertEquals("$stem ${difficulty.name}: time", direct.timeMs, thin.timeMs)
                    assertEquals("$stem ${difficulty.name}: lane", direct.lane, thin.lane)
                }
            }
        }
    }

    @Test
    fun onsetNativeDerivationKeepsDetectedTimestampsAndDensityCaps() {
        for (stem in listOf("dsp-beats-120bpm", "dsp-dense-120bpm")) {
            val fixture = load(stem)
            val analysis = analysisOf(fixture)
            val master = RhythmChartGenerator.renderMaster(analysis, LaneStrategy.BAND_ORDER)
            val masterTimes = master.notes.map { it.timeMs }.toSet()
            val counts = LinkedHashMap<DspDifficulty, Int>()

            for (difficulty in DspDifficulty.entries) {
                val chart = RhythmChartDeriver.deriveOnsetNative(master, difficulty)
                val label = "$stem ${difficulty.name}"
                val profile = difficulty.deriveProfile
                counts[difficulty] = chart.noteCount()
                val times = chart.notes.map { it.timeMs }.sorted()

                for (time in times) {
                    assertTrue("$label: $time is not a master onset", time in masterTimes)
                }
                for (index in 1 until times.size) {
                    val gap = times[index] - times[index - 1]
                    if (gap < profile.minGapMs) {
                        assertTrue(
                            "$label: $gap ms is neither a chord nor minGap",
                            gap <= RhythmChartDeriver.CHORD_WINDOW_MS,
                        )
                    }
                }
                val cap = profile.maxNotesPerSecond.toInt()
                for (end in times) {
                    val inWindow = times.count { it > end - 1000 && it <= end }
                    assertTrue("$label: $inWindow notes in the 1 s before $end", inWindow <= cap)
                }
            }
            println("ONSET-NATIVE $stem: master=${master.noteCount()} counts=$counts")
        }
    }

    @Test
    fun fixtureIsAtLeastTwentySecondsOfAudio() {
        for (stem in listOf("dsp-beats-120bpm", "dsp-dense-120bpm")) {
            val (audio, sampleRate) = RhythmTestAudio.readMonoWav16(resource("$stem.wav"))
            assertTrue("$stem too short", audio.size / sampleRate >= 19)
            assertTrue("$stem clipping", audio.all { abs(it) <= 1f })
        }
    }

    private fun matchRate(actual: List<Double>, expected: List<Double>, tolerance: Int): Double {
        if (actual.isEmpty() || expected.isEmpty()) return 0.0
        var matched = 0
        for (value in actual) {
            if (expected.any { abs(it - value) <= tolerance }) matched++
        }
        return matched.toDouble() / actual.size
    }
}
