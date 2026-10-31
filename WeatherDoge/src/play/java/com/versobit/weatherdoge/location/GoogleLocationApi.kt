package com.versobit.weatherdoge.location

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsResponse

class GoogleLocationApi internal constructor() : DogeLocationApi {

    companion object {
        private val TAG = GoogleLocationApi::class.java.simpleName
        private const val REQUEST_PLAY_ERR_DIAG = 52000000
        private const val REQUEST_PLAY_CONN_FAIL_RES = 3643
        private const val UPDATE_INTERVAL = 5000L
        private const val FASTEST_UPDATE_INTERVAL = 1000L
        private const val DELAY_BETWEEN_FAIL_DIAG = 5000L
    }

    private var ctx: Context? = null
    private var receiver: LocationReceiver? = null
    private var client: FusedLocationProviderClient? = null
    private var locationRequest: LocationRequest? = null
    private var locationSettingsRequest: LocationSettingsRequest? = null
    override var status: ApiStatus = ApiStatus.DISCONNECTED
        private set
    private var lastFailDiag = -DELAY_BETWEEN_FAIL_DIAG - 1

    private val locationSettingsSuccessCallback = { _: LocationSettingsResponse ->
        try {
            val rec = receiver
            val cl = client
            val req = locationRequest
            if (rec != null && cl != null && req != null) {
                cl.requestLocationUpdates(req, locationCallback, null)
                status = ApiStatus.CONNECTED
                Log.d(TAG, "Connected")
                rec.onConnected()
            } else {
                Log.e(TAG, "Location services connected but client/receiver unavailable")
                disconnect()
            }
        } catch (ex: SecurityException) {
            Log.wtf(TAG, ex)
            disconnect()
        }
    }

    private val locationSettingsFailureCallback = { ex: Exception ->
        Log.w(TAG, "Connection to location API failed")
        status = ApiStatus.DISCONNECTED
        val context = ctx
        if (context is Activity && ex is ResolvableApiException) {
            if (SystemClock.elapsedRealtime() >= lastFailDiag + DELAY_BETWEEN_FAIL_DIAG) {
                try {
                    ex.startResolutionForResult(context, REQUEST_PLAY_CONN_FAIL_RES)
                    lastFailDiag = SystemClock.elapsedRealtime()
                } catch (sendEx: IntentSender.SendIntentException) {
                    Log.e(TAG, sendEx.message, sendEx)
                }
            }
        } else if (context != null) {
            Toast.makeText(context, "Connection to location API failed.", Toast.LENGTH_SHORT).show()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            Log.d(TAG, "LocationCallback received")
            val loc = locationResult.lastLocation
            if (loc == null) {
                Log.e(TAG, "LocationCallback was provided a null LocationResult")
                return
            }
            val rec = receiver
            if (rec != null) {
                rec.onLocation(loc)
            } else {
                Log.e(TAG, "No receiver is available to the LocationCallback")
                disconnect()
            }
        }
    }

    override fun configure(ctx: Context, receiver: LocationReceiver): DogeLocationApi {
        this.ctx = ctx
        this.receiver = receiver
        val req = LocationRequest.Builder(LocationRequest.PRIORITY_LOW_POWER, UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL)
            .build()
        locationRequest = req
        locationSettingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(req)
            .build()
        Log.d(TAG, "Configured")
        return this
    }

    @SuppressLint("MissingPermission")
    override fun connect() {
        Log.d(TAG, "Connecting...")
        if (status != ApiStatus.DISCONNECTED) {
            Log.d(TAG, "Already connected")
            return
        }
        val context = ctx ?: return
        val settingsReq = locationSettingsRequest ?: return
        status = ApiStatus.CONNECTING
        client = LocationServices.getFusedLocationProviderClient(context)
        val task = LocationServices.getSettingsClient(context).checkLocationSettings(settingsReq)
        task.addOnSuccessListener(locationSettingsSuccessCallback)
        task.addOnFailureListener(locationSettingsFailureCallback)
    }

    override fun disconnect() {
        client?.removeLocationUpdates(locationCallback)
        client = null
        status = ApiStatus.DISCONNECTED
        Log.d(TAG, "Disconnected")
    }

    override val isAvailable: Boolean
        get() {
            val context = ctx ?: return false
            val apiAvail = GoogleApiAvailability.getInstance()
            val result = apiAvail.isGooglePlayServicesAvailable(context)
            if (result == ConnectionResult.SUCCESS) {
                return true
            }
            if (context is Activity) {
                val errorDialog = apiAvail.getErrorDialog(context, result, REQUEST_PLAY_ERR_DIAG)
                if (errorDialog != null && !context.isFinishing) {
                    errorDialog.show()
                }
            } else {
                apiAvail.showErrorNotification(context, result)
            }
            return false
        }
}
