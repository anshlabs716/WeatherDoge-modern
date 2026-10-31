package com.versobit.weatherdoge.dialogs

import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import com.versobit.weatherdoge.BuildConfig
import com.versobit.weatherdoge.OptionsActivity
import com.versobit.weatherdoge.R
import java.text.DecimalFormat

class DropShadowDialog : DialogFragment() {

    companion object {
        const val FRAGMENT_TAG = "fragment_dialog_dropshadow"
    }

    private var radius = 1f
    private var x = 3f
    private var y = 3f
    private var adjs = false

    private var preview: TextView? = null
    private var txtR: TextView? = null
    private var txtX: TextView? = null
    private var txtY: TextView? = null

    private var seekR: SeekBar? = null
    private var seekX: SeekBar? = null
    private var seekY: SeekBar? = null

    private var chkAdjs: CheckBox? = null

    private val df = DecimalFormat.getInstance() as DecimalFormat

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val act = activity ?: return super.onCreateDialog(savedInstanceState)
        val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(act)
        val useNeue = sp.getBoolean(OptionsActivity.PREF_APP_USE_COMIC_NEUE, false) ||
                BuildConfig.FLAVOR == BuildConfig.FLAVOR_FOSS

        var ctx: Context = act
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ctx = ContextThemeWrapper(act, R.style.AppTheme_Dialog_Light)
        }
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_dropshadow, null)

        preview = v.findViewById(R.id.dialog_dropshadow_txtpreview)
        val fontPath = if (useNeue) "ComicNeue-Regular.ttf" else "comic.ttf"
        val tf = try {
            Typeface.createFromAsset(act.assets, fontPath)
        } catch (e: Exception) {
            try {
                Typeface.createFromAsset(act.assets, "ComicNeue-Regular.ttf")
            } catch (e2: Exception) {
                Typeface.DEFAULT
            }
        }
        preview?.typeface = tf

        txtR = v.findViewById(R.id.dialog_dropshadow_txtradius)
        txtX = v.findViewById(R.id.dialog_dropshadow_txtx)
        txtY = v.findViewById(R.id.dialog_dropshadow_txty)

        seekR = v.findViewById(R.id.dialog_dropshadow_seekradius)
        seekX = v.findViewById(R.id.dialog_dropshadow_seekx)
        seekY = v.findViewById(R.id.dialog_dropshadow_seeky)

        chkAdjs = v.findViewById(R.id.dialog_dropshadow_checkadj)

        seekR?.setOnSeekBarChangeListener(onSeek)
        seekX?.setOnSeekBarChangeListener(onSeek)
        seekY?.setOnSeekBarChangeListener(onSeek)

        chkAdjs?.setOnCheckedChangeListener(onCheck)

        df.isDecimalSeparatorAlwaysShown = true
        df.maximumFractionDigits = 2
        df.minimumFractionDigits = 2
        df.maximumIntegerDigits = 2
        df.minimumIntegerDigits = 1

        val cs = txtR?.text
        txtR?.text = df.format(-25f)
        txtR?.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val measuredWidth = txtR?.measuredWidth ?: 80
        val widthHeight = LinearLayout.LayoutParams(measuredWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        txtR?.text = cs
        txtR?.layoutParams = widthHeight
        txtX?.layoutParams = widthHeight
        txtY?.layoutParams = widthHeight

        radius = sp.getFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_radius", 1f)
        x = sp.getFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_x", 3f)
        y = sp.getFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_y", 3f)
        adjs = sp.getBoolean(OptionsActivity.PREF_APP_DROP_SHADOW + "_adjs", false)

        seekR?.progress = map(radius, 0f, 25f, 0f, 100f).toInt()
        seekX?.progress = map(x, -25f, 25f, 0f, 100f).toInt()
        seekY?.progress = map(y, -25f, 25f, 0f, 100f).toInt()
        chkAdjs?.isChecked = adjs

        val scrollView = v.findViewById<View>(R.id.dialog_dropshadow_scroll)
        scrollView?.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val scrollH = scrollView?.measuredHeight ?: ViewGroup.LayoutParams.WRAP_CONTENT
        v.findViewById<View>(R.id.dialog_dropshadow_bg)?.layoutParams =
            RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, scrollH)

        return AlertDialog.Builder(act, theme)
            .setView(v)
            .setNeutralButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(R.string.save) { _, _ ->
                sp.edit()
                    .putFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_radius", radius)
                    .putFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_x", x)
                    .putFloat(OptionsActivity.PREF_APP_DROP_SHADOW + "_y", y)
                    .putBoolean(OptionsActivity.PREF_APP_DROP_SHADOW + "_adjs", adjs)
                    .apply()
            }
            .create()
    }

    private val onSeek = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            when (seekBar) {
                seekR -> {
                    radius = map(progress.toFloat(), 0f, 100f, 0f, 25f)
                    txtR?.text = df.format(radius.toDouble())
                }
                seekX -> {
                    x = map(progress.toFloat(), 0f, 100f, -25f, 25f)
                    txtX?.text = df.format(x.toDouble())
                }
                seekY -> {
                    y = map(progress.toFloat(), 0f, 100f, -25f, 25f)
                    txtY?.text = df.format(y.toDouble())
                }
            }
            preview?.setShadowLayer(radius, x, y, Color.BLACK)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private val onCheck = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        adjs = isChecked
    }

    private fun map(input: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
        return (input - inMin) * (outMax - outMin) / (inMax - inMin) + outMin
    }
}
