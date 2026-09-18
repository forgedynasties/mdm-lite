package com.aioapp.mdmlite

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * How the host app connects to the MDM.
 *
 * @param serverUrl MDM base URL, e.g. "https://mdm.example.com".
 * @param enrollToken an enrollment profile token ("enr_..."). One fleet-wide token per build
 *   flavor is fine: a device exchanges it once for its own key, so any device that installs
 *   the app enrolls itself (zero-touch).
 * @param serial the id the host app already uses for this device, so the MDM, the app's own
 *   backend and its crash tools agree. null falls back to "android-<ANDROID_ID>".
 * @param checkinSeconds how often vitals are reported.
 */
data class MdmConfig(
    val serverUrl: String,
    val enrollToken: String,
    val serial: String? = null,
    val checkinSeconds: Long = 60,
)

/**
 * AIO MDM-lite. Two calls cover everything: [init] once from Application.onCreate, and
 * the report* hooks from wherever the host app already sees failures (WebView renderer,
 * JavaScript console, page loads, memory pressure).
 *
 * What it does without a Device Owner, adb or root:
 *  - enrolls itself with [MdmConfig.enrollToken] and keeps the device key it is issued;
 *  - reports vitals on every check-in (RAM, storage, CPU temperature where readable,
 *    thermal status, network, display, uptime, clock, app and WebView versions);
 *  - reports this app's crashes, ANRs, native crashes and low-memory kills, with traces
 *    where Android keeps them, main-thread freezes (see [Watchdog]), plus the WebView
 *    events the host reports.
 *
 * Everything runs on one background thread; nothing here blocks the caller.
 */
/**
 * A snapshot of what MDM-lite is doing, for a host's status or debug screen.
 * [lastCheckinResult] is "ok", "rejected" (device key refused; re-enrolling),
 * "failed" (network / server error), "enroll failed" (token refused or server
 * unreachable) or "" before the first attempt.
 */
data class MdmStatus(
    val enrolled: Boolean,
    val serial: String,
    val serverUrl: String,
    val lastCheckinAtMs: Long,
    val lastCheckinResult: String,
    val pendingEvents: Int,
    val libraryVersion: String,
    val appInForeground: Boolean,
)

object AioMdm {
    private const val TAG = "AioMdm"

    @Volatile private var started = false
    private lateinit var app: Context
    private lateinit var config: MdmConfig
    private lateinit var store: Store
    private lateinit var client: Client
    private lateinit var executor: ScheduledExecutorService
    private lateinit var commands: Commands

