package com.zerohash.sdk.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Target of the `appRedirectUrl` another app opens to send the user back to the
 * host, such as a wallet after a WalletConnect approval. The host app declares
 * its own scheme or App Link on this activity in its manifest (see the README),
 * so the link never reaches one of the host's own activities.
 *
 * A host activity can drop the SDK: a `singleTask` launcher (the Capacitor
 * default) clears every activity above it, which destroys [WebViewActivity].
 * This activity holds no state. While the SDK is alive it hands the link to the
 * running flow (the deposit page appends the wallet's result to it) and brings
 * the SDK's task to the front if the link landed in another one. When the
 * process was killed meanwhile, it only brings the SDK's task back. Then it
 * finishes.
 */
class AppRedirectActivity : Activity() {

    companion object {
        private const val TAG = "AppRedirectActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val target = AppRedirectTargets.active
            if (target != null) {
                if (target.hostTaskId != taskId) bringToFront { idOf(it) == target.hostTaskId }
                intent?.data?.let(target::handleAppRedirect)
            } else {
                bringToFront { Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && it.topActivity?.className == WebViewActivity::class.java.name }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle the app redirect", e)
        } finally {
            finish()
        }
    }

    private fun bringToFront(matches: (ActivityManager.RecentTaskInfo) -> Boolean) {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        activityManager.appTasks.firstOrNull { matches(it.taskInfo) }?.moveToFront()
    }

    @Suppress("DEPRECATION")
    private fun idOf(info: ActivityManager.RecentTaskInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.id
}
