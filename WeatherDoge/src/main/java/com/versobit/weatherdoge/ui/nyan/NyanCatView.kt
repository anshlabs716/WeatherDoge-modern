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

package com.versobit.weatherdoge.ui.nyan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * The built-in NYAN tab animation: an original, procedurally drawn rainbow cat so that
 * published builds (which ship without any third-party media) still have something to
 * look at when the user's own video is not present.
 *
 * Everything here is drawn from primitives — there is no bitmap to ship and nothing that
 * could be mistaken for anyone else's artwork. Animation stops completely when the tab is
 * closed or the user asked for reduced motion.
 */
class NyanCatView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val rainbow = intArrayOf(
        0xFFF857A6.toInt(),
        0xFFFF7A59.toInt(),
        0xFFFFD166.toInt(),
        0xFF06D6A0.toInt(),
        0xFF4CC9F0.toInt(),
        0xFF7B68EE.toInt()
    )

    private val bgPaint = Paint().apply { color = 0xFF12121A.toInt() }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFE08A.toInt() }
    private val bodyEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A2E12.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A2E12.toInt() }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    private val path = Path()
    private val rect = RectF()

    /** Master switch: false freezes the animation on the current frame. */
    var animateFrames: Boolean = false
        set(value) {
            field = value
            if (value) postInvalidateOnAnimation() else invalidate()
        }

    private var startTime = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTime = android.os.SystemClock.elapsedRealtime()
        if (animateFrames) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = android.os.SystemClock.elapsedRealtime()
        val t = if (animateFrames && startTime > 0) (now - startTime) / 1000f else 0f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Twinkling stars (static positions, twinkling driven by time).
        drawStars(canvas, w, h, t)

        val bandH = h / (rainbow.size * 3.2f)
        val trailRight = w * 0.62f
        val wobble = if (animateFrames) (sin(t * 6f) * (4f * density)) else 0f

        // Rainbow trail behind the cat.
        for (i in rainbow.indices) {
            bandPaint.color = rainbow[i]
            val top = h * 0.34f + i * bandH + wobble
            canvas.drawRect(0f, top, trailRight, top + bandH + 1f, bandPaint)
        }

        // Cat body.
        val bodyW = w * 0.34f
        val bodyH = h * 0.30f
        val bodyL = trailRight - bodyW * 0.35f
        val bodyT = h * 0.36f + wobble
        rect.set(bodyL, bodyT, bodyL + bodyW, bodyT + bodyH)
        canvas.drawRoundRect(rect, bodyH * 0.34f, bodyH * 0.34f, bodyPaint)
        canvas.drawRoundRect(rect, bodyH * 0.34f, bodyH * 0.34f, bodyEdgePaint)

        // Head.
        val headR = bodyH * 0.52f
        val headCx = bodyL + bodyW * 0.92f
        val headCy = bodyT + bodyH * 0.44f
        canvas.drawCircle(headCx, headCy, headR, bodyPaint)
        canvas.drawCircle(headCx, headCy, headR, bodyEdgePaint)

        // Ears.
        val ear = headR * 0.52f
        path.reset()
        path.moveTo(headCx - headR * 0.72f, headCy - headR * 0.52f)
        path.lineTo(headCx - headR * 0.30f, headCy - headR * 1.34f)
        path.lineTo(headCx - headR * 0.04f, headCy - headR * 0.72f)
        path.close()
        canvas.drawPath(path, bodyPaint)
        canvas.drawPath(path, bodyEdgePaint)
        path.reset()
        path.moveTo(headCx + headR * 0.14f, headCy - headR * 0.76f)
        path.lineTo(headCx + headR * 0.54f, headCy - headR * 1.36f)
        path.lineTo(headCx + headR * 0.80f, headCy - headR * 0.46f)
        path.close()
        canvas.drawPath(path, bodyPaint)
        canvas.drawPath(path, bodyEdgePaint)
        @Suppress("UNUSED_EXPRESSION") ear

        // Face: two eyes and a mouth.
        val eyeR = headR * 0.13f
        canvas.drawCircle(headCx - headR * 0.34f, headCy - headR * 0.10f, eyeR, facePaint)
        canvas.drawCircle(headCx + headR * 0.30f, headCy - headR * 0.10f, eyeR, facePaint)
        path.reset()
        path.moveTo(headCx - headR * 0.26f, headCy + headR * 0.34f)
        path.quadTo(headCx, headCy + headR * 0.62f, headCx + headR * 0.30f, headCy + headR * 0.30f)
        canvas.drawPath(path, bodyEdgePaint)

        // Legs (little pixel-ish stubs).
        val legW = bodyW * 0.14f
        val legH = bodyH * 0.30f
        val legY = bodyT + bodyH - legH * 0.4f + wobble
        bandPaint.color = bodyPaint.color
        canvas.drawRect(bodyL + bodyW * 0.12f, legY, bodyL + bodyW * 0.12f + legW, legY + legH, bandPaint)
        canvas.drawRect(bodyL + bodyW * 0.58f, legY, bodyL + bodyW * 0.58f + legW, legY + legH, bandPaint)

        // Tail.
        path.reset()
        path.moveTo(bodyL, bodyT + bodyH * 0.34f)
        path.quadTo(bodyL - bodyW * 0.24f, bodyT + bodyH * 0.10f,
            bodyL - bodyW * 0.16f, bodyT - bodyH * 0.16f)
        path.lineTo(bodyL - bodyW * 0.02f, bodyT - bodyH * 0.10f)
        path.quadTo(bodyL - bodyW * 0.08f, bodyT + bodyH * 0.24f, bodyL, bodyT + bodyH * 0.54f)
        path.close()
        canvas.drawPath(path, bodyPaint)
        canvas.drawPath(path, bodyEdgePaint)

        if (animateFrames) postInvalidateOnAnimation()
    }

    private fun drawStars(canvas: Canvas, w: Float, h: Float, t: Float) {
        // Fixed pseudo-random star field.
        var seed = 1234567
        for (i in 0 until 26) {
            seed = seed * 1103515245 + 12345
            val x = ((seed ushr 8) and 0x7FFFFFFF) % w.toInt().coerceAtLeast(1)
            seed = seed * 1103515245 + 12345
            val y = ((seed ushr 8) and 0x7FFFFFFF) % h.toInt().coerceAtLeast(1)
            val phase = ((i * 37) % 100) / 100f
            val twinkle = if (animateFrames) {
                (0.45f + 0.55f * ((sin((t * 2f + phase * 6.28f)) + 1f) / 2f))
            } else {
                0.8f
            }
            starPaint.alpha = (255 * twinkle).toInt().coerceIn(0, 255)
            val r = (1.5f + (i % 3)) * density
            canvas.drawCircle(x.toFloat(), y.toFloat(), r, starPaint)
        }
    }
}
