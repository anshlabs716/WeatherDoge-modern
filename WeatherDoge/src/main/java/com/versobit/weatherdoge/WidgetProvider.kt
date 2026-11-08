/*
 * Copyright (C) 2014-2016, 2019 VersoBit
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

package com.versobit.weatherdoge

import android.annotation.TargetApi
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * The home screen widget manifest entry point and the bitmap factory that dresses it.
 *
 * Kotlin rewrite of the original provider: the legacy `WeatherUtil`/`Cache` types and
 * Apache Commons are gone (array concatenation is plain `+` here), everything else —
 * text/status/sky/wow-layer rendering, the periodic WorkManager schedule — is ported
 * line-for-line so the widget looks and behaves exactly as before.
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Will only be called once (on widget startup)
        requeueWorkSchedule(ctx)
        WidgetWorker.enqueueOnceMultiple(appWidgetIds)
    }

    override fun onEnabled(ctx: Context) {
        requeueWorkSchedule(ctx)
    }

    override fun onDisabled(ctx: Context) {
        val workManager = WorkManager.getInstance(ctx)
        workManager.cancelAllWorkByTag(WidgetWorker.TASK_ALL_TAG)
        workManager.cancelAllWorkByTag(WidgetWorker.TASK_MULTIPLE_TAG)
        workManager.cancelAllWorkByTag(WidgetWorker.TASK_ONE_TAG)
        workManager.cancelAllWorkByTag(WORKER_ALL_TAG)
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    override fun onAppWidgetOptionsChanged(
        ctx: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(ctx, appWidgetManager, appWidgetId, newOptions)
        WidgetWorker.enqueueOnceSingle(appWidgetId)
    }

    companion object {
        private const val WORKER_ALL_TAG = "WidgetWorker_All"
        private const val UPDATE_ALL_WIDGETS_SEQUENCE = "update_widgets_recurring"

        private const val DEFAULT_REFRESH_SECONDS = "1800"

        fun requeueWorkSchedule(ctx: Context) {
            if (!areWidgetsActive(ctx)) {
                return
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val interval = prefs.getString(OptionsActivity.PREF_WIDGET_REFRESH, DEFAULT_REFRESH_SECONDS)
                ?.toLongOrNull()
                ?: DEFAULT_REFRESH_SECONDS.toLong()

            val workerConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val workerData = Data.Builder()
                .putString(WidgetWorker.ACTION, WidgetWorker.ACTION_REFRESH_ALL)
                .build()
            val widgetWorkerRequest = PeriodicWorkRequest.Builder(
                WidgetWorker::class.java, interval, TimeUnit.SECONDS
            )
                .addTag(WORKER_ALL_TAG)
                .setConstraints(workerConstraints)
                .setInputData(workerData)
                .build()

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UPDATE_ALL_WIDGETS_SEQUENCE,
                ExistingPeriodicWorkPolicy.REPLACE,
                widgetWorkerRequest
            )
        }

        fun areWidgetsActive(ctx: Context): Boolean =
            AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java)).isNotEmpty()

        /** Renders the four bottom-bar/temperature strings into reusable bitmaps. */
        fun getTextBitmaps(
            ctx: Context, temp: String, description: String, location: String, lastUpdated: String
        ): Array<Bitmap?> {
            val bitmaps = arrayOfNulls<Bitmap>(4)
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val useComicNeue = prefs.getBoolean(OptionsActivity.PREF_WIDGET_USE_COMIC_NEUE, false) ||
                BuildConfig.FLAVOR == BuildConfig.FLAVOR_FOSS
            val res = ctx.resources
            val primaryFont = Typeface.createFromAsset(
                ctx.assets, if (useComicNeue) "ComicNeue-Regular.ttf" else "comic.ttf"
            )
            val secondaryFont = Typeface.createFromAsset(ctx.assets, "RobotoCondensed-Regular.ttf")
            val shadowRadius = res.getDimension(R.dimen.widget_text_shadow_radius)
            // Odd results with fractional offsets
            val shadowXY = Math.round(res.getDimension(R.dimen.widget_text_shadow_xy)).toFloat()
            // Better to have more padding than not enough
            val shadowPadX = Math.ceil(res.getDimension(R.dimen.widget_text_shadow_padding_width).toDouble()).toInt()
            val shadowPadY = Math.ceil(res.getDimension(R.dimen.widget_text_shadow_padding_height).toDouble()).toInt()

            // Configure text painter
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
                typeface = primaryFont
                color = Color.WHITE
                style = Paint.Style.FILL
                textSize = res.getDimension(R.dimen.widget_temp_font_size)
                textAlign = Paint.Align.CENTER
                setShadowLayer(shadowRadius, shadowXY, shadowXY, Color.BLACK)
            }

            var textBounds = Rect()
            textPaint.getTextBounds(temp, 0, temp.length, textBounds)

            val tempBitmap = Bitmap.createBitmap(
                textBounds.width() + shadowPadX, textBounds.height() + shadowPadY,
                Bitmap.Config.ARGB_8888
            )
            Canvas(tempBitmap).drawText(
                temp, textBounds.width() / 2f, textBounds.height().toFloat(), textPaint
            )
            bitmaps[0] = tempBitmap

            textPaint.textSize = res.getDimension(R.dimen.widget_desc_font_size)
            textBounds = Rect()
            textPaint.getTextBounds(description + "g", 0, description.length + 1, textBounds)
            val b2 = Rect()
            textPaint.getTextBounds("a", 0, 1, b2)

            val descBitmap = Bitmap.createBitmap(
                textBounds.width() + shadowPadX, textBounds.height() + shadowPadY,
                Bitmap.Config.ARGB_8888
            )
            Canvas(descBitmap).drawText(
                description,
                textBounds.width() / 2f,
                (textBounds.height() + b2.height()) / 2f,
                textPaint
            )
            bitmaps[1] = descBitmap

            textPaint.textSize = res.getDimension(R.dimen.widget_bottom_bar_font_size)
            textPaint.typeface = secondaryFont
            textPaint.setShadowLayer(0f, 0f, 0f, Color.BLACK)

            textBounds = Rect()
            textPaint.getTextBounds(location + "g", 0, location.length + 1, textBounds)

            val locationBounds = Rect()
            textPaint.getTextBounds("a", 0, 1, locationBounds)

            val locationBitmap = Bitmap.createBitmap(
                textBounds.width(), textBounds.height(), Bitmap.Config.ARGB_8888
            )
            Canvas(locationBitmap).drawText(
                location,
                textBounds.width() / 2f,
                (textBounds.height() + locationBounds.height()) / 2f,
                textPaint
            )
            bitmaps[2] = locationBitmap

            textBounds = Rect()
            textPaint.getTextBounds(lastUpdated + "g", 0, lastUpdated.length + 1, textBounds)

            val updatedBitmap = Bitmap.createBitmap(
                textBounds.width(), textBounds.height(), Bitmap.Config.ARGB_8888
            )
            Canvas(updatedBitmap).drawText(
                lastUpdated,
                textBounds.width() / 2f,
                (textBounds.height() + locationBounds.height()) / 2f + 1,
                textPaint
            )
            bitmaps[3] = updatedBitmap

            return bitmaps
        }

        /** The bottom bar text (loading / error) drawn straight into a bitmap. */
        fun getStatusBitmap(ctx: Context, status: String): Bitmap {
            val roboto = Typeface.createFromAsset(ctx.assets, "RobotoCondensed-Regular.ttf")
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
                typeface = roboto
                color = Color.WHITE
                style = Paint.Style.FILL
                textAlign = Paint.Align.CENTER
                textSize = ctx.resources.getDimension(R.dimen.widget_bottom_bar_font_size)
            }

            val textBounds = Rect()
            textPaint.getTextBounds(status + "g", 0, status.length + 1, textBounds)
            val baselineBounds = Rect()
            textPaint.getTextBounds("a", 0, 1, baselineBounds)

            val loading = Bitmap.createBitmap(
                textBounds.width(), textBounds.height(), Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(loading)
            canvas.drawText(
                status,
                canvas.width / 2f,
                (canvas.height + baselineBounds.height()) / 2f,
                textPaint
            )
            return loading
        }

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
        private fun getWidgetSize(metrics: DisplayMetrics, options: Bundle): FloatArray =
            floatArrayOf(
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat(),
                    metrics
                ),
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat(),
                    metrics
                )
            )

        /** Updates the sky bitmap for a single app widget instance, or null on failure. */
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
        fun getSkyBitmap(ctx: Context, options: Bundle, skyId: Int): Bitmap? {
            val res = ctx.resources
            val originalSky = BitmapFactory.decodeResource(res, skyId) ?: return null

            // Obtain (approximate?) size of widget (and by extension the image view)
            val widgetSize = getWidgetSize(res.displayMetrics, options)
            var viewW = widgetSize[0]
            var viewH = widgetSize[1]
            // Obtain size of sky bitmap
            val bmpW = originalSky.width.toFloat()
            val bmpH = originalSky.height.toFloat()

            // For some reason the calculated view height is too small by about a pixel or so. Let's
            // compensate for that and while we're at it increase the height by the radius of the
            // rounded edges to hide the edges behind the bottom bar
            val bottomBarHeight = res.getDimension(R.dimen.widget_bottom_bar_height)
            val radius = res.getDimension(R.dimen.widget_corner_radius)
            val extra = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1f, res.displayMetrics
            )
            viewH += extra + radius - bottomBarHeight

            // Implement ImageView's CENTER_CROP scale type
            val skyMatrix = Matrix()
            var scale: Float
            var dx = 0f
            var dy = 0f
            if (bmpW * viewH > viewW * bmpH) {
                scale = viewH / bmpH
                dx = (viewW - bmpW * scale) * 0.5f
            } else {
                scale = viewW / bmpW
                dy = (viewH - bmpH * scale) * 0.5f
            }
            skyMatrix.setScale(scale, scale)
            skyMatrix.postTranslate((dx + 0.5f).toInt().toFloat(), (dy + 0.5f).toInt().toFloat())

            // Create a new bitmap/canvas pair at the size we need and draw on it
            val scaledSky = Bitmap.createBitmap(viewW.toInt(), viewH.toInt(), Bitmap.Config.ARGB_8888)
            Canvas(scaledSky).drawBitmap(originalSky, skyMatrix, Paint())
            originalSky.recycle()

            // Rounded corner time! We need to round the top two corners.
            val skyShader = BitmapShader(scaledSky, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = skyShader }
            val roundedSky = Bitmap.createBitmap(
                scaledSky.width, scaledSky.height, Bitmap.Config.ARGB_8888
            )
            Canvas(roundedSky).drawRoundRect(
                RectF(0f, 0f, scaledSky.width.toFloat(), scaledSky.height.toFloat()),
                radius, radius, cornerPaint
            )
            scaledSky.recycle()

            return roundedSky
        }

        /** Random dogeisms scattered over the widget, avoiding the temperature display. */
        fun getWowLayer(ctx: Context, options: Bundle, image: String, temp: Int): Bitmap {
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val useComicNeue = prefs.getBoolean(OptionsActivity.PREF_WIDGET_USE_COMIC_NEUE, false) ||
                BuildConfig.FLAVOR == BuildConfig.FLAVOR_FOSS

            val res = ctx.resources

            // Find the size in pixels of the widget
            val widgetSize = getWidgetSize(res.displayMetrics, options)
            // Subtract out the bottom bar so now we have the rough size of the wowlayer
            widgetSize[1] -= res.getDimension(R.dimen.widget_bottom_bar_height)

            // Text shadow radius for wow text
            val shadowRadius = res.getDimension(R.dimen.widget_wowlayer_shadow_radius)
            // Odd results with fractional offsets
            val shadowXY = Math.max(1f, Math.round(res.getDimension(R.dimen.widget_wowlayer_shadow_xy)).toFloat())

            // Create a bitmap + canvas the size of the wowlayer to draw wows on
            val bitmap = Bitmap.createBitmap(
                widgetSize[0].toInt(), widgetSize[1].toInt(), Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)

            // Setup the initial text painter
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
                typeface = Typeface.createFromAsset(
                    ctx.assets, if (useComicNeue) "ComicNeue-Regular.ttf" else "comic.ttf"
                )
                style = Paint.Style.FILL
                textAlign = Paint.Align.LEFT
                textSize = res.getDimension(
                    if (useComicNeue) R.dimen.widget_wowlayer_font_size_neue
                    else R.dimen.widget_wowlayer_font_size
                )
                setShadowLayer(shadowRadius, shadowXY, shadowXY, Color.BLACK)
            }

            // Initialize the random number generator
            val r = Random()

            // Find the colors, and text to use for our dogeisms
            val colors = res.getIntArray(R.array.wow_colors)
            val wows = res.getStringArray(R.array.wows)
            val dogefixes = res.getStringArray(R.array.dogefix)
            val weatherAdjs = res.getStringArray(WeatherDoge.getTempAdjectives(temp)) +
                res.getStringArray(WeatherDoge.getBgAdjectives(image))

            // Calculate how many dogeisms to display for a given density independent widget area
            val area = (widgetSize[0] / res.displayMetrics.density) *
                (widgetSize[1] / res.displayMetrics.density)
            val total = maxOf(1, Math.round(0.000066156726853611 * area + 1.1833074350128).toInt())

            // Store our drawn rectangles so we can avoid text overlaps
            val drawnRects = arrayOfNulls<Rect>(total + 1)
            // Prevent text from drawing underneath the temperature display at the bottom left
            drawnRects[total] = Rect(
                0,
                (widgetSize[1] - res.getDimension(R.dimen.widget_wowlayer_rect_top)).toInt(),
                res.getDimension(R.dimen.widget_wowlayer_rect_right).toInt(),
                widgetSize[1].toInt()
            )

            // Store dogeisms used to avoid duplicates
            val drawnText = arrayOfNulls<String>(total)

            // Loop over all the dogeisms we're going to create
            for (i in 0 until total) {
                val textBounds = Rect()
                // The rectangle which represents the text's actual position on the canvas
                val realRect = Rect()
                textPaint.color = colors[r.nextInt(colors.size)]

                // The text to draw
                val ism = getUniqueDogeism(drawnText, r, wows, dogefixes, weatherAdjs)
                drawnText[i] = ism

                // Find the bounding rectangle of the text
                textPaint.getTextBounds(ism, 0, ism.length, textBounds)
                // Prevent the text from going offscreen
                val xMax = (widgetSize[0].toInt() - textBounds.width()).coerceAtLeast(1)
                val yMax = (widgetSize[1].toInt() - textBounds.height()).coerceAtLeast(1)
                do {
                    // Create the real rectangle using a random origin
                    realRect.left = r.nextInt(xMax)
                    realRect.right = realRect.left + textBounds.width()
                    realRect.bottom = r.nextInt(yMax)
                    realRect.top = realRect.bottom - textBounds.height()
                    // Continue to loop until we find a valid in-bounds rectangle.
                    // Since the drawText origin is the bottom left we need to make certain that
                    // our text will not clip off the top edge of the widget.
                    // We also need to check if this rectangle intersects any previously drawn
                    // text rectangles. We don't want text-on-text.
                } while (realRect.bottom < textBounds.height() || intersects(realRect, drawnRects))

                // realRect is valid. Draw it out.
                canvas.drawText(ism, realRect.left.toFloat(), realRect.bottom.toFloat(), textPaint)

                // Store it for intersection checks
                drawnRects[i] = realRect
            }

            return bitmap
        }

        private fun getUniqueDogeism(
            existing: Array<String?>, r: Random, wows: Array<String>,
            dogefixes: Array<String>, weatherAdjs: Array<String>
        ): String {
            while (true) {
                val candidate = WeatherDoge.getDogeism(r, wows, dogefixes, weatherAdjs)
                if (existing.none { candidate == it }) return candidate
            }
        }

        // Searches a Rect array for an intersection with a given Rect
        private fun intersects(needle: Rect, haystack: Array<Rect?>): Boolean {
            for (rect in haystack) {
                if (rect != null && Rect.intersects(rect, needle)) {
                    return true
                }
            }
            return false
        }
    }
}
