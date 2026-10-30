/*
 * Copyright (C) 2026 Weather Doge contributors
 *
 * Weather Doge is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Weather Doge is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package com.versobit.weatherdoge.data

import android.content.Context
import android.content.SharedPreferences
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import com.versobit.weatherdoge.OptionsActivity
import com.versobit.weatherdoge.provider.Http
import com.versobit.weatherdoge.provider.OpenMeteoProvider
import com.versobit.weatherdoge.provider.OpenWeatherMapProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Single source of truth for weather data, shared by the app and the widget.
 *
 * Why this exists (upstream #6 / #9):
 *  - The old cache lived in getCacheDir() which the system may purge at any time, and the
 *    widget and app each talked to the network independently. Now both sides read/write
 *    ONE JSON file in filesDir(), which is never auto-purged, and the widget renders
 *    whatever was last successfully fetched — even when the app is closed or offline.
 *  - A failed refresh NEVER deletes previously good data.
 *  - Callers are notified on the main thread whenever new data is persisted so widgets
 *    update the moment fresh data exists.
 */
object WeatherRepository {

    private const val TAG = "WeatherRepository"
    private const val CACHE_FILE = "weather_data.json"
    private const val CACHE_MAX_AGE_MS = 10 * 60 * 1000L
    private const val COORD_FUZZ = 2 // decimal places ≈ 1.1 km

