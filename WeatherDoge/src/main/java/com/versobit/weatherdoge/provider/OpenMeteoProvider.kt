/*
 * Copyright (C) 2026 Weather Doge contributors
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

package com.versobit.weatherdoge.provider

import com.versobit.weatherdoge.data.DailyForecast
import com.versobit.weatherdoge.data.WeatherData
import com.versobit.weatherdoge.data.WeatherResult
import com.versobit.weatherdoge.data.WeatherSource
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Open-Meteo integration — https://open-meteo.com/en/docs
 *
 * Keyless, free for non-commercial use, and it returns the local timezone plus a real
 * 7-day daily forecast. Attribution: "Weather data by Open-Meteo.com".
 *
 * Requests only what the app displays:
 *  - current: temperature_2m, weather_code, is_day, relative_humidity_2m (unused in UI,
 *    omitted), precipitation → only temperature/weather_code/is_day are requested
 *  - daily: weather_code, temperature_2m_max, temperature_2m_min, precipitation_probability_max
 *
 * WMO weather codes are mapped onto OpenWeatherMap-style icon IDs because the entire
 * existing UI (doge art, sky art, adjective arrays) is keyed on them.
 */
class OpenMeteoProvider(private val http: (String) -> Http.Response = { Http.get(it) }) {

    companion object {
        const val ATTRIBUTION = "Weather data by Open-Meteo.com"
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
    }

    fun fetch(latitude: Double, longitude: Double): WeatherResult {
        val url = StringBuilder(FORECAST_URL)
            .append("?latitude=").append(String.format(java.util.Locale.US, "%.5f", latitude))
            .append("&longitude=").append(String.format(java.util.Locale.US, "%.5f", longitude))
            // Exactly the fields the app uses. Nothing speculative.
            .append("&current=temperature_2m,weather_code,is_day")
            .append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .append("&forecast_days=7")
            .append("&timezone=auto")
            .append("&wind_speed_unit=mph")
            .toString()
        return fetchAndParse(url, latitude, longitude, placeOverride = null)
    }

