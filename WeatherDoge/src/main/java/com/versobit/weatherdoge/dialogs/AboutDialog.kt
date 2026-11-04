package com.versobit.weatherdoge.dialogs

import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import com.versobit.weatherdoge.BuildConfig
import com.versobit.weatherdoge.R

class AboutDialog : DialogFragment() {

    companion object {
        const val FRAGMENT_TAG = "fragment_dialog_about"
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = activity ?: return super.onCreateDialog(savedInstanceState)
        val v = activity.layoutInflater.inflate(R.layout.dialog_about, null)
        v.findViewById<android.view.View>(R.id.dialog_about_vb)?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.addr_versobit))))
            } catch (e: Exception) {
                // browser not available
            }
        }
        v.findViewById<TextView>(R.id.dialog_about_version)?.text = BuildConfig.VERSION_NAME
        v.findViewById<TextView>(R.id.dialog_about_text2)?.movementMethod = LinkMovementMethod.getInstance()
        v.findViewById<android.view.View>(R.id.dialog_about_privacy)?.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.dialog_about_privacy_url))))
            } catch (e: Exception) {
                // browser not available
            }
        }
        return AlertDialog.Builder(activity, theme)
            .setView(v)
            .setPositiveButton(R.string.wow) { dialog, _ -> dialog.dismiss() }
            .create()
    }
}