    @JvmStatic
    fun init(application: Application, config: MdmConfig) {
        if (started) return
        started = true
        this.app = application.applicationContext
        this.config = config
        store = Store(app)
        client = Client(config.serverUrl)
        // First, so a crash during the rest of startup is still caught.
        Crashes.installHandler(store)
        Watchdog(store).start()
        Screen.track(application)
        commands = Commands(app, client, store, ::serial)
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "aio-mdm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
        executor.execute { Crashes.collectExitReasons(app, store) }
        executor.scheduleWithFixedDelay(::tick, 5, config.checkinSeconds, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay(::checkBlank, 90, BLANK_CHECK_SECONDS, TimeUnit.SECONDS)
        // Keeps reporting (every ~15 min) when the app is backgrounded or the device dozes.
        runCatching { CheckinJob.schedule(app) }
        Log.i(TAG, "started (serial=${serial()})")
    }

    /**
     * The WebView that shows the host's content. The remote "reload page" and "clear web
     * cache" commands act on it. Call again whenever the host replaces its WebView (e.g.
     * after a renderer crash); only a weak reference is kept.
     */
    @JvmStatic
    fun attachWebView(webView: android.webkit.WebView) {
        if (started) commands.webView = java.lang.ref.WeakReference(webView)
    }

    /** What the remote "check for update" command runs (on the main thread). */
    @JvmStatic
    fun setUpdateCheck(action: () -> Unit) {
        if (started) commands.onUpdateCheck = action
    }

    /** The WebView's renderer died. Call from WebViewClient.onRenderProcessGone. */
    @JvmStatic
    fun reportRendererGone(detail: RenderProcessGoneDetail, url: String?) {
        val crashed = detail.didCrash()
        report(
            if (crashed) "webview_renderer_crash" else "webview_renderer_killed",
            if (crashed) "WebView renderer crashed" else "WebView renderer killed by the system (memory)",
            "url=$url\npriority_at_exit=${detail.rendererPriorityAtExit()}",
        )
    }

    /** A JavaScript console error. Repeats of one message are collapsed (see [Store]). */
    @JvmStatic
    fun reportJsError(message: String, source: String?, line: Int) =
        report("js_error", message.take(300), "$source:$line")

    /** A page failed to load (WebViewClient.onReceivedError / onReceivedHttpError). */
    @JvmStatic
    fun reportPageError(url: String, code: Int, description: String, mainFrame: Boolean) {
        // Sub-resource failures (an image, a font) are routine; only a failed main frame
        // leaves the screen blank, so that is what is worth an event.
        if (!mainFrame) return
        report("page_load_error", "$description ($code)", url)
    }

    /** The app is under memory pressure (ComponentCallbacks2.onTrimMemory). */
    @JvmStatic
    fun reportTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL &&
            level < android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            report("app_memory_critical", "Memory critical while running (trim level $level)", null)
        }
    }

    /** What MDM-lite is doing right now; null until [init] has run. */
    @JvmStatic
    fun status(): MdmStatus? {
        if (!started) return null
        return MdmStatus(
            enrolled = store.deviceKey.isNotEmpty(),
            serial = serial(),
            serverUrl = config.serverUrl,
            lastCheckinAtMs = lastCheckinAtMs,
            lastCheckinResult = lastCheckinResult,
            pendingEvents = store.pendingCount(),
            libraryVersion = BuildConfig.LIB_VERSION,
            appInForeground = Screen.foreground(),
        )
    }

    /** Check in now instead of waiting for the next interval (e.g. a "sync" button). */
    @JvmStatic
    fun checkinNow() {
        if (started) executor.execute(::tick)
    }

    @Volatile private var lastCheckinAtMs = 0L
    @Volatile private var lastCheckinResult = ""

    /** Anything else the host wants on the device's timeline. */
    @JvmStatic
    fun report(kind: String, summary: String, detail: String?) {
        if (!started) return
        store.addEvent(kind, System.currentTimeMillis(), summary, detail)
    }

    @Volatile private var sendApps = false

    /**
     * One check-in right now, off the caller's thread; [done] runs when it finishes. For
     * the background job. False when MDM-lite is not running in this process.
     */
    internal fun checkinNow(done: () -> Unit): Boolean {
        if (!started) return false
        executor.execute {
            try { tick() } finally { done() }
        }
        return true
    }

    private fun tick() {
        try {
            if (store.deviceKey.isEmpty() && !enroll()) {
                lastCheckinAtMs = System.currentTimeMillis()
                lastCheckinResult = "enroll failed"
                return
            }
            val events = store.pendingEvents()
            val apps = runCatching { Apps.list(app) }.getOrNull()
            val appsHash = apps?.let { Apps.hash(it) }.orEmpty()
            val includeApps = apps != null && (sendApps || appsHash != store.lastAppsHash)
            val payload = Vitals.checkin(app, serial(), events, appsHash, if (includeApps) apps else null)
            val reply = client.post("/api/v1/checkin", payload, store.deviceKey)
            lastCheckinAtMs = System.currentTimeMillis()
            lastCheckinResult = when (reply.result) {
                Client.Result.OK -> "ok"
                Client.Result.UNAUTHORIZED -> "rejected"
                Client.Result.FAILED -> "failed"
            }
            when (reply.result) {
                Client.Result.OK -> {
                    store.dropEvents(events.length())
                    if (includeApps) { store.lastAppsHash = appsHash; sendApps = false }
                    // The server asks for the full list when it lost track of ours.
                    if (reply.body?.optBoolean("send_apps") == true) sendApps = true
                    commands.run(reply.body?.optJSONArray("commands"))
                }
                Client.Result.UNAUTHORIZED -> {
                    // The key was revoked or the device deleted: enroll again next tick.
                    Log.w(TAG, "device key rejected, re-enrolling")
                    store.deviceKey = ""
                }
                Client.Result.FAILED -> Unit // keep the events for the next try
            }
        } catch (t: Throwable) {
            Log.w(TAG, "check-in failed: ${t.message}")
        }
    }

    private var blankStreak = 0

    /**
     * Blank-screen check: a near-uniform picture on two checks in a row (a black or white
     * screen for 2+ minutes) is reported once, until the picture comes back. Skipped while
     * the display is off or the app is not in front: neither is a blank menu.
     */
    private fun checkBlank() {
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isInteractive || !Screen.foreground()) { blankStreak = 0; return }
            val (bmp, content) = Screen.captureWithContent() ?: return
            val blank = Screen.isBlank(bmp, content)
            bmp.recycle()
            if (!blank) { blankStreak = 0; return }
            blankStreak++
            if (blankStreak == 2) {
                report("screen_blank", "Screen blank (uniform colour) for ${2 * BLANK_CHECK_SECONDS / 60}+ minutes", null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "blank check failed: ${t.message}")
        }
    }

    private const val BLANK_CHECK_SECONDS = 120L

    private fun enroll(): Boolean {
        val body = JSONObject()
            .put("token", config.enrollToken)
            .put("serial", serial())
            .put("product", Vitals.product())
            .put("model", Build.MODEL)
            .put("os_version", Build.VERSION.RELEASE ?: "")
        val resp = client.postForJson("/api/v1/enroll", body) ?: return false
        val key = resp.optString("device_key")
        if (key.isBlank()) return false
        store.deviceKey = key
        Log.i(TAG, "enrolled as ${serial()}")
        return true
    }

    @SuppressLint("HardwareIds")
    private fun serial(): String {
        config.serial?.takeIf { it.isNotBlank() }?.let { return it }
        val id = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        return if (!id.isNullOrBlank()) "android-$id" else "unknown-${Build.MODEL}"
    }
}
