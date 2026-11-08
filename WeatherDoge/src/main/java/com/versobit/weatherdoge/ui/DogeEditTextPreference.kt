package com.versobit.weatherdoge.ui

import android.content.Context
import android.os.Build
import android.preference.EditTextPreference
import android.util.AttributeSet

class DogeEditTextPreference : EditTextPreference {
    constructor(ctx: Context) : super(ctx)
    constructor(ctx: Context, attrs: AttributeSet) : super(ctx, attrs)
    constructor(ctx: Context, attrs: AttributeSet, defStyleAttr: Int) : super(ctx, attrs, defStyleAttr)
    constructor(ctx: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(ctx, attrs, defStyleAttr, defStyleRes)

    fun showDialog() {
        showDialog(null)
    }
}
