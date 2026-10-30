package com.versobit.weatherdoge.location

import android.content.Context

/**
 * Selects the location API implementation based on build flavor.
 * FOSS build uses a stub that never connects (user must enter location manually).
 */
object FlavoredApiSelector {
    @JvmStatic
    fun get(): DogeLocationApi = StubLocationApi

    private object StubLocationApi : DogeLocationApi {
        override val status: ApiStatus = ApiStatus.DISCONNECTED
        override val isAvailable: Boolean = false
        override fun configure(ctx: Context, receiver: LocationReceiver): DogeLocationApi = this
        override fun connect() {}
        override fun disconnect() {}
    }
}