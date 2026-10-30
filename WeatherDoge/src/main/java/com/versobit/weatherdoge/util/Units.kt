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
 */

package com.versobit.weatherdoge.util

import java.util.Locale

/** Locale-driven unit detection (metric vs imperial). */
object Units {

    enum class System { METRIC, IMPERIAL }

    /** US, Liberia, and Myanmar (Burma) default to imperial. */
    fun defaultSystem(locale: Locale = Locale.getDefault()): System {
        val country = locale.country.uppercase(Locale.US)
        return when (country) {
            "US", "LR", "MM" -> System.IMPERIAL
            else -> System.METRIC
        }
    }

    /** Convert internal Celsius into display temperature (rounded integer). */
    fun toDisplay(celsius: Double, forceMetric: Boolean): Int {
        val useF = defaultSystem() == System.IMPERIAL && !forceMetric
        val v = if (useF) celsius * 1.8 + 32.0 else celsius
        return Math.round(v).toInt()
    }

    fun unitSymbol(forceMetric: Boolean): String {
        val useF = defaultSystem() == System.IMPERIAL && !forceMetric
        return if (useF) "°F" else "°C"
    }

    /** Formats e.g. "-5°" or "72°" — guaranteed no trailing spaces, no fractions. */
    fun formatShort(celsius: Double, forceMetric: Boolean): String =
        "${toDisplay(celsius, forceMetric)}°"
}
