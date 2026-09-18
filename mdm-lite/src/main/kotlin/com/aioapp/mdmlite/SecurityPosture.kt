package com.aioapp.mdmlite

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Security posture flags, the same keys and meanings the firmware client
 * (mdm-client SecurityPosture.java) sends, so the server's compliance rules read one
 * vocabulary across the fleet. Everything here is readable by an ordinary app; a flag
 * the device will not reveal is left out (unknown), never guessed.
 */
internal object SecurityPosture {

    private val SU_PATHS = arrayOf(
        "/system/xbin/su", "/system/bin/su", "/sbin/su", "/system/sbin/su",
        "/vendor/bin/su", "/su/bin/su", "/debug_ramdisk/su", "/data/local/xbin/su",
        "/data/local/bin/su",
    )
    private const val OP_REQUEST_INSTALL_PACKAGES = "android:request_install_packages"

    fun put(ctx: Context, extra: JSONObject) {
        val cr = ctx.contentResolver
        extra.put("adb_enabled", Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1)
        systemProp("service.adb.tcp.port")?.let { port ->
            extra.put("adb_tcp", port.isNotEmpty() && port != "0" && port != "-1")
        }
        extra.put("dev_options_enabled",
            Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)
        @Suppress("DEPRECATION")
        val legacyUnknown = Settings.Secure.getInt(cr, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        val installers = unknownSourceApps(ctx)
        extra.put("unknown_sources", legacyUnknown || (installers?.length() ?: 0) > 0)
        installers?.let { extra.put("unknown_source_apps", it) }
        extra.put("su_present", SU_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) })
        extra.put("build_type", Build.TYPE)
        extra.put("build_tags", Build.TAGS)
        val a11y = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        extra.put("accessibility_services", JSONArray(a11y))
        extra.put("play_protect", Settings.Global.getInt(cr, "package_verifier_enable", 1) != 0)
    }

    /**
     * Apps allowed to install unknown apps. Reading another app's app-op needs a permission
     * an ordinary app may not hold on every build: null (unknown) when refused.
     */
    private fun unknownSourceApps(ctx: Context): JSONArray? = runCatching {
        val pm = ctx.packageManager
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val out = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .filter { pi -> pi.requestedPermissions?.contains("android.permission.REQUEST_INSTALL_PACKAGES") == true }
            .filter { pi ->
                val uid = pi.applicationInfo?.uid ?: return@filter false
                ops.unsafeCheckOpNoThrow(OP_REQUEST_INSTALL_PACKAGES, uid, pi.packageName) ==
                    AppOpsManager.MODE_ALLOWED
            }
            .map { it.packageName }.sorted()
        JSONArray(out)
    }.getOrNull()

    /** android.os.SystemProperties is hidden; null when the property is unreadable. */
    private fun systemProp(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()
}
