package com.versobit.weatherdoge

import android.content.Context
import com.versobit.weatherdoge.data.WeatherRepository
import java.io.Serializable
import java.util.Date

object WeatherUtil {

    enum class Source(val key: String) {
        OPEN_WEATHER_MAP("0"),
        YAHOO("1"),
        ACCUWEATHER("2"),
        OPEN_METEO("3");

        companion object {
            @JvmStatic
            fun fromKey(key: String?): Source {
                return entries.firstOrNull { it.key == key } ?: OPEN_METEO
            }
        }
    }

    class WeatherResult(
        @JvmField val data: WeatherData?,
        @JvmField val error: Int,
        @JvmField val msg: String?,
        @JvmField val throwable: Throwable?
    ) {
        companion object {
            const val ERROR_NONE = 0
            const val ERROR_API = 1
            const val ERROR_THROWABLE = 2
        }
    }

    class WeatherData(
        @JvmField val temperature: Double,
        @JvmField val condition: String,
        @JvmField val image: String,
        @JvmField val latitude: Double,
        @JvmField val longitude: Double,
        @JvmField val place: String,
        @JvmField val time: Date,
        @JvmField val source: Source,
        @JvmField val link: String
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 2253249035716676067L
        }

        fun sanitizedImage(): String {
            return if (image == "02d") "02n" else image
        }

        override fun toString(): String {
            return "WeatherData[temp=$temperature, cond=$condition, img=$image, place=$place]"
        }
    }

    @JvmStatic
    fun getWeather(location: String, source: Source): WeatherResult {
        return getWeather(Double.MIN_VALUE, Double.MIN_VALUE, location, source)
    }

    @JvmStatic
    fun getWeather(latitude: Double, longitude: Double, source: Source): WeatherResult {
        return getWeather(latitude, longitude, null, source)
    }

    @JvmStatic
    fun getWeather(latitude: Double, longitude: Double, location: String?, source: Source): WeatherResult {
        val app = WeatherDoge.instance
        val repoSource = when (source) {
            Source.OPEN_WEATHER_MAP -> com.versobit.weatherdoge.data.WeatherSource.OPEN_WEATHER_MAP
            else -> com.versobit.weatherdoge.data.WeatherSource.OPEN_METEO
        }

        val hasCoords = latitude != Double.MIN_VALUE && longitude != Double.MIN_VALUE
        val latArg = if (hasCoords) latitude else null
        val lonArg = if (hasCoords) longitude else null

        val res = WeatherRepository.refresh(app, latArg, lonArg, location, repoSource)
        return when (res) {
            is com.versobit.weatherdoge.data.WeatherResult.Success -> {
                val d = res.data
                val legacyData = WeatherData(
                    temperature = d.temperature,
                    condition = d.condition,
                    image = if (d.image == "02d") "02n" else d.image,
                    latitude = d.latitude,
                    longitude = d.longitude,
                    place = d.place,
                    time = Date(d.time),
                    source = source,
                    link = d.link ?: ""
                )
                WeatherResult(legacyData, WeatherResult.ERROR_NONE, null, null)
            }
            is com.versobit.weatherdoge.data.WeatherResult.ApiError -> {
                WeatherResult(null, WeatherResult.ERROR_API, res.message, null)
            }
            is com.versobit.weatherdoge.data.WeatherResult.Failure -> {
                WeatherResult(null, WeatherResult.ERROR_THROWABLE, res.message, res.cause)
            }
        }
    }
}
