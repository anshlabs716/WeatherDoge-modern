package com.versobit.weatherdoge

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.versobit.weatherdoge.data.WeatherRepository
import com.versobit.weatherdoge.location.ApiStatus
import com.versobit.weatherdoge.location.DogeLocationApi
import com.versobit.weatherdoge.location.FlavoredApiSelector
import com.versobit.weatherdoge.location.LocationReceiver
import java.io.IOException
import java.text.DecimalFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class WidgetWorker(
    ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params), LocationReceiver {

    private val locationRef = AtomicReference<Location?>()
    private val locationBarrier = CyclicBarrier(2)

    private var widgetManager: AppWidgetManager? = null
    private var widgets: IntArray = intArrayOf()
    private var pIntents: Array<PendingIntent?> = emptyArray()

    override fun doWork(): Result {
        val appCtx = applicationContext
        locationBarrier.reset()
        widgetManager = AppWidgetManager.getInstance(appCtx)
        val action = inputData.getString(ACTION)

        widgets = when (action) {
            ACTION_REFRESH_ALL -> widgetManager?.getAppWidgetIds(ComponentName(appCtx, WidgetProvider::class.java)) ?: intArrayOf()
            ACTION_REFRESH_MULTIPLE -> inputData.getIntArray(EXTRA_WIDGET_ID) ?: intArrayOf()
            ACTION_REFRESH_ONE -> intArrayOf(inputData.getInt(EXTRA_WIDGET_ID, 0))
            else -> {
                Log.w(TAG, "Unknown action: $action")
                return Result.failure()
            }
        }

        if (widgets.isEmpty()) {
            Log.w(TAG, "WidgetWorker tasked with 0 widgets")
            return Result.failure()
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(appCtx)
        val forceMetric = prefs.getBoolean(OptionsActivity.PREF_FORCE_METRIC, false)
        val forceLocation = prefs.getString(OptionsActivity.PREF_FORCE_LOCATION, "") ?: ""
        val weatherSource = when (prefs.getString(OptionsActivity.PREF_WEATHER_SOURCE, "0")) {
            "1" -> WeatherUtil.Source.OPEN_WEATHER_MAP
            else -> WeatherUtil.Source.OPEN_METEO
        }
        val tapToRefresh = prefs.getBoolean(OptionsActivity.PREF_WIDGET_TAP_TO_REFRESH, false)
        val showWowText = prefs.getBoolean(OptionsActivity.PREF_WIDGET_SHOW_WOWTEXT, true)
        val showDate = prefs.getBoolean(OptionsActivity.PREF_WIDGET_SHOW_DATE, false)
        val backgroundFix = prefs.getBoolean(OptionsActivity.PREF_WIDGET_BACKGROUND_FIX, false)

        pIntents = Array(widgets.size) { i ->
            if (tapToRefresh) {
                val dataUri = Uri.parse("widget://${BuildConfig.APPLICATION_ID}/${widgets[i]}")
                PendingIntent.getBroadcast(
                    appCtx,
                    widgets[i],
                    Intent(appCtx, WidgetRefreshReceiver::class.java)
                        .setAction(ACTION_REFRESH_ONE)
                        .setData(dataUri)
                        .putExtra(EXTRA_WIDGET_ID, widgets[i]),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getActivity(
                    appCtx,
                    widgets[i],
                    Intent(appCtx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        }

        setStatus(appCtx.getString(R.string.loading), true)

        var data: WeatherUtil.WeatherData? = null
        var locationName = ""

        // Check if we already have fresh cached data
        val cached = Cache.loadWeatherData(appCtx)
        if (cached != null && !Cache.isWeatherDataExpired(cached)) {
            data = cached
        }

        if (data == null) {
            if (forceLocation.isNotBlank()) {
                locationName = forceLocation
                val result = WeatherUtil.getWeather(forceLocation, weatherSource)
                if (result.error == WeatherUtil.WeatherResult.ERROR_NONE) {
                    data = result.data
                }
            } else {
                // Try to acquire location or use last known location
                val lastCoords = WeatherRepository.lastKnownLocation(appCtx)
                var locLat: Double? = lastCoords?.first
                var locLon: Double? = lastCoords?.second

                if (ContextCompat.checkSelfPermission(appCtx, WeatherDoge.LOCATION_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                    val locationApi: DogeLocationApi = FlavoredApiSelector.get()
                    locationApi.configure(appCtx, this)
                    if (locationApi.isAvailable) {
                        locationApi.connect()
                        try {
                            locationBarrier.await(10, TimeUnit.SECONDS)
                        } catch (ex: Exception) {
                            Log.w(TAG, "Location barrier wait error", ex)
                        }
                        locationApi.disconnect()
                        val location = locationRef.get()
                        if (location != null) {
                            locLat = location.latitude
                            locLon = location.longitude
                        }
                    }
                }

                if (locLat != null && locLon != null) {
                    val result = WeatherUtil.getWeather(locLat, locLon, weatherSource)
                    if (result.error == WeatherUtil.WeatherResult.ERROR_NONE) {
                        data = result.data
                    }
                    if (Geocoder.isPresent()) {
                        try {
                            @Suppress("DEPRECATION")
                            val addresses = Geocoder(appCtx).getFromLocation(locLat, locLon, 1)
                            if (!addresses.isNullOrEmpty()) {
                                locationName = addresses[0].locality ?: ""
                            }
                        } catch (ex: Exception) {
                            Log.w(TAG, "Geocoder error", ex)
                        }
                    }
                }
            }
        }

        // If network fetch failed but we have any cached data at all, render it!
        if (data == null && cached != null) {
            data = cached
        }

        if (data == null) {
            showError(R.string.widget_error_location)
            return Result.retry()
        }

        Cache.putWeatherData(appCtx, data)

        var temp = data.temperature
        if (UnitLocale.getDefault() == UnitLocale.IMPERIAL && !forceMetric) {
            temp = temp * 1.8 + 32.0
        }
        val formattedTemp = "${temp.roundToInt()}°"

        val dogeImg = WeatherDoge.dogeSelect(data.image)
        val skyImg = WeatherDoge.skySelect(data.image)

        if (locationName.isBlank()) {
            locationName = data.place
        }

        val condition = data.condition.ifEmpty { " " }
        val now = Date()
        val timeStr = buildString {
            if (showDate) {
                append(DateFormat.format(" MMM d ", now)).append(appCtx.getString(R.string.widget_at))
            }
            append(" ").append(DateFormat.getTimeFormat(appCtx).format(now)).append(" ")
        }

        val textBitmaps = WidgetProvider.getTextBitmaps(
            appCtx,
            formattedTemp,
            condition,
            locationName.ifEmpty { " " },
            timeStr
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
                    val options = widgetManager?.getAppWidgetOptions(widgets[i])
                    if (options != null) {
                        if (!backgroundFix) {
                            sky = WidgetProvider.getSkyBitmap(appCtx, options, skyImg)
                            views.setImageViewBitmap(R.id.widget_sky, sky)
                            views.setInt(R.id.widget_sky, "setVisibility", View.VISIBLE)
                            views.setInt(R.id.widget_sky_compat, "setVisibility", View.GONE)
                        }
                        if (showWowText) {
                            wowLayer = WidgetProvider.getWowLayer(appCtx, options, data.image, data.temperature.toInt())
                            views.setImageViewBitmap(R.id.widget_wowlayer, wowLayer)
                        } else {
                            views.setImageViewBitmap(R.id.widget_wowlayer, null)
                        }
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Error generating widget options bitmaps", ex)
                    failed = true
                }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN || failed || backgroundFix) {
                views.setInt(R.id.widget_sky, "setVisibility", View.GONE)
                views.setInt(R.id.widget_sky_compat, "setVisibility", View.VISIBLE)
                views.setImageViewResource(R.id.widget_sky_compat, skyImg)
            }

            widgetManager?.updateAppWidget(widgets[i], views)

            sky?.let { if (!it.isRecycled) it.recycle() }
            wowLayer?.let { if (!it.isRecycled) it.recycle() }
        }

        for (b in textBitmaps) {
            b?.let { if (!it.isRecycled) it.recycle() }
        }

        return Result.success()
    }

    private fun showError(resId: Int) {
        val error = applicationContext.getString(resId)
        Log.e(TAG, error)
        setStatus(error, false)
    }

    private fun setStatus(status: String, isLoading: Boolean = false) {
        val appCtx = applicationContext
        val loading = WidgetProvider.getStatusBitmap(appCtx, status)
        for (i in widgets.indices) {
            val views = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.widget)
            views.setImageViewBitmap(R.id.widget_locationimg, loading)
            views.setImageViewBitmap(R.id.widget_last_updated_img, null)
            views.setOnClickPendingIntent(R.id.widget_root, pIntents.getOrNull(i))
            views.setViewVisibility(R.id.widget_loading, if (isLoading) View.VISIBLE else View.GONE)
            widgetManager?.partiallyUpdateAppWidget(widgets[i], views)
        }
        loading.recycle()
    }

    override fun onLocation(location: Location) {
        locationRef.set(location)
        try {
            locationBarrier.await()
        } catch (ex: Exception) {
            // Barrier cancelled
        }
    }

    override fun onConnected() {}

    companion object {
        private val TAG = WidgetWorker::class.java.simpleName

        const val TASK_ALL_TAG = "WidgetWorker_All"
        const val TASK_MULTIPLE_TAG = "WidgetWorker_Multiple"
        const val TASK_ONE_TAG = "WidgetWorker_One"

        const val ACTION = "action"
        const val ACTION_REFRESH_ALL = "refresh_all"
        const val ACTION_REFRESH_MULTIPLE = "refresh_multiple"
        const val ACTION_REFRESH_ONE = "refresh_one"
        const val EXTRA_WIDGET_ID = "widget_id"
        const val PERMISSION_NOTIFICATION_ID = 0x444f4745

        @JvmStatic
        fun enqueueOnceAll(context: Context) {
            enqueueOnceNow(context, ACTION_REFRESH_ALL, null)
        }

        @JvmStatic
        fun enqueueOnceMultiple(context: Context, appWidgetIds: IntArray) {
            enqueueOnceNow(context, ACTION_REFRESH_MULTIPLE, appWidgetIds)
        }

        @JvmStatic
        fun enqueueOnceSingle(context: Context, appWidgetId: Int) {
            enqueueOnceNow(context, ACTION_REFRESH_ONE, intArrayOf(appWidgetId))
        }

        private fun enqueueOnceNow(context: Context, action: String, appWidgetIds: IntArray?) {
            val dataBuilder = Data.Builder().putString(ACTION, action)
            val workRequestBuilder = OneTimeWorkRequest.Builder(WidgetWorker::class.java)
            when (action) {
                ACTION_REFRESH_ALL -> workRequestBuilder.addTag(TASK_ALL_TAG)
                ACTION_REFRESH_MULTIPLE -> {
                    if (appWidgetIds != null) dataBuilder.putIntArray(EXTRA_WIDGET_ID, appWidgetIds)
                    workRequestBuilder.addTag(TASK_MULTIPLE_TAG)
                }
                ACTION_REFRESH_ONE -> {
                    if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
                        dataBuilder.putInt(EXTRA_WIDGET_ID, appWidgetIds[0])
                    }
                    workRequestBuilder.addTag(TASK_ONE_TAG)
                }
            }
            workRequestBuilder.setInputData(dataBuilder.build())
            WorkManager.getInstance(context).enqueue(workRequestBuilder.build())
        }
    }
}
