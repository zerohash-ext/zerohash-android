package com.zerohash.sdk.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.zerohash.sdk.BuildConfig
import com.zerohash.sdk.oauth.OAuthCallbackUri

/**
 * Receiver for the `connectsdk-oauth://<applicationId>` redirect from Chrome Custom Tabs
 * (see [OAuthCallbackUri]).
 *
 * This is the **only** activity in the SDK that is `android:exported="true"`.
 * Its sole responsibility is to receive the OAuth redirect (dispatched
 * cross-process by the system after Chrome navigates to the custom scheme) and
 * forward it to the running [WebViewActivity], which is `singleTop` and
 * non-exported. The activity uses a translucent theme and finishes immediately
 * — the user never sees it.
 *
 * The SDK picks the callback URI and the backend only passes it along; see
 * the OAuth-callback note in AndroidManifest.xml.
 */
class OAuthCallbackActivity : Activity() {

    companion object {
        private const val TAG = "OAuthCallbackActivity"
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
            val callbackUri = OAuthCallbackUri.buildForApp(this)
            if (data == null || !OAuthCallbackUri.matches(data.scheme, data.host, callbackUri)) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Ignoring intent: not an OAuth callback")
                return
            }

            startActivity(WebViewActivity.forwardIntent(this, data))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward OAuth callback", e)
        } finally {
            finish()
        }
    }
}
