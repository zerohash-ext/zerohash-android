package com.zerohash.sdk.ui

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import com.zerohash.sdk.BuildConfig
import com.zerohash.sdk.CallbackHandler
import java.net.URI

/**
 * JavaScript↔Kotlin communication bridge.
 *
 * Two reception paths, both routed through [dispatchMessage]:
 *
 * 1. **Preferred — WebMessageListener (API 24+ with up-to-date WebView).**
 *    [WebViewActivity] registers this handler via
 *    `WebViewCompat.addWebMessageListener` with [targetOrigin] as the only
 *    allowed-origin rule. Origin filtering happens **per-frame** inside the
 *    WebView runtime, so a cross-origin frame cannot reach the handler even if
 *    it tries to call `NativeAndroid.postMessage`. Messages arriving on this
 *    path are flagged origin-verified.
 *
 * 2. **Fallback — `@JavascriptInterface` (older WebView builds).** The
 *    [postMessage] method below is exposed on the WebView via
 *    `addJavascriptInterface`. In this mode the SDK can only validate the
 *    **top-frame** URL (cached on the main thread via [onPageLoaded]).
 *
 * The [allowedHost] drives both the inbound origin check and the outbound
 * `window.postMessage` target — it is set per-session from [Environment.webHost]
 * so sandbox sessions talk to `sdk-cdn.cert.zerohash.com` and production
 * sessions talk to `sdk-cdn.zerohash.com`.
 *
 * The bridge contract matches the zerohash mobile web app (`apps/mobile`):
 * inbound (web→native) `page-ready`, `navigate`, `close`, `error`, `event`,
 * `deposit`; outbound (native→web) `jwt`, `config`.
 */
