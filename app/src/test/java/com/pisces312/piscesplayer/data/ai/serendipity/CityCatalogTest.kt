/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original work: unit tests for the offline place index and the bundled place list.
 */
package com.pisces312.piscesplayer.data.ai.serendipity

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class CityCatalogTest {

    // --- Search and lookup over a hand-built list ---------------------------------------------

    private val sample =
            listOf(
                    city("上海市", latinName = "Shanghai", latitude = 31.2222, longitude = 121.4581),
                    city(
                            "黄浦区",
                            latinName = "Huangpu",
                            latitude = 31.2317,
                            longitude = 121.4692,
                            rank = 1,
                            parentName = "上海",
                            parentPrompt = "Shanghai"
                    ),
                    city(
                            "Cedar Rapids",
                            country = "US",
                            latitude = 41.9779,
                            longitude = -91.6656
                    ),
                    city("Rapid City", country = "US", latitude = 44.0805, longitude = -103.2310),
                    city("Zürich", country = "CH", latitude = 47.3667, longitude = 8.5500),
                    city("New York City", country = "US", latitude = 40.7143, longitude = -74.0060),
                    city("Paris", country = "FR", latitude = 48.8534, longitude = 2.3488, rank = 3)
            )

    @Test
    fun `an empty query returns the head of the list, which is what the picker opens on`() {
        assertThat(CityIndex.search(sample, "")).isEqualTo(sample)
    }

    @Test
    fun `a prefix match outranks a match found inside a name`() {
        // "Cedar Rapids" sits before "Rapid City" in the list, so only the ranking can explain
        // the order of the result.
        val matches = CityIndex.search(sample, "rapid")

        assertThat(matches.map { it.name }).containsExactly("Rapid City", "Cedar Rapids").inOrder()
    }

    @Test
    fun `case, accents and spaces do not affect matching`() {
        assertThat(CityIndex.search(sample, "ZÜRICH").map { it.name }).containsExactly("Zürich")
        assertThat(CityIndex.search(sample, "new york").map { it.name })
                .containsExactly("New York City")
        assertThat(CityIndex.search(sample, "newyork").map { it.name })
                .containsExactly("New York City")
    }

    @Test
    fun `a place is reachable by its localized and its latin name`() {
        assertThat(CityIndex.findByName(sample, "上海市")?.promptName).isEqualTo("Shanghai")
        assertThat(CityIndex.findByName(sample, "shanghai")?.name).isEqualTo("上海市")
    }

    @Test
    fun `the result limit is honoured`() {
        assertThat(CityIndex.search(sample, "", limit = 2).map { it.name })
                .containsExactly("上海市", "黄浦区")
                .inOrder()
    }

    @Test
    fun `an unmatched query yields nothing`() {
        assertThat(CityIndex.search(sample, "zzzzz")).isEmpty()
        assertThat(CityIndex.findByName(sample, "zzzzz")).isNull()
        assertThat(CityIndex.findByName(sample, "   ")).isNull()
    }

    @Test
    fun `findByName prefers an exact match and otherwise falls back to a search`() {
        assertThat(CityIndex.findByName(sample, "Paris")?.name).isEqualTo("Paris")
        assertThat(CityIndex.findByName(sample, "New York")?.name).isEqualTo("New York City")
    }

    // --- The two names a place carries --------------------------------------------------------

    @Test
    fun `a district is labelled with the city it belongs to`() {
        val district = CityIndex.findByName(sample, "黄浦区")

        assertThat(district?.label).isEqualTo("黄浦区 · 上海")
    }

    @Test
    fun `a district is searchable by its city, which is how the five 东区 are told apart`() {
        val matches = CityIndex.search(sample, "上海").map { it.name }

        assertThat(matches).containsExactly("上海市", "黄浦区").inOrder()
    }

    @Test
    fun `a district carries its city into the prompt too`() {
        val district = CityIndex.findByName(sample, "黄浦区")

        assertThat(district?.promptName).isEqualTo("Huangpu, Shanghai")
    }

    @Test
    fun `a place without a parent is labelled plainly at home and with its country abroad`() {
        assertThat(CityIndex.findByName(sample, "上海市")?.label).isEqualTo("上海市")
        assertThat(CityIndex.findByName(sample, "Paris")?.label).isEqualTo("Paris, FR")
    }

    // --- The offline reverse lookup -----------------------------------------------------------

    @Test
    fun `nearest picks the closest place`() {
        assertThat(CityIndex.nearest(sample, 48.9, 2.4)?.name).isEqualTo("Paris")
    }

    @Test
    fun `nearest scales longitude by latitude instead of comparing degrees`() {
        // At 60°N a degree of longitude is about half a degree of latitude, so the city that is
        // one degree east is genuinely closer than the one a degree north.
        val cities =
                listOf(
                        city("North", latitude = 61.0, longitude = 0.0),
                        city("East", latitude = 60.0, longitude = 1.0)
                )

        assertThat(CityIndex.nearest(cities, 60.0, 0.0)?.name).isEqualTo("East")
    }

    @Test
    fun `a district wins over its own city, so a position is named as precisely as it can be`() {
        val cities =
                listOf(
                        city(
                                "黄浦区",
                                latinName = "Huangpu",
                                latitude = 31.2317,
                                longitude = 121.4692,
                                rank = 1,
                                parentName = "上海",
                                parentPrompt = "Shanghai"
                        ),
                        city(
                                "上海市",
                                latinName = "Shanghai",
                                latitude = 31.2222,
                                longitude = 121.4581,
                                rank = 3
                        )
                )

        assertThat(CityIndex.nearest(cities, 31.2290, 121.4760)?.name).isEqualTo("黄浦区")
    }

    @Test
    fun `past the district radius the city is named instead`() {
        // A district centre 55 km away says nothing about where the position is; the city 1 km
        // away does. This is what keeps Hong Kong from being named after a Shenzhen district.
        val cities =
                listOf(
                        city(
                                "远区",
                                latitude = 30.5,
                                longitude = 120.0,
                                rank = 1,
                                parentName = "近市"
                        ),
                        city("近市", latitude = 30.01, longitude = 120.0)
                )

        assertThat(CityIndex.nearest(cities, 30.0, 120.0)?.name).isEqualTo("近市")
    }

    @Test
    fun `with no city in range the district is still the answer`() {
        val cities =
                listOf(
                        city(
                                "远区",
                                latitude = 30.5,
                                longitude = 120.0,
                                rank = 1,
                                parentName = "远市"
                        )
                )

        assertThat(CityIndex.nearest(cities, 30.0, 120.0)?.name).isEqualTo("远区")
    }

    // --- The list that actually ships ---------------------------------------------------------

    @Test
    fun `the bundled place list parses into a usable catalog`() {
        val cities = bundledCities()

        assertThat(cities.size).isAtLeast(8_000)
        assertThat(cities.map { it.name }).contains("上海市")
        assertThat(cities.map { it.promptName }).contains("Beijing")
        assertThat(cities.all { it.name.isNotBlank() }).isTrue()
        assertThat(cities.all { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 })
                .isTrue()
    }

    @Test
    fun `the bundled list is ordered so the picker opens on notable home places`() {
        val head = CityIndex.search(bundledCities(), "")

        assertThat(head.first().country).isEqualTo("CN")
        assertThat(head.map { it.name }).contains("北京市")
        assertThat(head.all { it.rank > 1 }).isTrue()
    }

    @Test
    fun `the bundled list resolves a Chinese city name to the coordinates it was picked for`() {
        val cities = bundledCities()

        val shanghai = CityIndex.findByName(cities, "上海市")
        assertThat(shanghai?.promptName).isEqualTo("Shanghai")
        assertThat(shanghai?.label).isEqualTo("上海市")
        assertThat(shanghai?.latitude).isWithin(0.1).of(31.22)
        assertThat(shanghai?.longitude).isWithin(0.1).of(121.46)
    }

    @Test
    fun `the bundled list names a position as its district, city and all`() {
        val cities = bundledCities()

        val district = CityIndex.nearest(cities, 31.2317, 121.4692)
        assertThat(district?.name).isEqualTo("黄浦区")
        assertThat(district?.label).isEqualTo("黄浦区 · 上海")
        assertThat(district?.promptName).isEqualTo("Huangpu, Shanghai")
    }

    @Test
    fun `coordinates abroad resolve back to the city around them`() {
        assertThat(CityIndex.nearest(bundledCities(), 48.8566, 2.3522)?.name).isEqualTo("Paris")
    }

    @Test
    fun `Hong Kong, Macao and Taiwan are bundled as Chinese places`() {
        // They are inside China, so they carry no country code of their own and resolve to names
        // written in Chinese.
        val cities = bundledCities()

        assertThat(cities.none { it.country == "HK" || it.country == "MO" || it.country == "TW" })
                .isTrue()
        assertThat(cities.map { it.name }).contains("香港特别行政区")
        assertThat(CityIndex.nearest(cities, 22.6273, 120.3014)?.name).isEqualTo("高雄")
        assertThat(CityIndex.nearest(cities, 22.4445, 114.0226)?.name).isEqualTo("元朗")
    }

    private fun city(
            name: String,
            latinName: String = "",
            country: String = "CN",
            latitude: Double = 0.0,
            longitude: Double = 0.0,
            rank: Int = 2,
            parentName: String? = null,
            parentPrompt: String? = null
    ) = City(name, latinName, country, latitude, longitude, rank, parentName, parentPrompt)

    /** The shipped asset, read straight off disk: the unit test runs without an Android context. */
    private fun bundledCities(): List<City> = bundled.value

    private fun bundledFile(): File =
            listOf(File("src/main/assets/cities.json"), File("app/src/main/assets/cities.json"))
                    .firstOrNull { it.exists() }
                    ?: error("cities.json not found; cwd=${File(".").absolutePath}")

    private val bundled: Lazy<List<City>> = lazy { parseCities(bundledFile().readText()) }
}
