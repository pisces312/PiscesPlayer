package com.pisces312.piscesplayer.data.ai.serendipity

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import org.json.JSONObject

/** The country whose places are all bundled, and whose names are shown in Chinese. */
private const val HOME_COUNTRY = "CN"

/** Combining marks left over after NFD decomposition; stripped so "munchen" finds "München". */
private val DIACRITICS = Regex("\\p{Mn}+")

/**
 * One entry of the bundled place list.
 *
 * Two names are kept because the two audiences differ: [label] is read by the user in the picker
 * and in the sheet's chips, while [promptName] is the one spliced into the (English) prompt.
 */
data class City(
    /** Name shown to the user: the official name inside China, the local name abroad. */
    val name: String,
    /** Latin spelling of [name], empty when the two are identical. */
    val latinName: String,
    /** ISO 3166-1 alpha-2 code, `CN` for Hong Kong, Macao and Taiwan as well. */
    val country: String,
    val latitude: Double,
    val longitude: Double,
    /** 1 = the finest unit bundled (a Chinese district), 2 = a city, 3 = a province or capital. */
    val rank: Int,
    /** The city a district belongs to; `null` for anything that is not a district. */
    val parentName: String? = null,
    /** [parentName] in Latin script, so the prompt can qualify the district with its city. */
    val parentPrompt: String? = null
) {
    /**
     * Name handed to the model, which is fed an English prompt end to end.
     *
     * A district is qualified by its city — "Huangpu, Shanghai" — because on its own it says
     * little: there are five districts called 东区 in China, and the model has no way to tell
     * which one the listener is in.
     */
    val promptName: String
        get() =
                listOfNotNull(latinName.ifBlank { name }, parentPrompt).joinToString(", ")

    /** Entry shown in the picker and in the sheet's chips. */
    val label: String
        get() =
                when {
                    parentName != null -> "$name · $parentName"
                    country == HOME_COUNTRY -> name
                    // The country only disambiguates cities abroad.
                    else -> "$name, $country"
                }

    /** Folded haystack for [CityIndex.search], built once per place. */
    val searchKeys: List<String> =
            listOfNotNull(name, promptName, label)
                    .map(::foldForSearch)
                    .filter { it.isNotEmpty() }
                    .distinct()
}

/**
 * Search and nearest-neighbour over a list of places.
 *
 * Split out of [CityCatalog] so the logic is plain Kotlin: no assets, no Android types, and
 * therefore directly unit-testable.
 */
object CityIndex {

    /** How many rows the picker offers at once. */
    const val SEARCH_LIMIT = 60

    /** The rank of the finest unit bundled, which is as precise as a name ever gets. */
    private const val RANK_FINEST = 1

    /**
     * How far the nearest district may be and still count as "where you are": about 25 km,
     * expressed in degrees (one degree of latitude is roughly 111 km).
     *
     * Beyond that a district centre stops standing in for the district the position is in — the
     * counties of western China are larger than that on their own. It cannot be much looser
     * either: the districts of Shenzhen reach to within 9 km of Yuen Long, which is in Hong Kong.
     */
    private const val DETAIL_RADIUS_DEGREES = 0.23

    private const val DETAIL_RADIUS_SQUARED = DETAIL_RADIUS_DEGREES * DETAIL_RADIUS_DEGREES

    /**
     * How far away a city can be and still count as "where you are": about 30 km, in degrees.
     */
    private const val NOTABLE_RADIUS_DEGREES = 0.27

    private const val NOTABLE_RADIUS_SQUARED = NOTABLE_RADIUS_DEGREES * NOTABLE_RADIUS_DEGREES

    /**
     * Places matching [query], best first.
     *
     * An empty query is the picker's opening state and returns the head of the list. The bundled
     * data is sorted so that head is made of provinces and cities, home country first. Prefix
     * matches outrank matches found deeper inside a name.
     */
    fun search(cities: List<City>, query: String, limit: Int = SEARCH_LIMIT): List<City> {
        val needle = foldForSearch(query)
        if (needle.isEmpty()) return cities.take(limit)
        val prefixMatches = ArrayList<City>(limit)
        val looseMatches = ArrayList<City>(limit)
        for (city in cities) {
            when {
                city.searchKeys.any { it.startsWith(needle) } -> prefixMatches += city
                city.searchKeys.any { it.contains(needle) } -> looseMatches += city
            }
            if (prefixMatches.size >= limit && looseMatches.size >= limit) break
        }
        return (prefixMatches + looseMatches).take(limit)
    }

    /** Exact name first, then whatever a search would surface. Used to resolve a stored choice. */
    fun findByName(cities: List<City>, name: String): City? {
        val needle = foldForSearch(name)
        if (needle.isEmpty()) return null
        return cities.firstOrNull { city -> city.searchKeys.any { it == needle } }
                ?: search(cities, name, 1).firstOrNull()
    }

