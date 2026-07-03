package com.zerohash.sdk.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.zerohash.sdk.BuildConfig

/**
 * Receiver for the `connectsdk-oauth://callback` redirect from Chrome Custom Tabs.
 *
 * This is the **only** activity in the SDK that is `android:exported="true"`.
 * Its sole responsibility is to receive the OAuth redirect (dispatched
 * cross-process by the system after Chrome navigates to the custom scheme) and
 * forward it to the running [WebViewActivity], which is `singleTop` and
 * non-exported. The activity uses a translucent theme and finishes immediately
 * — the user never sees it.
 *
 * See the OAuth-scheme note in AndroidManifest.xml: the scheme is dictated by
 * the backend (connection-service) redirect URI and must stay in sync with it.
 */
class OAuthCallbackActivity : Activity() {

    companion object {
        private const val TAG = "OAuthCallbackActivity"
        private const val OAUTH_CALLBACK_SCHEME = "connectsdk-oauth"
        private const val OAUTH_CALLBACK_HOST = "callback"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        forward(intent)
    }

    private fun forward(incoming: Intent?) {
        try {
            val data: Uri? = incoming?.data
            if (data == null ||
                data.scheme != OAUTH_CALLBACK_SCHEME ||
                data.host != OAUTH_CALLBACK_HOST
            ) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Ignoring intent: not an OAuth callback")
                return
            }

            val forward = Intent(this, WebViewActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                this.data = data
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(forward)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward OAuth callback", e)
        } finally {
            finish()
        }
    }
}
