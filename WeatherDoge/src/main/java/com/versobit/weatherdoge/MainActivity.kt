package com.versobit.weatherdoge

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getbase.floatingactionbutton.FloatingActionsMenu
import com.google.android.material.snackbar.Snackbar
import com.plattysoft.leonids.ParticleSystem
import com.versobit.weatherdoge.data.DailyForecast
import com.versobit.weatherdoge.data.WeatherRepository
import com.versobit.weatherdoge.location.ApiStatus
import com.versobit.weatherdoge.location.DogeLocationApi
import com.versobit.weatherdoge.location.FlavoredApiSelector
import com.versobit.weatherdoge.location.LocationReceiver
import com.versobit.weatherdoge.ui.sound.SoundManager
import com.versobit.weatherdoge.util.Units
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.Queue
import java.util.Random
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil

class MainActivity : Activity(), LocationReceiver, ActivityCompat.OnRequestPermissionsResultCallback {

    companion object {
        private const val WOW_INTERVAL = 2300L
        private val TAG = MainActivity::class.java.simpleName
        private const val REQUEST_LOCATION_PERMISSION = 410
    }

    private var forceMetric = false
    private var forceLocation = ""
    private var weatherSource = WeatherUtil.Source.OPEN_METEO
    private var useNeue = false
    private var shadowR = 1f
    private var shadowX = 3f
    private var shadowY = 3f
    private var shadowAdjs = false
    private var textOnTop = false
    private var enableParticles = true
    private var lastVersion = 0

    private var suchBg: ImageView? = null
    private var suchOverlay: RelativeLayout? = null
    private var suchTopOverlay: RelativeLayout? = null
    private var suchInfoGroup: LinearLayout? = null
    private var suchDoge: ImageView? = null
    private var suchStatus: TextView? = null
    private var suchTempGroup: RelativeLayout? = null
    private var suchNegative: TextView? = null
    private var suchTemp: TextView? = null
    private var suchDegree: TextView? = null
    private var suchLocation: TextView? = null
    private var suchMenu: FloatingActionsMenu? = null
    private val wowApi: DogeLocationApi = FlavoredApiSelector.get()

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
    private val overlays: Queue<WowText> = ArrayDeque(4)
    private var currentBackgroundId = Int.MIN_VALUE
    private var currentDogeId = R.drawable.doge_01d
    private var currentlyAnim = false

