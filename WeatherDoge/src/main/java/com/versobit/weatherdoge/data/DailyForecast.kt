package com.versobit.weatherdoge.data

data class DailyForecast(
    val epochDay: Long,
    val highC: Double?,
    val lowC: Double?,
    val image: String,
    val condition: String,
    val precipProbability: Int? = null
)
