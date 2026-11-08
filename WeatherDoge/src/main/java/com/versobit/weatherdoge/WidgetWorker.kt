/*
 * Copyright (C) 2019, 2023 VersoBit
 * Copyright (C) 2026 Weather Doge contributors
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

package com.versobit.weatherdoge

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.versobit.weatherdoge.data.WeatherData
import com.versobit.weatherdoge.data.WeatherRepository
import com.versobit.weatherdoge.data.WeatherResult
import com.versobit.weatherdoge.location.ApiStatus
import com.versobit.weatherdoge.location.DogeLocationApi
import com.versobit.weatherdoge.location.FlavoredApiSelector
import com.versobit.weatherdoge.location.LocationReceiver
import java.io.IOException
import java.text.DecimalFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches weather for the home screen widgets and pushes bitmaps into their RemoteViews.
 *
 * Kotlin rewrite of the original worker. What changed beyond the language:
 *  - the legacy `WeatherUtil`/`Cache` pair is gone; everything goes through the shared
 *    [WeatherRepository] cache that the app also uses (upstream #6/#9), so a failed
 *    refresh renders the last known weather instead of an empty widget and never
 *    deletes good data,
 *  - the `CyclicBarrier` that `onLocation()` blocked on is a [CountDownLatch] that only
 *    the worker thread waits on — location callbacks now arrive on the main thread and
 *    must never block it,
 *  - the dead Google Play Services branch is gone; location always comes from the
 *    platform `LocationManagerApi` (upstream #8, no Play Services, F-Droid eligible).
 */
class WidgetWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params), LocationReceiver {

    companion object {
        private const val TAG = "WidgetWorker"

        const val TASK_ALL_TAG = TAG + "_All"
        const val TASK_MULTIPLE_TAG = TAG + "_Multiple"
        const val TASK_ONE_TAG = TAG + "_One"

        const val ACTION = "action"
        const val ACTION_REFRESH_ALL = "refresh_all"
        const val ACTION_REFRESH_MULTIPLE = "refresh_multiple"
        const val ACTION_REFRESH_ONE = "refresh_one"
        const val EXTRA_WIDGET_ID = "widget_id"

        private const val WIDGETS_NOTIFICATION_GROUP_ID = "Widgets"
        private const val WIDGET_PERMISSION_REQ_NOTIFICATION_CHANNEL_ID =
            "WidgetLocationPermissionRequired"
        const val PERMISSION_NOTIFICATION_ID = 410

        /** How long the worker waits on a location fix before giving up. */
        private const val LOCATION_TIMEOUT_SECONDS = 15L

        @JvmStatic
        fun enqueueOnceAll() = enqueueOnceNow(ACTION_REFRESH_ALL, null)

        @JvmStatic
        fun enqueueOnceMultiple(appWidgetIds: IntArray?) =
            enqueueOnceNow(ACTION_REFRESH_MULTIPLE, appWidgetIds)

        @JvmStatic
        fun enqueueOnceSingle(appWidgetId: Int) =
            enqueueOnceNow(ACTION_REFRESH_ONE, intArrayOf(appWidgetId))

        private fun enqueueOnceNow(action: String, appWidgetIds: IntArray?) {
            val dataBuilder = Data.Builder().putString(ACTION, action)
            val workRequestBuilder = OneTimeWorkRequest.Builder(WidgetWorker::class.java)
            when (action) {
                ACTION_REFRESH_ALL -> workRequestBuilder.addTag(TASK_ALL_TAG)
                ACTION_REFRESH_MULTIPLE -> {
                    dataBuilder.putIntArray(EXTRA_WIDGET_ID, appWidgetIds ?: IntArray(0))
                    workRequestBuilder.addTag(TASK_MULTIPLE_TAG)
                }
                ACTION_REFRESH_ONE -> {
                    dataBuilder.putInt(EXTRA_WIDGET_ID, appWidgetIds?.getOrNull(0) ?: 0)
                    workRequestBuilder.addTag(TASK_ONE_TAG)
                }
            }
            workRequestBuilder.setInputData(dataBuilder.build())
            WorkManager.getInstance().enqueue(workRequestBuilder.build())
        }
    }

    private val locationRef = AtomicReference<Location?>()
    @Volatile
    private var locationLatch = CountDownLatch(1)

    private lateinit var widgetManager: AppWidgetManager
    private lateinit var widgets: IntArray
    private lateinit var pIntents: Array<PendingIntent>

