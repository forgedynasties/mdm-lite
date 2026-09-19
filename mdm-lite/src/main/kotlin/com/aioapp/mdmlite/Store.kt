package com.aioapp.mdmlite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The device key and the queue of events waiting to be reported.
 *
 * Events go to a file, not memory: the most important ones (a crash) are written just
 * before the process dies and sent by the next process. The queue is bounded, and a
 * message repeated within [REPEAT_WINDOW_MS] is counted rather than stored again, so a
 * page logging the same JavaScript error every second costs one entry.
 */
internal class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("aio_mdm_lite", Context.MODE_PRIVATE)
    private val file = File(ctx.filesDir, "aio_mdm_events.json")
    private val lock = Any()

    var deviceKey: String
        get() = prefs.getString("device_key", "").orEmpty()
        set(v) { prefs.edit().putString("device_key", v).apply() }

    /** apps_hash of the inventory the server last accepted. */
    var lastAppsHash: String
        get() = prefs.getString("apps_hash", "").orEmpty()
        set(v) { prefs.edit().putString("apps_hash", v).apply() }

    /** The serial this device enrolled with; kept so its identity never changes. */
    var enrolledSerial: String
        get() = prefs.getString("enrolled_serial", "").orEmpty()
        set(v) { prefs.edit().putString("enrolled_serial", v).apply() }

    /** "cmdId|targetVersionCode|versionName" of an app update in flight ("" = none). */
    var pendingUpdate: String
        get() = prefs.getString("pending_update", "").orEmpty()
        set(v) { prefs.edit().putString("pending_update", v).commit() } // commit: the process may die next

    /** Newest ApplicationExitInfo timestamp already reported. */
    var lastExitSeenMs: Long
        get() = prefs.getLong("last_exit_ms", 0L)
        set(v) { prefs.edit().putLong("last_exit_ms", v).apply() }

    fun addEvent(kind: String, timeMs: Long, summary: String, detail: String?, sync: Boolean = false) {
        synchronized(lock) {
            val arr = read()
            for (i in arr.length() - 1 downTo 0) {
                val e = arr.getJSONObject(i)
                if (e.optString("kind") == kind && e.optString("summary") == summary &&
                    timeMs - e.optLong("last_ms", e.optLong("time_ms")) < REPEAT_WINDOW_MS
                ) {
                    e.put("count", e.optInt("count", 1) + 1).put("last_ms", timeMs)
                    write(arr, sync)
                    return
                }
            }
            val e = JSONObject().put("kind", kind).put("time_ms", timeMs).put("summary", summary)
            if (!detail.isNullOrEmpty()) e.put("trace", detail.take(MAX_TRACE_CHARS))
            arr.put(e)
            while (arr.length() > MAX_EVENTS) arr.remove(0)
            write(arr, sync)
        }
    }

    /** How many events are waiting to be sent. */
    fun pendingCount(): Int = synchronized(lock) { read().length() }

    /** Events for the next check-in: oldest first, capped so one check-in stays small. */
    fun pendingEvents(): JSONArray = synchronized(lock) {
        val all = read()
        val out = JSONArray()
        var budget = MAX_TRACES_PER_CHECKIN_CHARS
        for (i in 0 until minOf(all.length(), MAX_PER_CHECKIN)) {
            val e = JSONObject(all.getJSONObject(i).toString())
            val count = e.optInt("count", 1)
            if (count > 1) e.put("summary", "${e.optString("summary")} (×$count)")
            e.remove("count"); e.remove("last_ms")
            val trace = e.optString("trace")
            if (trace.length > budget) e.remove("trace") else budget -= trace.length
            out.put(e)
        }
        out
    }

    /** The first [n] events were delivered. */
    fun dropEvents(n: Int) {
        if (n <= 0) return
        synchronized(lock) {
            val arr = read()
            val rest = JSONArray()
            for (i in n until arr.length()) rest.put(arr.get(i))
            write(rest, false)
        }
    }

    private fun read(): JSONArray =
        runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())

    private fun write(arr: JSONArray, sync: Boolean) {
        val tmp = File(file.path + ".tmp")
        runCatching {
            tmp.outputStream().use { out ->
                out.write(arr.toString().toByteArray())
                if (sync) out.fd.sync() // the process is about to die
            }
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val MAX_EVENTS = 200
        private const val MAX_PER_CHECKIN = 40
        private const val MAX_TRACE_CHARS = 48 * 1024
        private const val MAX_TRACES_PER_CHECKIN_CHARS = 128 * 1024
        private const val REPEAT_WINDOW_MS = 10 * 60_000L
    }
}
