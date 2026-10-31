package com.versobit.weatherdoge.ui

import android.content.Context
import android.preference.EditTextPreference
import android.util.AttributeSet

class DogeEditTextPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextPreferenceStyle,
    defStyleRes: Int = 0
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    fun showDialog() {
        showDialog(null)
    }
}