    override fun doWork(): ListenableWorker.Result {
        val app = applicationContext

        // Fresh synchronization state for this run.
        locationRef.set(null)
        locationLatch = CountDownLatch(1)
        widgetManager = AppWidgetManager.getInstance(app)

        widgets = when (inputData.getString(ACTION)) {
            ACTION_REFRESH_ALL ->
                widgetManager.getAppWidgetIds(ComponentName(app, WidgetProvider::class.java))
            ACTION_REFRESH_MULTIPLE ->
                inputData.getIntArray(EXTRA_WIDGET_ID) ?: IntArray(0)
            ACTION_REFRESH_ONE ->
                intArrayOf(inputData.getInt(EXTRA_WIDGET_ID, 0))
            else -> {
                Log.wtf(TAG, "Unknown action: ${inputData.getString(ACTION)}")
                return ListenableWorker.Result.failure()
            }
        }

        if (widgets.isEmpty()) {
            Log.w(TAG, "WidgetWorker tasked with 0 widgets")
            return ListenableWorker.Result.failure()
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val forceMetric = prefs.getBoolean(OptionsActivity.PREF_FORCE_METRIC, false)
        val forceLocation = prefs.getString(OptionsActivity.PREF_FORCE_LOCATION, "") ?: ""
        val weatherSource = WeatherRepository.source(app)
        val tapToRefresh = prefs.getBoolean(OptionsActivity.PREF_WIDGET_TAP_TO_REFRESH, false)
        val showWowText = prefs.getBoolean(OptionsActivity.PREF_WIDGET_SHOW_WOWTEXT, true)
        val showDate = prefs.getBoolean(OptionsActivity.PREF_WIDGET_SHOW_DATE, false)
        val backgroundFix = prefs.getBoolean(OptionsActivity.PREF_WIDGET_BACKGROUND_FIX, false)

        pIntents = Array(widgets.size) { i ->
            if (tapToRefresh) {
                // Worth noting that the data URI in this pending intent is used only
                // to distinguish it from other widget intents. The extra data is what's
                // processed by the WidgetRefreshReceiver.
                val dataUri = Uri.parse(
                    String.format(Locale.US, "widget://%s/%d", BuildConfig.APPLICATION_ID, widgets[i])
                )
                PendingIntent.getBroadcast(
                    app,
                    0,
                    Intent(app, WidgetRefreshReceiver::class.java)
                        .setAction(ACTION_REFRESH_ONE)
                        .setData(dataUri)
                        .putExtra(EXTRA_WIDGET_ID, widgets[i]),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getActivity(
                    app,
                    0,
                    Intent(app, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }

        setStatus(app.getString(R.string.loading), true)

        // What to ask the provider for: either a constant place or a coordinate fix.
        var queryLat: Double? = null
        var queryLon: Double? = null
        var queryPlace: String? = null
        var locationName = ""
        var data: WeatherData?

        if (forceLocation.isNotBlank()) {
            queryPlace = forceLocation
            locationName = forceLocation
            data = WeatherRepository.fresh(app, null, null, forceLocation)
        } else {
            val locationApi: DogeLocationApi = FlavoredApiSelector.get().configure(app, this)

            if (!locationApi.isAvailable) {
                showError(R.string.widget_error_location_settings)
                return ListenableWorker.Result.failure()
            }
            if (ContextCompat.checkSelfPermission(app, WeatherDoge.LOCATION_PERMISSION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                showError(R.string.widget_error_permission)
                showPermissionNotification()
                return ListenableWorker.Result.failure()
            }

            locationApi.connect()
            val arrived = try {
                locationLatch.await(LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.wtf(TAG, ex)
                locationApi.disconnect()
                showError(R.string.widget_error_unknown)
                return ListenableWorker.Result.retry()
            }
            if (!arrived) {
                Log.wtf(TAG, "Timed out waiting for a location fix")
                locationApi.disconnect()
                showError(R.string.widget_error_unknown)
                return ListenableWorker.Result.retry()
            }
            if (locationApi.status != ApiStatus.CONNECTED) {
                showError(R.string.widget_error_location_settings)
                locationApi.disconnect()
                return ListenableWorker.Result.retry()
            }

            val location = locationRef.get()
            locationApi.disconnect()
            if (location == null) {
                Log.e(TAG, "Unable to retrieve location. (null)")
                showError(R.string.widget_error_location_settings)
                return ListenableWorker.Result.retry()
            }

            queryLat = location.latitude
            queryLon = location.longitude
            data = WeatherRepository.fresh(app, queryLat, queryLon, null)

            try {
                val addresses: List<Address?>? =
                    @Suppress("DEPRECATION")
                    Geocoder(app).getFromLocation(queryLat, queryLon, 1)
                val locality = addresses?.firstOrNull()?.locality
                if (!locality.isNullOrEmpty()) {
                    locationName = locality
                }
            } catch (ex: IOException) {
                Log.wtf(TAG, ex)
                showError(R.string.widget_error_geocoder)
                return ListenableWorker.Result.retry()
            }
        }

        if (data == null || data.source != weatherSource) {
            // The repository persists successful fetches itself and leaves the cache
            // untouched on failure, so a failure here still leaves us with data.
            when (val result = WeatherRepository.refresh(app, queryLat, queryLon, queryPlace, weatherSource)) {
                is WeatherResult.Success -> data = result.data
                else -> {
                    val fallback = WeatherRepository.current(app)
                    if (fallback != null) {
                        // Offline or rate limited: render the last known weather.
                        Log.w(TAG, "Refresh failed, rendering cached weather: $result")
                        data = fallback
                    } else when (result) {
                        is WeatherResult.ApiError -> {
                            Log.e(TAG, "ERROR_API: ${result.message}")
                            showError(R.string.widget_error_api)
                            return ListenableWorker.Result.retry()
                        }
                        is WeatherResult.Failure -> {
                            Log.e(TAG, "ERROR_THROWABLE: ${result.message}", result.cause)
                            showError(R.string.widget_error_weather_util)
                            return ListenableWorker.Result.retry()
                        }
                        else -> {
                            Log.wtf(TAG, "Unhandled WeatherResult: $result")
                            showError(R.string.widget_error_unknown)
                            return ListenableWorker.Result.retry()
                        }
                    }
                }
            }
        }

        val weather = data ?: run {
            Log.wtf(TAG, "No weather data available for the widgets")
            showError(R.string.widget_error_unknown)
            return ListenableWorker.Result.retry()
        }

        var temp = weather.temperature
        if (UnitLocale.getDefault() == UnitLocale.IMPERIAL && !forceMetric) {
            temp = temp * 1.8 + 32.0 // F
        }
        temp = Math.round(temp).toDouble()
        val tempFormat = DecimalFormat().apply {
            maximumFractionDigits = 0
            isDecimalSeparatorAlwaysShown = false
            isGroupingUsed = false
        }
        var formattedTemp = tempFormat.format(temp) + "°"

        val dogeImg = WeatherDoge.dogeSelect(weather.image)
        val skyImg = WeatherDoge.skySelect(weather.image)

        if (locationName.isEmpty()) {
            locationName = weather.place
        }

        // Generate the common text bitmaps
        formattedTemp = if (formattedTemp.isEmpty()) " " else formattedTemp
        val condition = if (weather.condition.isEmpty()) " " else weather.condition
        locationName = if (locationName.isEmpty()) " " else locationName
        val now = Date()
        val time = StringBuilder()
        if (showDate) {
            time.append(DateFormat.format(" MMM d ", now))
                .append(app.getString(R.string.widget_at))
        }
        time.append(" ").append(DateFormat.getTimeFormat(app).format(now)).append(" ")
        val textBitmaps = WidgetProvider.getTextBitmaps(
            app, formattedTemp, condition, locationName, time.toString()
        )

        for (i in widgets.indices) {
            val views = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.widget)
            var sky: Bitmap? = null
            var wowLayer: Bitmap? = null
            var failed = false

            views.setViewVisibility(R.id.widget_loading, View.GONE)
            views.setOnClickPendingIntent(R.id.widget_root, pIntents[i])
            views.setImageViewResource(R.id.widget_dogeimg, dogeImg)
            views.setImageViewBitmap(R.id.widget_tempimg, textBitmaps[0])
            views.setImageViewBitmap(R.id.widget_descimg, textBitmaps[1])
            views.setImageViewBitmap(R.id.widget_locationimg, textBitmaps[2])
            views.setImageViewBitmap(R.id.widget_last_updated_img, textBitmaps[3])

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                try {
                    val options = widgetManager.getAppWidgetOptions(widgets[i])
                    if (!backgroundFix) {
                        sky = WidgetProvider.getSkyBitmap(app, options, skyImg)
                        if (sky != null) {
                            views.setImageViewBitmap(R.id.widget_sky, sky)
                            views.setInt(R.id.widget_sky, "setVisibility", View.VISIBLE)
                            views.setInt(R.id.widget_sky_compat, "setVisibility", View.GONE)
                        } else {
                            failed = true
                        }
                    }
                    if (showWowText) {
                        wowLayer = WidgetProvider.getWowLayer(
                            app, options, weather.image, weather.temperature.toInt()
                        )
                        views.setImageViewBitmap(R.id.widget_wowlayer, wowLayer)
                    } else {
                        views.setImageViewBitmap(R.id.widget_wowlayer, null)
                    }
                } catch (ex: Exception) {
                    Log.wtf(TAG, ex)
                    failed = true
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN || failed || backgroundFix) {
                views.setInt(R.id.widget_sky, "setVisibility", View.GONE)
                views.setInt(R.id.widget_sky_compat, "setVisibility", View.VISIBLE)
                views.setImageViewResource(R.id.widget_sky_compat, skyImg)
            }

            widgetManager.updateAppWidget(widgets[i], views)

            sky?.takeIf { !it.isRecycled }?.recycle()
            wowLayer?.takeIf { !it.isRecycled }?.recycle()
        }

        for (b in textBitmaps) {
            if (b != null && !b.isRecycled) {
                b.recycle()
            }
        }

        return ListenableWorker.Result.success()
    }

    // ---- location -------------------------------------------------------------

    override fun onConnected() {
        // Nothing to do: a fix (or a timeout) is what unblocks the worker.
    }

    /** Delivered on the main thread — record the fix and wake the worker, never block. */
    override fun onLocation(location: Location) {
        locationRef.set(location)
        locationLatch.countDown()
    }

    // ---- widget status --------------------------------------------------------

    private fun showError(resId: Int) {
        val error = applicationContext.getString(resId)
        Log.e(TAG, error)
        setStatus(error)
    }

    private fun setStatus(status: String) = setStatus(status, false)

    private fun setStatus(status: String, isLoading: Boolean) {
        val loading = WidgetProvider.getStatusBitmap(applicationContext, status)
        for (i in widgets.indices) {
            val views = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.widget)
            views.setImageViewBitmap(R.id.widget_locationimg, loading)
            views.setImageViewBitmap(R.id.widget_last_updated_img, null)
            views.setOnClickPendingIntent(R.id.widget_root, pIntents[i])
            views.setViewVisibility(
                R.id.widget_loading, if (isLoading) View.VISIBLE else View.GONE
            )
            widgetManager.partiallyUpdateAppWidget(widgets[i], views)
        }
        loading.recycle()
    }

    // ---- permission notification ----------------------------------------------

    private fun createNotificationGroupAndChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val app = applicationContext
            val groupName = app.getString(R.string.notification_group_widgets_name)
            val group = NotificationChannelGroup(WIDGETS_NOTIFICATION_GROUP_ID, groupName)

            val channelName = app.getString(R.string.notification_channel_widgets_location_name)
            val channelDesc = app.getString(R.string.notification_channel_widgets_location_desc)
            val channel = NotificationChannel(
                WIDGET_PERMISSION_REQ_NOTIFICATION_CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = channelDesc
            channel.group = WIDGETS_NOTIFICATION_GROUP_ID

            val notificationManager =
                app.getSystemService(NotificationManager::class.java) ?: return
            notificationManager.createNotificationChannelGroup(group)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showPermissionNotification() {
        try {
            createNotificationGroupAndChannel()
            val app = applicationContext
            val intent = PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val title = app.getString(R.string.widget_notification_permission_title)
            val body = app.getString(R.string.widget_notification_permission_body)
            val builder = NotificationCompat.Builder(
                app, WIDGET_PERMISSION_REQ_NOTIFICATION_CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_doge_circle_notif) // TODO: Needs a real icon
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(intent)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .setBigContentTitle(title)
                        .bigText(body)
                )
            val manager =
                app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(PERMISSION_NOTIFICATION_ID, builder.build())
        } catch (ex: Exception) {
            // The permission error is already on the widget; a failed notification is noise.
            Log.w(TAG, "Unable to post permission notification", ex)
        }
    }
}