internal class WebViewMessageHandler(
    private val webView: WebView,
    private val jwt: String,
    private val environment: String,
    private val theme: String,
    private val callbackHandler: CallbackHandler,
    /** Trusted host for this session — derived from [Environment.webHost]. */
    private val allowedHost: String = "sdk-cdn.zerohash.com"
) {
    companion object {
        private const val TAG = "WebViewMessageHandler"
        const val INTERFACE_NAME = "NativeAndroid"

        /** Wire role marking an inbound scraping-bridge request (`ZeroAuthRequest`). */
        private const val ROLE_HOST = "zeroauth-host"

        /**
         * Fallback constant kept for backward-compat with ProGuard rules and
         * tests that don't supply an explicit environment. Prefer
         * [WebViewMessageHandler.targetOrigin] for runtime use.
         */
        const val TARGET_ORIGIN = "https://sdk-cdn.zerohash.com"

        /**
         * Strict origin check used by the legacy [postMessage]
         * `@JavascriptInterface` path.
         *
         * Requires the page URL's scheme to be `https` and its host to match
         * [allowedHost] exactly (case-insensitive).
         */
        internal fun isAllowedOrigin(url: String?, allowedHost: String): Boolean {
            if (url.isNullOrBlank()) return false
            return try {
                val uri = URI(url)
                val scheme = uri.scheme?.lowercase()
                val host = uri.host?.lowercase()
                scheme == "https" && host == allowedHost.lowercase()
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * The exact postMessage target origin for this session.
     * Used both for outbound messages and as the WebMessageListener origin rule.
     */
    val targetOrigin: String get() = "https://$allowedHost"

    interface Delegate {
        fun onContentReady()
        fun onNavigate(url: String, mobileTarget: String?)
        fun onSessionClose()

        /**
         * A scraping-bridge request (`role:"zeroauth-host"`) arrived on this
         * channel. Routed to the activity, which owns the Activity + coroutine
         * scope the native platform flows need. [request] is the parsed
         * `ZeroAuthRequest` envelope.
         */
        fun onAutomationRequest(request: JSONObject)

        /**
         * The web app surfaced a terminal `error` (`{errorCode, reason}`). The
         * error screen it now shows is static, but its animation keeps the
         * WebView repainting — pegging the (software-rendered) GPU on the
         * emulator and wasting battery on device. The activity halts rendering
         * in response. Distinct from [onSessionClose], which tears the whole
         * session down.
         */
        fun onTerminalError()
    }

    var delegate: Delegate? = null

    /**
     * Cached URL of the last fully-loaded top-frame page.
     */
    @Volatile
    private var currentPageUrl: String? = null

    internal fun onPageLoaded(url: String?) {
        currentPageUrl = url
    }

    /**
     * Legacy `@JavascriptInterface` entry point. Reached only when the device's
     * WebView does not support per-frame origin filtering via
     * `WebViewCompat.addWebMessageListener`. Runs on a background thread.
     */
    @JavascriptInterface
    fun postMessage(message: String) {
        if (!isAllowedOrigin(currentPageUrl, allowedHost)) {
            Log.w(TAG, "Message rejected: origin not allowed")
            return
        }
        dispatchMessage(message)
    }

    /**
     * Entry point for messages whose origin has already been verified by the
     * WebView framework (WebMessageListener with allowedOriginRules).
     */
    internal fun handleVerifiedMessage(message: String) {
        dispatchMessage(message)
    }

    private fun dispatchMessage(message: String) {
        try {
            val json = JSONObject(message)

            // Scraping-bridge requests share this channel but use a different
            // protocol: they carry role:"zeroauth-host" and an `operation`, not a
            // `type`. Route them to the bridge (matches iOS NativeIOSMessageHandler
            // dispatching by role).
            if (json.optString("role") == ROLE_HOST) {
                webView.post { delegate?.onAutomationRequest(json) }
                return
            }

            val type = json.optString("type")
            val data = json.optJSONObject("data")

            if (BuildConfig.DEBUG) Log.d(TAG, "Received message type: $type")

            when (type) {
                "page-ready" -> handlePageReady()
                "content-ready" -> handleContentReady()
                "navigate" -> handleNavigate(data)
                "close" -> handleClose()
                "error" -> handleError(data)
                "event" -> handleEvent(data)
                "deposit" -> handleDeposit(data)
                else -> Log.w(TAG, "Unknown message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
        }
    }

    private fun handlePageReady() {
        sendJWT()
        sendConfig()
    }

    private fun handleContentReady() {
        webView.post {
            delegate?.onContentReady()
        }
    }

    private fun handleNavigate(data: JSONObject?) {
        val url = data?.optString("url") ?: return
        val mobileTarget = data.optString("mobileTarget")

        webView.post {
            delegate?.onNavigate(url, mobileTarget)
        }
    }

    private fun handleClose() {
        webView.post {
            callbackHandler.handleClose()
            delegate?.onSessionClose()
        }
    }

    /**
     * Fund error payloads carry `{ errorCode, reason }`; older/other flows use
     * `{ code, message }`. Read both so the typed error mapping works either way.
     */
    private fun handleError(data: JSONObject?) {
        val code = data?.optString("errorCode")?.takeIf { it.isNotBlank() }
            ?: data?.optString("code")?.takeIf { it.isNotBlank() }
        val message = data?.optString("reason")?.takeIf { it.isNotBlank() }
            ?: data?.optString("message")?.takeIf { it.isNotBlank() }
            ?: "Unknown error"

        webView.post {
            callbackHandler.handleError(code, message, data)
            delegate?.onTerminalError()
        }
    }

    private fun handleEvent(data: JSONObject?) {
        // The mobile bridge flattens events and carries the original type in
        // `eventType` (the `data` object spreads `...event.data`).
        val eventType = data?.optString("eventType")?.takeIf { it.isNotBlank() }
            ?: data?.optString("type")?.takeIf { it.isNotBlank() }
            ?: "unknown"

        webView.post {
            callbackHandler.handleEvent(eventType, data)
        }
    }

    private fun handleDeposit(data: JSONObject?) {
        webView.post {
            callbackHandler.handleDeposit(data)
        }
    }

    private fun sendJWT() {
        val jwtMessage = JSONObject().apply {
            put("token", jwt)
            put("env", environment)
        }
        sendMessageToWeb("jwt", jwtMessage)
    }

    private fun sendConfig() {
        val configMessage = JSONObject().apply {
            put("theme", theme)
        }
        sendMessageToWeb("config", configMessage)
    }

    fun sendOAuthSuccess(connectionId: String?) {
        val oauthMessage = JSONObject().apply {
            put("success", true)
            connectionId?.let { put("connectionId", it) }
        }
        // Web contract: the OAuth flow's `waitForConnectionId` listens for
        // `oauth-success` / `oauth-error` (matches connect-ios). The host relays
        // this into the iframe, where `data.connectionId` resolves the flow.
        sendMessageToWeb("oauth-success", oauthMessage)
    }

    fun sendOAuthError(error: String) {
        val oauthMessage = JSONObject().apply {
            put("success", false)
            put("error", error)
        }
        sendMessageToWeb("oauth-error", oauthMessage)
    }

    /**
     * Outbound message — uses exact [targetOrigin] (never wildcard).
     */
    private fun sendMessageToWeb(type: String, data: JSONObject) {
        val message = JSONObject().apply {
            put("type", type)
            put("data", data)
        }

        val script = "window.postMessage(${message}, '$targetOrigin');"

        webView.post {
            webView.evaluateJavascript(script) { result ->
                if (BuildConfig.DEBUG) Log.d(TAG, "Sent message type: $type, result: $result")
            }
        }
    }
}