    // persisted location fix for widget/background use (no background permission needed)
    const val PREF_LAST_LAT = "internal_last_lat"
    const val PREF_LAST_LON = "internal_last_lon"
    const val PREF_OW_API_KEY = "pref_ow_api_key"
    const val PREF_WEATHER_SOURCE = "pref_weather_source"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "weather-repo").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var memory: WeatherData? = null
    private val listeners = ArrayList<(WeatherData) -> Unit>()

    // ---- providers -----------------------------------------------------------

    private fun openMeteo() = OpenMeteoProvider()

    private fun openWeatherMap(context: Context): OpenWeatherMapProvider {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val userKey = prefs.getString(PREF_OW_API_KEY, "") ?: ""
        val key = if (userKey.isNotBlank()) userKey
                  else com.versobit.weatherdoge.BuildConfig.OWM_APPID
        return OpenWeatherMapProvider({ key })
    }

    private fun providerFor(context: Context, source: WeatherSource) = when (source) {
        WeatherSource.OPEN_METEO -> Fetcher { lat, lon, place ->
            if (place == null) openMeteo().fetch(lat, lon) else openMeteo().fetch(place)
        }
        WeatherSource.OPEN_WEATHER_MAP -> Fetcher { lat, lon, place ->
            val p = openWeatherMap(context)
            if (place == null) p.fetch(lat, lon) else p.fetch(place)
        }
    }

    private class Fetcher(val block: (Double, Double, String?) -> WeatherResult)

    // ---- public API ----------------------------------------------------------

    /** Last successfully fetched data of any age, or null. Cheap (memory/disk). */
    fun current(context: Context): WeatherData? {
        memory?.let { return it }
        val loaded = load(context.applicationContext) ?: return null
        memory = loaded
        return loaded
    }

    /** Current data when fresh for the given query, else null (caller decides to refresh). */
    fun fresh(context: Context, latitude: Double?, longitude: Double?, place: String?): WeatherData? {
        val data = current(context) ?: return null
        if (data.time + CACHE_MAX_AGE_MS < System.currentTimeMillis()) return null
        if (place != null) {
            if (!data.place.equals(place, ignoreCase = true)) return null
        } else if (latitude != null && longitude != null) {
            if (round2(data.latitude) != round2(latitude) || round2(data.longitude) != round2(longitude)) return null
        }
        return data
    }

    /**
     * Fetch weather, persist on success. On failure the previous cached data is kept
     * intact. Runs synchronously — call from a background thread.
     * @param outOfDateHint set when serving potentially stale data so UI can mark it.
     */
    fun refresh(
        context: Context,
        latitude: Double?,
        longitude: Double?,
        place: String?,
        source: WeatherSource
    ): WeatherResult {
        val app = context.applicationContext
        val fetcher = providerFor(app, source)
        val result = fetcher.block(
            latitude ?: 0.0,
            longitude ?: 0.0,
            if (latitude == null || longitude == null) place else null
        )
        if (result is WeatherResult.Success) {
            val data = result.data
            // Fill in a human-readable place name for coordinate queries when possible.
            val enriched = if (data.place.isBlank() && latitude != null && longitude != null) {
                data.copy(place = reverseGeocode(app, latitude, longitude) ?: data.place)
            } else data
            val normalized = if (enriched.place.isBlank()) enriched.copy(place = place ?: "") else enriched
            memory = normalized
            save(app, normalized)
            rememberLocation(app, normalized)
            notifyListeners(normalized)
            return WeatherResult.Success(normalized)
        }
        // Failure: keep cache untouched.
        Log.w(TAG, "refresh failed, cache preserved: $result")
        return result
    }

    /** Persist a location fix so the widget can refresh without any location permission. */
    fun rememberLocation(context: Context, data: WeatherData) {
        if (data.latitude == 0.0 && data.longitude == 0.0) return
        prefs(context).edit()
            .putFloat(PREF_LAST_LAT, data.latitude.toFloat())
            .putFloat(PREF_LAST_LON, data.longitude.toFloat())
            .putString("internal_last_place", data.place)
            .apply()
    }

    fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        if (!p.contains(PREF_LAST_LAT)) return null
        return Pair(p.getFloat(PREF_LAST_LAT, 0f).toDouble(), p.getFloat(PREF_LAST_LON, 0f).toDouble())
    }

    fun source(context: Context): WeatherSource =
        WeatherSource.fromKey(prefs(context).getString(PREF_WEATHER_SOURCE, WeatherSource.OPEN_METEO.key))

    // ---- listeners (main thread) ---------------------------------------------

    @Synchronized
    fun addListener(l: (WeatherData) -> Unit) { synchronized(listeners) { listeners.add(l) } }

    @Synchronized
    fun removeListener(l: (WeatherData) -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun notifyListeners(data: WeatherData) {
        val snapshot = synchronized(listeners) { ArrayList(listeners) }
        if (snapshot.isEmpty()) return
        mainHandler.post { snapshot.forEach { it(data) } }
    }

    // ---- persistence ---------------------------------------------------------

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    private fun file(context: Context) = File(context.filesDir, CACHE_FILE)

    private fun load(context: Context): WeatherData? = try {
        val f = file(context)
        if (!f.exists()) null else fromJson(JSONObject(f.readText()))
    } catch (e: Exception) {
        Log.w(TAG, "cache load failed", e)
        null
    }

    private fun save(context: Context, data: WeatherData) {
        try {
            val f = file(context)
            val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
            tmp.writeText(toJson(data).toString())
            if (!tmp.renameTo(f)) {
                f.delete(); tmp.renameTo(f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cache save failed", e)
        }
    }

    internal fun toJson(data: WeatherData): JSONObject = JSONObject().apply {
        put("temperature", data.temperature)
        put("condition", data.condition)
        put("image", data.image)
        put("latitude", data.latitude)
        put("longitude", data.longitude)
        put("place", data.place)
        put("time", data.time)
        put("source", data.source.key)
        put("link", data.link ?: JSONObject.NULL)
        put("timezone", data.timezone ?: JSONObject.NULL)
        put("daily", JSONArray().apply {
            data.daily.forEach { d ->
                put(JSONObject().apply {
                    put("epochDay", d.epochDay)
                    put("highC", d.highC ?: JSONObject.NULL)
                    put("lowC", d.lowC ?: JSONObject.NULL)
                    put("image", d.image)
                    put("condition", d.condition)
                    put("precipProbability", d.precipProbability ?: JSONObject.NULL)
                })
            }
        })
    }

    internal fun fromJson(json: JSONObject): WeatherData {
        val dailyArr = json.optJSONArray("daily")
        val daily = ArrayList<DailyForecast>()
        if (dailyArr != null) {
            for (i in 0 until dailyArr.length()) {
                val d = dailyArr.getJSONObject(i)
                daily.add(
                    DailyForecast(
                        epochDay = d.getLong("epochDay"),
                        highC = if (d.isNull("highC")) null else d.getDouble("highC"),
                        lowC = if (d.isNull("lowC")) null else d.getDouble("lowC"),
                        image = d.getString("image"),
                        condition = d.getString("condition"),
                        precipProbability = if (d.isNull("precipProbability")) null else d.getInt("precipProbability")
                    )
                )
            }
        }
        return WeatherData(
            temperature = json.getDouble("temperature"),
            condition = json.getString("condition"),
            image = json.getString("image"),
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            place = json.getString("place"),
            time = json.getLong("time"),
            source = WeatherSource.fromKey(json.getString("source")),
            link = json.optString("link", "").takeIf { it.isNotEmpty() && it != "null" },
            timezone = json.optString("timezone", "").takeIf { it.isNotEmpty() && it != "null" },
            daily = daily
        )
    }

    private fun reverseGeocode(context: Context, lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null else run {
            @Suppress("DEPRECATION")
            Geocoder(context).getFromLocation(lat, lon, 1)?.firstOrNull()?.locality
        }
    } catch (e: Exception) {
        null
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0

    /** Test seam: clear memory between unit tests. */
    internal fun resetForTests() { memory = null }

    // unused http reference kept out; providers own their transport
    @Suppress("unused")
    private val httpGuard: (String) -> Http.Response = { Http.get(it) }
}
