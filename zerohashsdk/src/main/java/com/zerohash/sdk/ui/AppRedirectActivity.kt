package com.zerohash.sdk.ui

import android.app.Activity
import android.app.ActivityManager
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
 * This activity holds no state. It brings the task that holds [WebViewActivity]
 * to the front when that task is not its own (for example with
 * `taskAffinity=""`), and then finishes.
 */
class AppRedirectActivity : Activity() {

    companion object {
        private const val TAG = "AppRedirectActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            bringSdkTaskToFront()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle the app redirect", e)
        } finally {
            finish()
        }
    }

    private fun bringSdkTaskToFront() {
        // RecentTaskInfo.topActivity needs API 23. Below that, finishing still
        // works when this activity opened in the SDK's own task (the default).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val activityManager = getSystemService(ActivityManager::class.java) ?: return
        activityManager.appTasks
            .firstOrNull { it.taskInfo.topActivity?.className == WebViewActivity::class.java.name }
            ?.moveToFront()
    }
}