    /** Manual location: resolve through Open-Meteo's geocoding API, then fetch. */
    fun fetch(location: String): WeatherResult {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) {
            return WeatherResult.ApiError("No location provided.")
        }
        val geoUrl = "$GEOCODING_URL?name=${java.net.URLEncoder.encode(trimmed, "UTF-8")}&count=1&language=en&format=json"
        val geoBody = try {
            http(geoUrl).body
        } catch (e: Http.HttpStatusException) {
            return httpError(e)
        } catch (e: Exception) {
            return WeatherResult.Failure(e.message ?: "Network error", e)
        }
        try {
            val results = JSONObject(geoBody)
            val arr = results.optJSONArray("results")
            if (arr == null || arr.length() == 0) {
                return WeatherResult.ApiError("No results found for \"$trimmed\".")
            }
            val first = arr.getJSONObject(0)
            val lat = first.getDouble("latitude")
            val lon = first.getDouble("longitude")
            val name = first.optString("name", trimmed)
            return fetch(lat, lon).let {
                // Preserve the user's typed name when the geocoder would show something else.
                if (it is WeatherResult.Success) {
                    it.copy(data = it.data.copy(place = name))
                } else it
            }
        } catch (e: Exception) {
            return WeatherResult.Failure("Malformed geocoding response", e)
        }
    }

    private fun fetchAndParse(url: String, lat: Double, lon: Double, placeOverride: String?): WeatherResult {
        val body = try {
            http(url).body
        } catch (e: Http.HttpStatusException) {
            return httpError(e)
        } catch (e: Exception) {
            return WeatherResult.Failure(e.message ?: "Network error", e)
        }

        return try {
            val json = JSONObject(body)
            val current = json.getJSONObject("current")
            val temp = current.getDouble("temperature_2m")
            val code = current.getInt("weather_code")
            val isDay = current.optInt("is_day", 1) == 1
            val timezoneId = json.optString("timezone", "")

            // The API returns "local time" — derive the local epoch day *in the location's
            // timezone* so day boundaries and DST are handled by the provider, not by us.
            val localDate = current.getString("time").substring(0, 10)
            val todayEpochDay = try {
                java.time.LocalDate.parse(localDate).toEpochDay()
            } catch (e: Exception) {
                Instant.now().atZone(ZoneId.of(timezoneId.ifEmpty { "UTC" })).toLocalDate().toEpochDay()
            }

            val daily = json.optJSONObject("daily")
            val dailyList = parseDaily(daily, todayEpochDay)

            WeatherResult.Success(
                WeatherData(
                    temperature = temp,
                    condition = describeCode(code, isDay),
                    image = iconForCode(code, isDay),
                    latitude = lat,
                    longitude = lon,
                    // Reverse-geocoded place name is resolved by the repository via Android's
                    // Geocoder; Open-Meteo does not need to be asked again for it.
                    place = placeOverride ?: "",
                    time = System.currentTimeMillis(),
                    source = WeatherSource.OPEN_METEO,
                    link = "https://open-meteo.com/",
                    timezone = timezoneId.ifEmpty { null },
                    daily = dailyList
                )
            )
        } catch (e: Exception) {
            WeatherResult.Failure("Malformed weather response", e)
        }
    }

    /**
     * Map the daily block into [DailyForecast] entries starting today. Missing provider
     * fields stay null — the UI must not invent them.
     */
    internal fun parseDaily(daily: JSONObject?, todayEpochDay: Long): List<DailyForecast> {
        if (daily == null) return emptyList()
        val dates = daily.optJSONArray("time") ?: return emptyList()
        val highs = daily.optJSONArray("temperature_2m_max")
        val lows = daily.optJSONArray("temperature_2m_min")
        val codes = daily.optJSONArray("weather_code")
        val precip = daily.optJSONArray("precipitation_probability_max")
        val out = ArrayList<DailyForecast>(dates.length())
        for (i in 0 until dates.length()) {
            val epochDay = try {
                java.time.LocalDate.parse(dates.getString(i)).toEpochDay()
            } catch (e: Exception) {
                todayEpochDay + i
            }
            // Skip days before today (defensive; Open-Meteo starts at today).
            if (epochDay < todayEpochDay) continue
            val code = codes?.optInt(i, 0) ?: 0
            val hasDayNight = true
            out.add(
                DailyForecast(
                    epochDay = epochDay,
                    highC = highs?.takeIf { !it.isNull(i) }?.getDouble(i),
                    lowC = lows?.takeIf { !it.isNull(i) }?.getDouble(i),
                    image = iconForCode(code, hasDayNight),
                    condition = describeCode(code, hasDayNight),
                    precipProbability = precip?.takeIf { !it.isNull(i) }?.getInt(i)
                )
            )
        }
        return out.take(7)
    }

    private fun httpError(e: Http.HttpStatusException): WeatherResult = when {
        e.code == 429 -> WeatherResult.ApiError("Rate limited by Open-Meteo. Try again shortly.", e.code)
        e.code >= 500 -> WeatherResult.ApiError("Open-Meteo server error (${e.code}).", e.code)
        e.code == 400 -> WeatherResult.ApiError("Open-Meteo rejected the request (${e.code}).", e.code)
        else -> WeatherResult.ApiError("Open-Meteo error (${e.code}).", e.code)
    }

    // ---- WMO code → OWM-style icon + English description ---------------------

    internal fun iconForCode(code: Int, isDay: Boolean): String {
        val base = when (code) {
            0 -> "01"                       // clear sky
            1 -> "02"                       // mainly clear
            2 -> "03"                       // partly cloudy
            3 -> "04"                       // overcast
            45, 48 -> "50"                  // fog, rime fog
            51, 53, 55 -> "09"              // drizzle
            56, 57 -> "09"                  // freezing drizzle
            61, 63, 65 -> "10"              // rain
            66, 67 -> "10"                  // freezing rain
            71, 73, 75, 77 -> "13"          // snow
            80, 81, 82 -> "09"              // rain showers
            85, 86 -> "13"                  // snow showers
            95 -> "11"                      // thunderstorm
            96, 99 -> "11"                  // thunderstorm w/ hail
            else -> "01"
        }
        val suffix = if (isDay) "d" else "n"
        return base + suffix
    }

    internal fun describeCode(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "Sky Is Clear" else "Clear Night"
        1 -> "Mainly Clear"
        2 -> "Partly Cloudy"
        3 -> "Overcast"
        45 -> "Fog"
        48 -> "Depositing Rime Fog"
        51 -> "Light Drizzle"
        53 -> "Moderate Drizzle"
        55 -> "Dense Drizzle"
        56 -> "Light Freezing Drizzle"
        57 -> "Dense Freezing Drizzle"
        61 -> "Slight Rain"
        63 -> "Moderate Rain"
        65 -> "Heavy Rain"
        66 -> "Light Freezing Rain"
        67 -> "Heavy Freezing Rain"
        71 -> "Slight Snowfall"
        73 -> "Moderate Snowfall"
        75 -> "Heavy Snowfall"
        77 -> "Snow Grains"
        80 -> "Slight Rain Showers"
        81 -> "Moderate Rain Showers"
        82 -> "Violent Rain Showers"
        85 -> "Slight Snow Showers"
        86 -> "Heavy Snow Showers"
        95 -> "Thunderstorm"
        96 -> "Thunderstorm With Slight Hail"
        99 -> "Thunderstorm With Heavy Hail"
        else -> "Unknown"
    }
}
