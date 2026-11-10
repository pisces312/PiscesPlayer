package com.pisces312.piscesplayer.data.ai.serendipity

/**
 * Where Serendipity's weather signal comes from.
 *
 * The choice exists because the two ways of getting weather differ in what they cost the user:
 * the device route needs a location permission and hands the phone's coordinates to a weather
 * service, while the city route only ever sends coordinates the user picked from a public list.
 */
enum class SerendipityWeatherSource {

    /**
     * Coarse location from the device.
     *
     * Covers travelling — the mix follows wherever the phone is — at the price of a location
     * permission. The city name is resolved on the device, without a geocoding service.
     */
    DEVICE_LOCATION,

    /**
     * A city picked in settings.
     *
     * Needs no location permission at all, and the coordinates sent are the city's own public
     * ones rather than the device's.
     */
    SPECIFIC_CITY,

    /** No weather: the mix is built from the clock alone. */
    OFF;

    companion object {
        /** Picking a city costs no permission, so that is where the user starts. */
        val DEFAULT = SPECIFIC_CITY

        fun fromName(name: String?): SerendipityWeatherSource =
                entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
