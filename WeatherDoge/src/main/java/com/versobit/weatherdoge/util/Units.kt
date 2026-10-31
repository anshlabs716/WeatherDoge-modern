package com.versobit.weatherdoge.util

import java.util.Locale
import kotlin.math.roundToInt

object Units {

    enum class System { METRIC, IMPERIAL }

    fun defaultSystem(locale: Locale = Locale.getDefault()): System {
        val country = locale.country.uppercase(Locale.US)
        return when (country) {
            "US", "LR", "MM" -> System.IMPERIAL
            else -> System.METRIC
        }
    }

    fun toDisplay(celsius: Double, forceMetric: Boolean): Int {
        val useF = defaultSystem() == System.IMPERIAL && !forceMetric
        val v = if (useF) celsius * 1.8 + 32.0 else celsius
        return v.roundToInt()
    }

    fun unitSymbol(forceMetric: Boolean): String {
        val useF = defaultSystem() == System.IMPERIAL && !forceMetric
        return if (useF) "°F" else "°C"
    }

    fun formatShort(celsius: Double, forceMetric: Boolean): String =
        "${toDisplay(celsius, forceMetric)}°"
}
