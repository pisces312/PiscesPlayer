package com.pisces312.piscesplayer.data.postcard.generate

import com.pisces312.piscesplayer.data.postcard.model.PostcardContent
import com.pisces312.piscesplayer.data.postcard.model.PostcardPaletteSource
import com.pisces312.piscesplayer.data.postcard.model.PostcardTimeOfDay
import com.pisces312.piscesplayer.data.postcard.model.PostcardWeather
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostcardComposerTest {

    private val planner = JourneyPlanner(generatorVersion = 1)
    private val composer = PostcardComposer(generatorVersion = 1, rendererContentVersion = 1)
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    // 12:00 / 18:00 / 00:00 (next day) local on 2026-11-14/15 in Asia/Shanghai.
    private val noon = Instant.parse("2026-11-14T04:00:00Z").toEpochMilli()
    private val evening = Instant.parse("2026-11-14T10:00:00Z").toEpochMilli()
    private val night = Instant.parse("2026-11-14T16:00:00Z").toEpochMilli()

    private fun facts(
        key: String = "local:42",
        durationMs: Long = 200_000L,
        bpm: Float? = null,
        genreKey: String? = null,
        energy: Float? = null,
        lowRatio: Float? = null,
        highRatio: Float? = null,
        title: String = "Title",
        artist: String = "Artist",
        artwork: List<Int> = emptyList(),
    ) = SongFacts(
        stableSongKey = key,
        librarySongId = 42L,
        title = title,
        artist = artist,
        durationMs = durationMs,
        bpm = bpm,
        genreKey = genreKey,
        energy = energy,
        lowRatio = lowRatio,
        midRatio = null,
        highRatio = highRatio,
        artworkPaletteArgb = artwork,
        artworkBrightness = null,
        masterGeneratedAtMs = null,
    )

    private fun compose(
        f: SongFacts = facts(genreKey = "folk"),
        at: Long = noon,
    ) = composer.compose(f, planner.plan(f, at, zone), at, zone)

    @Test
    fun composeIsDeterministicForSameInput() {
        val a = compose()
        val b = compose()
        assertEquals(a, b)
        assertEquals(
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.pisces312.piscesplayer.data.postcard.model.PostcardSpec.serializer()), a),
            Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.pisces312.piscesplayer.data.postcard.model.PostcardSpec.serializer()), b),
        )
    }

    @Test
    fun specCountFollowsRouteLength() {
        // M0 caps at the 2 configured spots per region (see JourneyPlannerTest).
        assertEquals(1, compose(facts(durationMs = 90_000L)).size)
        assertEquals(2, compose(facts(durationMs = 200_000L)).size)
        assertEquals(2, compose(facts(durationMs = 300_000L)).size)
    }

    @Test
    fun nightLowEnergyYieldsSnow() {
        // Rule 1: energy < 0.35 and night -> snow, regardless of seed.
        for (i in 0 until 20) {
            val specs = compose(facts(key = "local:$i", energy = 0.3f, lowRatio = 0.8f), at = night)
            assertTrue(
                "key $i night low energy must be snowy",
                specs.all { it.conditionKey.endsWith("_snowy") },
            )
        }
    }

    @Test
    fun lowEnergyNonNightSnowIsSeedGatedAndStable() {
        // Rule 2: energy < 0.25 outside night only snows when hash % 4 == 0; find one and pin it.
        var snowyKey: String? = null
        for (i in 0 until 100) {
            val specs = compose(facts(key = "local:$i", energy = 0.2f), at = noon)
            if (specs.any { it.conditionKey.endsWith("_snowy") }) {
                snowyKey = "local:$i"
                break
            }
        }
        val key = requireNotNull(snowyKey) { "no seed-gated snow found in 100 keys — rule broken" }
        // The found key must reproduce the same snowy condition forever.
        val first = compose(facts(key = key, energy = 0.2f), at = noon)
        val second = compose(facts(key = key, energy = 0.2f), at = noon)
        assertEquals(first, second)
        assertTrue(first.any { it.conditionKey.endsWith("_snowy") })
    }

    @Test
    fun highRatioYieldsSunny() {
        val specs = compose(facts(energy = 0.5f, highRatio = 0.5f, lowRatio = 0.2f), at = noon)
        assertTrue(specs.all { it.conditionKey.endsWith("_sunny") })
    }

    @Test
    fun lowRatioYieldsRainy() {
        val specs = compose(facts(energy = 0.5f, highRatio = 0.1f, lowRatio = 0.6f), at = noon)
        assertTrue(specs.all { it.conditionKey.endsWith("_rainy") })
    }

    @Test
    fun noDspNeverSnows() {
        for (i in 0 until 50) {
            for (at in listOf(noon, evening, night)) {
                val specs = compose(facts(key = "local:$i"), at = at)
                assertTrue(
                    "no-DSP key $i must never roll snow",
                    specs.none { it.conditionKey.endsWith("_snowy") },
                )
            }
        }
    }

    @Test
    fun allNineConditionKeysConstructible() {
        val found = mutableSetOf<String>()
        for (i in 0 until 400) {
            val key = "local:$i"
            for (at in listOf(noon, evening, night)) {
                for (energy in listOf(0.2f, 0.5f, 0.9f)) {
                    val specs = compose(facts(key = key, energy = energy), at = at)
                    specs.forEach { found.add(it.conditionKey) }
                }
            }
        }
        val expected = PostcardTimeOfDay.entries.flatMap { time ->
            PostcardWeather.entries.map { weather -> "${time.key}_${weather.key}" }
        }.toSet()
        assertEquals("all 9 condition keys must be constructible", expected, found)
    }

    @Test
    fun messageMatchesRegionWeatherAndContainsDistance() {
        val specs = compose(facts(genreKey = "folk", durationMs = 300_000L, bpm = 90f))
        for (spec in specs) {
            val template = PostcardContent.MESSAGE_TEMPLATES
                .firstOrNull { it.id == spec.messageTemplateId }
            requireNotNull(template) { "unknown template id ${spec.messageTemplateId}" }
            val exact = template.regionId == spec.regionId && template.weather != null
            val generic = template.regionId == null && template.weather == null
            assertTrue("template ${template.id} must match or be generic", exact || generic)
            if (exact) {
                val weatherKey = spec.conditionKey.substringAfterLast('_')
                assertEquals(weatherKey, template.weather!!.key)
            }
            assertEquals(
                template.text.replace("{distance}", spec.messageArgs.getValue("distance")),
                spec.messageTextSnapshot,
            )
            assertTrue("{distance} replaced in snapshot", "{distance}" !in spec.messageTextSnapshot)
            assertTrue("snapshot names the distance", "公里" in spec.messageTextSnapshot)
        }
    }

    @Test
    fun titleAndArtistChangesDoNotChangeTravelIdentity() {
        val base = compose(facts(key = "local:7", genreKey = "folk"))
        val renamed = compose(facts(key = "local:7", genreKey = "folk", title = "改名", artist = "別人"))
        assertEquals(base.map { it.cardKey }, renamed.map { it.cardKey })
        assertEquals(base.map { it.songSeedHash }, renamed.map { it.songSeedHash })
        assertEquals(base.map { it.spotId }, renamed.map { it.spotId })
        assertEquals(base.map { it.conditionKey }, renamed.map { it.conditionKey })
        assertEquals("改名", renamed.first().songTitleSnapshot)
        assertEquals("別人", renamed.first().artistSnapshot)
    }

    @Test
    fun artworkPaletteUsedWhenPresentAndPaddedToFour() {
        val artwork = listOf(0xFF123456.toInt(), 0xFF654321.toInt())
        val specs = compose(facts(artwork = artwork, genreKey = "folk"))
        assertTrue(specs.all { it.paletteSource == PostcardPaletteSource.ARTWORK })
        assertTrue(specs.all { it.paletteArgb.size >= 4 })
        assertTrue(specs.all { it.paletteArgb.take(2) == artwork })
    }

    @Test
    fun fallbackPaletteOtherwise() {
        val specs = compose(facts(genreKey = "folk"))
        val region = PostcardContent.REGION_BY_ID.getValue("old_town")
        assertTrue(specs.all { it.paletteSource == PostcardPaletteSource.FALLBACK })
        assertTrue(specs.all { it.paletteArgb == region.fallbackPaletteArgb })
    }

    @Test
    fun stampSerialInRangeAndDerivedFromCardKey() {
        val a = compose(facts(key = "local:1", genreKey = "folk"))
        val b = compose(facts(key = "local:1", genreKey = "folk"))
        for (spec in a) {
            assertTrue(spec.stampSerial in 1..9999)
        }
        assertEquals(a.map { it.stampSerial }, b.map { it.stampSerial })
        // Different songs at the same spot+condition get different serials (over a sample).
        val serials = (0 until 30)
            .flatMap { compose(facts(key = "local:$it", genreKey = "folk")).map { it.stampSerial } }
        assertTrue("serials should spread over 30 songs", serials.toSet().size > 1)
    }

    @Test
    fun cardKeyUsesUnitSeparatorBetweenThreeParts() {
        val spec = compose(facts(genreKey = "folk")).first()
        val parts = spec.cardKey.split("\u001F")
        assertEquals(3, parts.size)
        assertEquals(spec.spotId, parts[1])
        assertEquals(spec.conditionKey, parts[2])
    }

    @Test
    fun specJsonRoundTrips() {
        val json = Json
        val specs = compose(facts(genreKey = "folk", durationMs = 300_000L, bpm = 100f))
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.pisces312.piscesplayer.data.postcard.model.PostcardSpec.serializer()),
            specs,
        )
        val decoded = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(com.pisces312.piscesplayer.data.postcard.model.PostcardSpec.serializer()),
            encoded,
        )
        assertEquals(specs, decoded)
        // Compact JSON stays small — a spec is a recipe, not a picture.
        assertTrue("spec JSON too large: ${encoded.length}", encoded.length < 4096)
    }

    @Test
    fun figurePoseFollowsWeatherAndBpm() {
        val rainy = compose(facts(energy = 0.5f, lowRatio = 0.8f, genreKey = "folk"))
        assertTrue(rainy.all { it.figure.poseId == "figure_umbrella" })
        val snowyNight = compose(facts(energy = 0.3f, genreKey = "folk"), at = night)
        assertTrue(snowyNight.all { it.figure.poseId == "figure_snow" })
        val brisk = compose(facts(energy = 0.9f, highRatio = 0.6f, bpm = 130f))
        assertTrue(brisk.all { it.figure.poseId == "figure_walk" })
        val calm = compose(facts(energy = 0.5f, highRatio = 0.6f, bpm = 70f))
        assertTrue(calm.all { it.figure.poseId == "figure_rest" })
    }

    @Test
    fun skyGradientAndMoonFollowCondition() {
        val nightSpecs = compose(facts(energy = 0.3f, genreKey = "folk"), at = night)
        assertTrue(nightSpecs.all { it.sky.moonPhase != null })
        val daySpecs = compose(facts(genreKey = "folk"), at = noon)
        assertTrue(daySpecs.all { it.sky.moonPhase == null })
        assertTrue(daySpecs.all { it.sky.gradientId.startsWith("sky_morning_") })
    }

    @Test
    fun rendererContentVersionChangesVariantSeed() {
        val upgraded = PostcardComposer(generatorVersion = 1, rendererContentVersion = 2)
        val f = facts(key = "local:9", genreKey = "folk")
        val v1 = compose(f)
        val v2 = upgraded.compose(f, planner.plan(f, noon, zone), noon, zone)
        // Same region/route (generator unchanged), different variant details.
        assertEquals(v1.map { it.spotId }, v2.map { it.spotId })
        assertNotEquals(v1.map { it.sky }, v2.map { it.sky })
        // Design: stampSerial derives from cardKey ONLY — a renderer content bump never
        // renumbers existing cards, so v1 and v2 must agree here.
        assertEquals(v1.map { it.stampSerial }, v2.map { it.stampSerial })
    }

    // ------------------------------------------------------------- localization

    @Test
    fun defaultLanguageIsChineseSoM0GoldenSpecsStayByteIdentical() {
        val f = facts(genreKey = "folk")
        val implicit = compose(f)
        val explicitZh = composer.compose(f, planner.plan(f, noon, zone), noon, zone, "zh")
        assertEquals(implicit, explicitZh)
        assertTrue(implicit.first().messageTextSnapshot.any { it.code > 0x2FFF })
    }

    @Test
    fun englishCardsChangeOnlyTheMessageAndPlaceNameSnapshots() {
        val f = facts(key = "local:en_42", genreKey = "folk", durationMs = 300_000L, bpm = 90f)
        val plan = planner.plan(f, noon, zone)
        val zh = composer.compose(f, plan, noon, zone, "zh")
        val en = composer.compose(f, plan, noon, zone, "en")

        assertEquals(zh.size, en.size)
        for ((chinese, english) in zh.zip(en)) {
            // Identity and art decisions are language-independent.
            assertEquals(chinese.copy(
                messageTextSnapshot = "",
                stamp = chinese.stamp.copy(spotName = ""),
            ), english.copy(
                messageTextSnapshot = "",
                stamp = english.stamp.copy(spotName = ""),
            ))
            assertEquals(chinese.messageTemplateId, english.messageTemplateId)
            assertEquals(chinese.messageArgs, english.messageArgs)
            assertEquals(chinese.stamp.date, english.stamp.date)
            assertEquals(chinese.stamp.serial, english.stamp.serial)
            // Only the two text snapshots differ.
            assertNotEquals(chinese.messageTextSnapshot, english.messageTextSnapshot)
            assertNotEquals(chinese.stamp.spotName, english.stamp.spotName)
            assertTrue("{distance}" !in english.messageTextSnapshot)
            assertTrue("Walked" in english.messageTextSnapshot)
            assertTrue(english.stamp.spotName.contains("Old Town"))
        }
    }

    @Test
    fun englishOutputIsDeterministicAndUnknownLanguageFallsBackToEnglishPool() {
        val f = facts(key = "local:en_7", genreKey = "electronic")
        val plan = planner.plan(f, noon, zone)
        val a = composer.compose(f, plan, noon, zone, "en")
        val b = composer.compose(f, plan, noon, zone, "en-US")
        assertEquals(a, b)
        assertTrue(a.all { "Walked" in it.messageTextSnapshot })
        assertTrue(a.all { it.stamp.spotName.startsWith("Longlight") })
    }

    @Test
    fun chineseVariantsAllUseChineseNames() {
        val f = facts(key = "local:zh_1", genreKey = "rock", durationMs = 300_000L)
        for (spec in compose(f)) {
            // Every region name is CJK in the Chinese pool.
            assertTrue(spec.stamp.spotName.any { it.code > 0x2FFF })
        }
    }
}
