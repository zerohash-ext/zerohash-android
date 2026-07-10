package com.zerohash.sdk.oauth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.zerohash.sdk.BuildConfig

/**
 * Manages OAuth authentication flows using Chrome Custom Tabs.
 *
 * ## When this is used
 *
 * The Fund flow embeds the shared integrations-flow ("auth as a feature")
 * deposit-via-external-source experience. When the user links an external
 * funding source, the web app sends a `navigate` message with
 * `mobileTarget = "oauth"`; the SDK opens the authorization URL in Chrome
 * Custom Tabs (NOT the WebView) to preserve SSO and keep credentials isolated.
 *
 * ## Architecture context
 *
 * zerohash uses a **confidential-client OAuth architecture**: the authorization
 * code → token exchange happens entirely server-side inside connection-service,
 * which holds the `client_secret`. The Android SDK never sees the raw
 * authorization code; connection-service validates the OAuth callback, performs
 * the token exchange, and then redirects to the web app's oauth-callback page
 * which in turn fires `connectsdk-oauth://callback?connectionId=<uuid>`.
 *
 * The callback only delivers a `connectionId` (not an authorization code or
 * tokens), so custom-scheme hijacking is harmless: a malicious app that
 * intercepts the intent receives only a reference UUID it cannot exchange for
 * credentials. This handler additionally validates that `connectionId` is a
 * well-formed UUID and sanitises provider errors to the RFC 6749 §5.2 allowlist.
 *
 * OAuth URLs and connection IDs are only logged in debug builds.
 */
class OAuthHandler(
    private val activity: Activity
) {
    companion object {
        private const val TAG = "OAuthHandler"
        private const val OAUTH_CALLBACK_SCHEME = "connectsdk-oauth"
        private const val OAUTH_CALLBACK_HOST = "callback"
        const val REQUEST_CODE_OAUTH = 1001

        // RFC 4122 UUID — 8-4-4-4-12 hex chars
        private val UUID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        // RFC 6749 §5.2 standard OAuth 2.0 error codes
        private val STANDARD_OAUTH_ERRORS = setOf(
            "invalid_request",
            "unauthorized_client",
            "access_denied",
            "unsupported_response_type",
            "invalid_scope",
            "server_error",
            "temporarily_unavailable",
            "invalid_grant",
            "unsupported_grant_type",
            "invalid_client"
        )

        /**
         * Whether [value] is a syntactically well-formed UUID. Exposed for unit tests.
         */
        internal fun isValidUuid(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            return UUID_REGEX.matches(value)
        }

        /**
         * Map a raw provider error to a known OAuth 2.0 error code, falling back
         * to a generic `oauth_error` if unrecognised.
         */
        internal fun sanitizeOAuthError(rawError: String?): String {
            if (rawError.isNullOrBlank()) return "oauth_error"
            val normalized = rawError.trim().lowercase()
            return if (normalized in STANDARD_OAUTH_ERRORS) normalized else "oauth_error"
        }
    }

    interface OAuthCallback {
        fun onSuccess(connectionId: String?)
        fun onError(error: String)
        fun onCancel()
    }

    private var currentCallback: OAuthCallback? = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start OAuth flow with Chrome Custom Tabs.
     *
     * The authorization URL is passed through unchanged — connection-service
     * has already appended the `state` token and any other required parameters.
     *
     * @param url      OAuth authorization URL constructed by connection-service
     * @param callback Callback for OAuth results
     */
    fun startOAuthFlow(url: String, callback: OAuthCallback) {
        currentCallback = callback

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()

            if (BuildConfig.DEBUG) Log.d(TAG, "Starting OAuth flow")

            customTabsIntent.launchUrl(activity, Uri.parse(url))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OAuth flow", e)
            currentCallback = null
            callback.onError("Failed to open OAuth URL: ${e.message}")
        }
    }

    /**
     * Handle OAuth callback from redirect.
     *
     * Validates scheme and host of the incoming intent, then extracts the
     * `connectionId` from query parameters or fragment. Rejects non-UUID
     * connectionIds and sanitises provider-supplied errors to standard OAuth 2.0
     * codes.
     *
     * @param intent Intent containing the callback URL
     * @return true if the intent was handled, false otherwise
     */
    fun handleCallback(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        val callback = currentCallback ?: return false

        if (data.scheme != OAUTH_CALLBACK_SCHEME || data.host != OAUTH_CALLBACK_HOST) {
            return false
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "OAuth callback received")

        try {
            // Query parameters first (Authorization Code flow)
            var connectionId = data.getQueryParameter("connectionId")
            var error = data.getQueryParameter("error")

            // Fragment fallback (Implicit flow)
            if (connectionId == null && error == null) {
                val fragment = data.fragment
                if (fragment != null) {
                    val params = parseFragment(fragment)
                    connectionId = params["connectionId"]
                    error = params["error"]
                }
            }

            currentCallback = null

            when {
                error != null -> {
                    Log.e(TAG, "OAuth error received")
                    callback.onError(sanitizeOAuthError(error))
                }
                connectionId != null -> {
                    if (!isValidUuid(connectionId)) {
                        Log.e(TAG, "OAuth callback rejected: connectionId is not a valid UUID")
                        callback.onError("invalid_callback")
                    } else {
                        if (BuildConfig.DEBUG) Log.d(TAG, "OAuth success")
                        callback.onSuccess(connectionId)
                    }
                }
                else -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "OAuth cancelled or incomplete")
                    callback.onCancel()
                }
            }

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse OAuth callback", e)
            currentCallback = null
            callback.onError("Failed to parse OAuth response: ${e.message}")
            return true
        }
    }

    /**
     * Whether an OAuth flow is currently awaiting its redirect callback — true
     * from [startOAuthFlow] until [handleCallback] resolves (or the flow is
     * cancelled/cleared).
     */
    val hasPendingFlow: Boolean
        get() = currentCallback != null

    /**
     * Cancel a pending flow because the user returned to the app without an
     * OAuth redirect — i.e. backed out of the Custom Tab. Chrome Custom Tabs
     * emit no dismiss event, so the host activity infers this on resume. Fires
     * [OAuthCallback.onCancel] so the web SDK's `waitForConnectionId` resolves
     * instead of spinning forever. No-op if no flow is pending.
     */
    fun cancelPendingFlow() {
        val callback = currentCallback ?: return
        currentCallback = null
        if (BuildConfig.DEBUG) Log.d(TAG, "OAuth flow cancelled: returned without callback")
        callback.onCancel()
    }

    /**
     * Clear current state (e.g., on activity destroy).
     */
    fun clear() {
        currentCallback = null
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun parseFragment(fragment: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        fragment.split("&").forEach { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                params[parts[0]] = Uri.decode(parts[1])
            }
        }
        return params
    }
}
