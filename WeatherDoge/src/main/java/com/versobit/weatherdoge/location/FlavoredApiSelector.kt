package com.versobit.weatherdoge.location

import android.content.Context

/**
 * Selects the location API implementation for this build flavor.
 *
 * The FOSS build (the only flavor in this repository) uses the platform's own
 * `android.location` stack — no Google Play Services anywhere, which keeps the app
 * F-Droid eligible while fixing upstream issue #8 (location never worked because the
 * selector handed out a permanently disconnected stub).
 */
object FlavoredApiSelector {
    @JvmStatic
    fun get(): DogeLocationApi = LocationManagerApi()
}
