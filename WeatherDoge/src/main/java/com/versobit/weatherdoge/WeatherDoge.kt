package com.versobit.weatherdoge

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.core.content.ContextCompat
import com.versobit.weatherdoge.ui.sound.SoundManager
import java.util.Random

class WeatherDoge : Application() {

    val soundManager: SoundManager by lazy { SoundManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false)
        PreferenceManager.setDefaultValues(this, R.xml.pref_app, false)
        PreferenceManager.setDefaultValues(this, R.xml.pref_widget, false)
    }

    override fun onTerminate() {
        super.onTerminate()
        soundManager.release()
    }

    companion object {
        lateinit var instance: WeatherDoge
            private set

        @JvmField
        val LOCATION_PERMISSION =
            if (BuildConfig.FLAVOR == BuildConfig.FLAVOR_PLAY)
                Manifest.permission.ACCESS_COARSE_LOCATION
            else
                Manifest.permission.ACCESS_FINE_LOCATION

        private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
        private const val EXTRA_CUSTOM_TABS_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"

        @JvmStatic
        fun skySelect(icon: String): Int {
            if (icon.length < 3) return R.drawable.sky_01d
            val img = icon.substring(0, 2).toIntOrNull() ?: 1
            val day = icon.getOrNull(2) == 'd'
            return when (img) {
                1 -> if (day) R.drawable.sky_01d else R.drawable.sky_01n
                2 -> if (day) R.drawable.sky_02d else R.drawable.sky_02n
                3 -> if (day) R.drawable.sky_03d else R.drawable.sky_03n
                4 -> if (day) R.drawable.sky_04d else R.drawable.sky_04n
                9 -> if (day) R.drawable.sky_09d else R.drawable.sky_09n
                10 -> if (day) R.drawable.sky_10d else R.drawable.sky_09n
                11 -> if (day) R.drawable.sky_11d else R.drawable.sky_11n
                13 -> if (day) R.drawable.sky_13d else R.drawable.sky_13n
                50 -> if (day) R.drawable.sky_50d else R.drawable.sky_50n
                else -> R.drawable.sky_01d
            }
        }

        @JvmStatic
        fun dogeSelect(icon: String): Int {
            if (icon.length < 3) return R.drawable.doge_01d
            val img = icon.substring(0, 2).toIntOrNull() ?: 1
            val day = icon.getOrNull(2) == 'd'
            return when (img) {
                1 -> if (day) R.drawable.doge_01d else R.drawable.doge_01n
                2 -> if (day) R.drawable.doge_02d else R.drawable.doge_02n
                3 -> if (day) R.drawable.doge_03d else R.drawable.doge_03n
                4 -> R.drawable.doge_04
                9 -> R.drawable.doge_09
                10 -> R.drawable.doge_10
                11 -> R.drawable.doge_11
                13 -> R.drawable.doge_13
                50 -> R.drawable.doge_50
                else -> R.drawable.doge_01d
            }
        }

        @JvmStatic
        fun isSnowing(resId: Int): Boolean {
            return when (resId) {
                R.drawable.sky_13d, R.drawable.sky_13n, R.drawable.doge_13 -> true
                else -> false
            }
        }

        @JvmStatic
        fun getTempAdjectives(temp: Int): Int {
            return when {
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
        }

        @JvmStatic
        fun getBgAdjectives(bg: String): Int {
            if (bg.length < 3) return R.array.bg_01d
            val img = bg.substring(0, 2).toIntOrNull() ?: 1
            val day = bg.getOrNull(2) == 'd'
            return when (img) {
                1 -> if (day) R.array.bg_01d else R.array.bg_01n
                2 -> if (day) R.array.bg_02d else R.array.bg_02d
                3 -> if (day) R.array.bg_03d else R.array.bg_03n
                4 -> R.array.bg_04
                9 -> if (day) R.array.bg_09d else R.array.bg_09n
                10 -> if (day) R.array.bg_10d else R.array.bg_10n
                11 -> if (day) R.array.bg_11d else R.array.bg_11n
                13 -> if (day) R.array.bg_13d else R.array.bg_13n
                50 -> R.array.bg_50
                else -> R.array.bg_01d
            }
        }

        @JvmStatic
        fun getDogeism(wows: Array<String>, dogefixes: Array<String>, weatherAdjectives: Array<String>): String {
            return getDogeism(Random(), wows, dogefixes, weatherAdjectives)
        }

        @JvmStatic
        fun getDogeism(r: Random, wows: Array<String>, dogefixes: Array<String>, weatherAdjectives: Array<String>): String {
            if (weatherAdjectives.isEmpty() && wows.isEmpty()) return "wow"
            val total = weatherAdjectives.size + wows.size
            val wowOrNot = r.nextInt(total)
            if (wowOrNot >= weatherAdjectives.size) {
                return wows.getOrElse(wowOrNot - weatherAdjectives.size) { "wow" }
            }
            if (dogefixes.isEmpty() || weatherAdjectives.isEmpty()) return "such weather"
            val prefix = dogefixes[r.nextInt(dogefixes.size)]
            val adj = weatherAdjectives[r.nextInt(weatherAdjectives.size)]
            return try {
                String.format(prefix, adj)
            } catch (e: Exception) {
                "$prefix $adj"
            }
        }

        @JvmStatic
        fun applyChromeCustomTab(ctx: Context, intent: Intent) {
            val extras = Bundle()
            extras.putBinder(EXTRA_CUSTOM_TABS_SESSION, null)
            extras.putInt(EXTRA_CUSTOM_TABS_TOOLBAR_COLOR, ContextCompat.getColor(ctx, R.color.primary))
            intent.putExtras(extras)
        }
    }
}
