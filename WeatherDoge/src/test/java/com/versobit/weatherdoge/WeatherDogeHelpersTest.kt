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

package com.versobit.weatherdoge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The art/adjective selectors are keyed on OWM-style icon IDs. Every icon either
 * provider can emit must resolve to real artwork, or the main screen shows nothing.
 */
class WeatherDogeHelpersTest {

    /** Every icon ID the two providers can produce. */
    private val allIcons = listOf(
        "01d", "01n", "02d", "02n", "03d", "03n", "04d", "04n",
        "09d", "09n", "10d", "10n", "11d", "11n", "13d", "13n", "50d", "50n"
    )

    @Test
    fun everyProviderIconResolvesToDogeAndSkyArt() {
        for (icon in allIcons) {
            assertNotEquals("no doge art for $icon", 0, WeatherDoge.dogeSelect(icon))
            assertNotEquals("no sky art for $icon", 0, WeatherDoge.skySelect(icon))
        }
    }

    @Test
    fun dayAndNightSelectDifferentArt() {
        assertNotEquals(WeatherDoge.dogeSelect("01d"), WeatherDoge.dogeSelect("01n"))
        assertNotEquals(WeatherDoge.skySelect("01d"), WeatherDoge.skySelect("01n"))
        assertNotEquals(WeatherDoge.skySelect("10d"), WeatherDoge.skySelect("10n"))
    }

    @Test
    fun onlySnowySkiesEmitSnowfall() {
        assertTrue(WeatherDoge.isSnowing(WeatherDoge.skySelect("13d")))
        assertTrue(WeatherDoge.isSnowing(WeatherDoge.skySelect("13n")))
        for (icon in allIcons.filter { !it.startsWith("13") }) {
            assertTrue(
                "unexpected snowfall for $icon",
                !WeatherDoge.isSnowing(WeatherDoge.skySelect(icon))
            )
        }
    }

    @Test
    fun temperatureBucketsPickTheRightAdjectiveArray() {
        assertEquals(R.array.weather_polarvortex, WeatherDoge.getTempAdjectives(-40))
        assertEquals(R.array.weather_yuck, WeatherDoge.getTempAdjectives(-20))
        assertEquals(R.array.weather_yuck, WeatherDoge.getTempAdjectives(-15))
        assertEquals(R.array.weather_notokay, WeatherDoge.getTempAdjectives(-7))
        assertEquals(R.array.weather_chilly, WeatherDoge.getTempAdjectives(0))
        assertEquals(R.array.weather_concern, WeatherDoge.getTempAdjectives(10))
        assertEquals(R.array.weather_whatever, WeatherDoge.getTempAdjectives(20))
        assertEquals(R.array.weather_warmth, WeatherDoge.getTempAdjectives(30))
        assertEquals(R.array.weather_globalwarming, WeatherDoge.getTempAdjectives(31))
        assertEquals(R.array.weather_globalwarming, WeatherDoge.getTempAdjectives(60))
    }

    @Test
    fun backgroundBucketsAreKeyedOnTheIcon() {
        assertEquals(R.array.bg_01d, WeatherDoge.getBgAdjectives("01d"))
        assertEquals(R.array.bg_01n, WeatherDoge.getBgAdjectives("01n"))
        assertEquals(R.array.bg_50, WeatherDoge.getBgAdjectives("50d"))
        assertEquals(R.array.bg_50, WeatherDoge.getBgAdjectives("50n"))
        assertEquals(R.array.bg_04, WeatherDoge.getBgAdjectives("04d"))
        assertNotEquals(R.array.bg_01d, WeatherDoge.getBgAdjectives("13d"))
    }

    @Test
    fun dogeismIsBuiltFromTheProvidedWordsOnly() {
        val wows = arrayOf("wow", "much scare")
        val dogefixes = arrayOf("such %s", "so %s")
        val adjectives = arrayOf("cold", "wet")

        val expected = setOf("wow", "much scare", "such cold", "such wet", "so cold", "so wet")
        val random = Random(12345L)
        for (i in 0 until 50) {
            val ism = WeatherDoge.getDogeism(random, wows, dogefixes, adjectives)
            assertTrue("unexpected dogeism '$ism'", ism in expected)
        }

        // The no-Random overload behaves the same way.
        val plain = WeatherDoge.getDogeism(wows, dogefixes, adjectives)
        assertTrue("unexpected dogeism '$plain'", plain in expected)
    }

    @Test
    fun dogeismSubstitutesTheWeatherAdjective() {
        val ism = WeatherDoge.getDogeism(
            Random(7L), arrayOf(), arrayOf("very %s"), arrayOf("chilly")
        )
        assertEquals("very chilly", ism)
    }

    @Test
    fun locationPermissionIsThePreciseOneOnFossBuilds() {
        assertEquals(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            WeatherDoge.LOCATION_PERMISSION
        )
    }
}
