package com.versobit.weatherdoge.data

enum class WeatherSource(val key: String, val title: String) {
    OPEN_METEO("open_meteo", "Open-Meteo"),
    OPEN_WEATHER_MAP("open_weather_map", "OpenWeatherMap");

    companion object {
        fun fromKey(key: String?): WeatherSource {
            return entries.firstOrNull { it.key == key } ?: OPEN_METEO
        }
    }
}
