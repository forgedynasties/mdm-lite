package com.aioapp.mdmlite

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * What the host app is showing. Captures the app's own window with PixelCopy, which
 * includes hardware-rendered content such as a WebView and needs no permission (unlike
 * MediaProjection, which would prompt). Only the app's own window is visible to it.
 */
internal object Screen {
    @Volatile private var resumed: WeakReference<Activity>? = null
    private val copyThread by lazy { HandlerThread("aio-mdm-pixelcopy").apply { start() } }

    fun track(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { resumed = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (resumed?.get() === a) resumed = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** True while one of the host's activities is in the foreground. */
    fun foreground(): Boolean = resumed?.get() != null

    /**
     * The current window as a bitmap, or null when nothing of ours is on screen. Blocks
     * the caller (never the main thread) for at most a few seconds.
     */
    fun capture(): Bitmap? = captureWithContent()?.first

    /**
     * The window bitmap plus where the app's own content sits in it. On a phone the
     * window also holds the status and navigation bar backgrounds, which are not the
     * app's picture; the blank check looks at the content area only.
     */
    fun captureWithContent(): Pair<Bitmap, Rect>? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "capture() blocks; call it off the main thread" }
        val activity = resumed?.get() ?: return null
        val window = activity.window ?: return null
        val view = window.decorView
        if (view.width <= 0 || view.height <= 0) return null
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var ok = false
        var content = Rect(0, 0, view.width, view.height)
        Handler(Looper.getMainLooper()).post {
            runCatching {
                window.findViewById<View>(android.R.id.content)?.let { c ->
                    val xy = IntArray(2).also { c.getLocationInWindow(it) }
                    if (c.width > 0 && c.height > 0) content = Rect(xy[0], xy[1], xy[0] + c.width, xy[1] + c.height)
                }
                PixelCopy.request(window, bmp, { result ->
                    ok = result == PixelCopy.SUCCESS
                    done.countDown()
                }, Handler(copyThread.looper))
            }.onFailure { done.countDown() }
        }
        if (!done.await(5, TimeUnit.SECONDS) || !ok) {
            bmp.recycle()
            return null
        }
        content.intersect(0, 0, bmp.width, bmp.height)
        return bmp to content
    }

    /**
     * Whether a frame is blank: near-uniform colour across the picture (a black, white
     * or single-colour screen). Samples a grid instead of every pixel.
     */
    fun isBlank(bmp: Bitmap, area: Rect = Rect(0, 0, bmp.width, bmp.height)): Boolean {
        if (area.width() < 2 || area.height() < 2) return false
        val steps = 40
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        for (yi in 0 until steps) for (xi in 0 until steps) {
            val c = bmp.getPixel(area.left + xi * (area.width() - 1) / (steps - 1),
                area.top + yi * (area.height() - 1) / (steps - 1))
            val luma = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            sum += luma; sumSq += luma * luma; n++
        }
        val mean = sum / n
        return sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0)) < BLANK_STDDEV
    }

    private const val BLANK_STDDEV = 3.0
}
