package com.versobit.weatherdoge.dialogs

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.versobit.weatherdoge.BuildConfig
import com.versobit.weatherdoge.R

class OtherShibesDialog : DialogFragment() {

    companion object {
        const val FRAGMENT_TAG = "fragment_dialog_othershibes"
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val act = activity ?: return super.onCreateDialog(savedInstanceState)
        val wv = WebView(act)
        wv.settings.defaultTextEncodingName = "utf-8"
        wv.settings.javaScriptEnabled = true
        wv.addJavascriptInterface(JsInterface(), "App")

        wv.webViewClient = object : WebViewClient() {
            @TargetApi(Build.VERSION_CODES.N)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.method != "GET") {
                    return true
                }
                val scheme = request.url.scheme
                if ("https" == scheme || "http" == scheme) {
                    return false
                }
                val url = request.url.toString()
                if ("file:///android_asset/othershibes.html" == url ||
                    "file:///android_asset/gpl.html" == url) {
                    wv.loadUrl(url)
                }
                if (BuildConfig.FLAVOR == BuildConfig.FLAVOR_PLAY &&
                    "file:///android_asset/comicsans.html" == url) {
                    wv.loadUrl(url)
                }
                return true
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    return false
                }
                if ("file:///android_asset/othershibes.html" == url ||
                    "file:///android_asset/gpl.html" == url) {
                    wv.loadUrl(url)
                }
                if (BuildConfig.FLAVOR == BuildConfig.FLAVOR_PLAY &&
                    "file:///android_asset/comicsans.html" == url) {
                    wv.loadUrl(url)
                }
                return true
            }
        }
        wv.loadUrl("file:///android_asset/othershibes.html")
        return AlertDialog.Builder(act, theme)
            .setView(wv)
            .setPositiveButton(R.string.wow) { dialog, _ -> dialog.dismiss() }
            .create()
    }

    private class JsInterface {
        @JavascriptInterface
        fun isPlay(): Boolean {
            return BuildConfig.FLAVOR == BuildConfig.FLAVOR_PLAY
        }
    }
}
