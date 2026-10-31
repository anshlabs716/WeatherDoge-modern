package com.versobit.weatherdoge.data

data class WeatherData(
    val temperature: Double,
    val condition: String,
    val image: String,
    val latitude: Double,
    val longitude: Double,
    val place: String,
    val time: Long,
    val source: WeatherSource,
    val link: String? = null,
    val timezone: String? = null,
    val daily: List<DailyForecast> = emptyList()
)
