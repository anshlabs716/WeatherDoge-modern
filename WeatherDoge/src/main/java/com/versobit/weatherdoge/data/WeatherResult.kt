package com.versobit.weatherdoge.data

sealed class WeatherResult {
    data class Success(val data: WeatherData) : WeatherResult()
    data class Failure(val message: String, val cause: Throwable? = null) : WeatherResult()
    data class ApiError(val message: String, val code: Int? = null) : WeatherResult()
}
