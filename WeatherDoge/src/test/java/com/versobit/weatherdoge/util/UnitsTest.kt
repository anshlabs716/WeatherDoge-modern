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

package com.versobit.weatherdoge.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class UnitsTest {

    private lateinit var original: Locale

    @Before
    fun rememberLocale() {
        original = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun imperialCountriesAreDetected() {
        assertEquals(Units.System.IMPERIAL, Units.defaultSystem(Locale.US))
        assertEquals(Units.System.IMPERIAL, Units.defaultSystem(Locale("", "lr")))
        assertEquals(Units.System.IMPERIAL, Units.defaultSystem(Locale("", "mm")))
        // Country codes are matched case-insensitively.
        assertEquals(Units.System.IMPERIAL, Units.defaultSystem(Locale("", "us")))
    }

    @Test
    fun everyoneElseIsMetric() {
        assertEquals(Units.System.METRIC, Units.defaultSystem(Locale.GERMANY))
        assertEquals(Units.System.METRIC, Units.defaultSystem(Locale.FRANCE))
        assertEquals(Units.System.METRIC, Units.defaultSystem(Locale("", "gb")))
        assertEquals(Units.System.METRIC, Units.defaultSystem(Locale.ROOT))
    }

    @Test
    fun celsiusConvertsToFahrenheitByDefaultInTheUs() {
        Locale.setDefault(Locale.US)
        assertEquals(32, Units.toDisplay(0.0, forceMetric = false))
        assertEquals(212, Units.toDisplay(100.0, forceMetric = false))
        assertEquals(-40, Units.toDisplay(-40.0, forceMetric = false))
    }

    @Test
    fun forceMetricOverridesTheImperialLocale() {
        Locale.setDefault(Locale.US)
        assertEquals(0, Units.toDisplay(0.0, forceMetric = true))
        assertEquals(100, Units.toDisplay(100.0, forceMetric = true))
        assertEquals("°C", Units.unitSymbol(forceMetric = true))
        assertEquals("°F", Units.unitSymbol(forceMetric = false))
    }

    @Test
    fun metricLocalesStayCelsius() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals(21, Units.toDisplay(21.4, forceMetric = false))
        assertEquals("°C", Units.unitSymbol(forceMetric = false))
    }

    @Test
    fun formatShortIsAlwaysAnIntegerWithASymbol() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("-5°", Units.formatShort(-5.0, forceMetric = true))
        assertEquals("0°", Units.formatShort(0.4, forceMetric = true))
        assertEquals("1°", Units.formatShort(0.6, forceMetric = true))
        assertEquals("37°", Units.formatShort(36.6, forceMetric = true))
        assertEquals("-40°", Units.formatShort(-40.0, forceMetric = true))
        for (value in listOf(-77.7, -0.1, 0.0, 12.5, 99.9)) {
            val formatted = Units.formatShort(value, forceMetric = true)
            assertTrue("no fractions in '$formatted'", !formatted.contains('.'))
            assertTrue("no trailing space in '$formatted'", formatted == formatted.trim())
            assertTrue("ends with a degree symbol in '$formatted'", formatted.endsWith("°"))
        }
    }
}
