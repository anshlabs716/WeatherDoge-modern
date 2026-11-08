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

import com.versobit.weatherdoge.data.WeatherResult
import com.versobit.weatherdoge.data.WeatherSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * OpenWeatherMap stays as the key-based secondary provider. The important logic is
 * turning OWM's 3-hour steps into whole *local* days — day boundaries and DST must
 * follow the location's timezone, not UTC.
 */
class OpenWeatherMapProviderTest {

    private val currentJson = """
        {
          "cod": 200,
          "id": 2950159,
          "name": "Berlin",
          "timezone": 7200,
          "coord": {"lat": 52.52, "lon": 13.41},
          "weather": [{"description": "moderate rain", "icon": "10d"}],
          "main": {"temp": 18.6}
        }
    """.trimIndent()

    private fun step(dt: Long, hi: Double, lo: Double, icon: String, pop: Double? = null): JSONObject =
        JSONObject().apply {
            put("dt", dt)
            put("main", JSONObject().apply {
                put("temp_max", hi)
                put("temp_min", lo)
            })
            put("weather", JSONArray().put(JSONObject().put("icon", icon)))
            if (pop != null) put("pop", pop)
        }

    private fun forecastOf(vararg steps: JSONObject): JSONObject =
        JSONObject().put("list", JSONArray().apply { steps.forEach { put(it) } })

    private fun seconds(iso: String): Long = Instant.parse(iso).epochSecond

    private fun provider(
        key: String = "test-key",
        http: (String) -> Http.Response
    ) = OpenWeatherMapProvider({ key }, http)

    // ---- key handling -------------------------------------------------------

    @Test
    fun missingKeyIsAnActionableApiError() {
        val p = OpenWeatherMapProvider({ "  " }) { throw AssertionError("no request without a key") }
        val result = p.fetch(52.52, 13.41)
        assertTrue("expected ApiError, got $result", result is WeatherResult.ApiError)
        val message = (result as WeatherResult.ApiError).message
        assertTrue("message should say which key is missing: $message", message.contains("App ID"))
        assertTrue("message should point at Open-Meteo: $message", message.contains("Open-Meteo"))
    }

    @Test
    fun rejectedKeyIsMappedToASpecificError() {
        val p = provider { throw Http.HttpStatusException(401, "bad key") }
        val result = p.fetch(52.52, 13.41)
        assertTrue(result is WeatherResult.ApiError)
        assertTrue((result as WeatherResult.ApiError).message.contains("401"))
    }

    // ---- current + forecast happy path --------------------------------------

    @Test
    fun fetchCombinesCurrentConditionsWithAggregatedDailyForecast() {
        // parseForecast derives "today" from the clock when not told otherwise, so the
        // fixture days are built relative to now in the same zone the provider will use.
        val zone = ZoneId.of("UTC+02:00")
        val today = LocalDate.now(zone)
        val step1 = step(today.atTime(11, 0).atZone(zone).toInstant().epochSecond, 20.0, 14.0, "10d", 0.4)
        val step2 = step(today.atTime(14, 0).atZone(zone).toInstant().epochSecond, 22.0, 15.0, "10d", 0.8)
        val step3 = step(today.plusDays(1).atTime(11, 0).atZone(zone).toInstant().epochSecond, 25.0, 16.0, "01d", 0.0)
        val forecastJson = JSONObject().put("list", JSONArray().apply {
            put(step1); put(step2); put(step3)
        })

        val p = provider { url ->
            if (url.contains("/forecast")) Http.Response(forecastJson.toString(), 200)
            else Http.Response(currentJson, 200)
        }
        val result = p.fetch(52.52, 13.41)
        assertTrue("expected Success, got $result", result is WeatherResult.Success)
        val data = (result as WeatherResult.Success).data

        assertEquals(18.6, data.temperature, 0.0)
        assertEquals("Moderate rain", data.condition)
        assertEquals("10d", data.image)
        assertEquals("Berlin", data.place)
        assertEquals(WeatherSource.OPEN_WEATHER_MAP, data.source)
        assertEquals("https://openweathermap.org/city/2950159", data.link)
        // timezone 7200 → a fixed offset zone, rendered as UTC+02:00
        assertEquals("UTC+02:00", data.timezone)

        assertEquals(2, data.daily.size)
        assertEquals(today.toEpochDay(), data.daily[0].epochDay)
        // Same local day: high is the max of the steps, low the min, precip the max.
        assertEquals(22.0, data.daily[0].highC!!, 0.0)
        assertEquals(14.0, data.daily[0].lowC!!, 0.0)
        assertEquals(80, data.daily[0].precipProbability)
        assertEquals("10d", data.daily[0].image)
        assertEquals(today.plusDays(1).toEpochDay(), data.daily[1].epochDay)
        assertEquals(25.0, data.daily[1].highC!!, 0.0)
        assertEquals("01d", data.daily[1].image)
    }

