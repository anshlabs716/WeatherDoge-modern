/*
 * Copyright (C) 2019, 2026 Weather Doge contributors
 *
 * This file is part of Weather Doge.
 *
 * Weather Doge is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Weather Doge is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Weather Doge.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.versobit.weatherdoge.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A [DogeLocationApi] built on the platform's own `android.location` stack.
 *
 * This is the FOSS/F-Droid implementation: no Google Play Services, no Play Store,
 * nothing proprietary. It is what upstream issue #8 ("location broken") needs —
 * the previous selector returned a stub that was permanently DISCONNECTED, so the
 * app and the widget could never obtain a position at all.
 *
 * Contract:
 *  - [connect] immediately reports CONNECTING, emits [LocationReceiver.onConnected]
 *    once, then delivers the best fix it can (last known, otherwise a single update).
 *  - Callbacks are delivered on the main thread.
 *  - [disconnect] stops listening and returns to DISCONNECTED.
 */
class LocationManagerApi : DogeLocationApi, LocationListener {

    companion object {
        private const val TAG = "LocationManagerApi"
        /** A last known fix older than this is ignored in favour of a fresh single update. */
        private const val MAX_LAST_KNOWN_AGE_MS = 120_000L
        private const val TIMEOUT_MS = 30_000L
        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }

    private var appContext: Context? = null
    private var receiver: LocationReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentStatus: ApiStatus = ApiStatus.DISCONNECTED
    private var timeoutRunnable: Runnable? = null

    override val status: ApiStatus get() = currentStatus

    override val isAvailable: Boolean
        get() {
            val lm = locationManagerOrNull() ?: return false
            return PROVIDERS.any { provider ->
                try {
                    lm.allProviders.contains(provider)
                } catch (e: Exception) {
                    false
                }
            }
        }

    override fun configure(ctx: Context, receiver: LocationReceiver): DogeLocationApi {
        appContext = ctx.applicationContext
        this.receiver = receiver
        return this
    }

    override fun connect() {
        if (currentStatus == ApiStatus.CONNECTING || currentStatus == ApiStatus.CONNECTED) {
            return
        }
        val lm = locationManagerOrNull()
        if (lm == null || !hasPermission()) {
            currentStatus = ApiStatus.DISCONNECTED
            return
        }
        currentStatus = ApiStatus.CONNECTING
        receiver?.onConnected()

        // 1) A recent last known fix is instant and usually good enough.
        val last = bestLastKnown(lm)
        if (last != null && System.currentTimeMillis() - last.time <= MAX_LAST_KNOWN_AGE_MS) {
            deliver(last)
            return
        }

        // 2) Otherwise ask for a single fresh update.
        val provider = PROVIDERS.firstOrNull { p ->
            try {
                lm.allProviders.contains(p)
            } catch (e: Exception) {
                false
            }
        }
        if (provider == null) {
            currentStatus = ApiStatus.DISCONNECTED
            return
        }
        try {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "requestSingleUpdate denied", e)
            currentStatus = ApiStatus.DISCONNECTED
            return
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "requestSingleUpdate failed", e)
            currentStatus = ApiStatus.DISCONNECTED
            return
        }

        // Safety net: never stay in CONNECTING forever.
        val timeout = Runnable {
            if (currentStatus == ApiStatus.CONNECTING) {
                Log.w(TAG, "location request timed out")
                removeUpdates()
                currentStatus = ApiStatus.DISCONNECTED
            }
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, TIMEOUT_MS)
    }

    override fun disconnect() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        removeUpdates()
        currentStatus = ApiStatus.DISCONNECTED
    }

    // ---- LocationListener ---------------------------------------------------

    override fun onLocationChanged(location: Location) = deliver(location)

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    // ---- internals ----------------------------------------------------------

    private fun deliver(location: Location) {
        if (currentStatus != ApiStatus.CONNECTING && currentStatus != ApiStatus.CONNECTED) {
            return
        }
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        removeUpdates()
        currentStatus = ApiStatus.CONNECTED
        // Deliver on the main thread; consumers decide what to do next.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            receiver?.onLocation(location)
        } else {
            mainHandler.post { receiver?.onLocation(location) }
        }
    }

    private fun removeUpdates() {
        val lm = locationManagerOrNull() ?: return
        try {
            lm.removeUpdates(this)
        } catch (e: SecurityException) {
            // Permission was revoked between request and delivery; nothing to do.
        }
    }

    private fun locationManagerOrNull(): LocationManager? = try {
        appContext?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    } catch (e: Exception) {
        null
    }

    private fun hasPermission(): Boolean {
        val ctx = appContext ?: return false
        // Install-time permissions on pre-Marshmallow devices.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun bestLastKnown(lm: LocationManager): Location? {
        var best: Location? = null
        for (provider in PROVIDERS) {
            val fix = try {
                @Suppress("DEPRECATION")
                lm.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            } ?: continue
            if (best == null || fix.time > best.time) {
                best = fix
            }
        }
        return best
    }
}
