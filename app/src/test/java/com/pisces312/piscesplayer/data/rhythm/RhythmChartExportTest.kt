/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work in 2026 for PixelPlayerOSS
 * (docs/plans/manual-tap-chart-mobile-plan.md §3, chart export).
 */
package com.pisces312.piscesplayer.data.rhythm

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors are produced by running the RhythmChartLab PC code (`rhythm_lab/chart.py`,
 * `rhythm_lab/bundle.py`) over this exact fixture: the canonical-JSON strings and the
 * chartSha256 / timingHash / bundleId hashes below are what `chart.chart_hash()` and
 * `bundle._manifest_hash()` compute there. Matching them means the PC importers accept
 * the exported files unchanged.
 */
class RhythmChartExportTest {

    // ---------------------------------------------------------------- fixture

    private fun fixtureChart() = RhythmChart(
        source = RhythmChart.SOURCE_TAP,
        difficultyName = "手打测试",
        timing = listOf(
            RhythmTimingPoint(timeMs = 0, beatMs = 500.0),
            RhythmTimingPoint(timeMs = 10000, beatMs = 461.53846153846155),
        ),
        notes = listOf(
            RhythmNote(id = 0, timeMs = 0, lane = 0),
            RhythmNote(id = 1, timeMs = 1000, lane = 3),
            RhythmNote(id = 2, timeMs = 2550, lane = 2),
        ),
        durationMs = 30000,
    )

    private fun fixtureInfo() = RhythmChartExport.SongInfo(
        title = "测试曲",
        artist = "测试歌手",
        durationMs = 30000,
        audioFileName = "test.mp3",
        audioFingerprint = "fp-test-1234",
    )

    private fun fixtureTake() = RhythmTapTake(
        audio = RhythmTapAudio(path = "/music/test.mp3", durationMs = 30000, sampleRate = 44100),
        startedAt = "2026-11-19T22:00:00Z",
        hitCount = 3,
        hits = listOf(
            RhythmTapHit(timeMs = 0.0, key = "d"),
            RhythmTapHit(timeMs = 1000.0, key = "k"),
            RhythmTapHit(timeMs = 2550.0, key = "j"),
        ),
    )

    // ---------------------------------------------------------------- canonical json

    @Test
    fun canonicalChartJsonMatchesPcGolden() {
        val canonical = RhythmChartExport.canonical(
            RhythmChartExport.chartDict(fixtureChart(), fixtureInfo()),
        )
        assertEquals(GOLDEN_CANONICAL, canonical)
        assertEquals(GOLDEN_CHART_SHA256, sha256(canonical))
    }

    @Test
    fun canonicalScalarsMatchPythonJsonDumps() {
        assertEquals("null", RhythmChartExport.canonical(null))
        assertEquals("true", RhythmChartExport.canonical(true))
        assertEquals("30000", RhythmChartExport.canonical(30000))
        assertEquals("0.0", RhythmChartExport.canonical(0.0))
        assertEquals("25.0", RhythmChartExport.canonical(25.0))
        assertEquals("1.176", RhythmChartExport.canonical(1.176))
        assertEquals("461.53846153846155", RhythmChartExport.canonical(461.53846153846155))
        // ensure_ascii=False keeps non-ASCII raw; only quote/backslash/control are escaped.
        assertEquals("\"手打\\n测试\\\"q\\\\\"", RhythmChartExport.canonical("手打\n测试\"q\\"))
        assertEquals("\"\\u0001\"", RhythmChartExport.canonical("\u0001"))
        assertEquals("{\"a\":2,\"b\":1}", RhythmChartExport.canonical(mapOf("b" to 1, "a" to 2)))
        assertEquals("[]", RhythmChartExport.canonical(emptyList<Any>()))
        assertEquals("{}", RhythmChartExport.canonical(emptyMap<String, Any?>()))
    }

    @Test
    fun emptyTimingHashesLikePcEmptyList() {
        // sha256("[]") is exactly what the PC computes for a chart without a beat grid.
        val chart = fixtureChart().copy(timing = emptyList())
        val values = RhythmChartExport.chartDict(chart, fixtureInfo()).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val source = values.getValue("source") as Map<String, Any?>
        assertEquals(EMPTY_TIMING_HASH, source.getValue("timingHash"))
    }

    // ---------------------------------------------------------------- prc bundle