    @Test
    fun forecastFailureNeverDiscardsGoodCurrentData() {
        val p = provider { url ->
            if (url.contains("/forecast")) throw java.io.IOException("offline")
            else Http.Response(currentJson, 200)
        }
        val result = p.fetch(52.52, 13.41)
        assertTrue("expected Success, got $result", result is WeatherResult.Success)
        val data = (result as WeatherResult.Success).data
        assertEquals(18.6, data.temperature, 0.0)
        assertTrue("daily should degrade to empty, not fail", data.daily.isEmpty())
    }

    @Test
    fun cityNameSearchIsSupported() {
        val p = provider { url ->
            if (url.contains("/forecast")) Http.Response("""{"list":[]}""", 200)
            else Http.Response(currentJson, 200)
        }
        val result = p.fetch("Berlin")
        assertTrue(result is WeatherResult.Success)
        assertEquals("Berlin", (result as WeatherResult.Success).data.place)
    }

    // ---- local-day aggregation / timezone / DST ------------------------------

    @Test
    fun utcStepsAreBucketedIntoLocalDays() {
        // 03:00Z is still the previous day in New York (UTC-5).
        val list = JSONArray().apply {
            put(step(seconds("2024-01-15T03:00:00Z"), 5.0, -2.0, "13d"))
            put(step(seconds("2024-01-15T12:00:00Z"), 7.0, -1.0, "10d"))
            put(step(seconds("2024-01-16T02:00:00Z"), 6.0, -3.0, "10d"))
        }
        val json = JSONObject().put("list", list)
        val p = provider { Http.Response("{}", 200) }

        val nyToday = LocalDate.parse("2024-01-14").toEpochDay()
        val out = p.parseForecast(json, "America/New_York", nyToday)

        assertEquals(2, out.size)
        assertEquals(LocalDate.parse("2024-01-14").toEpochDay(), out[0].epochDay)
        assertEquals(LocalDate.parse("2024-01-15").toEpochDay(), out[1].epochDay)
        // The two New-York-local steps on Jan 15 merge into one day.
        assertEquals(7.0, out[1].highC!!, 0.0)
        assertEquals(-3.0, out[1].lowC!!, 0.0)

        // Same steps, UTC grouping: 03:00Z is still Jan 15 there, so the first bucket
        // starts a day later than the New York grouping above.
        val utcOut = p.parseForecast(json, "UTC", nyToday)
        assertEquals(2, utcOut.size)
        assertEquals(LocalDate.parse("2024-01-15").toEpochDay(), utcOut[0].epochDay)
        assertEquals(LocalDate.parse("2024-01-16").toEpochDay(), utcOut[1].epochDay)
        assertEquals(7.0, utcOut[0].highC!!, 0.0)
        assertEquals(-2.0, utcOut[0].lowC!!, 0.0)
    }

    @Test
    fun stepsBeforeTodayAreSkipped() {
        val list = JSONArray().apply {
            put(step(seconds("2024-01-14T12:00:00Z"), 1.0, -5.0, "13d"))
            put(step(seconds("2024-01-15T12:00:00Z"), 2.0, -4.0, "13d"))
        }
        val json = JSONObject().put("list", list)
        val p = provider { Http.Response("{}", 200) }

        val today = LocalDate.parse("2024-01-15").toEpochDay()
        val out = p.parseForecast(json, "UTC", today)
        assertEquals(1, out.size)
        assertEquals(today, out[0].epochDay)
    }

    @Test
    fun springForwardDstKeepsOneLocalDayTogether() {
        // America/New_York, 2024-03-10: 02:00 EST jumps to 03:00 EDT.
        // The last step (2024-03-11T03:00Z) is still Mar 10 locally (UTC-4).
        val list = JSONArray().apply {
            put(step(seconds("2024-03-10T06:30:00Z"), 10.0, 2.0, "10d", 0.5))
            put(step(seconds("2024-03-10T07:30:00Z"), 12.0, 3.0, "10d", 0.6))
            put(step(seconds("2024-03-11T03:00:00Z"), 11.0, 1.0, "01d", 0.1))
        }
        val json = JSONObject().put("list", list)
        val p = provider { Http.Response("{}", 200) }

        val out = p.parseForecast(
            json, "America/New_York", LocalDate.parse("2024-03-10").toEpochDay()
        )

        assertEquals("DST must not split or duplicate a local day", 1, out.size)
        assertEquals(LocalDate.parse("2024-03-10").toEpochDay(), out[0].epochDay)
        assertEquals(12.0, out[0].highC!!, 0.0)
        assertEquals(1.0, out[0].lowC!!, 0.0)
        assertEquals(60, out[0].precipProbability)
    }

