package com.aioapp.mdmlite

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Installed-app inventory, in the firmware client's protocol: every check-in carries
 * apps_hash, and the full installed_apps list goes only when the hash changed or the
 * server asked for it (send_apps in the check-in response). Needs QUERY_ALL_PACKAGES
 * (declared by the library) to see beyond the host app's own package-visibility set.
 */
internal object Apps {

    fun list(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { it.activityInfo?.packageName }.toHashSet()
        val arr = JSONArray()
        for (pi in pm.getInstalledPackages(0).sortedBy { it.packageName }) {
            val ai = pi.applicationInfo ?: continue
            arr.put(JSONObject()
                .put("package", pi.packageName)
                .put("name", pm.getApplicationLabel(ai).toString())
                .put("version_name", pi.versionName ?: pi.longVersionCode.toString())
                .put("is_system", ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                .put("launchable", pi.packageName in launchable)
                .apply {
                    // Icons only for launcher apps (what the dashboard's app drawer shows),
                    // as the DPC agent does, so the list stays small on the wire.
                    if (pi.packageName in launchable) iconBase64(pm, ai)?.let { put("icon", it) }
                })
        }
        return arr
    }

    /** A package's launcher icon as a 96 px base64 PNG; null on any failure. */
    private fun iconBase64(pm: PackageManager, ai: ApplicationInfo): String? = runCatching {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        pm.getApplicationIcon(ai).apply { setBounds(0, 0, size, size) }.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    fun hash(apps: JSONArray): String {
        val crc = CRC32()
        // Bumped when the list's shape changes (v2: icons; v3: resend once after the server began keeping them), so devices already reporting
        // resend the full list once after updating instead of keeping the old one.
        crc.update("v3\n".toByteArray())
        for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i)
            crc.update("${a.optString("package")}:${a.optString("version_name")}\n".toByteArray())
        }
        return crc.value.toString(16)
    }
}