    @Test
    fun prcBundleMatchesPcGolden() {
        val entries = unzip(RhythmChartExport.build(RhythmChartExport.Format.PRC, fixtureChart(), null, fixtureInfo()))
        assertEquals(setOf("manifest.json", "手打测试.json"), entries.keys)

        val manifest = parseValues(entries.getValue("manifest.json"))
        assertEquals(GOLDEN_BUNDLE_ID, manifest.getValue("bundleId"))
        assertEquals(GOLDEN_TIMING_HASH, manifest.getValue("timingHash"))
        assertEquals(false, manifest.getValue("aiGenerated"))
        assertEquals(30000L, manifest.getValue("durationMs"))
        assertEquals(
            mapOf("recorder" to "piscesplayer-tap"),
            manifest.getValue("generationParams"),
        )
        // bundleId must be the semantic hash of the manifest without bundleId itself.
        assertEquals(GOLDEN_SEMANTIC, RhythmChartExport.canonical(manifest - "bundleId"))
        assertEquals(GOLDEN_BUNDLE_ID, sha256(RhythmChartExport.canonical(manifest - "bundleId")))

        @Suppress("UNCHECKED_CAST")
        val difficulties = manifest.getValue("difficulties") as Map<String, Any?>
        assertEquals(setOf("手打测试"), difficulties.keys)
        @Suppress("UNCHECKED_CAST")
        val stats = difficulties.getValue("手打测试") as Map<String, Any?>
        assertEquals(3, (stats.getValue("noteCount") as Number).toInt())
        assertEquals(0, (stats.getValue("holdCount") as Number).toInt())
        assertEquals(1.176, (stats.getValue("nps") as Number).toDouble(), 1e-12)
        assertEquals(GOLDEN_CHART_SHA256, stats.getValue("chartSha256"))
        assertEquals(null, stats.getValue("seed"))

        // The chart entry itself must hash to chartSha256 exactly like the PC would compute.
        val chartValues = parseValues(entries.getValue("手打测试.json"))
        assertEquals(GOLDEN_CANONICAL, RhythmChartExport.canonical(chartValues))
        assertEquals(GOLDEN_CHART_SHA256, sha256(RhythmChartExport.canonical(chartValues)))
    }

    // ---------------------------------------------------------------- chart json

    @Test
    fun chartJsonExportMatchesPcGolden() {
        val text = RhythmChartExport.build(RhythmChartExport.Format.CHART_JSON, fixtureChart(), null, fixtureInfo())
            .toString(Charsets.UTF_8)
        val values = parseValues(text)
        assertEquals(GOLDEN_CANONICAL, RhythmChartExport.canonical(values))
        assertEquals(GOLDEN_CHART_SHA256, sha256(RhythmChartExport.canonical(values)))
    }

    // ---------------------------------------------------------------- osu

    @Test
    fun osuExportMatchesGoldenTextAndRoundTrips() {
        val text = RhythmChartExport.build(RhythmChartExport.Format.OSU, fixtureChart(), null, fixtureInfo())
            .toString(Charsets.UTF_8)
        assertEquals(GOLDEN_OSU + "\n", text)

        val parsed = OsuManiaParser.parse(text)
        assertEquals("手打测试", parsed.difficultyName)
        assertEquals(
            listOf(
                RhythmNote(id = 0, timeMs = 0, lane = 0),
                RhythmNote(id = 1, timeMs = 1000, lane = 3),
                RhythmNote(id = 2, timeMs = 2550, lane = 2),
            ),
            parsed.notes,
        )
        assertEquals(
            listOf(
                RhythmTimingPoint(timeMs = 0, beatMs = 500.0),
                RhythmTimingPoint(timeMs = 10000, beatMs = 461.53846153846155),
            ),
            parsed.timing,
        )
    }

    @Test
    fun osuExportDefaultsTimingWhenChartHasNone() {
        val chart = fixtureChart().copy(timing = emptyList())
        val text = RhythmChartExport.build(RhythmChartExport.Format.OSU, chart, null, fixtureInfo())
            .toString(Charsets.UTF_8)
        assertTrue(text.contains("[TimingPoints]\n0,500.0,4,2,0,60,1,0\n"))

        val parsed = OsuManiaParser.parse(text)
        assertEquals(listOf(RhythmTimingPoint(timeMs = 0, beatMs = 500.0)), parsed.timing)
        assertEquals(3, parsed.notes.size)
    }

    @Test
    fun osuExportKeepsLanesThroughOdds() {
        val chart = fixtureChart().copy(
            notes = (0..3).map { RhythmNote(id = it, timeMs = it * 1000, lane = it) },
        )
        val text = RhythmChartExport.build(RhythmChartExport.Format.OSU, chart, null, fixtureInfo())
            .toString(Charsets.UTF_8)
        val parsed = OsuManiaParser.parse(text)
        assertEquals(listOf(0, 1, 2, 3), parsed.notes.map { it.lane })
    }

    // ---------------------------------------------------------------- tap take

    @Test
    fun tapJsonExportIsTheTakeVerbatim() {
        val take = fixtureTake()
        val bytes = RhythmChartExport.build(RhythmChartExport.Format.TAP_JSON, fixtureChart(), take, fixtureInfo())
        assertEquals(take.toJson(), bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun tapJsonWithoutTakeIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            RhythmChartExport.build(RhythmChartExport.Format.TAP_JSON, fixtureChart(), null, fixtureInfo())
        }
    }

