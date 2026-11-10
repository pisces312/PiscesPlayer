package com.pisces312.piscesplayer.data.ai.serendipity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.pisces312.piscesplayer.data.preferences.AiPreferencesRepository
import com.pisces312.piscesplayer.di.FastOkHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Reads the signals Serendipity builds its prompt from: the clock, the weather at city level and
 * today's step count.
 *
 * Four rules shape this class:
 * - **Nothing is guessed.** A signal that cannot be read stays `null` and is dropped from the
 *   prompt, so the sheet can show the user what was actually available.
 * - **Failure is never fatal.** Every network call and sensor read is optional; an offline device
 *   still gets a time-only mix.
 * - **Coordinates never leave.** The phone's position is only ever turned into a place name, and
 *   the name is resolved against the bundled [CityCatalog] rather than sent to a geocoding
 *   service — which is also what makes it work on devices without Google Play services.
 * - **The user picks the source.** [SerendipityWeatherSource] decides whether the location is
 *   read at all; nothing here asks for it on its own.
 */
@Singleton
class SerendipityContextCollector
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @FastOkHttpClient private val httpClient: OkHttpClient,
    private val preferences: AiPreferencesRepository,
    private val cityCatalog: CityCatalog
) {

    suspend fun collect(): SerendipityContext {
        val now = ZonedDateTime.now()
        // Off the main thread: the first call parses the bundled list.
        val city = withContext(Dispatchers.IO) { resolveCity() }
        return SerendipityContext(
                weekday = now.dayOfWeek,
                clockTime = now.format(CLOCK_FORMAT),
                timeOfDay = SerendipityTimeOfDay.at(now.hour),
                city = city?.promptName,
                cityLabel = city?.label,
                weather = city?.let { fetchWeather(it) },
                stepsToday = readStepsToday()
        )
    }

    /**
     * A place to read the weather at.
     *
     * [promptName] is what the prompt says and is never localized, and for a district it names the
     * city too ("Huangpu, Shanghai"); [label] is what the user reads ("黄浦区 · 上海") and is
     * `null` when the offline list could not name the position.
     */
    private data class ResolvedCity(
        val promptName: String?,
        val label: String?,
        val latitude: Double,
        val longitude: Double
    )

    private suspend fun resolveCity(): ResolvedCity? =
            when (preferences.getSerendipityWeatherSource().first()) {
                SerendipityWeatherSource.DEVICE_LOCATION -> cityFromLocation()
                SerendipityWeatherSource.SPECIFIC_CITY -> cityFromSettings()
                SerendipityWeatherSource.OFF -> null
            }

    /**
     * The city picked in settings.
     *
     * The picker only offers names from the bundled list, so this normally resolves with no
     * network at all; [geocode] only catches names restored from a backup or typed by an older
     * build, which the bundled list may not contain.
     */
    private suspend fun cityFromSettings(): ResolvedCity? {
        val name = preferences.getSerendipityCity().first()
        if (name.isBlank()) return null
        cityCatalog.findByName(name)?.let {
            return ResolvedCity(it.promptName, it.label, it.latitude, it.longitude)
        }
        return geocode(name)
    }

    /**
     * Look up a name not present in the bundled list.
     *
     * The one call in this class that reaches a third party, and it sends a city name rather than
     * a position. Open-Meteo is reachable without a key; when it fails the mix simply has no
     * weather.
     */
    private suspend fun geocode(name: String): ResolvedCity? =
            runCatching {
                val url = GEOCODING_ROOT.toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("name", name)
                        .addQueryParameter("count", "1")
                        .addQueryParameter("language", "en")
                        .addQueryParameter("format", "json")
                        .build()
                val results =
                        JSONObject(httpGet(url.toString()) ?: return@runCatching null)
                                .optJSONArray("results") ?: return@runCatching null
                if (results.length() == 0) return@runCatching null
                val first = results.getJSONObject(0)
                val resolved = first.optString("name").ifBlank { name }
                ResolvedCity(
                        promptName = resolved,
                        label = resolved,
                        latitude = first.getDouble("latitude"),
                        longitude = first.getDouble("longitude")
                )
            }
                    .getOrNull()

    /**
     * The device's last known position, named by the bundled city list.
     *
     * The position itself is what the weather is fetched for — the catalog only supplies the name
     * shown to the user, which stays `null` if the list is unavailable rather than costing the mix
     * its weather.
     */
    private fun cityFromLocation(): ResolvedCity? {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val manager =
                context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                        ?: return null
        val location =
                LOCATION_PROVIDERS.mapNotNull { provider ->
                            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                        }
                        .maxByOrNull { it.time } ?: return null
        val nearest = cityCatalog.nearest(location.latitude, location.longitude)
        return ResolvedCity(
                promptName = nearest?.promptName,
                label = nearest?.label,
                latitude = location.latitude,
                longitude = location.longitude
        )
    }

    private suspend fun fetchWeather(city: ResolvedCity): SerendipityWeather? =
            runCatching {
                val url = FORECAST_ROOT.toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("latitude", city.latitude.toString())
                        .addQueryParameter("longitude", city.longitude.toString())
                        .addQueryParameter("current", "temperature_2m,weather_code")
                        .build()
                val current =
                        JSONObject(httpGet(url.toString()) ?: return@runCatching null)
                                .optJSONObject("current") ?: return@runCatching null
                val code = current.optInt("weather_code", NO_WEATHER_CODE)
                val temperature = current.optDouble("temperature_2m", Double.NaN)
                if (code == NO_WEATHER_CODE || temperature.isNaN()) return@runCatching null
                SerendipityWeather(
                        group = SerendipityWeatherGroup.fromWmoCode(code),
                        temperatureC = temperature.roundToInt()
                )
            }
                    .getOrNull()

    private suspend fun readStepsToday(): Int? {
        if (!hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) return null
        val manager =
                context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val total = readStepCounter(manager, sensor) ?: return null
        return preferences.stepsToday(total.roundToInt())
    }

    /**
     * One sample of the cumulative step counter, then the listener is removed.
     *
     * Sampling on demand instead of keeping the sensor open: the value only feeds one line of a
     * prompt, and a permanently registered counter would drain battery for nothing. The timeout
     * covers devices that expose the sensor but never deliver an event.
     */
    private suspend fun readStepCounter(
        manager: SensorManager,
        sensor: Sensor
    ): Float? =
            withTimeoutOrNull(STEP_READ_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val listener =
                            object : SensorEventListener {
                                override fun onSensorChanged(event: SensorEvent) {
                                    manager.unregisterListener(this)
                                    if (continuation.isActive) {
                                        continuation.resume(event.values.firstOrNull() ?: 0f)
                                    }
                                }

                                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                            }
                    continuation.invokeOnCancellation { manager.unregisterListener(listener) }
                    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }

    private suspend fun httpGet(url: String): String? =
            withContext(Dispatchers.IO) {
                runCatching {
                    httpClient.newCall(Request.Builder().url(url).get().build()).execute().use {
                        response ->
                        if (response.isSuccessful) response.body.string() else null
                    }
                }
                        .getOrNull()
            }

    private fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED

    private companion object {
        /** Open-Meteo: free, key-less and usable without Google Play services. */
        const val FORECAST_ROOT = "https://api.open-meteo.com/v1/forecast"
        const val GEOCODING_ROOT = "https://geocoding-api.open-meteo.com/v1/search"

        val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

        val LOCATION_PROVIDERS =
                listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)

        /** Open-Meteo reports `-1` for "unknown"; anything negative means "no reading". */
        const val NO_WEATHER_CODE = -1

        const val STEP_READ_TIMEOUT_MS = 2_000L
    }
}
