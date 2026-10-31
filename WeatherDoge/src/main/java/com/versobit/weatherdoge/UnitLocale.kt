package com.versobit.weatherdoge

import java.util.Locale

object UnitLocale {
    const val IMPERIAL = 0
    const val METRIC = 1

    @JvmStatic
    fun getDefault(): Int = getFrom(Locale.getDefault())

    @JvmStatic
    fun getFrom(locale: Locale): Int {
        val countryCode = locale.country
        return when (countryCode) {
            "US", "LR", "MM" -> IMPERIAL
            else -> METRIC
        }
    }
}