    // ---------------------------------------------------------------- file names

    @Test
    fun fileNamesCarryTheFormatExtensionAndDropIllegalCharacters() {
        assertEquals(
            "My_Song-手打.osu",
            RhythmChartExport.fileName(RhythmChartExport.Format.OSU, "My/Song", "手打"),
        )
        assertEquals(
            "a_b_c_d_e_f_g_h.prc",
            RhythmChartExport.fileName(RhythmChartExport.Format.PRC, "a:b*c?d\"e<f>g|h", ""),
        )
        assertEquals("chart.json", RhythmChartExport.fileName(RhythmChartExport.Format.CHART_JSON, "", ""))
        assertTrue(
            RhythmChartExport.fileName(RhythmChartExport.Format.TAP_JSON, "曲", "打谱").endsWith(".tap.json"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return entries
    }

    /** Parse a JSON document into the plain value model the canonical writer consumes. */
    private fun parseValues(text: String): Map<String, Any?> =
        Json.parseToJsonElement(text).jsonObject.toValueMap()

    private fun JsonElement.toValue(): Any? = when (this) {
        is JsonNull -> null
        is JsonObject -> toValueMap()
        is JsonArray -> map { it.toValue() }
        is JsonPrimitive -> when {
            isString -> content
            else -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
        }
    }

    private fun JsonObject.toValueMap(): Map<String, Any?> =
        entries.associate { (key, value) -> key to value.toValue() }

    private companion object {
        const val EMPTY_TIMING_HASH = "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"

        const val GOLDEN_TIMING_HASH = "f68cb14f276f5ef2f6ef9f09d23e3f114b20815854f4ee6f13a7eeaeac704225"
        const val GOLDEN_CHART_SHA256 = "8324df54bb746dc1f5a725874c78362415675b83e0a6d244ee46ba6bfea53e5d"
        const val GOLDEN_BUNDLE_ID = "ea3ae9a9449a12829d040b02f3c7626c3cf1058e7a24eb2d3931d7079657cd00"

        const val GOLDEN_CANONICAL =
            """{"aiGenerated":false,"difficultyName":"手打测试","difficultyStars":0.0,"durationMs":30000,"generatorVersion":"chart-4","keyCount":4,"mode":3,"notes":[{"endTimeMs":null,"id":0,"lane":0,"timeMs":0},{"endTimeMs":null,"id":1,"lane":3,"timeMs":1000},{"endTimeMs":null,"id":2,"lane":2,"timeMs":2550}],"schemaVersion":1,"source":{"audioFingerprint":"fp-test-1234","generationParams":{"recorder":"piscesplayer-tap"},"modelRevision":"","osuSha256":"","postProcessorVersion":"chart-4","seed":null,"sourceCommit":"","timingHash":"f68cb14f276f5ef2f6ef9f09d23e3f114b20815854f4ee6f13a7eeaeac704225"},"timing":[{"beatMs":500.0,"timeMs":0},{"beatMs":461.53846153846155,"timeMs":10000}]}"""

        const val GOLDEN_SEMANTIC =
            """{"aiGenerated":false,"audioFingerprint":"fp-test-1234","difficulties":{"手打测试":{"chartSha256":"8324df54bb746dc1f5a725874c78362415675b83e0a6d244ee46ba6bfea53e5d","conditioning":0.0,"holdCount":0,"noteCount":3,"nps":1.176,"seed":null}},"durationMs":30000,"generationParams":{"recorder":"piscesplayer-tap"},"modelRevision":"","postProcessorVersion":"chart-4","schemaVersion":1,"seed":null,"sourceCommit":"","timingHash":"f68cb14f276f5ef2f6ef9f09d23e3f114b20815854f4ee6f13a7eeaeac704225"}"""

        val GOLDEN_OSU = """
            osu file format v14

            [General]
            AudioFilename: test.mp3
            AudioLeadIn: 0
            PreviewTime: -1
            Countdown: 0
            SampleSet: Soft
            StackLeniency: 0.7
            Mode: 3
            LetterboxInBreaks: 0

            [Metadata]
            Title: 测试曲
            TitleUnicode: 测试曲
            Artist: 测试歌手
            ArtistUnicode: 测试歌手
            Creator: PiscesPlayer
            Version: 手打测试
            BeatmapID: 0
            BeatmapSetID: -1

            [Difficulty]
            HPDrainRate: 5
            CircleSize: 4
            OverallDifficulty: 5
            ApproachRate: 5
            SliderMultiplier: 1.4
            SliderTickRate: 1

            [TimingPoints]
            0,500.0,4,2,0,60,1,0
            10000,461.53846153846155,4,2,0,60,1,0

            [HitObjects]
            64,192,0,1,0,0:0:0:0:
            448,192,1000,1,0,0:0:0:0:
            320,192,2550,1,0,0:0:0:0:
        """.trimIndent()
    }
}
