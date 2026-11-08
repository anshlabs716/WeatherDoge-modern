/*
 * Copyright (C) 2014-2017, 2019, 2025 VersoBit
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
import com.versobit.weatherdoge.dialogs.AboutDialog
import com.versobit.weatherdoge.dialogs.ContributeDialog
import com.versobit.weatherdoge.dialogs.DropShadowDialog
import com.versobit.weatherdoge.dialogs.OtherShibesDialog
import com.versobit.weatherdoge.ui.DogeEditTextPreference
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatCheckedTextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.Toolbar
import com.google.android.material.appbar.AppBarLayout

/**
 * A [PreferenceActivity] that presents a set of application settings. On
 * handset devices, settings are presented as a single list. On tablets,
 * settings are split by category, with category headers shown to the left of
 * the list of settings.
 *
 * Every preference key used anywhere in the app lives in [companion] so that
 * the app, the widget and the background worker can never drift apart.
 */
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

        // Thanks to David Passmore http://stackoverflow.com/a/27455330/238374
        @Suppress("DEPRECATION")
        val root = findViewById<LinearLayout>(android.R.id.content).parent as LinearLayout
        val bar = LayoutInflater.from(this).inflate(R.layout.toolbar_options, root, false) as AppBarLayout
        root.addView(bar, 0)
        val toolbar = bar.getChildAt(0) as Toolbar
        toolbar.setNavigationOnClickListener { finish() }

        fragmentManager.beginTransaction()
            .replace(android.R.id.content, OptionsFragment())
            .commit()
    }

    @Suppress("DEPRECATION")
    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        val result = super.onCreateView(name, context, attrs)
        if (result != null) {
            return result
        }

        // Provide colorized/tinted widgets on non-Material devices
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
        private var widgetRefreshInterval = "1800"
        private var widgetTapToRefresh = false
        private var widgetComicNeue = false
        private var widgetShowWowText = true
        private var widgetShowDate = false
        private var widgetBackgroundFix = false

        override fun onResume() {
            super.onResume()
            @Suppress("DEPRECATION")
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            genForceMetric = prefs.getBoolean(PREF_FORCE_METRIC, genForceMetric)
            genForceLocation = prefs.getString(PREF_FORCE_LOCATION, genForceLocation) ?: genForceLocation
            widgetRefreshInterval = prefs.getString(PREF_WIDGET_REFRESH, widgetRefreshInterval) ?: widgetRefreshInterval
            widgetTapToRefresh = prefs.getBoolean(PREF_WIDGET_TAP_TO_REFRESH, widgetTapToRefresh)
            widgetComicNeue = prefs.getBoolean(PREF_WIDGET_USE_COMIC_NEUE, widgetComicNeue)
            widgetShowWowText = prefs.getBoolean(PREF_WIDGET_SHOW_WOWTEXT, widgetShowWowText)
            widgetShowDate = prefs.getBoolean(PREF_WIDGET_SHOW_DATE, widgetShowDate)
            widgetBackgroundFix = prefs.getBoolean(PREF_WIDGET_BACKGROUND_FIX, widgetBackgroundFix)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // Add 'general' preferences.
            addPreferencesFromResource(R.xml.pref_general)

            // Add 'app' preferences, and a corresponding header.
            var fakeHeader = PreferenceCategory(activity)
            fakeHeader.setTitle(R.string.pref_app_header)
            preferenceScreen.addPreference(fakeHeader)
            addPreferencesFromResource(R.xml.pref_app)
            findPreference(PREF_APP_DROP_SHADOW).onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    DropShadowDialog().show(fragmentManager, DropShadowDialog.FRAGMENT_TAG)
                    true
                }

            // Add 'widget' preferences, and a corresponding header.
            fakeHeader = PreferenceCategory(activity)
            fakeHeader.setTitle(R.string.pref_widget_header)
            preferenceScreen.addPreference(fakeHeader)
            addPreferencesFromResource(R.xml.pref_widget)

            // Add 'about' preferences, and a corresponding header.
            fakeHeader = PreferenceCategory(activity)
            fakeHeader.setTitle(R.string.about)
            preferenceScreen.addPreference(fakeHeader)
            addPreferencesFromResource(R.xml.pref_about)

            val aboutVersion = findPreference(PREF_ABOUT_VERSION)
            aboutVersion.title = getString(R.string.app_name) + " v" +
                    BuildConfig.VERSION_NAME.split("-")[0]
            aboutVersion.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                AboutDialog().show(fragmentManager, AboutDialog.FRAGMENT_TAG)
                true
            }
            findPreference(PREF_ABOUT_CONTRIBUTE).onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    ContributeDialog().show(fragmentManager, ContributeDialog.FRAGMENT_TAG)
                    true
                }
            findPreference(PREF_ABOUT_ADD_CREDITS).onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    OtherShibesDialog().show(fragmentManager, OtherShibesDialog.FRAGMENT_TAG)
                    true
                }

            // Bind the summaries of EditText/List/Dialog preferences to their values.
            bindPreferenceSummaryToValue(findPreference(PREF_FORCE_LOCATION))
            bindPreferenceSummaryToValue(findPreference(PREF_OW_API_KEY))
            bindPreferenceSummaryToValue(findPreference(PREF_WEATHER_SOURCE))
            bindPreferenceSummaryToValue(findPreference(PREF_WIDGET_REFRESH))

            // Disable the ability to switch fonts for FOSS flavor
            if (BuildConfig.FLAVOR == BuildConfig.FLAVOR_FOSS) {
                val appNeue = findPreference(PREF_APP_USE_COMIC_NEUE) as CheckBoxPreference
                val widgetNeue = findPreference(PREF_WIDGET_USE_COMIC_NEUE) as CheckBoxPreference
                appNeue.isChecked = true
                appNeue.isEnabled = false
                widgetNeue.isChecked = true
                widgetNeue.isEnabled = false
            }

            // Apply a shortcut
            activity?.intent?.let { i ->
                when (i.getIntExtra(EXTRA_SHORTCUT, EXTRA_SHORTCUT_NONE)) {
                    EXTRA_SHORTCUT_FORCE_LOCATION ->
                        (findPreference(PREF_FORCE_LOCATION) as DogeEditTextPreference).showDialog()
                }
                // Prevent it from displaying again
                i.removeExtra(EXTRA_SHORTCUT)
            }
        }

        override fun onStop() {
            // Refresh widget options if they've changed
            @Suppress("DEPRECATION")
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val newGenForceMetric = prefs.getBoolean(PREF_FORCE_METRIC, genForceMetric)
            val newGenForceLocation = prefs.getString(PREF_FORCE_LOCATION, genForceLocation) ?: genForceLocation
            val newWidgetRefreshInterval = prefs.getString(PREF_WIDGET_REFRESH, widgetRefreshInterval) ?: widgetRefreshInterval
            val newWidgetTapToRefresh = prefs.getBoolean(PREF_WIDGET_TAP_TO_REFRESH, widgetTapToRefresh)
            val newWidgetComicNeue = prefs.getBoolean(PREF_WIDGET_USE_COMIC_NEUE, widgetComicNeue)
            val newWidgetShowWowText = prefs.getBoolean(PREF_WIDGET_SHOW_WOWTEXT, widgetShowWowText)
            val newWidgetShowDate = prefs.getBoolean(PREF_WIDGET_SHOW_DATE, widgetShowDate)
            val newWidgetBackgroundFix = prefs.getBoolean(PREF_WIDGET_BACKGROUND_FIX, widgetBackgroundFix)
            if (newGenForceMetric != genForceMetric ||
                genForceLocation != newGenForceLocation ||
                newWidgetTapToRefresh != widgetTapToRefresh ||
                newWidgetComicNeue != widgetComicNeue ||
                newWidgetShowWowText != widgetShowWowText ||
                newWidgetShowDate != widgetShowDate ||
                newWidgetBackgroundFix != widgetBackgroundFix
            ) {
                activity?.let {
                    if (WidgetProvider.areWidgetsActive(it)) {
                        WidgetWorker.enqueueOnceAll()
                    }
                }
            }
            if (widgetRefreshInterval != newWidgetRefreshInterval) {
                activity?.let { WidgetProvider.requeueWorkSchedule(it) }
            }
            super.onStop()
        }

        companion object {
            /**
             * A preference value change listener that updates the preference's summary
             * to reflect its new value.
             */
            private val sBindPreferenceSummaryToValueListener =
                Preference.OnPreferenceChangeListener { preference, value ->
                    val stringValue = value?.toString() ?: ""

                    if (preference is ListPreference) {
                        // For list preferences, look up the correct display value in
                        // the preference's 'entries' list.
                        val index = preference.findIndexOfValue(stringValue)
                        preference.summary = if (index >= 0) preference.entries[index] else null
                    } else {
                        // For all other preferences, set the summary to the value's
                        // simple string representation.
                        preference.summary = if (stringValue.isEmpty()) {
                            preference.context.getString(R.string.unset)
                        } else {
                            stringValue
                        }
                    }
                    true
                }

            /**
             * Binds a preference's summary to its value. More specifically, when the
             * preference's value is changed, its summary (line of text below the
             * preference title) is updated to reflect the new value.
             */
            private fun bindPreferenceSummaryToValue(preference: Preference) {
                preference.onPreferenceChangeListener = sBindPreferenceSummaryToValueListener
                @Suppress("DEPRECATION")
                sBindPreferenceSummaryToValueListener.onPreferenceChange(
                    preference,
                    PreferenceManager.getDefaultSharedPreferences(preference.context)
                        .getString(preference.key, preference.context.getString(R.string.unset))
                )
            }
        }
    }
}
