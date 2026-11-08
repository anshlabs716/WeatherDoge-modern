package com.versobit.weatherdoge.dialogs

import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import com.versobit.weatherdoge.R
import com.versobit.weatherdoge.WeatherDoge

class ContributeDialog : DialogFragment() {
    companion object {
        const val FRAGMENT_TAG = "fragment_dialog_contribute"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val v = activity.layoutInflater.inflate(R.layout.dialog_contribute, null)
        v.findViewById<View>(R.id.dialog_contribute_github_layout).setOnClickListener {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.addr_github)))
            WeatherDoge.applyChromeCustomTab(activity, i)
            startActivity(i)
        }
        return AlertDialog.Builder(activity, theme)
            .setView(v)
            .setPositiveButton(R.string.wow) { dialog, _ -> dialog.dismiss() }
            .create()
    }
}
