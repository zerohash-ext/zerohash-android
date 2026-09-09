package com.zerohash.sdk.cryptodeposits

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.zerohash.sdk.BuildConfig
import com.zerohash.sdk.ZerohashAllowList
import com.zerohash.sdk.ZerohashApp
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.ZerohashSession
import com.zerohash.sdk.Environment
import com.zerohash.sdk.Theme
import com.zerohash.sdk.internal.JwtValidator
import com.zerohash.sdk.ui.WebViewActivity

/**
 * Manages the lifecycle of a Crypto Deposits session.
 *
 * Mirrors [com.zerohash.sdk.fund.ZerohashFundSession]: validates the JWT, then
 * launches [WebViewActivity] pointed at the `#crypto-deposits` route. A deposit
 * made through the flow's own screens arrives over the bridge as a
 * `crypto-deposit` (or `transaction-failed`) message; one funded from a
 * connected external account arrives as `deposit-status`.
 */
class ZerohashCryptoDepositsSession internal constructor(
    private val jwt: String,
    private val environment: Environment,
    private val theme: Theme,
    private val allowList: ZerohashAllowList = ZerohashAllowList.DEFAULT,
    private val callbacks: CryptoDepositsCallbacks
) {
    private var session: ZerohashSession? = null
    private var hasPresented = false

    companion object {
        private const val TAG = "ZHCryptoDeposits"
        // Hash route served by the zerohash mobile web app (createHashRouter,
        // base "/mobile"). The route embeds the Crypto Deposits web component
        // + iframe.
        private const val PATH = "/mobile/#crypto-deposits"
    }

    /**
     * Present the Crypto Deposits session.
     *
     * @param activity The activity to launch from
     * @return The created [ZerohashSession], or null if already presented or the
     *         JWT is invalid.
     */
    fun present(activity: Activity): ZerohashSession? {
        if (hasPresented) {
            Log.w(TAG, "Session already presented")
            return null
        }

        // Validate JWT structure and expiry before proceeding
        val validationResult = JwtValidator.validate(jwt)
        if (validationResult.isFailure) {
            val msg = validationResult.exceptionOrNull()?.message ?: "JWT validation failed"
            Log.e(TAG, "JWT validation failed: $msg")
            callbacks.onError(ZerohashError.ConfigurationError(msg))
            return null
        }

        hasPresented = true

        val newSession = ZerohashSession(app = ZerohashApp.CRYPTO_DEPOSITS)
        session = newSession

        newSession.setOnCloseCallback {
            callbacks.onClose()
        }

        val callbackHandler = CryptoDepositsCallbackHandler(callbacks)

        val url = "https://${environment.webHost}$PATH"

        val intent = Intent(activity, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_URL, url)
            putExtra(WebViewActivity.EXTRA_JWT, jwt)
            putExtra(WebViewActivity.EXTRA_ENVIRONMENT, environment.toWebValue())
            putExtra(WebViewActivity.EXTRA_THEME, theme.toWebValue())
            putExtra(WebViewActivity.EXTRA_SESSION_ID, newSession.id)
            putExtra(WebViewActivity.EXTRA_WEB_HOST, environment.webHost)
            putStringArrayListExtra(
                WebViewActivity.EXTRA_ALLOW_HOSTS,
                ArrayList(allowList.hosts)
            )
        }

        WebViewActivity.setCallbackHandler(newSession.id, callbackHandler)

        try {
            if (BuildConfig.DEBUG) Log.d(TAG, "Starting WebViewActivity")
            activity.startActivity(intent)
        } catch (e: Exception) {
            // Remove stale handler on activity-start failure
            WebViewActivity.removeCallbackHandler(newSession.id)
            Log.e(TAG, "Failed to start WebViewActivity", e)
            callbacks.onError(
                ZerohashError.UnknownError("Failed to open Crypto Deposits: ${e.message}")
            )
        }

        return newSession
    }

    fun cancel() {
        session?.close()
        session = null
    }

    fun isActive(): Boolean = session?.isActive() ?: false
}
