/*
 * Copyright (C) 2014-2017, 2019 VersoBit
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

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.google.android.material.snackbar.Snackbar
import com.plattysoft.leonids.ParticleSystem
import com.versobit.weatherdoge.data.DailyForecast
import com.versobit.weatherdoge.data.WeatherData
import com.versobit.weatherdoge.data.WeatherRepository
import com.versobit.weatherdoge.data.WeatherResult
import com.versobit.weatherdoge.data.WeatherSource
import com.versobit.weatherdoge.location.ApiStatus
import com.versobit.weatherdoge.location.DogeLocationApi
import com.versobit.weatherdoge.location.FlavoredApiSelector
import com.versobit.weatherdoge.location.LocationReceiver
import com.versobit.weatherdoge.ui.DogeDialView
import com.versobit.weatherdoge.ui.chaos.ChaosMode
import com.versobit.weatherdoge.ui.nyan.NyanCatView
import com.versobit.weatherdoge.ui.sound.SoundManager
import com.versobit.weatherdoge.util.Units
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import java.io.IOException
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors

/**
 * The doge weather screen.
 *
 * Kotlin rewrite of the original activity. What changed beyond the language:
 *  - weather is fetched through [WeatherRepository] (one shared, never-purged cache that
 *    the widget reads too — upstream #6),
 *  - the Doge Dial, the seven day forecast strip, CHAOS MODE and the NYAN tab are new,
 *  - every tap and every doge poke goes through [SoundManager] (six locked slots),
 *  - the temperature row now scales itself down instead of being clipped (upstream #7),
 *  - location comes from the platform LocationManager (upstream #8, no Play Services).
 */
