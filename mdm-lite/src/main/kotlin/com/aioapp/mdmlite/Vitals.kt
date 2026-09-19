package com.aioapp.mdmlite

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/**
 * The check-in payload. Keys and units match what the firmware client and the DPC agent
 * send (ram_usage_mb, storage_free_gb, cpu_temp_c, ...), so the server's existing
 * charts, filters and alerts read them unchanged. Everything here is readable by an
 * ordinary app; anything a device refuses is simply left out.
 */
internal object Vitals {

    /** Server `agent_type`: the MDM-lite library embedded in another app. */
    const val AGENT_TYPE = "mdm-lite"

    fun product(): String = Build.PRODUCT.ifBlank { Build.MODEL }

    fun checkin(ctx: Context, serial: String, events: JSONArray, appsHash: String, apps: JSONArray?): JSONObject {
        val battery = battery(ctx)
        return JSONObject()
            .put("serial_number", serial)
            .put("build_id", Build.DISPLAY.ifBlank { Build.ID })
            .put("product", product())
            .put("apps_hash", appsHash)
            .put("extra", extra(ctx, events, battery))
            .apply {
                battery?.pct?.let { put("battery_pct", it) }
                if (apps != null) put("installed_apps", apps)
            }
    }

    /** The battery broadcast, only when a pack is present (phones, tablets; not TV boxes). */
    private class Battery(val pct: Int?, val tempC: Double?, val plugged: Int, val status: Int)

    private fun battery(ctx: Context): Battery? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        // A box with no pack reports present=false with 0% and 0 °C: send nothing at all.
        if (!i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return Battery(
            pct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            tempC = if (tenths != Int.MIN_VALUE) tenths / 10.0 else null,
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
        )
    }

    private fun extra(ctx: Context, events: JSONArray, battery: Battery?) = JSONObject().apply {
        put("agent_type", AGENT_TYPE)
        put("agent_version", BuildConfig.LIB_VERSION)
        // Only what the library can actually do. The server offers a device no command
        // outside this list.
        // self_update: the host app can be updated remotely (silently when allowed to
        // install apps, otherwise through Android's on-screen prompt).
        put("capabilities", JSONArray(listOf("telemetry", "screen_capture", "app_control", "self_update")))
        put("self_update_silent", Updater.silentCapable(ctx))
        put("capabilities_degraded", JSONArray())
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("android_release", Build.VERSION.RELEASE)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("leanback", ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        put("uptime_seconds", SystemClock.elapsedRealtime() / 1000)
        put("timezone", TimeZone.getDefault().id)
        // The device's own clock. A box without a battery-backed clock can boot years in
        // the past, which breaks TLS; the server compares this with its own time.
        put("device_time_ms", System.currentTimeMillis())
        runCatching {
            put("boot_count", Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT))
        }
        // Battery keys as the firmware client sends them, so the server's battery chip,
        // charts and low-battery alerts read them unchanged.
        if (battery != null) {
            put("battery_present", true)
            battery.tempC?.let { put("battery_temp_c", it) }
            put("charging", battery.plugged != 0 ||
                battery.status == BatteryManager.BATTERY_STATUS_CHARGING ||
                battery.status == BatteryManager.BATTERY_STATUS_FULL)
            put("charger_type", when {
                battery.plugged == 0 -> "none"
                battery.plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"
                battery.plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"
                battery.plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
                else -> "unknown"
            })
        }
        memory(ctx, this)
        storage(this)
        thermal(ctx, this)
        network(ctx, this)
        display(ctx, this)
        host(ctx, this)
        // Is the host app on screen? False means something else is in front of it.
        put("app_foreground", Screen.foreground())
        runCatching { SecurityPosture.put(ctx, this) }
        if (events.length() > 0) put("crash_events", events)
    }

    private fun memory(ctx: Context, extra: JSONObject) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        extra.put("ram_usage_mb", JSONObject()
            .put("total", mi.totalMem / MB)
            .put("available", mi.availMem / MB)
            .put("used", (mi.totalMem - mi.availMem) / MB))
        extra.put("low_memory", mi.lowMemory)
    }

    private fun storage(extra: JSONObject) {
        val st = StatFs(Environment.getDataDirectory().path)
        extra.put("storage_free_gb", Math.round(st.availableBytes / GB * 10.0) / 10.0)
        extra.put("storage_total_bytes", st.totalBytes)
    }

    /**
     * cpu_temp_c from the first readable CPU/SoC thermal zone. Whether an ordinary app may
     * read sysfs thermal zones is up to the device's SELinux policy, so this is best
     * effort. thermal_status (Android's own 0..6 throttling scale) is always available.
     */
    private fun thermal(ctx: Context, extra: JSONObject) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        extra.put("thermal_status", pm.currentThermalStatus)
        cpuTempC()?.let { extra.put("cpu_temp_c", it) }
    }

    private fun cpuTempC(): Double? {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?: return null
        val ranked = zones.sortedBy { z ->
            val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrDefault("")
            if ("cpu" in type || "soc" in type) 0 else 1
        }
        for (z in ranked) {
            val raw = runCatching { File(z, "temp").readText().trim().toDouble() }.getOrNull() ?: continue
            // Most kernels report millidegrees, a few report degrees.
            val c = if (raw > 1000) raw / 1000.0 else raw
            if (c in 1.0..150.0) return Math.round(c * 10.0) / 10.0
        }
        return null
    }

    private fun network(ctx: Context, extra: JSONObject) {
        runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            val ip = cm.getLinkProperties(net)?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address && !it.address.isLoopbackAddress }
                ?.address?.hostAddress
            extra.put("ip_address", ip ?: JSONObject.NULL)
            extra.put("network_type", when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            })
            extra.put("network_validated",
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                @Suppress("DEPRECATION")
                val info = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
                extra.put("wifi_rssi", info?.rssi ?: JSONObject.NULL)
            }
        }
    }

    private fun display(ctx: Context, extra: JSONObject) {
        runCatching {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val mode = d.mode
            extra.put("display", JSONObject()
                .put("width", mode.physicalWidth)
                .put("height", mode.physicalHeight)
                .put("refresh_hz", Math.round(mode.refreshRate * 10) / 10.0)
                .put("count", dm.displays.size))
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            extra.put("screen_on", pm.isInteractive)
        }
    }

    /** The app hosting the library, and the WebView it renders with. */
    private fun host(ctx: Context, extra: JSONObject) {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            extra.put("host_app", JSONObject()
                .put("package", ctx.packageName)
                .put("version_name", pi.versionName)
                .put("version_code", pi.longVersionCode))
        }
        runCatching {
            WebView.getCurrentWebViewPackage()?.let {
                extra.put("webview", JSONObject().put("package", it.packageName).put("version", it.versionName))
            }
        }
    }

    private const val MB = 1024L * 1024L
    private const val GB = 1024.0 * 1024.0 * 1024.0
}
