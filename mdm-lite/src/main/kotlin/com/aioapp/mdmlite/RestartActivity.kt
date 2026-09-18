package com.aioapp.mdmlite

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Restarts the host app. Runs in its own process (":aio_mdm_restart", see the manifest).
 *
 * A process cannot reliably relaunch itself: an activity it starts just before exiting
 * dies with it, and an alarm that fires after it is gone counts as a background start,
 * which Android 10+ blocks. This activity is started while the app is still visible,
 * so it is a foreground start; from here, as the visible activity, it may end the
 * app's process and launch it again. It shows nothing and finishes at once.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let { startActivity(it) }
        finish()
        Runtime.getRuntime().exit(0)
    }

    internal companion object {
        private const val EXTRA_PID = "aio_mdm_pid"

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, RestartActivity::class.java)
                .putExtra(EXTRA_PID, Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
