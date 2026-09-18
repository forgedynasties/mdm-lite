package com.aioapp.mdmlite

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The host app's own crashes. No permission is needed for any of this.
 *
 * Java crashes are caught in-process by an uncaught-exception handler, written to disk
 * before the process dies, and chained so Crashlytics / New Relic still see them.
 *
 * Everything the process cannot catch itself (ANRs, native crashes, being killed for
 * memory) Android records as ApplicationExitInfo (API 30+), which the next start reads.
 * ANRs come with the thread dump.
 */
internal object Crashes {

    fun installHandler(store: Store) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val headline = "${error.javaClass.name}: ${error.message.orEmpty()}".take(300)
                store.addEvent("app_crash", System.currentTimeMillis(), headline,
                    "thread=${thread.name}\n$sw", sync = true)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun collectExitReasons(ctx: Context, store: Store) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = runCatching { am.getHistoricalProcessExitReasons(null, 0, 16) }.getOrNull() ?: return
        val seen = store.lastExitSeenMs
        var newest = seen
        for (e in exits.sortedBy { it.timestamp }) {
            if (e.timestamp <= seen) continue
            newest = maxOf(newest, e.timestamp)
            val kind = when (e.reason) {
                ApplicationExitInfo.REASON_ANR -> "app_anr"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "app_native_crash"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "app_lmk"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "app_resource_kill"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "app_init_failure"
                // A Java crash is already recorded, with its stack, by the handler above.
                // User stops, updates and normal exits are not failures.
                else -> null
            } ?: continue
            val summary = e.description?.takeIf { it.isNotBlank() }?.take(300)
                ?: "${kind.removePrefix("app_").replace('_', ' ')} (process ${e.processName})"
            store.addEvent(kind, e.timestamp, summary, detailOf(e))
        }
        if (newest > seen) store.lastExitSeenMs = newest
    }

    private fun detailOf(e: ApplicationExitInfo): String {
        val head = buildString {
            append("process=").append(e.processName)
            append(" pid=").append(e.pid)
            append(" importance=").append(e.importance)
            append(" pss_kb=").append(e.pss)
            append(" rss_kb=").append(e.rss)
        }
        // ANR traces are text. A native crash's trace is a binary tombstone proto on
        // Android 12+, which would arrive as noise, so only the ANR dump is attached.
        if (e.reason != ApplicationExitInfo.REASON_ANR) return head
        val trace = runCatching {
            e.traceInputStream?.use { it.readBytes().decodeToString() }
        }.getOrNull().orEmpty()
        return if (trace.isEmpty()) head else "$head\n\n$trace"
    }
}
