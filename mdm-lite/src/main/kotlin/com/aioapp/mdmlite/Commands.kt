package com.aioapp.mdmlite

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

/**
 * Commands from the check-in response (MDM-lite has no socket, so they arrive at most a
 * check-in interval late). Each is acknowledged completed or failed with a reason, over
 * the same ack endpoint the other agents use.
 */
internal class Commands(
    private val app: Context,
    private val client: Client,
    private val store: Store,
    private val serial: () -> String,
) {
    @Volatile var webView: WeakReference<WebView>? = null
    @Volatile var onUpdateCheck: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun run(cmds: JSONArray?) {
        if (cmds == null) return
        for (i in 0 until cmds.length()) {
            val c = cmds.optJSONObject(i) ?: continue
            val id = c.optString("id")
            if (id.isBlank()) continue
            val type = c.optString("type")
            try {
                when (type) {
                    "screenshot" -> screenshot(id)
                    "app_reload" -> onWebView(id, "reloaded") { it.reload() }
                    "app_clear_cache" -> onWebView(id, "cache cleared, reloaded") { it.clearCache(true); it.reload() }
                    "app_update_check" -> updateCheck(id)
                    "app_restart" -> restart(id)
                    "app_update" -> Updater.run(app, client.base, store.deviceKey, store, id, c.optString("apk_url"),
                        c.optJSONObject("payload")) { status, out -> ack(id, status, out) }
                    else -> ack(id, "failed", "not supported by MDM-lite: $type")
                }
            } catch (t: Throwable) {
                ack(id, "failed", "${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun screenshot(id: String) {
        val bmp = Screen.capture() ?: return ack(id, "failed", "the app is not on screen")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        ack(id, "completed", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    private fun onWebView(id: String, done: String, action: (WebView) -> Unit) {
        val wv = webView?.get() ?: return ack(id, "failed", "the host app registered no WebView")
        main.post { action(wv) }
        ack(id, "completed", done)
    }

    private fun updateCheck(id: String) {
        val cb = onUpdateCheck ?: return ack(id, "failed", "the host app has no update check hook")
        main.post { cb() }
        ack(id, "completed", "update check started")
    }

    /**
     * Relaunch the app in a fresh process, via [RestartActivity]. Starting it counts as a
     * foreground start only while the app is on screen, so a restart is refused (with the
     * reason) when it is not. The ack goes first: this process ends.
     */
    private fun restart(id: String) {
        if (app.packageManager.getLaunchIntentForPackage(app.packageName) == null) {
            return ack(id, "failed", "no launch activity")
        }
        if (!Screen.foreground()) return ack(id, "failed", "the app is not on screen; Android blocks a restart from the background")
        ack(id, "completed", "restarting")
        main.post { RestartActivity.start(app) }
    }

    fun ack(id: String, status: String, output: String) {
        val body = JSONObject().put("serial_number", serial()).put("status", status).put("output", output)
        val r = client.post("/api/v1/commands/$id/ack", body, store.deviceKey).result
        if (r != Client.Result.OK) Log.w("AioMdm", "ack $id ($status) failed: $r")
    }
}
