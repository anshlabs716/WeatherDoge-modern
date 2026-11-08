package com.versobit.weatherdoge

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.core.content.ContextCompat
import java.util.Random

class WeatherDoge : Application() {

    override fun onCreate() {
        super.onCreate()
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false)
        PreferenceManager.setDefaultValues(this, R.xml.pref_app, false)
        PreferenceManager.setDefaultValues(this, R.xml.pref_widget, false)
    }

    companion object {
        val LOCATION_PERMISSION: String =
            if (BuildConfig.FLAVOR == BuildConfig.FLAVOR_PLAY)
                Manifest.permission.ACCESS_COARSE_LOCATION
            else
                Manifest.permission.ACCESS_FINE_LOCATION

        private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
        private const val EXTRA_CUSTOM_TABS_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"

        @JvmStatic
        fun skySelect(icon: String): Int {
            val img = icon.substring(0, 2).toInt()
            val day = icon[2] == 'd'
            var resId = R.drawable.sky_01d
            when (img) {
                1 -> resId = if (day) R.drawable.sky_01d else R.drawable.sky_01n
                2 -> resId = if (day) R.drawable.sky_02d else R.drawable.sky_02n
                3 -> resId = if (day) R.drawable.sky_03d else R.drawable.sky_03n
                4 -> resId = if (day) R.drawable.sky_04d else R.drawable.sky_04n
                9 -> resId = if (day) R.drawable.sky_09d else R.drawable.sky_09n
                10 -> resId = if (day) R.drawable.sky_10d else R.drawable.sky_09n
                11 -> resId = if (day) R.drawable.sky_11d else R.drawable.sky_11n
                13 -> resId = if (day) R.drawable.sky_13d else R.drawable.sky_13n
                50 -> resId = if (day) R.drawable.sky_50d else R.drawable.sky_50n
            }
            return resId
        }

        @JvmStatic
        fun dogeSelect(icon: String): Int {
            val img = icon.substring(0, 2).toInt()
            val day = icon[2] == 'd'
            var resId = R.drawable.doge_01d
            when (img) {
                1 -> resId = if (day) R.drawable.doge_01d else R.drawable.doge_01n
                2 -> resId = R.drawable.doge_02n
                3 -> resId = if (day) R.drawable.doge_03d else R.drawable.doge_03n
                4 -> resId = R.drawable.doge_04
                9 -> resId = R.drawable.doge_09
                10 -> resId = R.drawable.doge_10
                11 -> resId = R.drawable.doge_11
                13 -> resId = R.drawable.doge_13
                50 -> resId = R.drawable.doge_50
            }
            return resId
        }

        @JvmStatic
        fun isSnowing(resId: Int): Boolean = when (resId) {
            R.drawable.sky_13d, R.drawable.sky_13n -> true
            else -> false
        }

        @JvmStatic
        fun getTempAdjectives(temp: Int): Int = when {
            temp <= -30 -> R.array.weather_polarvortex
            temp in -29..-15 -> R.array.weather_yuck
            temp in -14..-7 -> R.array.weather_notokay
            temp in -6..0 -> R.array.weather_chilly
            temp in 1..10 -> R.array.weather_concern
            temp in 11..20 -> R.array.weather_whatever
            temp in 21..30 -> R.array.weather_warmth
            temp > 30 -> R.array.weather_globalwarming
            else -> R.array.weather_wow
        }

        @JvmStatic
        fun getBgAdjectives(bg: String): Int {
            val img = bg.substring(0, 2).toInt()
            val day = bg[2] == 'd'
            var resId = R.array.bg_01d
            when (img) {
                1 -> resId = if (day) R.array.bg_01d else R.array.bg_01n
                2 -> resId = if (day) R.array.bg_02d else R.array.bg_02d
                3 -> resId = if (day) R.array.bg_03d else R.array.bg_03n
                4 -> resId = R.array.bg_04
                9 -> resId = if (day) R.array.bg_09d else R.array.bg_09n
                10 -> resId = if (day) R.array.bg_10d else R.array.bg_10n
                11 -> resId = if (day) R.array.bg_11d else R.array.bg_11n
                13 -> resId = if (day) R.array.bg_13d else R.array.bg_13n
                50 -> resId = R.array.bg_50
            }
            return resId
        }

        @JvmStatic
        fun getDogeism(wows: Array<String>, dogefixes: Array<String>, weatherAdjectives: Array<String>): String =
            getDogeism(Random(), wows, dogefixes, weatherAdjectives)

        @JvmStatic
        fun getDogeism(r: Random, wows: Array<String>, dogefixes: Array<String>, weatherAdjectives: Array<String>): String {
            val wowOrNot = r.nextInt(weatherAdjectives.size + wows.size)
            if (wowOrNot >= weatherAdjectives.size) {
                return wows[wowOrNot - weatherAdjectives.size]
            }
            return String.format(dogefixes[r.nextInt(dogefixes.size)], weatherAdjectives[r.nextInt(weatherAdjectives.size)])
        }

        @JvmStatic
        fun applyChromeCustomTab(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                val extras = Bundle()
                extras.putBinder(EXTRA_CUSTOM_TABS_SESSION, null)
                extras.putInt(EXTRA_CUSTOM_TABS_TOOLBAR_COLOR, ContextCompat.getColor(ctx, R.color.primary))
                intent.putExtras(extras)
            }
        }
    }
}
