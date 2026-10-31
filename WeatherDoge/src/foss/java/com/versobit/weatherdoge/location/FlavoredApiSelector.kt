package com.versobit.weatherdoge.location

object FlavoredApiSelector {
    @JvmStatic
    fun get(): DogeLocationApi {
        return AndroidLocationApi()
    }
}
