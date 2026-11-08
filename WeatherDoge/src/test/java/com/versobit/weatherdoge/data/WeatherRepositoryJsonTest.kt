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

package com.versobit.weatherdoge.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON cache is the contract between the app and the widget (upstream #6/#9):
 * whatever the app writes, the widget must read back identically — including when the
 * file was written by an older version of the app.
 */
class WeatherRepositoryJsonTest {

    private fun sample(
        link: String? = "https://open-meteo.com/",
        timezone: String? = "America/New_York",
        daily: List<DailyForecast> = listOf(
            DailyForecast(20_000L, 5.5, -3.0, "13d", "Heavy Snowfall", 70),
            DailyForecast(20_001L, 7.0, -1.0, "10d", "Moderate Rain", null)
        ),
        source: WeatherSource = WeatherSource.OPEN_METEO
    ) = WeatherData(
        temperature = -12.5,
        condition = "Heavy Snow",
        image = "13d",
        latitude = 40.7128,
        longitude = -74.0060,
        place = "New York",
        time = 1_767_123_456_789L,
        source = source,
        link = link,
        timezone = timezone,
        daily = daily
    )

    @Test
    fun roundTripPreservesEveryField() {
        val original = sample()
        val restored = WeatherRepository.fromJson(WeatherRepository.toJson(original))
        assertEquals(original, restored)
    }

    @Test
    fun roundTripKeepsOptionalFieldsAbsent() {
        val original = sample(link = null, timezone = null, daily = emptyList())
        val json = WeatherRepository.toJson(original)

        assertTrue(json.isNull("link"))
        assertTrue(json.isNull("timezone"))

        val restored = WeatherRepository.fromJson(json)
        assertNull(restored.link)
        assertNull(restored.timezone)
        assertTrue(restored.daily.isEmpty())
        assertEquals(original, restored)
    }

    @Test
    fun sourceKeysSurviveTheRoundTrip() {
        for (source in WeatherSource.entries) {
            val restored = WeatherRepository.fromJson(WeatherRepository.toJson(sample(source = source)))
            assertEquals(source, restored.source)
        }
        // The persisted key is the wire format — do not renumber it.
        assertEquals("3", WeatherSource.OPEN_METEO.key)
        assertEquals("0", WeatherSource.OPEN_WEATHER_MAP.key)
        assertEquals("3", WeatherRepository.toJson(sample()).getString("source"))
    }

    @Test
    fun unknownSourceKeyFallsBackToOpenMeteo() {
        val json = WeatherRepository.toJson(sample(source = WeatherSource.OPEN_WEATHER_MAP))
        json.put("source", "99")
        assertEquals(WeatherSource.OPEN_METEO, WeatherRepository.fromJson(json).source)
    }

    @Test
    fun legacyNullStringsAreTreatedAsAbsent() {
        // Older builds wrote the literal text "null" instead of a JSON null.
        val json = WeatherRepository.toJson(sample())
        json.put("link", "null")
        json.put("timezone", "null")
        val restored = WeatherRepository.fromJson(json)
        assertNull(restored.link)
        assertNull(restored.timezone)
    }

    @Test
    fun missingDailyBlockIsTolerated() {
        val json = WeatherRepository.toJson(sample())
        json.remove("daily")
        assertTrue(WeatherRepository.fromJson(json).daily.isEmpty())
    }

    @Test
    fun dailyEntriesRoundTripWithNullPrecipitation() {
        val restored = WeatherRepository.fromJson(WeatherRepository.toJson(sample()))
        assertEquals(2, restored.daily.size)
        assertEquals(70, restored.daily[0].precipProbability)
        assertNull(restored.daily[1].precipProbability)
        assertEquals(5.5, restored.daily[0].highC!!, 0.0)
        assertEquals(-3.0, restored.daily[0].lowC!!, 0.0)
        assertEquals(20_000L, restored.daily[0].epochDay)
    }

    @Test
    fun temperatureKeepsFullPrecision() {
        val restored = WeatherRepository.fromJson(WeatherRepository.toJson(sample()))
        assertEquals(-12.5, restored.temperature, 0.0)
        assertEquals(1_767_123_456_789L, restored.time)
    }

    @Test
    fun corruptedCacheThrowsSoTheCallerCanFallBack() {
        val broken = try {
            WeatherRepository.fromJson(JSONObject("{not json"))
            null
        } catch (e: Exception) {
            e
        }
        assertTrue("a corrupt file must not parse silently", broken != null)
    }
}