    /**
     * The place closest to a position — the offline "coordinates to place name" lookup.
     *
     * The nearest district wins whenever one is within [DETAIL_RADIUS_DEGREES], because inside
     * China that is the unit people name: it is what turns a position in Shanghai into 黄浦区
     * rather than 上海市. Farther out there is no district to trust, so the most notable city
     * within [NOTABLE_RADIUS_DEGREES] is used and distance only decides between equals — which is
     * what keeps the answer abroad a name people recognize instead of the closest commune.
     *
     * Known limit: this compares against district *centres*, not their outlines. A position inside
     * a large or oddly shaped district can sit nearer the centre of its neighbour — Lujiazui, in
     * 浦东新区, reads as 黄浦区 because Pudong's centre is in Huamu. Deciding properly needs the
     * boundary polygons, which are 159 MB and have no place in an APK. The user can always pick
     * the district by hand in settings.
     *
     * Candidates are ranked by squared distance on an equirectangular projection: the longitude
     * difference is scaled by the cosine of the latitude, which orders neighbours correctly and
     * skips a square root per candidate.
     */
    fun nearest(cities: List<City>, latitude: Double, longitude: Double): City? {
        val finest =
                cities.filter { it.rank == RANK_FINEST }
                        .minByOrNull { squaredDistance(it, latitude, longitude) }
        if (finest != null &&
                squaredDistance(finest, latitude, longitude) <= DETAIL_RADIUS_SQUARED) {
            return finest
        }
        val nearby =
                cities.filter {
                    it.rank > RANK_FINEST &&
                            squaredDistance(it, latitude, longitude) <= NOTABLE_RADIUS_SQUARED
                }
        return nearby.minWithOrNull(
                        compareByDescending<City> { it.rank }
                                .thenBy { squaredDistance(it, latitude, longitude) }
                )
                ?: finest
                ?: cities.minByOrNull { squaredDistance(it, latitude, longitude) }
    }

    private fun squaredDistance(city: City, latitude: Double, longitude: Double): Double {
        val dLat = city.latitude - latitude
        val dLon = (city.longitude - longitude) * cos(Math.toRadians(latitude))
        return dLat * dLat + dLon * dLon
    }
}

/** Lower case, no accents, no spaces: "New York" and "newyork" fold to the same needle. */
private fun foldForSearch(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().filterNot {
            it.isWhitespace()
        }

/**
 * The bundled place list: the picker's data, and the source of the offline reverse lookup.
 *
 * Both users of it — searching for a place in settings, and naming the coordinates the device
 * reports — work entirely on the device. Nothing here touches the network, which is what lets
 * Serendipity avoid a geocoding service (and hand out no coordinates) on devices without one.
 */
@Singleton
class CityCatalog
@Inject
constructor(
    @ApplicationContext private val context: Context
) {

    /** Parsed on first use and shared afterwards; the picker and the collector both hit this. */
    private val cities: List<City> by lazy { load() }

    fun search(query: String, limit: Int = CityIndex.SEARCH_LIMIT): List<City> =
            CityIndex.search(cities, query, limit)

    fun nearest(latitude: Double, longitude: Double): City? =
            CityIndex.nearest(cities, latitude, longitude)

    /** Resolves a name or a [City.label] the user picked earlier. */
    fun findByName(name: String): City? = CityIndex.findByName(cities, name)

    /**
     * Reads the asset.
     *
     * A missing or malformed file yields an empty catalog instead of an exception: Serendipity
     * already has to cope with "no place", so degrading there is cheaper than failing the sheet.
     */
    private fun load(): List<City> =
            runCatching {
                        context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                            parseCities(reader.readText())
                        }
                    }
                    .getOrDefault(emptyList())

    private companion object {
        /** Generated by `gen_city_catalog.py`; see THIRD_PARTY_NOTICES.md for the data licences. */
        const val ASSET_NAME = "cities.json"
    }
}

/**
 * Reads the parallel-array layout written by `gen_city_catalog.py`.
 *
 * Kept out of [CityCatalog] so the real bundled file can be parsed and searched in a plain JVM
 * unit test, without an Android context or the assets system.
 */
internal fun parseCities(json: String): List<City> {
    val root = JSONObject(json)
    val names = root.getJSONArray("name")
    val latinNames = root.getJSONArray("ascii")
    val countries = root.getJSONArray("country")
    val latitudes = root.getJSONArray("lat")
    val longitudes = root.getJSONArray("lon")
    val ranks = root.getJSONArray("rank")
    val parents = root.getJSONArray("parent")
    val parentNames = root.getJSONArray("parentName")
    return List(names.length()) { index ->
        val parent = parents.getInt(index)
        City(
                name = names.getString(index),
                latinName = latinNames.getString(index),
                country = countries.getString(index),
                latitude = latitudes.getDouble(index),
                longitude = longitudes.getDouble(index),
                rank = ranks.getInt(index),
                parentName = parentNames.getString(index).ifBlank { null },
                parentPrompt =
                        if (parent < 0) null
                        else latinNames.getString(parent).ifBlank { names.getString(parent) }
        )
    }
}
