/*
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

package com.versobit.weatherdoge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The Doge Dial: a circular gauge that rings the doge portrait.
 *
 * The arc sweeps 270° (leaving a gap at the bottom, like a proper gauge) from the coldest
 * end of [MIN_CELSIUS] to the hottest end of [MAX_CELSIUS], so the colour plus the marker
 * tells you at a glance how gnarly it is out there without reading a number. The centre is
 * left empty — the doge sits on top of it.
 *
 * It never animates on its own: [temperatureC] is set and the view redraws, which keeps it
 * friendly to reduced-motion users and cheap during CHAOS MODE.
 */
class DogeDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MIN_CELSIUS = -40.0
        const val MAX_CELSIUS = 50.0

        /** Gauge geometry: start at the bottom-left, sweep clockwise, gap at the bottom. */
        private const val START_ANGLE = 135f
        private const val SWEEP_ANGLE = 270f
        private const val SEGMENTS = 48
        private const val MAX_DIAMETER_DP = 370f
        private const val EDGE_MARGIN_DP = 12f

        /** cold → mild → warm → hot */
        private val GRADIENT = intArrayOf(
            0xFF1E88E5.toInt(), // -40
            0xFF26C6DA.toInt(), //  0
            0xFFFFEE58.toInt(), // +20
            0xFFFFA726.toInt(), // +35
            0xFFE53935.toInt()  // +50
        )
        private val GRADIENT_AT = doubleArrayOf(-40.0, 0.0, 20.0, 35.0, 50.0)
    }

    /** Current temperature in Celsius, or null when nothing has been fetched yet. */
    var temperatureC: Double? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x66FFFFFF
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val knobEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xB3000000.toInt()
    }

    private var diameter = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // wrap_content: as large as we can be while always fitting next to the doge.
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val wanted = (MAX_DIAMETER_DP * density).toInt()
        val cap = min(wanted, min(screenW, screenH) - (EDGE_MARGIN_DP * 2 * density).toInt())
        val size = resolveSize(maxOf(cap, 0), widthMeasureSpec)
        diameter = size.toFloat()
        val stroke = 10f * density
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (diameter <= 0f) return
        val inset = trackPaint.strokeWidth / 2f + 1f
        val rect = RectF(inset, inset, diameter - inset, diameter - inset)

        canvas.drawArc(rect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        val temp = temperatureC ?: return
        val t = ((temp - MIN_CELSIUS) / (MAX_CELSIUS - MIN_CELSIUS)).coerceIn(0.0, 1.0)
        if (t <= 0.0) return

        // Draw the coloured gauge in segments so the gradient follows the temperature scale.
        val step = SWEEP_ANGLE / SEGMENTS
        for (i in 0 until SEGMENTS) {
            val t0 = i.toDouble() / SEGMENTS
            val t1 = (i + 1).toDouble() / SEGMENTS
            if (t1 <= 0.0 || t0 >= t) continue
            val from = maxOf(t0, 0.0)
            val to = minOf(t1, t)
            arcPaint.color = colorAt(from)
            canvas.drawArc(rect, START_ANGLE + (from * SWEEP_ANGLE).toFloat(),
                ((to - from) * SWEEP_ANGLE).toFloat() + 0.6f, false, arcPaint)
        }

        // Marker knob at the current temperature.
        val angle = Math.toRadians((START_ANGLE + t * SWEEP_ANGLE).toDouble())
        val cx = diameter / 2f + (rect.width() / 2f) * Math.cos(angle).toFloat()
        val cy = diameter / 2f + (rect.height() / 2f) * Math.sin(angle).toFloat()
        val knobR = 7f * density
        canvas.drawCircle(cx, cy, knobR, knobPaint)
        canvas.drawCircle(cx, cy, knobR, knobEdgePaint)
    }

    /** Linear interpolation across the gradient stops, by temperature. */
    private fun colorAt(t: Double): Int {
        val temp = MIN_CELSIUS + t * (MAX_CELSIUS - MIN_CELSIUS)
        if (temp <= GRADIENT_AT.first()) return GRADIENT.first()
        for (i in 0 until GRADIENT_AT.size - 1) {
            val lo = GRADIENT_AT[i]
            val hi = GRADIENT_AT[i + 1]
            if (temp in lo..hi) {
                val f = ((temp - lo) / (hi - lo)).toFloat()
                return lerpColor(GRADIENT[i], GRADIENT[i + 1], f)
            }
        }
        return GRADIENT.last()
    }

    private fun lerpColor(a: Int, b: Int, f: Float): Int {
        val af = 1f - f
        val alpha = ((a ushr 24 and 0xFF) * af + (b ushr 24 and 0xFF) * f).toInt()
        val red = ((a shr 16 and 0xFF) * af + (b shr 16 and 0xFF) * f).toInt()
        val green = ((a shr 8 and 0xFF) * af + (b shr 8 and 0xFF) * f).toInt()
        val blue = ((a and 0xFF) * af + (b and 0xFF) * f).toInt()
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
