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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Open-Meteo is the default, keyless provider — its parsing is what most users see.
 * The transport is injected, so every test here runs against canned HTTP bodies.
 */
class OpenMeteoProviderTest {

    private val weatherJson = """
        {
          "latitude": 52.52,
          "longitude": 13.41,
          "timezone": "Europe/Berlin",
          "current": {
            "time": "2026-06-15T12:00",
            "temperature_2m": 21.4,
            "weather_code": 61,
            "is_day": 1
          },
          "daily": {
            "time": ["2026-06-13", "2026-06-15", "2026-06-16"],
            "weather_code": [0, 61, 3],
            "temperature_2m_max": [20.0, 22.0, 19.5],
            "temperature_2m_min": [10.0, 12.0, 10.2],
            "precipitation_probability_max": [10, 80, 20]
          }
        }
    """.trimIndent()

    private val geocodingJson = """
        {"results":[{"latitude":52.52,"longitude":13.41,"name":"Berlin"}]}
    """.trimIndent()

    private fun providerReturning(body: String) = OpenMeteoProvider { Http.Response(body, 200) }

    // ---- current conditions ------------------------------------------------

    @Test
    fun fetchParsesCurrentConditionsAndDailyForecast() {
        val result = providerReturning(weatherJson).fetch(52.52, 13.41)
        assertTrue("expected Success, got $result", result is WeatherResult.Success)
        val data = (result as WeatherResult.Success).data

        assertEquals(21.4, data.temperature, 0.0)
        assertEquals("Slight Rain", data.condition)
        assertEquals("10d", data.image)
        assertEquals("Europe/Berlin", data.timezone)
        assertEquals(52.52, data.latitude, 0.0)
        assertEquals(13.41, data.longitude, 0.0)
        assertEquals("", data.place) // repository resolves the name via Geocoder
        assertEquals(com.versobit.weatherdoge.data.WeatherSource.OPEN_METEO, data.source)
        assertEquals("https://open-meteo.com/", data.link)
        assertTrue(data.time > 0)

        // The 06-13 entry predates "today" (06-15) and must be dropped.
        assertEquals(2, data.daily.size)
        assertEquals(LocalDate.parse("2026-06-15").toEpochDay(), data.daily[0].epochDay)
        assertEquals(22.0, data.daily[0].highC!!, 0.0)
        assertEquals(12.0, data.daily[0].lowC!!, 0.0)
        assertEquals(80, data.daily[0].precipProbability)
        assertEquals("10d", data.daily[0].image)
        assertEquals(LocalDate.parse("2026-06-16").toEpochDay(), data.daily[1].epochDay)
        assertEquals(20, data.daily[1].precipProbability)
    }

    @Test
    fun fetchNightConditionsProduceNightArt() {
        val night = weatherJson.replace("\"weather_code\": 61", "\"weather_code\": 0")
            .replace("\"is_day\": 1", "\"is_day\": 0")
        val data = (providerReturning(night).fetch(1.0, 2.0) as WeatherResult.Success).data
        assertEquals("01n", data.image)
        assertEquals("Clear Night", data.condition)
    }

    @Test
    fun malformedBodyIsAFailureNotACrash() {
        val result = providerReturning("<html>oops</html>").fetch(1.0, 2.0)
        assertTrue("expected Failure, got $result", result is WeatherResult.Failure)
    }

    @Test
    fun rateLimitSurfacesAsAnActionableApiError() {
        val provider = OpenMeteoProvider { throw Http.HttpStatusException(429, "slow down") }
        val result = provider.fetch(1.0, 2.0)
        assertTrue("expected ApiError, got $result", result is WeatherResult.ApiError)
        assertTrue((result as WeatherResult.ApiError).message.contains("Rate limited"))
        assertEquals(429, result.httpCode)
    }

    @Test
    fun emptyManualLocationIsAnApiErrorWithoutTouchingTheNetwork() {
        var called = false
        val provider = OpenMeteoProvider { called = true; Http.Response("{}", 200) }
        val result = provider.fetch("   ")
        assertTrue(result is WeatherResult.ApiError)
        assertTrue("no request should be made", !called)
    }

    // ---- manual location + geocoding ---------------------------------------

