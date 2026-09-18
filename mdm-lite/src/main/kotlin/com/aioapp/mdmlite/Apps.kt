package com.aioapp.mdmlite

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import org.json.JSONArray
import org.json.JSONObject
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
                .put("launchable", pi.packageName in launchable))
        }
        return arr
    }

    fun hash(apps: JSONArray): String {
        val crc = CRC32()
        for (i in 0 until apps.length()) {
            val a = apps.getJSONObject(i)
            crc.update("${a.optString("package")}:${a.optString("version_name")}\n".toByteArray())
        }
        return crc.value.toString(16)
    }
}