    @Test
    fun fallBackDstCountsTheRepeatedHourOnce() {
        // America/New_York, 2024-11-03: 02:00 EDT falls back to 01:00 EST.
        val list = JSONArray().apply {
            put(step(seconds("2024-11-03T05:30:00Z"), 15.0, 8.0, "01d")) // 01:30 EDT
            put(step(seconds("2024-11-03T06:30:00Z"), 14.0, 7.0, "01d")) // 01:30 EST (again)
            put(step(seconds("2024-11-04T03:00:00Z"), 13.0, 6.0, "10d")) // Nov 3, 22:00 EST
            put(step(seconds("2024-11-04T05:00:00Z"), 12.0, 5.0, "10d")) // Nov 4, 00:00 EST
        }
        val json = JSONObject().put("list", list)
        val p = provider { Http.Response("{}", 200) }

        val out = p.parseForecast(
            json, "America/New_York", LocalDate.parse("2024-11-03").toEpochDay()
        )

        assertEquals(2, out.size)
        assertEquals(LocalDate.parse("2024-11-03").toEpochDay(), out[0].epochDay)
        assertEquals(15.0, out[0].highC!!, 0.0)
        assertEquals(6.0, out[0].lowC!!, 0.0)
        assertEquals(LocalDate.parse("2024-11-04").toEpochDay(), out[1].epochDay)
    }

    @Test
    fun unknownTimezoneFallsBackToUtcInsteadOfCrashing() {
        val list = JSONArray().apply {
            put(step(seconds("2024-01-15T12:00:00Z"), 3.0, -1.0, "01d"))
        }
        val json = JSONObject().put("list", list)
        val p = provider { Http.Response("{}", 200) }
        val out = p.parseForecast(json, "Not/AZone", LocalDate.parse("2024-01-15").toEpochDay())
        assertEquals(1, out.size)
        assertEquals(LocalDate.parse("2024-01-15").toEpochDay(), out[0].epochDay)
    }

    // ---- aggregation details -------------------------------------------------

    @Test
    fun dominantIconIsTheMostFrequentOfTheDay() {
        val list = JSONArray().apply {
            put(step(seconds("2024-06-01T06:00:00Z"), 20.0, 12.0, "01d"))
            put(step(seconds("2024-06-01T09:00:00Z"), 21.0, 13.0, "01d"))
            put(step(seconds("2024-06-01T12:00:00Z"), 22.0, 14.0, "10d"))
        }
        val json = JSONObject().put("list", list)
        val p = provider { Http.Response("{}", 200) }
        val out = p.parseForecast(json, "UTC", LocalDate.parse("2024-06-01").toEpochDay())
        assertEquals(1, out.size)
        assertEquals("01d", out[0].image)
        assertEquals("Sky is Clear", out[0].condition)
    }

    @Test
    fun missingFieldsStayNullRatherThanZero() {
        val bare = JSONObject().apply {
            put("dt", seconds("2024-06-01T12:00:00Z"))
            // deliberately no "main" and no "pop"
        }
        val json = JSONObject().put("list", JSONArray().put(bare))
        val p = provider { Http.Response("{}", 200) }
        val out = p.parseForecast(json, "UTC", LocalDate.parse("2024-06-01").toEpochDay())
        assertEquals(1, out.size)
        assertNull(out[0].highC)
        assertNull(out[0].lowC)
        assertNull(out[0].precipProbability)
        assertEquals("01d", out[0].image) // fallback when no icons exist
    }

    @Test
    fun bogusOrEmptyForecastListsProduceNoDays() {
        val p = provider { Http.Response("{}", 200) }
        assertTrue(p.parseForecast(JSONObject("{}"), "UTC", 0).isEmpty())
        assertTrue(p.parseForecast(JSONObject("""{"list":[]}"""), "UTC", 0).isEmpty())
    }

    @Test
    fun forecastIsCappedAtSevenDays() {
        val list = JSONArray()
        for (day in 0 until 10) {
            val dt = Instant.parse("2024-06-01T12:00:00Z").epochSecond + day * 86_400L
            list.put(step(dt, 20.0, 10.0, "01d"))
        }
        val json = JSONObject().put("list", list)
        val p = provider { Http.Response("{}", 200) }
        val out = p.parseForecast(json, "UTC", LocalDate.parse("2024-06-01").toEpochDay())
        assertEquals(7, out.size)
        // Buckets stay in chronological order.
        assertEquals(out.map { it.epochDay }.sorted(), out.map { it.epochDay })
    }
}
