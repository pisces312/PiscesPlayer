package com.pisces312.piscesplayer.data.postcard.generate

import com.pisces312.piscesplayer.data.postcard.model.PostcardSpec
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden pin of the whole pure pipeline (design §13.1 "Spec: golden JSON 与 golden 组件选择").
 *
 * Fixed inputs -> [JourneyPlanner] + [PostcardComposer] -> serialized spec list must equal the
 * committed resource `postcard/golden/<case>.json`. When the resource is missing the test
 * writes it into the *build* resources directory and fails with the copy instruction — the
 * file under `src/test/resources` is version-controlled, so the golden never changes silently
 * (an intentional algorithm change must regenerate and review the diff).
 */
class PostcardGoldenTest {

    private val planner = JourneyPlanner(generatorVersion = 1)
    private val composer = PostcardComposer(generatorVersion = 1, rendererContentVersion = 1)
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val json = Json

    private val noon = Instant.parse("2026-11-14T04:00:00Z").toEpochMilli()
    private val night = Instant.parse("2026-11-14T16:00:00Z").toEpochMilli()

    private class Case(val name: String, val facts: SongFacts, val at: Long)

    private fun cases(): List<Case> = listOf(
        Case(
            name = "folk-morning-2cards",
            facts = facts(
                key = "local:gold_folk",
                genreKey = "folk",
                durationMs = 200_000L,
                bpm = 90f,
                title = "晨间民谣",
                artist = "旅人甲",
            ),
            at = noon,
        ),
        Case(
            name = "electronic-night-3cards",
            facts = facts(
                key = "local:gold_electronic",
                genreKey = "electronic",
                durationMs = 300_000L,
                bpm = 128f,
                energy = 0.8f,
                highRatio = 0.6f,
                lowRatio = 0.1f,
                title = "Night Circuit",
                artist = "Neon Fox",
            ),
            at = night,
        ),
        Case(
            name = "no-dsp-1card",
            facts = facts(
                key = "local:gold_bare",
                durationMs = 90_000L,
                title = "短歌",
                artist = "无名",
            ),
            at = night,
        ),
        Case(
            name = "low-energy-snow-night",
            facts = facts(
                key = "local:gold_snow",
                genreKey = "classical",
                durationMs = 260_000L,
                bpm = 60f,
                energy = 0.3f,
                lowRatio = 0.8f,
                highRatio = 0.1f,
                title = "夜雪练习曲",
                artist = "旅人乙",
            ),
            at = night,
        ),
    )

    private fun facts(
        key: String,
        genreKey: String? = null,
        durationMs: Long,
        bpm: Float? = null,
        title: String,
        artist: String,
        energy: Float? = null,
        lowRatio: Float? = null,
        highRatio: Float? = null,
    ) = SongFacts(
        stableSongKey = key,
        librarySongId = null,
        title = title,
        artist = artist,
        durationMs = durationMs,
        bpm = bpm,
        genreKey = genreKey,
        energy = energy,
        lowRatio = lowRatio,
        midRatio = null,
        highRatio = highRatio,
        artworkPaletteArgb = emptyList(),
        artworkBrightness = null,
        masterGeneratedAtMs = null,
    )

    @Test
    fun goldenSpecsMatchCommittedResources() {
        val missing = mutableListOf<String>()
        for (case in cases()) {
            val specs = composer.compose(case.facts, planner.plan(case.facts, case.at, zone), case.at, zone)
            val serialized = json.encodeToString(ListSerializer(PostcardSpec.serializer()), specs)

            val resourceUrl = javaClass.classLoader?.getResource("postcard/golden/${case.name}.json")
            if (resourceUrl == null) {
                // Record every missing case (not just the first) before failing.
                val buildResourcesRoot = requireNotNull(
                    javaClass.classLoader?.getResource("postcard"),
                ) { "test resources root missing" }.let { File(it.toURI()).parentFile }
                val target = File(buildResourcesRoot, "golden/${case.name}.json")
                target.parentFile.mkdirs()
                target.writeText(serialized)
                missing += "${case.name} (candidate at ${target.path})"
                continue
            }
            val committed = File(resourceUrl.toURI()).readText()
            assertEquals(
                "golden drift for case ${case.name} — intentional change? regenerate and review the diff",
                committed.trim(),
                serialized,
            )
        }
        assertTrue(
            "golden resources missing: ${missing.joinToString()}; " +
                "copy the recorded candidates into app/src/test/resources/postcard/golden/ and re-run",
            missing.isEmpty(),
        )
    }

    @Test
    fun goldenCasesCoverMultipleRegionsAndCardCounts() {
        val outputs = cases().map { case ->
            composer.compose(case.facts, planner.plan(case.facts, case.at, zone), case.at, zone)
        }
        val regions = outputs.flatten().map { it.regionId }.toSet()
        assertTrue("golden cases should cover >= 3 regions", regions.size >= 3)
        // M0 cap: every region configures 2 spots, so >=240 s cases yield 2 cards.
        assertEquals(listOf(2, 2, 1, 2), outputs.map { it.size })
    }
}