class MainActivity : Activity(), LocationReceiver,
    ActivityCompat.OnRequestPermissionsResultCallback {

    companion object {
        private const val TAG = "MainActivity"
        private const val WOW_INTERVAL = 2300L
        private const val REQUEST_LOCATION_PERMISSION = 410
        private const val NYAN_ASSET = "sounds/nyan.mp4"

        /** Never shrink the temperature below this (issue #7) — readability floor. */
        private const val MIN_TEMP_SP = 60f
        private const val TEMP_STEP_SP = 4f

        private enum class Tab { DOGE, NYAN }
    }

    // ---- options ------------------------------------------------------------
    private var forceMetric = false
    private var forceLocation = ""
    private var weatherSource: WeatherSource = WeatherSource.OPEN_METEO
    private var useNeue = false
    private var shadowR = 1f
    private var shadowX = 3f
    private var shadowY = 3f
    private var shadowAdjs = false
    private var textOnTop = false
    private var enableParticles = true
    private var lastVersion = 0

    // ---- views --------------------------------------------------------------
    private lateinit var suchBg: ImageView
    private lateinit var suchOverlay: RelativeLayout
    private lateinit var suchTopOverlay: RelativeLayout
    private lateinit var suchInfoGroup: LinearLayout
    private lateinit var suchDoge: ImageView
    private lateinit var suchStatus: TextView
    private lateinit var suchTempGroup: RelativeLayout
    private lateinit var suchNegative: TextView
    private lateinit var suchTemp: TextView
    private lateinit var suchDegree: TextView
    private lateinit var suchLocation: TextView
    private lateinit var rootLayout: View
    private lateinit var dial: DogeDialView
    private lateinit var forecastStrip: LinearLayout
    private lateinit var nyanFrame: View
    private lateinit var nyanVideo: VideoView
    private lateinit var nyanCat: NyanCatView
    private lateinit var tabDoge: TextView
    private lateinit var tabNyan: TextView
    private lateinit var chaosFlash: View

    // ---- state --------------------------------------------------------------
    private var wowApi: DogeLocationApi = FlavoredApiSelector.get()
    private var whereIsDoge: Location? = null
    private var wowComicSans: Typeface? = null

    private var errorDialog: AlertDialog? = null
    private var rationaleDialog: AlertDialog? = null
    private var locationSnackbar: Snackbar? = null

    private var particleSystem: ParticleSystem? = null
    private var isEmitting = false

    private var currentTemp = 0.0
    private var currentlyMetric = false
    private var currentLocation = ""
    private var currentLink: Uri? = null
    private var dogefixes: Array<String> = emptyArray()
    private var wows: Array<String> = emptyArray()
    private var weatherAdjectives: Array<String>? = null
    private var colors: IntArray = intArrayOf()

    private val overlayTimer = Timer("OverlayTimer", true)
    private var overlayTask: OverlayTimerTask? = null
    private var newWowText: WowText? = null
    private val overlays = ArrayDeque<WowText>()
    private var currentBackgroundId = Integer.MIN_VALUE
    private var currentDogeId = R.drawable.doge_01d
    private var currentlyAnim = false

    // ---- new machinery ------------------------------------------------------
    private lateinit var sound: SoundManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "doge-images").apply { isDaemon = true } }
    private val weatherExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "weather-fetch").apply { isDaemon = true } }
    private var weatherInFlight = false
    private var pendingLocation: Location? = null
    private var lastData: WeatherData? = null
    private var destroyed = false

    private val chaos = ChaosMode(clock = { SystemClock.elapsedRealtime() })
    private val shakeRandom = Random()
    private var chaosBeats: List<ChaosMode.Beat> = emptyList()
    private var chaosBeatIndex = 0
    private var chaosLastVisual = -1
    private var chaosStatusBefore: CharSequence? = null
    private var currentTab = Tab.DOGE
    private var nyanHasVideo = false
    private var reduceMotion = false

    private val chaosTicker = object : Runnable {
        override fun run() {
            if (!chaos.isActive) return
            chaos.tick()
            val elapsed = chaos.elapsedMs()

            while (chaosBeatIndex < chaosBeats.size && elapsed >= chaosBeats[chaosBeatIndex].atMs) {
                playChaosSfx(chaosBeats[chaosBeatIndex].sfx)
                chaosBeatIndex++
            }

            if (chaos.visualsEnabled) {
                val idx = ChaosMode.visualIndex(elapsed, ChaosMode.DEFAULT_DURATION_MS, true)
                if (idx != chaosLastVisual && idx >= 0) {
                    chaosLastVisual = idx
                    cycleChaosVisuals(idx)
                }
                val jitter = 6f * resources.displayMetrics.density
                rootLayout.translationX = (shakeRandom.nextFloat() * 2f - 1f) * jitter
                rootLayout.translationY = (shakeRandom.nextFloat() * 2f - 1f) * jitter
            }
            mainHandler.postDelayed(this, 100L)
        }
    }

    // ---- lifecycle ----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        destroyed = false

        sound = SoundManager(this)
        wowApi.configure(this, this)
        loadOptions()
        reduceMotion = detectReducedMotion()

        dogefixes = resources.getStringArray(R.array.dogefix)
        wows = resources.getStringArray(R.array.wows)
        colors = resources.getIntArray(R.array.wow_colors)

        rootLayout = requireView(R.id.main_suchlayout)
        suchBg = requireView(R.id.main_suchbg)
        suchOverlay = requireView(R.id.main_suchoverlay)
        suchTopOverlay = requireView(R.id.main_suchtopoverlay)
        suchInfoGroup = requireView(R.id.main_suchinfogroup)
        suchDoge = requireView(R.id.main_suchdoge)
        suchStatus = requireView(R.id.main_suchstatus)
        suchTempGroup = requireView(R.id.main_suchtempgroup)
        suchNegative = requireView(R.id.main_suchnegative)
        suchTemp = requireView(R.id.main_suchtemp)
        suchDegree = requireView(R.id.main_suchdegree)
        suchLocation = requireView(R.id.main_suchlocation)
        dial = requireView(R.id.main_dial)
        forecastStrip = requireView(R.id.main_forecast)
        nyanFrame = requireView(R.id.main_nyanframe)
        nyanVideo = requireView(R.id.main_nyanvideo)
        nyanCat = requireView(R.id.main_nyancat)
        tabDoge = requireView(R.id.main_tab_doge)
        tabNyan = requireView(R.id.main_tab_nyan)
        chaosFlash = requireView(R.id.main_chaosflash)

        nyanHasVideo = assetExists(NYAN_ASSET)

        suchInfoGroup.setOnClickListener {
            val link = currentLink ?: return@setOnClickListener
            val i = Intent(Intent.ACTION_VIEW, link)
            WeatherDoge.applyChromeCustomTab(this, i)
            startActivity(i)
        }

        suchDoge.setOnClickListener {
            sound.playBonk()
            when {
                forceLocation.isNotBlank() -> requestWeather(null)
                whereIsDoge != null -> requestWeather(whereIsDoge)
                else -> requestLocation()
            }
        }

        requireView<View>(R.id.man_fab_share).setOnClickListener { view ->
            sound.playBonk()
            shareWeather(view)
        }
        requireView<View>(R.id.man_fab_options).setOnClickListener {
            sound.playBonk()
            startActivity(Intent(this, OptionsActivity::class.java))
        }
        requireView<View>(R.id.man_fab_airhorn).setOnClickListener {
            sound.play(SoundManager.Slot.AIRHORN)
        }
        requireView<View>(R.id.man_fab_chaos).setOnClickListener {
            sound.playBonk()
            toggleChaos()
        }

        tabDoge.setOnClickListener { sound.playBonk(); selectTab(Tab.DOGE) }
        tabNyan.setOnClickListener { sound.playBonk(); selectTab(Tab.NYAN) }
        // Initial selection state (android:selected cannot be set from XML).
        tabDoge.isSelected = true
        tabNyan.isSelected = false

        updateFont()
        updateShadow()

        if (forceLocation.isNotBlank()) {
            requestWeather(null)
        } else {
            wowApi.isAvailable
        }
        applyBackground(R.drawable.sky_01d)

        if (BuildConfig.VERSION_CODE > lastVersion) {
            lastVersion = BuildConfig.VERSION_CODE
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putInt(OptionsActivity.PREF_INTERNAL_LAST_VERSION, lastVersion)
                .apply()
        }
    }

    override fun onStart() {
        super.onStart()
        if (forceLocation.isBlank()) {
            requestLocation()
        }
        val task = OverlayTimerTask()
        overlayTask = task
        overlayTimer.schedule(task, 0, WOW_INTERVAL)
        syncParticles()
    }

    override fun onResume() {
        super.onResume()
        val oldNeue = useNeue
        val oldShadowR = shadowR
        val oldShadowX = shadowX
        val oldShadowY = shadowY
        val oldShadowAdjs = shadowAdjs
        loadOptions()
        reduceMotion = detectReducedMotion()

        if (useNeue != oldNeue) updateFont()
        if (oldShadowR != shadowR || oldShadowX != shadowX || oldShadowY != shadowY || oldShadowAdjs != shadowAdjs) {
            updateShadow()
        }

        if (forceLocation.isBlank()) {
            if (wowApi.status == ApiStatus.DISCONNECTED) {
                // FIXME: Double request after selecting cancel on the first permission dialog
                requestLocation()
            }
        } else {
            if (wowApi.status != ApiStatus.DISCONNECTED) wowApi.disconnect()
            requestWeather(null)
        }

        syncParticles()
        if (currentTab == Tab.NYAN) resumeNyan()
    }

    override fun onPause() {
        // Leave the tab open but silent: nothing keeps playing in the background.
        stopNyanPlayback()
        super.onPause()
    }

    override fun onStop() {
        if (wowApi.status != ApiStatus.DISCONNECTED) wowApi.disconnect()
        overlayTask?.cancel()
        overlayTask = null
        if (chaos.isActive) chaos.cancel()
        stopParticles()
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        overlayTimer.cancel()
        chaos.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        weatherExecutor.shutdownNow()
        imageExecutor.shutdownNow()
        sound.release()
        super.onDestroy()
    }

    // ---- options ------------------------------------------------------------

    private fun loadOptions() {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        forceMetric = sp.getBoolean(OptionsActivity.PREF_FORCE_METRIC, false)
        forceLocation = sp.getString(OptionsActivity.PREF_FORCE_LOCATION, "") ?: ""
        weatherSource = WeatherRepository.source(this)
        useNeue = sp.getBoolean(OptionsActivity.PREF_APP_USE_COMIC_NEUE, false)
        shadowR = sp.getFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_radius", 1f)
        shadowX = sp.getFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_x", 3f)
        shadowY = sp.getFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_y", 3f)
        shadowAdjs = sp.getBoolean(OptionsActivity.PREF_APP_DROP_SHADOW + "_adjs", false)
        textOnTop = sp.getBoolean(OptionsActivity.PREF_APP_TEXT_ON_TOP, false)
        enableParticles = sp.getBoolean(OptionsActivity.PREF_APP_ENABLE_PARTICLES, true)
        lastVersion = sp.getInt(OptionsActivity.PREF_INTERNAL_LAST_VERSION, lastVersion)
    }

    private fun detectReducedMotion(): Boolean = try {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) {
        false
    }

    // ---- font & shadow ------------------------------------------------------

    private fun updateFont() {
        @Suppress("DEPRECATION")
        val layoutParams = suchTemp.layoutParams as RelativeLayout.LayoutParams
        if (useNeue || BuildConfig.FLAVOR == BuildConfig.FLAVOR_FOSS) {
            wowComicSans = Typeface.createFromAsset(assets, "ComicNeue-Regular.ttf")
            // Horizontal and vertical centering for proper display
            layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            // This only works when going from Comic Sans -> Comic Neue, not the other way around
            synchronized(overlays) {
                for (wowText in overlays) {
                    wowText.view.typeface = wowComicSans
                }
            }
        } else {
            wowComicSans = Typeface.createFromAsset(assets, "comic.ttf")
            layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, 0) // Disable vertical center
        }
        suchDegree.typeface = wowComicSans
        suchStatus.typeface = wowComicSans
        suchLocation.typeface = wowComicSans
        suchNegative.typeface = wowComicSans
        suchTemp.typeface = wowComicSans
        suchTemp.layoutParams = layoutParams
        applyTypefaceToForecast(wowComicSans)
    }

    private fun updateShadow() {
        suchStatus.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        suchNegative.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        suchTemp.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        suchDegree.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        suchLocation.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        synchronized(overlays) {
            for (wowText in overlays) {
                if (shadowAdjs) {
                    wowText.view.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
                } else {
                    wowText.view.setShadowLayer(0f, 0f, 0f, 0)
                }
            }
        }
    }

    private fun applyTypefaceToForecast(typeface: Typeface?) {
        if (typeface == null || forecastStrip.childCount == 0) return
        for (i in 0 until forecastStrip.childCount) {
            val item = forecastStrip.getChildAt(i)
            for (id in intArrayOf(R.id.forecast_day, R.id.forecast_high, R.id.forecast_low)) {
                item.findViewById<TextView>(id)?.typeface = typeface
            }
        }
    }

    // ---- location -----------------------------------------------------------

    override fun onConnected() {
        requestLocation()
    }

    override fun onLocation(location: Location) {
        whereIsDoge = location
        requestWeather(location)
    }

    private fun requestLocation() = requestLocation(false)

    /** Asynchronously request the current location. */
    private fun requestLocation(skipRationale: Boolean) {
        if (ContextCompat.checkSelfPermission(this, WeatherDoge.LOCATION_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (!skipRationale && ActivityCompat.shouldShowRequestPermissionRationale(
                    this, WeatherDoge.LOCATION_PERMISSION
                )
            ) {
                showLocationRationaleDialog()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(WeatherDoge.LOCATION_PERMISSION),
                    REQUEST_LOCATION_PERMISSION
                )
            }
        } else {
            doCheckedLocationRequest()
        }
    }

    /** Show the location rationale dialog which explains why the permission is needed. */
    private fun showLocationRationaleDialog() {
        if (rationaleDialog?.isShowing == true || locationSnackbar?.isShown == true) return
        val adb = AlertDialog.Builder(this)
            .setMessage(R.string.location_rationale_text)
            .setNegativeButton(R.string.location_rationale_negative) { _, _ ->
                // Go directly to the constant location setting in the options
                startActivity(
                    Intent(this, OptionsActivity::class.java)
                        .putExtra(
                            OptionsActivity.EXTRA_SHORTCUT,
                            OptionsActivity.EXTRA_SHORTCUT_FORCE_LOCATION
                        )
                )
            }
            .setPositiveButton(R.string.location_rationale_positive) { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(WeatherDoge.LOCATION_PERMISSION),
                    REQUEST_LOCATION_PERMISSION
                )
            }
            .setOnCancelListener { showLocationSnackbar() }
        // Prevent crash if MainActivity is finishing while attempting to display a new dialog
        if (!isFinishing) {
            rationaleDialog = adb.show()
        }
    }

    private fun showLocationSnackbar() {
        if (locationSnackbar?.isShown == true) return
        locationSnackbar = Snackbar.make(
            requireView(R.id.main_suchlayout),
            R.string.location_snackbar_text, Snackbar.LENGTH_INDEFINITE
        )
            .setAction(R.string.location_snackbar_button) { requestLocation(true) }
            .setActionTextColor(ContextCompat.getColor(this, R.color.primary_dark))
        locationSnackbar?.show()
    }

    /** Called after we're certain we have the location permission. */
    private fun doCheckedLocationRequest() {
        if (wowApi.status == ApiStatus.DISCONNECTED) {
            wowApi.connect()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode != REQUEST_LOCATION_PERMISSION) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Remove the widget notification, if any
            @Suppress("DEPRECATION")
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(WidgetWorker.PERMISSION_NOTIFICATION_ID)
            // Refresh the widget
            if (WidgetProvider.areWidgetsActive(this)) {
                WidgetWorker.enqueueOnceAll()
            }
            locationSnackbar?.takeIf { it.isShown }?.dismiss()
            doCheckedLocationRequest()
        } else {
            showLocationSnackbar()
        }
    }

    // ---- weather ------------------------------------------------------------

    /**
     * Fetch (or serve from cache) the weather for [location], or for the constant
     * location when one is configured. Runs off the UI thread; results are applied on it.
     */
    private fun requestWeather(location: Location?) {
        if (weatherInFlight) {
            // Remember only a positional request; a no-op refresh is dropped.
            if (location != null) pendingLocation = location
            return
        }
        weatherInFlight = true
        rationaleDialog?.takeIf { it.isShowing }?.dismiss()
        locationSnackbar?.takeIf { it.isShown }?.dismiss()

        weatherExecutor.execute {
            val outcome = fetchWeather(location)
            mainHandler.post {
                if (destroyed) return@post
                weatherInFlight = false
                applyWeather(outcome)
                val next = pendingLocation
                pendingLocation = null
                if (next != null) requestWeather(next)
            }
        }
    }

    private sealed class FetchOutcome {
        data class Ok(val data: WeatherData, val placeName: String, val stale: Boolean) : FetchOutcome()
        data class Error(val message: String) : FetchOutcome()
    }

    private fun fetchWeather(location: Location?): FetchOutcome {
        val useConstantLocation = forceLocation.isNotBlank()
        val latitude: Double?
        val longitude: Double?

        if (useConstantLocation) {
            latitude = null
            longitude = null
        } else {
            if (location == null) {
                return FetchOutcome.Error(getString(R.string.error_ensure_location_settings))
            }
            if (!Geocoder.isPresent()) {
                return FetchOutcome.Error(getString(R.string.geocoder_error_code))
            }
            latitude = location.latitude
            longitude = location.longitude
        }

        // Serve from the shared cache when it is fresh for this exact query.
        var stale = false
        val cached = WeatherRepository.fresh(this, latitude, longitude, forceLocation.takeIf { it.isNotBlank() })
        val data: WeatherData = if (cached != null) {
            cached
        } else {
            when (val result = WeatherRepository.refresh(
                this, latitude, longitude, forceLocation.takeIf { it.isNotBlank() }, weatherSource
            )) {
                is WeatherResult.Success -> result.data
                else -> {
                    // A failed refresh must never throw away good data: show the last known
                    // weather instead of an empty screen (upstream #6/#9).
                    val fallback = WeatherRepository.current(this)
                    if (fallback != null) {
                        stale = true
                        fallback
                    } else {
                        return FetchOutcome.Error(
                            (result as? WeatherResult.ApiError)?.message
                                ?: (result as? WeatherResult.Failure)?.message
                                ?: getString(R.string.error_ensure_location_settings)
                        )
                    }
                }
            }
        }

        // Human readable place name for coordinate queries.
        val placeName = if (!useConstantLocation && latitude != null && longitude != null) {
            reverseGeocode(latitude, longitude) ?: data.place
        } else {
            forceLocation.ifBlank { data.place }
        }

        return FetchOutcome.Ok(data, placeName, stale)
    }

    private fun reverseGeocode(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            val addresses: List<Address>? = Geocoder(this).getFromLocation(latitude, longitude, 1)
            addresses?.firstOrNull()?.locality
        } catch (e: IOException) {
            showGeocoderError()
            null
        } catch (e: Exception) {
            Log.w(TAG, "reverse geocode failed", e)
            null
        }
    }

    private fun showGeocoderError() {
        mainHandler.post {
            if (destroyed || isFinishing || errorDialog?.isShowing == true) return@post
            val adb = AlertDialog.Builder(ContextThemeWrapper(this, R.style.AppTheme_Options))
                .setTitle(R.string.geocoder_error_title)
                .setMessage(R.string.geocoder_error_msg)
                .setNeutralButton(R.string.wow) { dialog, _ -> dialog.dismiss() }
            if (!isFinishing) errorDialog = adb.show()
        }
    }

    private fun applyWeather(outcome: FetchOutcome) {
        when (outcome) {
            is FetchOutcome.Error -> {
                Log.e(TAG, outcome.message)
                Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
            }
            is FetchOutcome.Ok -> {
                val data = outcome.data
                lastData = data

                applyDoge(WeatherDoge.dogeSelect(data.image))
                applyBackground(WeatherDoge.skySelect(data.image))

                // The Doge Dial
                dial.temperatureC = data.temperature

                // Seven day forecast
                renderForecast(data)

                // Temperature source link
                currentLink = data.link?.let { link ->
                    val uri = Uri.parse(link)
                    if (uri.scheme == "http" || uri.scheme == "https") uri else null
                }

                currentLocation = outcome.placeName.ifBlank { data.place }
                weatherAdjectives = resources.getStringArray(WeatherDoge.getTempAdjectives(data.temperature.toInt())) +
                    resources.getStringArray(WeatherDoge.getBgAdjectives(data.image))

                val description = "${getString(R.string.wow)} ${data.condition.trim().lowercase(Locale.US)}"
                val temp = formatTemp(data.temperature)

                val nowImperial = UnitLocale.getDefault() == UnitLocale.IMPERIAL && !forceMetric
                val unchanged = suchStatus.text.toString() == description &&
                    currentTemp == data.temperature &&
                    currentlyMetric == !nowImperial &&
                    suchLocation.text.toString() == currentLocation

                if (unchanged || currentlyAnim) {
                    currentlyAnim = false
                    if (outcome.stale) {
                        Toast.makeText(this, R.string.weather_stale, Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                currentlyAnim = true
                animateWeatherText(data, description, temp, currentLocation, outcome.stale)

                // Keep the widget in step with the app (shared cache, upstream #6/#9).
                if (WidgetProvider.areWidgetsActive(this)) {
                    WidgetWorker.enqueueOnceAll()
                }
            }
        }
    }

    /** The staggered fade-out/fade-in dance the original app did, ported verbatim. */
    private fun animateWeatherText(
        data: WeatherData,
        description: String,
        temp: FormattedTemp,
        locationName: String,
        stale: Boolean
    ) {
        val animTime = (resources.getInteger(R.integer.anim_refresh_time) / 2.5f).toLong()

        val fadeOuts = Array(3) {
            AnimationUtils.loadAnimation(this, R.anim.textfade_out)
        }
        val fadeIns = Array(3) {
            AnimationUtils.loadAnimation(this, R.anim.textfade_in)
        }

        val tempGroupRunnable = Runnable { suchTempGroup.startAnimation(fadeOuts[1]) }
        val locationRunnable = Runnable { suchLocation.startAnimation(fadeOuts[2]) }

        fadeOuts[0].setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                mainHandler.postDelayed(tempGroupRunnable, animTime)
            }

            override fun onAnimationEnd(animation: Animation) {
                suchStatus.text = description
                suchStatus.startAnimation(fadeIns[0])
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })
        fadeOuts[1].setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                mainHandler.postDelayed(locationRunnable, animTime)
            }

            override fun onAnimationEnd(animation: Animation) {
                currentTemp = data.temperature
                currentlyMetric = UnitLocale.getDefault() != UnitLocale.IMPERIAL || forceMetric
                suchTemp.text = temp.str
                suchNegative.visibility = if (temp.temp < 0) View.VISIBLE else View.GONE
                suchDegree.visibility = View.VISIBLE
                fitTemperature()
                suchTempGroup.startAnimation(fadeIns[1])
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })
        fadeOuts[2].setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}

            override fun onAnimationEnd(animation: Animation) {
                suchLocation.text = locationName
                suchLocation.startAnimation(fadeIns[2])
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })
        fadeIns[2].setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}

            override fun onAnimationEnd(animation: Animation) {
                currentlyAnim = false
                if (stale) {
                    Toast.makeText(this@MainActivity, R.string.weather_stale, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })

        suchStatus.startAnimation(fadeOuts[0])
    }

    /**
     * Issue #7: the temperature row used to be a hard-coded 400dp wide, so 120sp values
     * were clipped on narrow screens. The row now spans the screen and the number steps
     * down until it fits — never below [MIN_TEMP_SP].
     */
    private fun fitTemperature() {
        val row = suchTempGroup
        if (row.width <= 0) {
            row.post { fitTemperature() }
            return
        }
        val metrics = resources.displayMetrics
        val available = row.width - row.paddingLeft - row.paddingRight
        if (available <= 0) return

        val text = suchTemp.text?.toString() ?: return
        val copy = Paint(suchTemp.paint)
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_TEMP_SP, metrics)
        val stepPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEMP_STEP_SP, metrics)

        var size = suchTemp.textSize
        var guard = 0
        while (size > minPx && guard++ < 60) {
            copy.textSize = size
            val needed = copy.measureText(text) + sideWidth(suchNegative) + sideWidth(suchDegree)
            if (needed <= available) break
            size = maxOf(size - stepPx, minPx)
        }
        suchTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, size / metrics.scaledDensity)
        // Re-measure the neighbours so the row stays balanced after the change.
        row.requestLayout()
    }

    private fun sideWidth(view: TextView): Float =
        if (view.visibility == View.VISIBLE) {
            val copy = Paint(view.paint)
            copy.textSize = view.textSize
            copy.measureText(view.text?.toString() ?: "")
        } else {
            0f
        }

    private data class FormattedTemp(val temp: Double, val str: String)

    private fun formatTemp(celsius: Double): FormattedTemp {
        var value = celsius
        if (UnitLocale.getDefault() == UnitLocale.IMPERIAL && !forceMetric) {
            value = value * 1.8 + 32.0
        }
        value = Math.round(value).toDouble()
        val df = DecimalFormat()
        df.negativePrefix = ""
        df.negativeSuffix = ""
        df.maximumFractionDigits = 0
        df.isDecimalSeparatorAlwaysShown = false
        df.isGroupingUsed = false
        return FormattedTemp(value, df.format(value))
    }

    // ---- doge / background art ---------------------------------------------

    private fun applyDoge(resId: Int) {
        if (currentDogeId == resId) return
        currentDogeId = resId
        if (reduceMotion) {
            suchDoge.setImageResource(resId)
            return
        }
        val zoomOut = AnimationUtils.loadAnimation(this, R.anim.dogezoom_out)
        val zoomIn = AnimationUtils.loadAnimation(this, R.anim.dogezoom_in)
        zoomOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}

            override fun onAnimationEnd(animation: Animation) {
                if (destroyed) return
                suchDoge.setImageResource(resId)
                suchDoge.startAnimation(zoomIn)
            }

            override fun onAnimationRepeat(animation: Animation) {}
        })
        suchDoge.startAnimation(zoomOut)
    }

    private fun applyBackground(resId: Int) {
        if (currentBackgroundId == resId) return
        currentBackgroundId = resId
        imageExecutor.execute {
            val sky = renderSky(resId) ?: return@execute
            mainHandler.post {
                if (destroyed || currentBackgroundId != resId) return@post
                showSky(sky)
                syncParticles()
            }
        }
    }

    /** Manually resize/crop the sky background (ported from the original activity). */
    private fun renderSky(resId: Int): Bitmap? = try {
        val resources = resources
        val theSky = BitmapFactory.decodeResource(resources, resId) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        // "Support" for multi-window mode
        var multiWindowOffsetWidth = 0
        var multiWindowOffsetHeight = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) {
            multiWindowOffsetHeight = try {
                resources.getDimensionPixelSize(
                    resources.getIdentifier("status_bar_height", "dimen", "android")
                )
            } catch (e: Resources.NotFoundException) {
                // Assume a status bar size of 25dp
                Math.ceil(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 25f, metrics).toDouble()).toInt()
            }
            multiWindowOffsetHeight += 5
            multiWindowOffsetWidth = 5
        }

        // Magic number to get some important image elements onscreen
        val magic = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 180f, metrics)
        val scaledSky = Bitmap.createBitmap(
            metrics.widthPixels + multiWindowOffsetWidth,
            metrics.heightPixels + multiWindowOffsetHeight,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(scaledSky)
        val wScale = (scaledSky.width + magic) / theSky.width
        val hScale = scaledSky.height.toFloat() / theSky.height
        val bigScale = maxOf(wScale, hScale)
        val matrix = Matrix()
        matrix.setScale(bigScale, bigScale, magic, 0f)
        canvas.setMatrix(matrix)
        canvas.drawBitmap(theSky, 0f, 0f, Paint())
        theSky.recycle()
        scaledSky
    } catch (e: Exception) {
        Log.w(TAG, "sky render failed", e)
        null
    }

    private fun showSky(scaledSky: Bitmap) {
        val current = suchBg.drawable
        if (current != null) {
            val old = if (current is TransitionDrawable) current.getDrawable(1) else current
            val transition = TransitionDrawable(arrayOf(old, BitmapDrawable(resources, scaledSky)))
            transition.isCrossFadeEnabled = true
            suchBg.setImageDrawable(transition)
            transition.startTransition(resources.getInteger(R.integer.anim_refresh_time) * 2)
        } else {
            suchBg.setImageDrawable(BitmapDrawable(resources, scaledSky))
        }
    }

    // ---- seven day forecast -------------------------------------------------

    private fun renderForecast(data: WeatherData) {
        val daily: List<DailyForecast> = data.daily
        if (daily.isEmpty()) {
            forecastStrip.visibility = View.GONE
            return
        }
        forecastStrip.removeAllViews()

        val zone = try {
            if (data.timezone.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(data.timezone)
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }
        val today = LocalDate.now(zone).toEpochDay()
        val density = resources.displayMetrics.density
        val iconPx = (30 * density).toInt()

        for (day in daily.take(7)) {
            val item = layoutInflater.inflate(R.layout.item_forecast, forecastStrip, false)
            val label = item.findViewById<TextView>(R.id.forecast_day)
            label.text = if (day.epochDay == today) {
                getString(R.string.forecast_today)
            } else {
                LocalDate.ofEpochDay(day.epochDay).dayOfWeek
                    .getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            }
            val icon = item.findViewById<ImageView>(R.id.forecast_icon)
            icon.setImageBitmap(decodeSampled(WeatherDoge.dogeSelect(day.image), iconPx))
            item.findViewById<TextView>(R.id.forecast_high).text =
                day.highC?.let { Units.formatShort(it, forceMetric) } ?: ""
            item.findViewById<TextView>(R.id.forecast_low).text =
                day.lowC?.let { Units.formatShort(it, forceMetric) } ?: ""
            wowComicSans?.let { type ->
                label.typeface = type
                item.findViewById<TextView>(R.id.forecast_high).typeface = type
                item.findViewById<TextView>(R.id.forecast_low).typeface = type
            }
            forecastStrip.addView(item)
        }
        forecastStrip.visibility = View.VISIBLE
    }

    private fun decodeSampled(resId: Int, targetPx: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, resId, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= targetPx && opts.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeResource(resources, resId, decode)
    } catch (e: Exception) {
        null
    }

    // ---- snowfall -----------------------------------------------------------

    private fun newParticleSystem(): ParticleSystem =
        ParticleSystem(this, 200, R.drawable.snowflake, 20000)
            .setSpeedByComponentsRange(0f, 0f, 0.05f, 0.1f)
            .setScaleRange(0.2f, 1f)
            .setParentViewGroup(requireView<ViewGroup>(R.id.main_snowframe))

    private fun startParticleSystem() {
        isEmitting = true
        particleSystem?.emitWithGravity(requireView(R.id.snow_emitter), Gravity.TOP, 5)
    }

    /** Keep snowfall in sync with the current sky, the in-app toggle and reduced motion. */
    private fun syncParticles() {
        val shouldSnow = WeatherDoge.isSnowing(currentBackgroundId) && enableParticles && !reduceMotion
        if (!shouldSnow) {
            stopParticles()
            return
        }
        if (!isEmitting) {
            particleSystem?.cancel()
            particleSystem = newParticleSystem()
            // Make it render as if it started 25 seconds ago
            particleSystem?.setStartTime(25000)
            startParticleSystem()
        }
    }

    private fun stopParticles() {
        if (isEmitting) {
            particleSystem?.cancel()
            isEmitting = false
        }
    }

    // ---- doge-ism overlays --------------------------------------------------

    private inner class OverlayTimerTask : TimerTask() {
        override fun run() {
            val adjectives = weatherAdjectives ?: return
            val random = Random()

            var created: WowText? = null
            // Loop until we come out the other side with a valid wowText
            while (created == null) {
                val view = TextView(this@MainActivity)

                // 15sp is a magic padding number I've tested with
                val padding = Math.ceil(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics).toDouble()
                ).toInt()

                view.typeface = wowComicSans
                if (shadowAdjs) {
                    view.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
                }

                val layoutDim = intArrayOf(suchOverlay.width, suchOverlay.height)

                view.text = uniqueDogeism(random, adjectives)
                view.setTextColor(colors[random.nextInt(colors.size)])
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, (random.nextInt(15) + 25).toFloat())

                view.measure(layoutDim[0], layoutDim[1])
                val textDim = intArrayOf(view.measuredWidth, view.measuredHeight)

                val params = RelativeLayout.LayoutParams(textDim[0], textDim[1])

                val absPos = intArrayOf(layoutDim[0] - textDim[0], layoutDim[1] - textDim[1])
                if (absPos[0] < 0 || absPos[1] < 0) continue // Can't fit that dogeism

                params.leftMargin = if (absPos[0] == 0) 0 else random.nextInt(absPos[0])
                params.topMargin = if (absPos[1] == 0) 0 else random.nextInt(absPos[1])

                params.width += padding * 2 // left + right
                params.height += padding * 2 // top + bottom
                // Padding is subtracted from top/left margins so the measured values are
                // still accurate. We don't care if the shadow clips at the screen edge.
                params.leftMargin = abs(params.leftMargin - padding)
                params.topMargin = abs(params.topMargin - padding)
                view.gravity = Gravity.CENTER // Text is centered within the now padded view

                if (checkWowTextConflict(params)) continue
                created = WowText(params, view)
            }

            newWowText = created ?: return
            runOnUiThread(overlayUiRunnable)
        }

        /** Keeps the newest overlay from repeating one that is already on screen. */
        private fun uniqueDogeism(random: Random, adjectives: Array<String>): String {
            while (true) {
                val candidate = WeatherDoge.getDogeism(random, wows, dogefixes, adjectives)
                val head = synchronized(overlays) { overlays.peek() }
                val duplicate = synchronized(overlays) {
                    overlays.any { wow ->
                        val willBeRemoved = head === wow && overlays.size == 4
                        !willBeRemoved && candidate.contentEquals(wow.view.text)
                    }
                }
                if (!duplicate) return candidate
            }
        }

        private fun checkWowTextConflict(params: RelativeLayout.LayoutParams): Boolean {
            val needleRect = layoutParamsToRect(params)
            synchronized(overlays) {
                val head = overlays.peek()
                for (wow in overlays) {
                    if (head === wow && overlays.size == 4) continue // head is going away
                    if (Rect.intersects(needleRect, layoutParamsToRect(wow.params))) return true
                }
            }
            return false
        }

        private fun layoutParamsToRect(params: RelativeLayout.LayoutParams): Rect =
            Rect(
                params.leftMargin, params.topMargin,
                params.leftMargin + params.width, params.topMargin + params.height
            )
    }

    private val overlayUiRunnable = Runnable {
        synchronized(overlays) {
            if (overlays.size == 4) {
                val v = overlays.remove().view
                suchOverlay.removeView(v)
                suchTopOverlay.removeView(v)
            }
            val fresh = newWowText ?: return@Runnable
            (fresh.view.parent as? ViewGroup)?.removeView(fresh.view)
            if (textOnTop) {
                suchTopOverlay.addView(fresh.view, fresh.params)
            } else {
                suchOverlay.addView(fresh.view, fresh.params)
            }
            overlays.add(fresh)
        }
        if (shadowAdjs) {
            newWowText?.view?.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        } else {
            newWowText?.view?.setShadowLayer(0f, 0f, 0f, 0)
        }
    }

    private class WowText(val params: RelativeLayout.LayoutParams, val view: TextView)

    // ---- sharing ------------------------------------------------------------

    private fun shareWeather(@Suppress("UNUSED_PARAMETER") anchor: View) {
        val i = Intent(Intent.ACTION_SEND).apply { setType("text/plain") }
        val adjectives = weatherAdjectives
        if (adjectives == null) {
            i.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            i.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text).split("\n\n")[1])
        } else {
            var unit = "${0x00b0.toChar()}C"
            var shareTemp = currentTemp
            if (UnitLocale.getDefault() == UnitLocale.IMPERIAL && !forceMetric) {
                shareTemp = shareTemp * 1.8 + 32.0
                unit = "${0x00b0.toChar()}F"
            }
            val temp = "${Math.round(shareTemp)} $unit"
            i.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_title, temp, currentLocation))
            i.putExtra(
                Intent.EXTRA_TEXT,
                getString(
                    R.string.share_text,
                    WeatherDoge.getDogeism(wows, dogefixes, adjectives),
                    temp,
                    currentLocation
                )
            )
        }
        startActivity(Intent.createChooser(i, getString(R.string.action_share)))
    }

    // ---- NYAN tab -----------------------------------------------------------

    private fun selectTab(tab: Tab) {
        if (tab == currentTab) return
        currentTab = tab
        tabDoge.isSelected = tab == Tab.DOGE
        tabNyan.isSelected = tab == Tab.NYAN
        tabDoge.setTextColor(if (tab == Tab.DOGE) 0xFF1A1A2E.toInt() else Color.WHITE)
        tabNyan.setTextColor(if (tab == Tab.NYAN) 0xFF1A1A2E.toInt() else Color.WHITE)

        if (tab == Tab.NYAN) {
            nyanFrame.visibility = View.VISIBLE
            suchDoge.visibility = View.INVISIBLE
            dial.visibility = View.INVISIBLE
            resumeNyan()
        } else {
            stopNyanPlayback()
            nyanFrame.visibility = View.GONE
            suchDoge.visibility = View.VISIBLE
            dial.visibility = View.VISIBLE
            // The tab gave up the audio stage; hand it back to the background music.
            sound.resumeMusicAfterChaos()
        }
    }

    private fun resumeNyan() {
        // Two sound sources at once is a mess: the tab owns the audio while it is open.
        sound.pauseMusicForChaos()
        if (nyanHasVideo) {
            nyanCat.visibility = View.GONE
            nyanVideo.visibility = View.VISIBLE
            nyanVideo.setVideoPath("file:///android_asset/$NYAN_ASSET")
            val volume = sound.videoVolume()
            nyanVideo.setOnPreparedListener { mp ->
                // VideoView has no looping/volume API of its own; drive the player directly.
                mp.isLooping = true
                mp.setVolume(volume, volume)
                if (currentTab == Tab.NYAN && !destroyed) mp.start()
            }
            nyanVideo.requestFocus()
        } else {
            nyanVideo.visibility = View.GONE
            nyanCat.visibility = View.VISIBLE
            nyanCat.animateFrames = !reduceMotion
        }
    }

    /** Stops playback without touching the music toggles — safe to call from onPause. */
    private fun stopNyanPlayback() {
        nyanCat.animateFrames = false
        if (nyanVideo.visibility == View.VISIBLE) {
            try {
                nyanVideo.pause()
                nyanVideo.seekTo(0)
            } catch (e: IllegalStateException) {
                // Never prepared; nothing to stop.
            }
        }
    }

    // ---- CHAOS MODE ---------------------------------------------------------

    private fun toggleChaos() {
        if (chaos.isActive) {
            chaos.cancel() // fires the cleanup exactly once
        } else {
            startChaos()
        }
    }

    private fun startChaos() {
        if (!chaos.start(reduceMotion)) return
        chaosBeats = chaos.script()
        chaosBeatIndex = 0
        chaosLastVisual = -1
        chaosStatusBefore = suchStatus.text

        sound.pauseMusicForChaos()

        chaosFlash.visibility = View.VISIBLE
        chaosFlash.alpha = 1f
        suchStatus.text = getString(R.string.chaos_active)

        chaos.onFinished = { finishChaos() }
        mainHandler.post(chaosTicker)
        Log.i(TAG, "CHAOS MODE started (reduceMotion=$reduceMotion)")
    }

    /** Runs exactly once per run — ChaosMode guarantees it. */
    private fun finishChaos() {
        mainHandler.removeCallbacks(chaosTicker)

        chaosFlash.visibility = View.INVISIBLE
        chaosFlash.alpha = 0f
        rootLayout.translationX = 0f
        rootLayout.translationY = 0f
        dial.rotation = 0f
        chaosStatusBefore?.let { suchStatus.text = it }
        chaosStatusBefore = null

        // Put the real weather art back.
        lastData?.let { data ->
            currentDogeId = Integer.MIN_VALUE
            applyDoge(WeatherDoge.dogeSelect(data.image))
        }
        sound.resumeMusicAfterChaos()
        Toast.makeText(this, R.string.chaos_finished, Toast.LENGTH_SHORT).show()
        Log.i(TAG, "CHAOS MODE finished after ${chaos.elapsedMs()}ms")
    }

    private fun playChaosSfx(sfx: ChaosMode.ChaosSfx) {
        when (sfx) {
            ChaosMode.ChaosSfx.KEYBOARD_CAT -> sound.play(SoundManager.Slot.KEYBOARD_CAT)
            ChaosMode.ChaosSfx.MEOW -> sound.play(SoundManager.Slot.MEOW)
            ChaosMode.ChaosSfx.AIRHORN -> sound.play(SoundManager.Slot.AIRHORN)
        }
    }

    private val chaosDoges = intArrayOf(
        R.drawable.doge_01d, R.drawable.doge_01n, R.drawable.doge_02n, R.drawable.doge_03d,
        R.drawable.doge_03n, R.drawable.doge_04, R.drawable.doge_09, R.drawable.doge_10,
        R.drawable.doge_11, R.drawable.doge_13, R.drawable.doge_50, R.drawable.doge_regular
    )
    private val chaosColors = intArrayOf(
        0x66FF5252, 0x66FF9800, 0x66FFEB3B, 0x6669F0AE, 0x6640C4FF, 0x66B388FF
    )

    private fun cycleChaosVisuals(index: Int) {
        suchDoge.setImageResource(chaosDoges[index % chaosDoges.size])
        currentDogeId = chaosDoges[index % chaosDoges.size]
        chaosFlash.setBackgroundColor(chaosColors[index % chaosColors.size])
        dial.rotation = (index * 12f) % 360f
    }

    // ---- helpers ------------------------------------------------------------

    private fun <T : View> requireView(id: Int): T {
        val view = findViewById<T>(id)
            ?: throw IllegalStateException("Missing view id=${resources.getResourceEntryName(id)}")
        return view
    }

    private fun assetExists(path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (e: IOException) {
        false
    }
}
