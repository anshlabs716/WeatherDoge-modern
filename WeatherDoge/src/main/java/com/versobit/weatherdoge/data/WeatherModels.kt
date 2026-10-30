/*
 * Copyright (C) 2014-2026 VersoBit and contributors
 *
 * This file is part of Weather Doge.
 *
 * Weather Doge is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Weather Doge is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Weather Doge.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.versobit.weatherdoge.data

import java.io.Serializable

/** The weather providers the app supports. Keys are persisted in settings — do not renumber. */
enum class WeatherSource(val key: String, val displayName: String) {
    OPEN_METEO("3", "Open-Meteo"),
    OPEN_WEATHER_MAP("0", "OpenWeatherMap");

    companion object {
        fun fromKey(key: String?): WeatherSource =
            entries.firstOrNull { it.key == key } ?: OPEN_METEO
    }
}

/**
 * A single day's forecast. Only values actually supplied by the provider are non-null;
 * the UI must not invent data that is missing here.
 */
data class DailyForecast(
    /** Epoch day number (days since 1970-01-01) in the *location's* timezone. */
    val epochDay: Long,
    /** Local day-of-week name is derived at render time using the location timezone. */
    val highC: Double?,
    val lowC: Double?,
    /** OpenWeatherMap-style icon ID ("01d".."50n"). */
    val image: String,
    val condition: String,
    /** 0..100, or null when the provider does not supply precipitation probability. */
    val precipProbability: Int?
) : Serializable

/**
 * The app-wide weather model. Both providers map into this. Always Celsius internally.
 */
data class WeatherData(
    val temperature: Double,
    val condition: String,
    /** OpenWeatherMap-style icon ID ("01d".."50n"). Drives doge/sky selection. */
    val image: String,
    val latitude: Double,
    val longitude: Double,
    val place: String,
    /** The system time the data was retrieved. */
    val time: Long,
    val source: WeatherSource,
    val link: String?,
    /** IANA timezone of the location, e.g. "America/New_York". Null when unknown. */
    val timezone: String?,
    val daily: List<DailyForecast>
) : Serializable {
    companion object {
        private const val serialVersionUID = 2253249035716676067L
    }
}

/** Result of a provider request. */
sealed class WeatherResult {
    data class Success(val data: WeatherData) : WeatherResult()
    /** Provider or network reported a user-actionable problem (bad key, rate limit, no results). */
    data class ApiError(val message: String, val httpCode: Int = 0) : WeatherResult()
    /** Unexpected failure (DNS, malformed JSON, IO). */
    data class Failure(val message: String, val cause: Throwable? = null) : WeatherResult()
}