    private val asyncExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "main-async").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val soundManager: SoundManager
        get() = (application as? WeatherDoge)?.soundManager ?: SoundManager(this)

    private var isChaos = false

    private class WowText(
        var params: RelativeLayout.LayoutParams?,
        val view: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wowApi.configure(this, this)
        loadOptions()

        dogefixes = resources.getStringArray(R.array.dogefix)
        wows = resources.getStringArray(R.array.wows)
        colors = resources.getIntArray(R.array.wow_colors)

        suchBg = findViewById(R.id.main_suchbg)
        suchOverlay = findViewById(R.id.main_suchoverlay)
        suchTopOverlay = findViewById(R.id.main_suchtopoverlay)
        suchInfoGroup = findViewById(R.id.main_suchinfogroup)
        suchInfoGroup?.setOnClickListener {
            val link = currentLink
            if (link != null) {
                val i = Intent(Intent.ACTION_VIEW, link)
                WeatherDoge.applyChromeCustomTab(this@MainActivity, i)
                startActivity(i)
            } else {
                showForecastDialog()
            }
        }

        suchDoge = findViewById(R.id.main_suchdoge)
        suchDoge?.setOnClickListener {
            soundManager.playBonk()
            val zoom = AnimationUtils.loadAnimation(this, R.anim.dogezoom_in)
            suchDoge?.startAnimation(zoom)
            fetchWeather()
        }

        suchDoge?.setOnLongClickListener {
            startChaosMode()
            true
        }

        suchStatus = findViewById(R.id.main_suchstatus)
        suchTempGroup = findViewById(R.id.main_suchtempgroup)
        suchNegative = findViewById(R.id.main_suchnegative)
        suchTemp = findViewById(R.id.main_suchtemp)
        suchDegree = findViewById(R.id.main_suchdegree)
        suchLocation = findViewById(R.id.main_suchlocation)
        suchMenu = findViewById(R.id.main_fam)

        findViewById<View>(R.id.man_fab_share)?.setOnClickListener {
            suchMenu?.collapse()
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                val adjectives = weatherAdjectives
                if (adjectives == null) {
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text).split("\n\n").getOrElse(1) { "Very doge weather" })
                } else {
                    var tempTemp = currentTemp
                    var unit = "°C"
                    if (UnitLocale.getDefault() == UnitLocale.IMPERIAL && !forceMetric) {
                        tempTemp = tempTemp * 1.8 + 32.0
                        unit = "°F"
                    }
                    val temp = "${Math.round(tempTemp)}$unit"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_title, temp, currentLocation))
                    val ism = WeatherDoge.getDogeism(wows, dogefixes, adjectives)
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, ism, temp, currentLocation))
                }
            }
            startActivity(Intent.createChooser(i, getString(R.string.action_share)))
        }

        findViewById<View>(R.id.man_fab_forecast)?.setOnClickListener {
            suchMenu?.collapse()
            showForecastDialog()
        }

        findViewById<View>(R.id.man_fab_airhorn)?.setOnClickListener {
            suchMenu?.collapse()
            soundManager.play(SoundManager.Slot.AIRHORN)
        }

        findViewById<View>(R.id.man_fab_chaos)?.setOnClickListener {
            suchMenu?.collapse()
            startChaosMode()
        }

        findViewById<View>(R.id.man_fab_nyan)?.setOnClickListener {
            suchMenu?.collapse()
            showNyanDialog()
        }

        findViewById<View>(R.id.man_fab_options)?.setOnClickListener {
            suchMenu?.collapse()
            startActivity(Intent(this@MainActivity, OptionsActivity::class.java))
        }

        updateFont()
        updateShadow()

        // Load cached weather right away if available
        val cached = Cache.loadWeatherData(this)
        if (cached != null) {
            onWeatherLoaded(cached, null)
        }

        if (forceLocation.isNotEmpty()) {
            fetchWeather()
        } else {
            wowApi.isAvailable
        }

        setBackground(R.drawable.sky_01d)

        if (BuildConfig.VERSION_CODE > lastVersion) {
            lastVersion = BuildConfig.VERSION_CODE
            val sp = PreferenceManager.getDefaultSharedPreferences(this)
            sp.edit().putInt(OptionsActivity.PREF_INTERNAL_LAST_VERSION, lastVersion).apply()
        }
    }

    private fun loadOptions() {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        forceMetric = sp.getBoolean(OptionsActivity.PREF_FORCE_METRIC, false)
        forceLocation = sp.getString(OptionsActivity.PREF_FORCE_LOCATION, "") ?: ""
        weatherSource = WeatherUtil.Source.fromKey(
            sp.getString(OptionsActivity.PREF_WEATHER_SOURCE, WeatherUtil.Source.OPEN_METEO.key)
        )
        useNeue = sp.getBoolean(OptionsActivity.PREF_APP_USE_COMIC_NEUE, false)
        shadowR = sp.getFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_radius", 1f)
        shadowX = sp.getFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_x", 3f)
        shadowY = sp.getFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_y", 3f)
        shadowAdjs = sp.getBoolean(OptionsActivity.PREF_APP_DROP_SHADOW + "_adjs", false)
        textOnTop = sp.getBoolean(OptionsActivity.PREF_APP_TEXT_ON_TOP, false)
        enableParticles = sp.getBoolean(OptionsActivity.PREF_APP_ENABLE_PARTICLES, true)
        lastVersion = sp.getInt(OptionsActivity.PREF_INTERNAL_LAST_VERSION, lastVersion)
    }

    override fun onStart() {
        super.onStart()
        if (forceLocation.isEmpty()) {
            requestLocation()
        }
        overlayTask = OverlayTimerTask()
        overlayTimer.schedule(overlayTask, 0, WOW_INTERVAL)
        if (WeatherDoge.isSnowing(currentBackgroundId) && enableParticles) {
            particleSystem?.cancel()
            particleSystem = newParticleSystem()
            particleSystem?.setStartTime(25000)
            startParticleSystem()
        }
    }

    override fun onStop() {
        if (wowApi.status != ApiStatus.DISCONNECTED) {
            wowApi.disconnect()
        }
        overlayTask?.cancel()
        overlayTask = null
        if (particleSystem != null && isEmitting) {
            particleSystem?.cancel()
            isEmitting = false
        }
        soundManager.stopMusic()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val oldNeue = useNeue
        val oldShadowR = shadowR
        val oldShadowX = shadowX
        val oldShadowY = shadowY
        val oldShadowBool = shadowAdjs
        loadOptions()

        if (useNeue != oldNeue) {
            updateFont()
        }
        if (oldShadowR != shadowR || oldShadowX != shadowX || oldShadowY != shadowY || oldShadowBool != shadowAdjs) {
            updateShadow()
        }
        if (forceLocation.isEmpty()) {
            if (wowApi.status == ApiStatus.DISCONNECTED) {
                requestLocation()
            }
        } else {
            if (wowApi.status != ApiStatus.DISCONNECTED) {
                wowApi.disconnect()
            }
            fetchWeather()
        }

        if (!enableParticles && isEmitting) {
            particleSystem?.cancel()
            isEmitting = false
        } else if (WeatherDoge.isSnowing(currentBackgroundId) && enableParticles && !isEmitting) {
            particleSystem?.cancel()
            particleSystem = newParticleSystem()
            particleSystem?.setStartTime(25000)
            startParticleSystem()
            isEmitting = true
        }

        if (soundManager.musicEnabled && !soundManager.masterMute) {
            soundManager.startMusic()
        }
    }

    override fun onPause() {
        soundManager.pauseMusicForChaos()
        super.onPause()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val menu = suchMenu
        if (ev.action == MotionEvent.ACTION_DOWN && menu != null && menu.isExpanded) {
            val outRect = Rect()
            menu.getGlobalVisibleRect(outRect)
            if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                menu.collapse()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val menu = suchMenu
        if (menu != null && menu.isExpanded) {
            menu.collapse()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun updateFont() {
        val tempView = suchTemp ?: return
        val layoutParams = tempView.layoutParams as? RelativeLayout.LayoutParams ?: return
        if (useNeue || BuildConfig.FLAVOR == BuildConfig.FLAVOR_FOSS) {
            wowComicSans = try {
                Typeface.createFromAsset(assets, "ComicNeue-Regular.ttf")
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
            layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            for (wowText in overlays) {
                wowText.view.typeface = wowComicSans
            }
        } else {
            wowComicSans = try {
                Typeface.createFromAsset(assets, "comic.ttf")
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
            layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, 0)
        }
        suchDegree?.typeface = wowComicSans
        suchStatus?.typeface = wowComicSans
        suchLocation?.typeface = wowComicSans
        suchNegative?.typeface = wowComicSans
        suchTemp?.typeface = wowComicSans
        suchTemp?.layoutParams = layoutParams
    }

    private fun updateShadow() {
        suchStatus?.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        suchNegative?.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        suchTemp?.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        suchDegree?.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        suchLocation?.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
        for (wowText in overlays) {
            if (shadowAdjs) {
                wowText.view.setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
            } else {
                wowText.view.setShadowLayer(0f, 0f, 0f, 0)
            }
        }
    }

    override fun onConnected() {
        requestLocation()
    }

    override fun onLocation(location: Location) {
        whereIsDoge = location
        fetchWeather(location)
    }

    private fun requestLocation(skipRationale: Boolean = false) {
        if (ContextCompat.checkSelfPermission(this, WeatherDoge.LOCATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            if (!skipRationale && ActivityCompat.shouldShowRequestPermissionRationale(this, WeatherDoge.LOCATION_PERMISSION)) {
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

    private fun showLocationRationaleDialog() {
        if ((rationaleDialog != null && rationaleDialog?.isShowing == true) ||
            (locationSnackbar != null && locationSnackbar?.isShown == true)) {
            return
        }
        val adb = AlertDialog.Builder(this)
            .setMessage(R.string.location_rationale_text)
            .setNegativeButton(R.string.location_rationale_negative) { _, _ ->
                startActivity(
                    Intent(this@MainActivity, OptionsActivity::class.java)
                        .putExtra(OptionsActivity.EXTRA_SHORTCUT, OptionsActivity.EXTRA_SHORTCUT_FORCE_LOCATION)
                )
            }
            .setPositiveButton(R.string.location_rationale_positive) { _, _ ->
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(WeatherDoge.LOCATION_PERMISSION),
                    REQUEST_LOCATION_PERMISSION
                )
            }
            .setOnCancelListener { showLocationSnackbar() }
        if (!isFinishing) {
            rationaleDialog = adb.show()
        }
    }

    private fun showLocationSnackbar() {
        if (locationSnackbar != null && locationSnackbar?.isShown == true) {
            return
        }
        val root = findViewById<View>(R.id.main_suchlayout) ?: return
        locationSnackbar = Snackbar.make(root, R.string.location_snackbar_text, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.location_snackbar_button) { requestLocation(true) }
            .setActionTextColor(ContextCompat.getColor(this, R.color.primary_dark))
        locationSnackbar?.show()
    }

    private fun doCheckedLocationRequest() {
        if (wowApi.status == ApiStatus.DISCONNECTED) {
            wowApi.connect()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_LOCATION_PERMISSION) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(WidgetWorker.PERMISSION_NOTIFICATION_ID)
            if (WidgetProvider.areWidgetsActive(this)) {
                WidgetWorker.enqueueOnceAll(this)
            }
            locationSnackbar?.dismiss()
            doCheckedLocationRequest()
        } else {
            showLocationSnackbar()
        }
    }

    private fun newParticleSystem(): ParticleSystem {
        return ParticleSystem(this, 200, R.drawable.snowflake, 20000L)
            .setSpeedByComponentsRange(0f, 0f, 0.05f, 0.1f)
            .setScaleRange(0.2f, 1f)
            .setParentViewGroup(findViewById(R.id.main_snowframe))
    }

    private fun startParticleSystem() {
        val emitter = findViewById<View>(R.id.snow_emitter) ?: return
        isEmitting = true
        particleSystem?.emitWithGravity(emitter, Gravity.TOP, 5)
    }

    private fun fetchWeather(loc: Location? = whereIsDoge) {
        if (rationaleDialog?.isShowing == true) rationaleDialog?.dismiss()
        locationSnackbar?.dismiss()

        asyncExecutor.execute {
            val result = if (forceLocation.isNotEmpty()) {
                WeatherUtil.getWeather(forceLocation, weatherSource)
            } else if (loc != null) {
                WeatherUtil.getWeather(loc.latitude, loc.longitude, weatherSource)
            } else {
                val lastKnown = WeatherRepository.lastKnownLocation(this@MainActivity)
                if (lastKnown != null) {
                    WeatherUtil.getWeather(lastKnown.first, lastKnown.second, weatherSource)
                } else {
                    WeatherUtil.getWeather(0.0, 0.0, weatherSource)
                }
            }

            var addr: Address? = null
            if (forceLocation.isEmpty() && loc != null && Geocoder.isPresent()) {
                try {
                    @Suppress("DEPRECATION")
                    val list = Geocoder(this@MainActivity).getFromLocation(loc.latitude, loc.longitude, 1)
                    if (!list.isNullOrEmpty()) addr = list[0]
                } catch (e: Exception) {
                    Log.w(TAG, "Geocoder lookup failed", e)
                }
            }

            mainHandler.post {
                if (result.error == WeatherUtil.WeatherResult.ERROR_NONE && result.data != null) {
                    onWeatherLoaded(result.data, addr)
                } else {
                    val msg = result.msg ?: result.throwable?.message ?: "Weather error"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onWeatherLoaded(data: WeatherUtil.WeatherData, addr: Address?) {
        setDoge(WeatherDoge.dogeSelect(data.image))
        setBackground(WeatherDoge.skySelect(data.image))

        currentLocation = if (forceLocation.isEmpty() && addr != null) {
            addr.locality ?: data.place
        } else {
            data.place
        }

        val tempAdjs = resources.getStringArray(WeatherDoge.getTempAdjectives(data.temperature.toInt()))
        val bgAdjs = resources.getStringArray(WeatherDoge.getBgAdjectives(data.image))
        weatherAdjectives = tempAdjs + bgAdjs

        if (data.link.startsWith("http")) {
            currentLink = Uri.parse(data.link)
        }

        val description = "${getString(R.string.wow)} ${data.condition.trim().lowercase(Locale.US)}"
        animateWeatherUpdate(data, description)
    }

    private fun animateWeatherUpdate(data: WeatherUtil.WeatherData, description: String) {
        val animTime = (resources.getInteger(R.integer.anim_refresh_time) / 2.5).toInt()
        val fadeOut = AnimationUtils.loadAnimation(this, R.anim.textfade_out)
        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.textfade_in)

        suchStatus?.startAnimation(fadeOut)
        mainHandler.postDelayed({
            suchStatus?.text = description
            suchStatus?.startAnimation(fadeIn)

            currentTemp = data.temperature
            currentlyMetric = UnitLocale.getDefault() != UnitLocale.IMPERIAL || forceMetric
            var dispTemp = currentTemp
            if (!currentlyMetric) {
                dispTemp = dispTemp * 1.8 + 32.0
            }
            val df = DecimalFormat().apply {
                negativePrefix = ""
                negativeSuffix = ""
                maximumFractionDigits = 0
                isDecimalSeparatorAlwaysShown = false
                isGroupingUsed = false
            }
            suchTemp?.text = df.format(dispTemp)
            suchNegative?.visibility = if (dispTemp < 0) View.VISIBLE else View.GONE
            suchDegree?.visibility = View.VISIBLE
            suchLocation?.text = currentLocation

            if (WeatherDoge.isSnowing(currentBackgroundId) && enableParticles) {
                if (!isEmitting) {
                    particleSystem?.cancel()
                    particleSystem = newParticleSystem()
                    startParticleSystem()
                }
            } else if (particleSystem != null && isEmitting) {
                particleSystem?.stopEmitting()
                isEmitting = false
            }
        }, animTime.toLong())
    }

    private fun setDoge(resId: Int) {
        if (currentDogeId == resId) return
        currentDogeId = resId
        val zoomOut = AnimationUtils.loadAnimation(this, R.anim.dogezoom_out)
        val zoomIn = AnimationUtils.loadAnimation(this, R.anim.dogezoom_in)
        zoomOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                suchDoge?.setImageResource(resId)
                suchDoge?.startAnimation(zoomIn)
            }
            override fun onAnimationRepeat(animation: Animation?) {}
        })
        suchDoge?.startAnimation(zoomOut)
    }

    private fun setBackground(resId: Int) {
        if (currentBackgroundId == resId) return
        currentBackgroundId = resId
        asyncExecutor.execute {
            val resources = resources
            val theSky = BitmapFactory.decodeResource(resources, resId) ?: return@execute
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(metrics)

            val magic = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 180f, metrics)
            val scaledSky = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(scaledSky)

            val wScale = (scaledSky.width.toFloat() + magic) / theSky.width.toFloat()
            val hScale = scaledSky.height.toFloat() / theSky.height.toFloat()
            val bigScale = maxOf(wScale, hScale)
            val matrix = Matrix()
            matrix.setScale(bigScale, bigScale, magic, 0f)
            canvas.setMatrix(matrix)
            canvas.drawBitmap(theSky, 0f, 0f, Paint())
            theSky.recycle()

            mainHandler.post {
                val current = suchBg?.drawable
                val newDrawable = BitmapDrawable(resources, scaledSky)
                if (current != null) {
                    val transition = TransitionDrawable(arrayOf(current, newDrawable))
                    transition.isCrossFadeEnabled = true
                    suchBg?.setImageDrawable(transition)
                    transition.startTransition(resources.getInteger(R.integer.anim_refresh_time) * 2)
                } else {
                    suchBg?.setImageDrawable(newDrawable)
                }
            }
        }
    }

    private fun startChaosMode() {
        if (isChaos) return
        isChaos = true
        soundManager.pauseMusicForChaos()
        soundManager.play(SoundManager.Slot.AIRHORN)

        val shake = AnimationUtils.loadAnimation(this, R.anim.dogezoom_out)
        suchDoge?.startAnimation(shake)

        // Rapid chaos sound effects and visuals
        mainHandler.postDelayed({
            soundManager.play(SoundManager.Slot.KEYBOARD_CAT)
        }, 800)

        mainHandler.postDelayed({
            soundManager.play(SoundManager.Slot.MEOW)
            soundManager.play(SoundManager.Slot.AIRHORN)
        }, 2200)

        // Chaos overlay burst
        val rnd = Random()
        for (i in 0 until 6) {
            mainHandler.postDelayed({
                val colors = intArrayOf(Color.RED, Color.YELLOW, Color.MAGENTA, Color.CYAN, Color.GREEN)
                suchOverlay?.setBackgroundColor(colors[rnd.nextInt(colors.size)] and 0x33FFFFFF)
            }, (i * 400).toLong())
        }

        mainHandler.postDelayed({
            isChaos = false
            suchOverlay?.setBackgroundColor(Color.TRANSPARENT)
            soundManager.resumeMusicAfterChaos()
            suchDoge?.setImageResource(currentDogeId)
        }, 5500)
    }

    private fun showForecastDialog() {
        val current = WeatherRepository.current(this)
        val daily = current?.daily ?: emptyList()
        if (daily.isEmpty()) {
            Toast.makeText(this, "Forecast loading...", Toast.LENGTH_SHORT).show()
            return
        }

        val context = this
        val dialogLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(context).apply {
            text = getString(R.string.forecast_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 16)
        }
        dialogLayout.addView(title)

        val formatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
        for (d in daily) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                gravity = Gravity.CENTER_VERTICAL
            }

            val dateStr = try {
                Instant.ofEpochMilli(d.epochDay * 86400000L).atZone(ZoneId.systemDefault()).format(formatter)
            } catch (e: Exception) {
                "Day"
            }

            val dateView = TextView(context).apply {
                text = dateStr
                textSize = 15f
                setTextColor(Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            }
            row.addView(dateView)

            val condView = TextView(context).apply {
                text = d.condition
                textSize = 13f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f)
            }
            row.addView(condView)

            val hi = d.highC?.let { Units.formatShort(it, forceMetric) } ?: "--"
            val lo = d.lowC?.let { Units.formatShort(it, forceMetric) } ?: "--"
            val tempView = TextView(context).apply {
                text = "$hi / $lo"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tempView)

            dialogLayout.addView(row)
        }

        val scroll = ScrollView(context).apply { addView(dialogLayout) }
        AlertDialog.Builder(context)
            .setView(scroll)
            .setPositiveButton(R.string.wow) { d, _ -> d.dismiss() }
            .show()
    }

    private fun showNyanDialog() {
        val context = this
        val nyanPath = soundManager.resolveAsset(SoundManager.Slot.NYAN)
        if (nyanPath == null) {
            Toast.makeText(context, "Nyan media unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val frame = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600)
            setBackgroundColor(Color.BLACK)
        }

        val videoView = VideoView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        }
        frame.addView(videoView)

        // Copy asset to cache for VideoView playback
        val cacheFile = File(cacheDir, "temp_nyan.mp4")
        if (!cacheFile.exists()) {
            try {
                assets.open(nyanPath).use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error caching nyan video", e)
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.action_nyan)
            .setView(frame)
            .setPositiveButton(R.string.wow) { d, _ -> d.dismiss() }
            .setOnDismissListener {
                videoView.stopPlayback()
            }
            .create()

        dialog.show()

        if (cacheFile.exists()) {
            videoView.setVideoPath(cacheFile.absolutePath)
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                videoView.start()
            }
        }
    }

    private inner class OverlayTimerTask : TimerTask() {
        override fun run() {
            val adjectives = weatherAdjectives ?: return
            val r = Random()
            var wowText: WowText? = null

            for (attempt in 0..20) {
                val candidateView = TextView(this@MainActivity).apply {
                    typeface = wowComicSans
                    if (shadowAdjs) {
                        setShadowLayer(shadowR, shadowX, shadowY, Color.BLACK)
                    }
                    text = getUniqueDogeism(r, adjectives)
                    setTextColor(colors[r.nextInt(colors.size)])
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, (r.nextInt(15) + 25).toFloat())
                }

                val overlay = suchOverlay ?: break
                val layoutW = overlay.width
                val layoutH = overlay.height
                if (layoutW <= 0 || layoutH <= 0) break

                candidateView.measure(layoutW, layoutH)
                val textW = candidateView.measuredWidth
                val textH = candidateView.measuredHeight

                val padding = ceil(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)).toInt()
                val absPos = intArrayOf(layoutW - textW, layoutH - textH)
                if (absPos[0] < 0 || absPos[1] < 0) continue

                val params = RelativeLayout.LayoutParams(textW + padding * 2, textH + padding * 2).apply {
                    leftMargin = abs(if (absPos[0] == 0) 0 else r.nextInt(absPos[0]) - padding)
                    topMargin = abs(if (absPos[1] == 0) 0 else r.nextInt(absPos[1]) - padding)
                }
                candidateView.gravity = Gravity.CENTER

                val candidate = WowText(params, candidateView)
                if (!checkWowTextConflict(candidate)) {
                    wowText = candidate
                    break
                }
            }

            val valid = wowText ?: return
            newWowText = valid
            mainHandler.post(overlayUiRunnable)
        }

        private fun getUniqueDogeism(r: Random, adjectives: Array<String>): String {
            var ism: String? = null
            var tries = 0
            while (ism == null && tries < 20) {
                tries++
                val candidate = WeatherDoge.getDogeism(r, wows, dogefixes, adjectives)
                val head = overlays.peek()
                var duplicate = false
                for (wow in overlays) {
                    if (head == wow && overlays.size == 4) continue
                    if (candidate == wow.view.text.toString()) {
                        duplicate = true
                        break
                    }
                }
                if (!duplicate) ism = candidate
            }
            return ism ?: "wow"
        }

        private fun checkWowTextConflict(needle: WowText): Boolean {
            val params = needle.params ?: return false
            val needleRect = Rect(params.leftMargin, params.topMargin, params.leftMargin + params.width, params.topMargin + params.height)
            val head = overlays.peek()
            for (wow in overlays) {
                if (head == wow && overlays.size == 4) continue
                val wp = wow.params ?: continue
                val other = Rect(wp.leftMargin, wp.topMargin, wp.leftMargin + wp.width, wp.topMargin + wp.height)
                if (Rect.intersects(needleRect, other)) return true
            }
            return false
        }
    }

    private val overlayUiRunnable = Runnable {
        val newText = newWowText ?: return@Runnable
        if (overlays.size == 4) {
            val old = overlays.remove()
            suchOverlay?.removeView(old.view)
            suchTopOverlay?.removeView(old.view)
        }
        (newText.view.parent as? ViewGroup)?.removeView(newText.view)
        if (textOnTop) {
            suchTopOverlay?.addView(newText.view, newText.params)
        } else {
            suchOverlay?.addView(newText.view, newText.params)
        }
        overlays.add(newText)
    }
}
