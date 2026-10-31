package com.versobit.weatherdoge

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatCheckedTextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.Toolbar
import com.google.android.material.appbar.AppBarLayout
import com.versobit.weatherdoge.dialogs.AboutDialog
import com.versobit.weatherdoge.dialogs.ContributeDialog
import com.versobit.weatherdoge.dialogs.DropShadowDialog
import com.versobit.weatherdoge.dialogs.OtherShibesDialog
import com.versobit.weatherdoge.ui.DogeEditTextPreference

class OptionsActivity : PreferenceActivity() {

    companion object {
        const val EXTRA_SHORTCUT = "shortcut"
        const val EXTRA_SHORTCUT_NONE = -1
        const val EXTRA_SHORTCUT_FORCE_LOCATION = 0

        const val PREF_FORCE_METRIC = "pref_force_metric"
        const val PREF_FORCE_LOCATION = "pref_force_location"
        const val PREF_WEATHER_SOURCE = "pref_weather_source"
        const val PREF_OW_API_KEY = "pref_ow_api_key"
        const val PREF_APP_USE_COMIC_NEUE = "pref_use_comic_neue"
        const val PREF_APP_DROP_SHADOW = "pref_drop_shadow"
        const val PREF_APP_TEXT_ON_TOP = "pref_text_on_top"
        const val PREF_APP_ENABLE_PARTICLES = "pref_enable_particles"
        const val PREF_WIDGET_REFRESH = "pref_widget_refresh"
        const val PREF_WIDGET_TAP_TO_REFRESH = "pref_widget_tap_refresh"
        const val PREF_WIDGET_USE_COMIC_NEUE = "pref_widget_use_comic_neue"
        const val PREF_WIDGET_SHOW_WOWTEXT = "pref_widget_show_wowtext"
        const val PREF_WIDGET_SHOW_DATE = "pref_widget_show_date"
        const val PREF_WIDGET_BACKGROUND_FIX = "pref_widget_background_fix"
        const val PREF_ABOUT_VERSION = "pref_about_version"
        const val PREF_ABOUT_CONTRIBUTE = "pref_about_contribute"
        const val PREF_ABOUT_ADD_CREDITS = "pref_about_additional_credits"
        const val PREF_INTERNAL_LAST_VERSION = "pref_internal_last_version"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = findViewById<View>(android.R.id.content)?.parent as? LinearLayout
        if (root != null) {
            val bar = LayoutInflater.from(this).inflate(R.layout.toolbar_options, root, false) as? AppBarLayout
            if (bar != null) {
                root.addView(bar, 0)
                val toolbar = bar.getChildAt(0) as? Toolbar
                toolbar?.setNavigationOnClickListener { finish() }
            }
        }

        fragmentManager.beginTransaction()
            .replace(android.R.id.content, OptionsFragment())
            .commit()
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        val result = super.onCreateView(name, context, attrs)
        if (result != null) return result

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            when (name) {
                "EditText" -> return AppCompatEditText(this, attrs)
                "Spinner" -> return AppCompatSpinner(this, attrs)
                "CheckBox" -> return AppCompatCheckBox(this, attrs)
                "RadioButton" -> return AppCompatRadioButton(this, attrs)
                "CheckedTextView" -> return AppCompatCheckedTextView(this, attrs)
            }
        }
        return null
    }

    class OptionsFragment : PreferenceFragment() {

        private var genForceMetric = false
        private var genForceLocation = ""
        private var genWeatherSource = "0"
        private var widgetRefreshInterval = "1800"
        private var widgetTapToRefresh = false
        private var widgetComicNeue = false
        private var widgetShowWowText = true
        private var widgetShowDate = false
        private var widgetBackgroundFix = false

        override fun onResume() {
            super.onResume()
            val act = activity ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(act)
            genForceMetric = prefs.getBoolean(PREF_FORCE_METRIC, genForceMetric)
            genForceLocation = prefs.getString(PREF_FORCE_LOCATION, genForceLocation) ?: ""
            genWeatherSource = prefs.getString(PREF_WEATHER_SOURCE, "0") ?: "0"
            widgetRefreshInterval = prefs.getString(PREF_WIDGET_REFRESH, widgetRefreshInterval) ?: "1800"
            widgetTapToRefresh = prefs.getBoolean(PREF_WIDGET_TAP_TO_REFRESH, widgetTapToRefresh)
            widgetComicNeue = prefs.getBoolean(PREF_WIDGET_USE_COMIC_NEUE, widgetComicNeue)
            widgetShowWowText = prefs.getBoolean(PREF_WIDGET_SHOW_WOWTEXT, widgetShowWowText)
            widgetShowDate = prefs.getBoolean(PREF_WIDGET_SHOW_DATE, widgetShowDate)
            widgetBackgroundFix = prefs.getBoolean(PREF_WIDGET_BACKGROUND_FIX, widgetBackgroundFix)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(R.xml.pref_general)

            var fakeHeader = PreferenceCategory(activity)
            fakeHeader.setTitle(R.string.pref_app_header)
            preferenceScreen.addPreference(fakeHeader)
            addPreferencesFromResource(R.xml.pref_app)

            findPreference(PREF_APP_DROP_SHADOW)?.setOnPreferenceClickListener {
                DropShadowDialog().show(fragmentManager, DropShadowDialog.FRAGMENT_TAG)
                true
            }

            // Weather sources configuration
            val srcPref = findPreference(PREF_WEATHER_SOURCE) as? ListPreference
            if (srcPref != null) {
                srcPref.isEnabled = true
                val options = arrayOf<CharSequence>("Open-Meteo (Keyless)", "OpenWeatherMap")
                val values = arrayOf<CharSequence>("0", "1")
                srcPref.entries = options
                srcPref.entryValues = values
                if (srcPref.value == null) {
                    srcPref.value = "0"
                }
            }

            fakeHeader = PreferenceCategory(activity)
            fakeHeader.setTitle(R.string.pref_widget_header)
            preferenceScreen.addPreference(fakeHeader)
            addPreferencesFromResource(R.xml.pref_widget)

            fakeHeader = PreferenceCategory(activity)
            fakeHeader.setTitle(R.string.about)
            preferenceScreen.addPreference(fakeHeader)
            addPreferencesFromResource(R.xml.pref_about)

            val aboutVersion = findPreference(PREF_ABOUT_VERSION)
            if (aboutVersion != null) {
                val vName = BuildConfig.VERSION_NAME.split("-")[0]
                aboutVersion.title = "${getString(R.string.app_name)} v$vName"
                aboutVersion.setOnPreferenceClickListener {
                    AboutDialog().show(fragmentManager, AboutDialog.FRAGMENT_TAG)
                    true
                }
            }

            findPreference(PREF_ABOUT_CONTRIBUTE)?.setOnPreferenceClickListener {
                ContributeDialog().show(fragmentManager, ContributeDialog.FRAGMENT_TAG)
                true
            }

            findPreference(PREF_ABOUT_ADD_CREDITS)?.setOnPreferenceClickListener {
                OtherShibesDialog().show(fragmentManager, OtherShibesDialog.FRAGMENT_TAG)
                true
            }

            findPreference(PREF_FORCE_LOCATION)?.let { bindPreferenceSummaryToValue(it) }
            findPreference(PREF_WEATHER_SOURCE)?.let { bindPreferenceSummaryToValue(it) }
            findPreference(PREF_WIDGET_REFRESH)?.let { bindPreferenceSummaryToValue(it) }

            if (BuildConfig.FLAVOR == BuildConfig.FLAVOR_FOSS) {
                (findPreference(PREF_APP_USE_COMIC_NEUE) as? CheckBoxPreference)?.apply {
                    isChecked = true
                    isEnabled = false
                }
                (findPreference(PREF_WIDGET_USE_COMIC_NEUE) as? CheckBoxPreference)?.apply {
                    isChecked = true
                    isEnabled = false
                }
            }

            val act = activity
            if (act != null) {
                val intent = act.intent
                if (intent.getIntExtra(EXTRA_SHORTCUT, EXTRA_SHORTCUT_NONE) == EXTRA_SHORTCUT_FORCE_LOCATION) {
                    (findPreference(PREF_FORCE_LOCATION) as? DogeEditTextPreference)?.showDialog()
                    intent.removeExtra(EXTRA_SHORTCUT)
                }
            }
        }

        override fun onStop() {
            val act = activity
            if (act != null) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(act)
                val newGenForceMetric = prefs.getBoolean(PREF_FORCE_METRIC, genForceMetric)
                val newGenForceLocation = prefs.getString(PREF_FORCE_LOCATION, genForceLocation) ?: ""
                val strSource = prefs.getString(PREF_WEATHER_SOURCE, "0") ?: "0"
                val newWidgetRefreshInterval = prefs.getString(PREF_WIDGET_REFRESH, widgetRefreshInterval) ?: "1800"
                val newWidgetTapToRefresh = prefs.getBoolean(PREF_WIDGET_TAP_TO_REFRESH, widgetTapToRefresh)
                val newWidgetComicNeue = prefs.getBoolean(PREF_WIDGET_USE_COMIC_NEUE, widgetComicNeue)
                val newWidgetShowWowText = prefs.getBoolean(PREF_WIDGET_SHOW_WOWTEXT, widgetShowWowText)
                val newWidgetShowDate = prefs.getBoolean(PREF_WIDGET_SHOW_DATE, widgetShowDate)
                val newWidgetBackgroundFix = prefs.getBoolean(PREF_WIDGET_BACKGROUND_FIX, widgetBackgroundFix)

                if (newGenForceMetric != genForceMetric ||
                    newGenForceLocation != genForceLocation ||
                    strSource != genWeatherSource ||
                    newWidgetTapToRefresh != widgetTapToRefresh ||
                    newWidgetComicNeue != widgetComicNeue ||
                    newWidgetShowWowText != widgetShowWowText ||
                    newWidgetShowDate != widgetShowDate ||
                    newWidgetBackgroundFix != widgetBackgroundFix) {
                    if (WidgetProvider.areWidgetsActive(act)) {
                        WidgetWorker.enqueueOnceAll(act)
                    }
                }
                if (widgetRefreshInterval != newWidgetRefreshInterval) {
                    WidgetProvider.requeueWorkSchedule(act)
                }
            }
            super.onStop()
        }

        private fun bindPreferenceSummaryToValue(preference: Preference) {
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, value ->
                val stringValue = value?.toString() ?: ""
                if (pref is ListPreference) {
                    val index = pref.findIndexOfValue(stringValue)
                    pref.summary = if (index >= 0) pref.entries[index] else null
                } else {
                    pref.summary = if (stringValue.isEmpty()) pref.context.getString(R.string.unset) else stringValue
                }
                true
            }

            val current = PreferenceManager.getDefaultSharedPreferences(preference.context)
                .getString(preference.key, preference.context.getString(R.string.unset))
            preference.onPreferenceChangeListener.onPreferenceChange(preference, current)
        }
    }
}