    @Test
    fun manualLocationResolvesThroughGeocodingAndKeepsTheTypedName() {
        val provider = OpenMeteoProvider { url ->
            if (url.contains("geocoding-api")) Http.Response(geocodingJson, 200)
            else Http.Response(weatherJson, 200)
        }
        val result = provider.fetch("Berlin")
        assertTrue("expected Success, got $result", result is WeatherResult.Success)
        val data = (result as WeatherResult.Success).data
        assertEquals("Berlin", data.place)
        assertEquals(52.52, data.latitude, 0.0)
    }

    @Test
    fun geocodingWithNoResultsIsAnApiError() {
        val provider = OpenMeteoProvider { Http.Response("""{"results":[]}""", 200) }
        val result = provider.fetch("Nowhereville")
        assertTrue("expected ApiError, got $result", result is WeatherResult.ApiError)
        assertTrue((result as WeatherResult.ApiError).message.contains("No results"))
    }

    // ---- daily mapping ------------------------------------------------------

    @Test
    fun parseDailySkipsPastDaysAndKeepsMissingFieldsNull() {
        val daily = JSONObject(
            """
            {
              "time": ["2026-06-13", "2026-06-14", "2026-06-15"],
              "weather_code": [0, 3, 61],
              "temperature_2m_max": [20.0, null, 22.0],
              "temperature_2m_min": [10.0, 11.0, null],
              "precipitation_probability_max": [10, null, 90]
            }
            """.trimIndent()
        )
        val today = LocalDate.parse("2026-06-14").toEpochDay()
        val out = OpenMeteoProvider().parseDaily(daily, today)

        assertEquals(2, out.size)
        assertEquals(LocalDate.parse("2026-06-14").toEpochDay(), out[0].epochDay)
        assertNull("missing high must stay null", out[0].highC)
        assertEquals(11.0, out[0].lowC!!, 0.0)
        assertNull("null precip must stay null", out[0].precipProbability)
        assertEquals(22.0, out[1].highC!!, 0.0)
        assertNull(out[1].lowC)
        assertEquals(90, out[1].precipProbability)
    }

    @Test
    fun parseDailyToleratesAbsentOrBrokenBlocks() {
        val provider = OpenMeteoProvider()
        assertTrue(provider.parseDaily(null, 0L).isEmpty())
        assertTrue(provider.parseDaily(JSONObject("{}"), 0L).isEmpty())
        assertTrue(provider.parseDaily(JSONObject("""{"time":"nope"}"""), 0L).isEmpty())
    }

    @Test
    fun parseDailyCapsAtSevenDays() {
        val dates = (0 until 10).joinToString(",") { """"2026-06-${15 + it}"""" }
        val daily = JSONObject("""{"time":[$dates]}""")
        assertEquals(7, OpenMeteoProvider().parseDaily(daily, LocalDate.parse("2026-06-15").toEpochDay()).size)
    }

    // ---- WMO code mapping ---------------------------------------------------

    @Test
    fun wmoCodesMapToOwmStyleIcons() {
        val p = OpenMeteoProvider()
        assertEquals("01d", p.iconForCode(0, isDay = true))
        assertEquals("01n", p.iconForCode(0, isDay = false))
        assertEquals("02d", p.iconForCode(1, isDay = true))
        assertEquals("03d", p.iconForCode(2, isDay = true))
        assertEquals("04n", p.iconForCode(3, isDay = false))
        assertEquals("50d", p.iconForCode(45, isDay = true))
        assertEquals("50d", p.iconForCode(48, isDay = true))
        assertEquals("09d", p.iconForCode(51, isDay = true))
        assertEquals("10d", p.iconForCode(63, isDay = true))
        assertEquals("13d", p.iconForCode(73, isDay = true))
        assertEquals("13n", p.iconForCode(86, isDay = false))
        assertEquals("11d", p.iconForCode(95, isDay = true))
        assertEquals("11d", p.iconForCode(99, isDay = true))
        // Unknown codes fall back to clear sky rather than exploding.
        assertEquals("01d", p.iconForCode(12345, isDay = true))
    }

    @Test
    fun wmoCodesMapToReadableDescriptions() {
        val p = OpenMeteoProvider()
        assertEquals("Sky Is Clear", p.describeCode(0, isDay = true))
        assertEquals("Clear Night", p.describeCode(0, isDay = false))
        assertEquals("Overcast", p.describeCode(3, isDay = true))
        assertEquals("Heavy Rain", p.describeCode(65, isDay = true))
        assertEquals("Thunderstorm", p.describeCode(95, isDay = true))
        assertEquals("Unknown", p.describeCode(4321, isDay = true))
    }
}
