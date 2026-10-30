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
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * OpenWeatherMap integration — the app's original provider, preserved.
 *
 * API key resolution order (never committed to source, never logged):
 *  1. User-entered key in app settings (works for every build, including F-Droid)
 *  2. BuildConfig.OWM_APPID from local.properties (developer convenience)
 * A missing/empty key is reported as an actionable error instead of failing opaquely,
 * which is the root cause of upstream issue #6 ("dunno, seriously dunno").
 *
 * Current conditions come from /data/2.5/weather (unchanged from the original app).
 * The 7-day forecast comes from /data/2.5/forecast aggregated into local days; OWM's free
 * tier only returns 5 days, so at most 5 DailyForecast entries are produced — the UI must
 * show what exists rather than invent the rest.
 */
class OpenWeatherMapProvider(
    private val apiKeyProvider: () -> String,
    private val http: (String) -> Http.Response = { Http.get(it) }
) {

    companion object {
        private const val CURRENT_URL = "https://api.openweathermap.org/data/2.5/weather"
        private const val FORECAST_URL = "https://api.openweathermap.org/data/2.5/forecast"
    }

    fun fetch(latitude: Double, longitude: Double): WeatherResult {
        val key = requireKey() ?: return keyMissing()
        val q = "lat=${enc(String.format(Locale.US, "%.6f", latitude))}" +
                "&lon=${enc(String.format(Locale.US, "%.6f", longitude))}"
        return fetchAll(q, latitude, longitude, key, placeOverride = null)
    }

    fun fetch(location: String): WeatherResult {
        val key = requireKey() ?: return keyMissing()
        if (location.isBlank()) return WeatherResult.ApiError("No location provided.")
        val q = "q=${enc(location.trim())}"
        return fetchAll(q, latitude = Double.NaN, longitude = Double.NaN, key, placeOverride = null)
    }

    private fun requireKey(): String? = apiKeyProvider().trim().takeIf { it.isNotEmpty() }

    private fun keyMissing() = WeatherResult.ApiError(
        "OpenWeatherMap App ID is not set. Use Open-Meteo (no key needed) or add your own " +
                "key in Options → Weather Source."
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetchAll(q: String, latitude: Double, longitude: Double, key: String,
                         placeOverride: String?): WeatherResult {
        // 1) current conditions
        val currentUrl = "$CURRENT_URL?$q&APPID=${enc(key)}&units=metric"
        val body = try {
            http(currentUrl).body
        } catch (e: Http.HttpStatusException) {
            return owmHttpError(e)
        } catch (e: Exception) {
            return WeatherResult.Failure(e.message ?: "Network error", e)
        }

        val data = when (val parsed = parseCurrent(body, latitude, longitude, placeOverride ?: "")) {
            is WeatherResult -> return parsed
            is WeatherData -> parsed
            else -> return WeatherResult.Failure("Malformed OpenWeatherMap response")
        }

        // 2) forecast (best effort — a forecast failure must not discard good current data)
        val daily = try {
            val fUrl = "$FORECAST_URL?$q&APPID=${enc(key)}&units=metric"
            val fBody = http(fUrl).body
            parseForecast(JSONObject(fBody), data.timezone ?: "UTC")
        } catch (e: Exception) {
            emptyList()
        }

        return WeatherResult.Success(data.copy(daily = daily, time = System.currentTimeMillis()))
    }

    /** Returns either parsed data or an error result. */
    private fun parseCurrent(body: String, latitude: Double, longitude: Double,
                             placeOverride: String): Any {
        val json = JSONObject(body)
        val cod = json.optInt("cod", 200)
        if (cod != 200) {
            return WeatherResult.ApiError(
                "$cod: ${json.optString("message", "unknown error")}", cod
            )
        }

        val weather = json.getJSONArray("weather").getJSONObject(0)
        val main = json.getJSONObject("main")

        val tempC = main.getDouble("temp") // units=metric
        val condition = prettyCondition(weather.getString("description"))
        val icon = weather.getString("icon")

        val place = placeOverride
            ?: json.optString("name", "")
        val timezoneOffset = json.optLong("timezone", 0L)
        val zone = ZoneId.ofOffset("UTC", java.time.ZoneOffset.ofTotalSeconds(timezoneOffset.toInt()))
        val link = "https://openweathermap.org/city/" + json.optInt("id", 0)
        val lat = if (latitude.isNaN()) json.optDouble("lat", 0.0) else latitude
        val lon = if (longitude.isNaN()) json.optDouble("lon", 0.0) else longitude

        return WeatherData(
            temperature = tempC,
            condition = condition,
            image = icon,
            latitude = lat,
            longitude = lon,
            place = place,
            time = System.currentTimeMillis(),
            source = WeatherSource.OPEN_WEATHER_MAP,
            link = if (link.endsWith("/0")) null else link,
            timezone = zone.id,
            daily = emptyList() // filled by parseForecast
        )
    }

    /**
     * Turn OWM's 3-hour steps into whole local days (high/low/dominant condition/
     * max precipitation). Days are computed in the location's timezone so day boundaries
     * and DST follow the user's local time, not UTC.
     *
     * @param todayEpochDay today in the location's timezone (or 0 to derive from now)
     */
    internal fun parseForecast(json: JSONObject, timezoneId: String?, todayEpochDay: Long = 0): List<DailyForecast> {
        val list = json.optJSONArray("list") ?: return emptyList()
        val zone = try {
            ZoneId.of(if (timezoneId.isNullOrBlank()) "UTC" else timezoneId)
        } catch (e: Exception) {
            ZoneId.of("UTC")
        }
        val today = if (todayEpochDay != 0L) todayEpochDay else
            Instant.now().atZone(zone).toLocalDate().toEpochDay()

        // group steps by local day
        data class Bucket(var high: Double?, var low: Double?, val codes: HashMap<Int, Int>,
                           var maxPrecip: Int?, val icons: HashMap<String, Int>)
        val buckets = LinkedHashMap<Long, Bucket>()

        for (i in 0 until list.length()) {
            val step = list.optJSONObject(i) ?: continue
            val dt = step.optLong("dt", 0)
            if (dt <= 0) continue
            val localDate = Instant.ofEpochSecond(dt).atZone(zone).toLocalDate()
            val epochDay = localDate.toEpochDay()
            if (epochDay < today) continue
            val bucket = buckets.getOrPut(epochDay) {
                Bucket(null, null, HashMap(), null, HashMap())
            }
            val main = step.optJSONObject("main")
            if (main != null) {
                val hi = main.optDouble("temp_max", Double.NaN)
                val lo = main.optDouble("temp_min", Double.NaN)
                if (!hi.isNaN()) bucket.high = maxOf(bucket.high ?: hi, hi)
                if (!lo.isNaN()) bucket.low = minOf(bucket.low ?: lo, lo)
            }
            val wx = step.optJSONArray("weather")
            if (wx != null && wx.length() > 0) {
                val w0 = wx.optJSONObject(0)
                val icon = w0?.optString("icon") ?: ""
                if (icon.isNotEmpty()) {
                    bucket.icons[icon] = (bucket.icons[icon] ?: 0) + 1
                }
            }
            val pop = step.optDouble("pop", -1.0)
            if (pop >= 0) {
                val pct = Math.round(pop * 100).toInt()
                bucket.maxPrecip = maxOf(bucket.maxPrecip ?: pct, pct)
            }
        }

        return buckets.entries.take(7).map { (epochDay, b) ->
            // Dominant icon of the day (most frequent 3h condition).
            val icon = b.icons.entries.maxByOrNull { it.value }?.key ?: "01d"
            val codeCount = b.codes
            DailyForecast(
                epochDay = epochDay,
                highC = b.high,
                lowC = b.low,
                image = icon,
                condition = prettyCondition(conditionFromIcon(icon)),
                precipProbability = b.maxPrecip
            )
        }
    }

    private fun owmHttpError(e: Http.HttpStatusException): WeatherResult = when (e.code) {
        401 -> WeatherResult.ApiError("OpenWeatherMap rejected the API key (401).", 401)
        429 -> WeatherResult.ApiError("OpenWeatherMap rate limit reached (429).", 429)
        404 -> WeatherResult.ApiError("Location not found (404).", 404)
        else -> WeatherResult.ApiError("OpenWeatherMap error (${e.code}).", e.code)
    }

    private fun prettyCondition(raw: String): String {
        val t = raw.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        // "Sky Is Clear" -> "Sky is Clear"
        return t.replace(Regex("(?<=[^\\w])Is(?=[^\\w])"), "is")
    }

    /** Rough reverse mapping used only for forecast days, where OWM sends icons not text. */
    private fun conditionFromIcon(icon: String): String = when (icon.substringBefore('d').substringBefore('n')) {
        "01" -> "Sky Is Clear"
        "02" -> "Few Clouds"
        "03" -> "Scattered Clouds"
        "04" -> "Overcast Clouds"
        "09" -> "Shower Rain"
        "10" -> "Rain"
        "11" -> "Thunderstorm"
        "13" -> "Snow"
        "50" -> "Mist"
        else -> "Unknown"
    }
}
