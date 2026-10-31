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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        requeueWorkSchedule(ctx)
        WidgetWorker.enqueueOnceMultiple(ctx, appWidgetIds)
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
    override fun onAppWidgetOptionsChanged(ctx: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(ctx, appWidgetManager, appWidgetId, newOptions)
        WidgetWorker.enqueueOnceSingle(ctx, appWidgetId)
    }

    companion object {
        const val WORKER_ALL_TAG = "WidgetWorker_All"
        const val UPDATE_ALL_WIDGETS_SEQUENCE = "update_widgets_recurring"

        @JvmStatic
        fun requeueWorkSchedule(ctx: Context) {
            if (!areWidgetsActive(ctx)) return
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val interval = prefs.getString(OptionsActivity.PREF_WIDGET_REFRESH, "1800")?.toLongOrNull() ?: 1800L
            val workerConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val workerData = Data.Builder()
                .putString(WidgetWorker.ACTION, WidgetWorker.ACTION_REFRESH_ALL)
                .build()
            val widgetWorkerRequest = PeriodicWorkRequest.Builder(WidgetWorker::class.java, interval, TimeUnit.SECONDS)
                .addTag(WORKER_ALL_TAG)
                .setConstraints(workerConstraints)
                .setInputData(workerData)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UPDATE_ALL_WIDGETS_SEQUENCE,
                ExistingPeriodicWorkPolicy.UPDATE,
                widgetWorkerRequest
            )
        }

        @JvmStatic
        fun areWidgetsActive(ctx: Context): Boolean {
            return AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(ComponentName(ctx, WidgetProvider::class.java)).isNotEmpty()
        }

        @JvmStatic
        fun getTextBitmaps(ctx: Context, temp: String, description: String, location: String, lastUpdated: String): Array<Bitmap?> {
            val bitmaps = arrayOfNulls<Bitmap>(4)
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val useComicNeue = prefs.getBoolean(OptionsActivity.PREF_WIDGET_USE_COMIC_NEUE, false) ||
                    BuildConfig.FLAVOR == BuildConfig.FLAVOR_FOSS
            val res = ctx.resources

            val fontName = if (useComicNeue) "ComicNeue-Regular.ttf" else "comic.ttf"
            val primaryFont = try {
                Typeface.createFromAsset(ctx.assets, fontName)
            } catch (e: Exception) {
                try {
                    Typeface.createFromAsset(ctx.assets, "ComicNeue-Regular.ttf")
                } catch (e2: Exception) {
                    Typeface.DEFAULT
                }
            }

            val secondaryFont = try {
                Typeface.createFromAsset(ctx.assets, "RobotoCondensed-Regular.ttf")
            } catch (e: Exception) {
                Typeface.DEFAULT
            }

            val shadowRadius = res.getDimension(R.dimen.widget_text_shadow_radius)
            val shadowXY = res.getDimension(R.dimen.widget_text_shadow_xy).roundToInt().toFloat()
            val shadowPadX = ceil(res.getDimension(R.dimen.widget_text_shadow_padding_width)).toInt()
            val shadowPadY = ceil(res.getDimension(R.dimen.widget_text_shadow_padding_height)).toInt()

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
            val bmp0 = Bitmap.createBitmap(max(1, textBounds.width() + shadowPadX), max(1, textBounds.height() + shadowPadY), Bitmap.Config.ARGB_8888)
            var c = Canvas(bmp0)
            c.drawText(temp, textBounds.width() / 2f, textBounds.height().toFloat(), textPaint)
            bitmaps[0] = bmp0

            textPaint.textSize = res.getDimension(R.dimen.widget_desc_font_size)
            textBounds = Rect()
            textPaint.getTextBounds(description + "g", 0, description.length + 1, textBounds)
            val b2 = Rect()
            textPaint.getTextBounds("a", 0, 1, b2)

            val bmp1 = Bitmap.createBitmap(max(1, textBounds.width() + shadowPadX), max(1, textBounds.height() + shadowPadY), Bitmap.Config.ARGB_8888)
            c = Canvas(bmp1)
            c.drawText(description, textBounds.width() / 2f, (textBounds.height() + b2.height()) / 2f, textPaint)
            bitmaps[1] = bmp1

            textPaint.textSize = res.getDimension(R.dimen.widget_bottom_bar_font_size)
            textPaint.typeface = secondaryFont
            textPaint.setShadowLayer(0f, 0f, 0f, Color.BLACK)

            textBounds = Rect()
            textPaint.getTextBounds(location + "g", 0, location.length + 1, textBounds)
            textPaint.getTextBounds("a", 0, 1, b2)

            val bmp2 = Bitmap.createBitmap(max(1, textBounds.width()), max(1, textBounds.height()), Bitmap.Config.ARGB_8888)
            c = Canvas(bmp2)
            c.drawText(location, c.width / 2f, (c.height + b2.height()) / 2f, textPaint)
            bitmaps[2] = bmp2

            textBounds = Rect()
            textPaint.getTextBounds(lastUpdated + "g", 0, lastUpdated.length + 1, textBounds)

            val bmp3 = Bitmap.createBitmap(max(1, textBounds.width()), max(1, textBounds.height()), Bitmap.Config.ARGB_8888)
            c = Canvas(bmp3)
            c.drawText(lastUpdated, c.width / 2f, (c.height + b2.height()) / 2f + 1, textPaint)
            bitmaps[3] = bmp3

            return bitmaps
        }

        @JvmStatic
        fun getStatusBitmap(ctx: Context, status: String): Bitmap {
            val roboto = try {
                Typeface.createFromAsset(ctx.assets, "RobotoCondensed-Regular.ttf")
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
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

            val loading = Bitmap.createBitmap(max(1, textBounds.width()), max(1, textBounds.height()), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(loading)
            canvas.drawText(status, canvas.width / 2f, (canvas.height + baselineBounds.height()) / 2f, textPaint)
            return loading
        }

        private fun getWidgetSize(metrics: DisplayMetrics, options: Bundle): FloatArray {
            return floatArrayOf(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat(), metrics),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat(), metrics)
            )
        }

        @JvmStatic
        fun getSkyBitmap(ctx: Context, options: Bundle, skyId: Int): Bitmap {
            val res = ctx.resources
            val originalSky = BitmapFactory.decodeResource(res, skyId) ?: return Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

            val widgetSize = getWidgetSize(res.displayMetrics, options)
            val viewW = max(1f, widgetSize[0])
            var viewH = max(1f, widgetSize[1])
            val bmpW = originalSky.width.toFloat()
            val bmpH = originalSky.height.toFloat()

            val bottomBarHeight = res.getDimension(R.dimen.widget_bottom_bar_height)
            val radius = res.getDimension(R.dimen.widget_corner_radius)
            val extra = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, res.displayMetrics)
            viewH += extra + radius - bottomBarHeight

            val skyMatrix = Matrix()
            val scale: Float
            val dx: Float
            val dy: Float
            if (bmpW * viewH > viewW * bmpH) {
                scale = viewH / bmpH
                dx = (viewW - bmpW * scale) * 0.5f
                dy = 0f
            } else {
                scale = viewW / bmpW
                dx = 0f
                dy = (viewH - bmpH * scale) * 0.5f
            }
            skyMatrix.setScale(scale, scale)
            skyMatrix.postTranslate((dx + 0.5f).toInt().toFloat(), (dy + 0.5f).toInt().toFloat())

            val scaledSky = Bitmap.createBitmap(viewW.toInt(), max(1, viewH.toInt()), Bitmap.Config.ARGB_8888)
            val scaledCanvas = Canvas(scaledSky)
            scaledCanvas.drawBitmap(originalSky, skyMatrix, Paint())
            originalSky.recycle()

            val shader = BitmapShader(scaledSky, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            val roundedSky = Bitmap.createBitmap(scaledSky.width, scaledSky.height, Bitmap.Config.ARGB_8888)
            val roundedCanvas = Canvas(roundedSky)
            roundedCanvas.drawRoundRect(RectF(0f, 0f, scaledSky.width.toFloat(), scaledSky.height.toFloat()), radius, radius, cornerPaint)
            scaledSky.recycle()

            return roundedSky
        }

        @JvmStatic
        fun getWowLayer(ctx: Context, options: Bundle, image: String, temp: Int): Bitmap {
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val useComicNeue = prefs.getBoolean(OptionsActivity.PREF_WIDGET_USE_COMIC_NEUE, false) ||
                    BuildConfig.FLAVOR == BuildConfig.FLAVOR_FOSS
            val res = ctx.resources

            val widgetSize = getWidgetSize(res.displayMetrics, options)
            widgetSize[1] -= res.getDimension(R.dimen.widget_bottom_bar_height)
            val w = max(1, widgetSize[0].toInt())
            val h = max(1, widgetSize[1].toInt())

            val shadowRadius = res.getDimension(R.dimen.widget_wowlayer_shadow_radius)
            val shadowXY = max(1f, res.getDimension(R.dimen.widget_wowlayer_shadow_xy).roundToInt().toFloat())

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val fontName = if (useComicNeue) "ComicNeue-Regular.ttf" else "comic.ttf"
            val font = try {
                Typeface.createFromAsset(ctx.assets, fontName)
            } catch (e: Exception) {
                try {
                    Typeface.createFromAsset(ctx.assets, "ComicNeue-Regular.ttf")
                } catch (e2: Exception) {
                    Typeface.DEFAULT
                }
            }

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
                typeface = font
                style = Paint.Style.FILL
                textAlign = Paint.Align.LEFT
                textSize = res.getDimension(if (useComicNeue) R.dimen.widget_wowlayer_font_size_neue else R.dimen.widget_wowlayer_font_size)
                setShadowLayer(shadowRadius, shadowXY, shadowXY, Color.BLACK)
            }

            val r = Random()
            val colors = res.getIntArray(R.array.wow_colors)
            val wows = res.getStringArray(R.array.wows)
            val dogefixes = res.getStringArray(R.array.dogefix)
            val weatherAdjs = res.getStringArray(WeatherDoge.getTempAdjectives(temp)) +
                    res.getStringArray(WeatherDoge.getBgAdjectives(image))

            val area = (widgetSize[0] / res.displayMetrics.density) * (widgetSize[1] / res.displayMetrics.density)
            val total = max(1, (0.000066156726853611 * area + 1.1833074350128).roundToInt())

            val drawnRects = arrayOfNulls<Rect>(total + 1)
            drawnRects[total] = Rect(
                0,
                (widgetSize[1] - res.getDimension(R.dimen.widget_wowlayer_rect_top)).toInt(),
                res.getDimension(R.dimen.widget_wowlayer_rect_right).toInt(),
                widgetSize[1].toInt()
            )

            val drawnText = arrayOfNulls<String>(total)

            for (i in 0 until total) {
                val textBounds = Rect()
                val realRect = Rect()
                textPaint.color = colors[r.nextInt(colors.size)]

                val ism = getUniqueDogeism(drawnText, r, wows, dogefixes, weatherAdjs)
                drawnText[i] = ism

                textPaint.getTextBounds(ism, 0, ism.length, textBounds)
                val xMax = max(1, w - textBounds.width())
                val yMax = max(1, h - textBounds.height())
                var attempts = 0
                do {
                    realRect.left = r.nextInt(xMax)
                    realRect.right = realRect.left + textBounds.width()
                    realRect.bottom = r.nextInt(yMax) + textBounds.height()
                    realRect.top = realRect.bottom - textBounds.height()
                    attempts++
                } while (attempts < 20 && (realRect.bottom < textBounds.height() || intersects(realRect, drawnRects)))

                canvas.drawText(ism, realRect.left.toFloat(), realRect.bottom.toFloat(), textPaint)
                drawnRects[i] = realRect
            }

            return bitmap
        }

        private fun getUniqueDogeism(
            existing: Array<String?>,
            r: Random,
            wows: Array<String>,
            dogefixes: Array<String>,
            weatherAdjs: Array<String>
        ): String {
            var ism: String? = null
            var tries = 0
            while (ism == null && tries < 30) {
                tries++
                val candidate = WeatherDoge.getDogeism(r, wows, dogefixes, weatherAdjs)
                if (existing.none { it == candidate }) {
                    ism = candidate
                }
            }
            return ism ?: "wow"
        }

        private fun intersects(needle: Rect, haystack: Array<Rect?>): Boolean {
            return haystack.any { it != null && Rect.intersects(it, needle) }
        }
    }
}
