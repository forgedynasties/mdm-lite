package com.aioapp.mdmlite

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Remote update of the host app itself ("app_update"), with no adb, root or Device Owner.
 *
 * The APK is downloaded, then checked before anything is installed: same package as the
 * host, signed with the same key, a higher version, and (when the server sends one) the
 * expected SHA-256. It is installed through the platform PackageInstaller. On Android 12+
 * with "Install unknown apps" allowed for the host (granted once at provisioning:
 * `appops set <pkg> REQUEST_INSTALL_PACKAGES allow`), the update is silent; otherwise
 * Android shows its standard "update this app?" prompt, which someone confirms.
 *
 * The install replaces this very process, so the command is acknowledged by the new
 * version: the pending update is written down before installing and settled at the next
 * start ([settlePending]), where the running version is compared with the target.
 */
internal object Updater {
    private const val TAG = "AioMdm"
    private const val ACTION_STATUS = "com.aioapp.mdmlite.INSTALL_STATUS"

    /** True when an update can install without anyone touching the screen. */
    fun silentCapable(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ctx.packageManager.canRequestPackageInstalls()

    /**
     * Download, verify and install. Runs on the MDM executor. [ack] reports a failure
     * right away; success is reported by the new version (see [settlePending]).
     */
    fun run(ctx: Context, serverBase: String, deviceKey: String, store: Store, id: String, apkUrl: String,
            payload: JSONObject?, ack: (String, String) -> Unit) {
        if (apkUrl.isBlank()) return ack("failed", "no apk_url")
        val apk = File(ctx.cacheDir, "aio_mdm_update.apk")
        try {
            download(resolve(serverBase, apkUrl), if (sameHost(serverBase, apkUrl)) deviceKey else null, apk)
        } catch (e: Exception) {
            return ack("failed", "download failed: ${e.message}")
        }
        verify(ctx, apk, payload?.optString("sha256").orEmpty())?.let { apk.delete(); return ack("failed", it) }

        val info = archiveInfo(ctx, apk)!!
        store.pendingUpdate = "$id|${info.longVersionCode}|${info.versionName ?: ""}"
        try {
            install(ctx, apk, id)
        } catch (e: Exception) {
            store.pendingUpdate = ""
            apk.delete()
            ack("failed", "install failed: ${e.message}")
        }
    }

    /**
     * At startup: settle an update this app asked for. Running the target version (or
     * newer) means it went in; otherwise it did not (declined, or failed after a restart).
     */
    fun settlePending(ctx: Context, store: Store, ack: (String, String, String) -> Unit) {
        val p = store.pendingUpdate.takeIf { it.isNotBlank() } ?: return
        val (id, target, name) = p.split("|").let { Triple(it[0], it.getOrNull(1)?.toLongOrNull() ?: 0, it.getOrNull(2).orEmpty()) }
        val now = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0)
        if (now >= target) {
            store.pendingUpdate = ""
            File(ctx.cacheDir, "aio_mdm_update.apk").delete()
            ack(id, "completed", "updated to ${name.ifBlank { target.toString() }}")
        }
        // Still older: the prompt may be waiting on screen; the status receiver settles
        // a refusal or failure.
    }

    private fun verify(ctx: Context, apk: File, sha256: String): String? {
        if (sha256.isNotBlank()) {
            val got = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
            if (!got.equals(sha256, ignoreCase = true)) return "checksum mismatch"
        }
        val info = archiveInfo(ctx, apk) ?: return "not a valid APK"
        if (info.packageName != ctx.packageName) return "APK is ${info.packageName}, not this app (${ctx.packageName})"
        val cur = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (info.longVersionCode <= cur.longVersionCode) {
            return "APK version ${info.versionName} (${info.longVersionCode}) is not newer than the installed ${cur.versionName} (${cur.longVersionCode})"
        }
        // Android refuses a mismatched signature anyway; this just fails early and clearly.
        if (signers(info).intersect(signers(cur)).isEmpty()) return "APK is signed with a different key"
        return null
    }

    private fun archiveInfo(ctx: Context, apk: File): PackageInfo? =
        ctx.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)

    /** Digests of every certificate the package is signed with, including its rotation lineage. */
    private fun signers(p: PackageInfo): Set<String> {
        val si = p.signingInfo ?: return emptySet()
        val all: List<android.content.pm.Signature> =
            si.apkContentsSigners?.toList().orEmpty() + si.signingCertificateHistory?.toList().orEmpty()
        return all.map { s: android.content.pm.Signature ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun install(ctx: Context, apk: File, id: String) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sid = installer.createSession(params)
        installer.openSession(sid).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val status = Intent(ctx, StatusReceiver::class.java).setAction(ACTION_STATUS).putExtra("cmd", id)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, sid, status, flags).intentSender)
        }
        Log.i(TAG, "update session $sid committed (silent=${silentCapable(ctx)})")
    }

    private fun resolve(base: String, url: String) = if (url.startsWith("/")) base.trimEnd('/') + url else url
    private fun sameHost(base: String, url: String) =
        url.startsWith("/") || runCatching { URL(url).host == URL(base).host }.getOrDefault(false)

    private fun download(url: String, deviceKey: String?, to: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        if (!deviceKey.isNullOrEmpty()) conn.setRequestProperty("X-API-Key", deviceKey)
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * PackageInstaller's result. Needs the user when "Install unknown apps" is off (or
     * before Android 12): show Android's prompt. A failure is acknowledged here; a
     * success is settled by the new version at startup.
     */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getStringExtra("cmd").orEmpty()
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    runCatching { ctx.startActivity(confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Log.w(TAG, "could not show the update prompt: ${it.message}") }
                    AioMdm.reportUpdate(id, null, "waiting for someone to confirm the update on screen")
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // the new version settles it
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                    AioMdm.reportUpdate(id, "failed", if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "update declined on the device" else msg)
                }
            }
        }
    }

    /** After this app was replaced: bring it back on screen (menu boards run unattended). */
    class ReplacedReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(ctx.packageName) ?: return
            // Allowed from the background when the app may draw over others (granted at
            // provisioning with the install permission) or is the home app.
            runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "relaunch after update blocked: ${it.message}") }
        }
    }
}
