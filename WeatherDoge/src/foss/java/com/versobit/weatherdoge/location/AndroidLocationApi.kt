package com.versobit.weatherdoge.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import java.math.BigDecimal
import java.math.RoundingMode

class AndroidLocationApi internal constructor() : DogeLocationApi, LocationListener {

    private var ctx: Context? = null
    private var receiver: LocationReceiver? = null
    private var locationManager: LocationManager? = null
    override var status: ApiStatus = ApiStatus.DISCONNECTED
        private set

    override fun configure(ctx: Context, receiver: LocationReceiver): DogeLocationApi {
        this.ctx = ctx
        this.receiver = receiver
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        Log.d(TAG, "Configured")
        return this
    }

    @SuppressLint("MissingPermission")
    override fun connect() {
        Log.d(TAG, "Connecting...")
        status = ApiStatus.CONNECTING
        val lm = locationManager
        if (lm == null) {
            status = ApiStatus.DISCONNECTED
            return
        }
        val provider = getBestProvider()
        try {
            Log.d(TAG, "Requesting location updates with provider: $provider")
            lm.requestLocationUpdates(provider, 0L, 0f, this)
        } catch (ex: SecurityException) {
            status = ApiStatus.DISCONNECTED
            Log.w(TAG, "SecurityException requesting location updates", ex)
            return
        } catch (ex: Exception) {
            status = ApiStatus.DISCONNECTED
            Log.w(TAG, "Exception requesting location updates", ex)
            return
        }

        status = ApiStatus.CONNECTED
        Log.d(TAG, "Connected")
        receiver?.onConnected()

        try {
            val last = lm.getLastKnownLocation(provider)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (last != null) {
                onLocationChanged(last)
            }
        } catch (ex: SecurityException) {
            Log.w(TAG, "SecurityException fetching last known location", ex)
        } catch (ex: Exception) {
            Log.w(TAG, "Exception fetching last known location", ex)
        }
    }

    override fun disconnect() {
        status = ApiStatus.DISCONNECTED
        try {
            Log.d(TAG, "Removing location updates")
            locationManager?.removeUpdates(this)
        } catch (ex: Exception) {
            Log.w(TAG, "Error removing location updates", ex)
        }
        Log.d(TAG, "Disconnected")
    }

    override val isAvailable: Boolean
        get() {
            val lm = locationManager ?: ctx?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            return lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
        }

    private fun getBestProvider(): String {
        val lm = locationManager ?: return LocationManager.NETWORK_PROVIDER
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return LocationManager.NETWORK_PROVIDER
        }
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER
        }
        return LocationManager.NETWORK_PROVIDER
    }

    private fun fuzzLocation(location: Location?): Location? {
        if (location == null) return null
        val lat = BigDecimal.valueOf(location.latitude).setScale(COORD_FUZZ, RoundingMode.DOWN).toDouble()
        val lon = BigDecimal.valueOf(location.longitude).setScale(COORD_FUZZ, RoundingMode.DOWN).toDouble()
        location.latitude = lat
        location.longitude = lon
        location.accuracy = LOC_ACCURACY.toFloat()
        return location
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onLocationChanged(location: Location) {
        Log.d(TAG, "Location changed")
        val fuzzed = fuzzLocation(location) ?: return
        receiver?.onLocation(fuzzed)
    }

    companion object {
        private val TAG = AndroidLocationApi::class.java.simpleName
        private const val COORD_FUZZ = 3
        private const val LOC_ACCURACY = 110
    }
}
