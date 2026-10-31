package com.versobit.weatherdoge

import android.content.Context
import com.versobit.weatherdoge.data.WeatherRepository
import java.util.Date

object Cache {

    @JvmStatic
    fun getWeatherData(ctx: Context, latitude: Double, longitude: Double): WeatherUtil.WeatherData? {
        val freshData = WeatherRepository.fresh(ctx, latitude, longitude, null)
        return freshData?.let { toLegacy(it) }
    }

    @JvmStatic
    fun getWeatherData(ctx: Context, location: String): WeatherUtil.WeatherData? {
        val freshData = WeatherRepository.fresh(ctx, null, null, location)
        return freshData?.let { toLegacy(it) }
    }

    @JvmStatic
    fun loadWeatherData(ctx: Context): WeatherUtil.WeatherData? {
        val current = WeatherRepository.current(ctx)
        return current?.let { toLegacy(it) }
    }

    @JvmStatic
    fun isWeatherDataExpired(data: WeatherUtil.WeatherData): Boolean {
        return (data.time.time + 10 * 60 * 1000L) < System.currentTimeMillis()
    }

    @JvmStatic
    fun putWeatherData(ctx: Context, data: WeatherUtil.WeatherData) {
        val modern = com.versobit.weatherdoge.data.WeatherData(
            temperature = data.temperature,
            condition = data.condition,
            image = data.image,
            latitude = data.latitude,
            longitude = data.longitude,
            place = data.place,
            time = data.time.time,
            source = when (data.source) {
                WeatherUtil.Source.OPEN_WEATHER_MAP -> com.versobit.weatherdoge.data.WeatherSource.OPEN_WEATHER_MAP
                else -> com.versobit.weatherdoge.data.WeatherSource.OPEN_METEO
            },
            link = data.link
        )
        WeatherRepository.rememberLocation(ctx, modern)
    }

    private fun toLegacy(modern: com.versobit.weatherdoge.data.WeatherData): WeatherUtil.WeatherData {
        val src = when (modern.source) {
            com.versobit.weatherdoge.data.WeatherSource.OPEN_WEATHER_MAP -> WeatherUtil.Source.OPEN_WEATHER_MAP
            else -> WeatherUtil.Source.OPEN_WEATHER_MAP
        }
        return WeatherUtil.WeatherData(
            modern.temperature,
            modern.condition,
            modern.image,
            modern.latitude,
            modern.longitude,
            modern.place,
            Date(modern.time),
            src,
            modern.link ?: ""
        )
    }
}
