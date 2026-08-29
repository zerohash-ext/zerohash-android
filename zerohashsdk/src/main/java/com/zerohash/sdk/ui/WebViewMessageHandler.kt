package com.zerohash.sdk.ui

import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import com.zerohash.sdk.BuildConfig
import com.zerohash.sdk.CallbackHandler

/**
 * JavaScript↔Kotlin communication bridge.
 *
 * One reception path: [WebViewActivity] registers this handler via
 * `WebViewCompat.addWebMessageListener` with [targetOrigin] as the only
 * allowed-origin rule. Origin filtering happens **per-frame** inside the WebView
 * runtime, so a cross-origin frame cannot reach [handleVerifiedMessage] even if
 * it tries to call `NativeAndroid.postMessage`.
 *
 * There is deliberately no `@JavascriptInterface` fallback for WebViews without
 * `WEB_MESSAGE_LISTENER`. `addJavascriptInterface` injects into *every* frame,
 * and the top-frame URL is the only thing the SDK could check from there — which
 * says nothing about the frame that actually called. Since this bridge drives
 * credential automation and the withdraw flow, [WebViewActivity] refuses the
 * session on such devices instead. Matches zerohash-ios, which validates
 * `frameInfo.securityOrigin` per message.
 *
 * The [allowedHost] drives both the inbound origin check and the outbound
 * `window.postMessage` target — it is set per-session from [Environment.webHost]
 * so sandbox sessions talk to `sdk-cdn.cert.zerohash.com` and production
 * sessions talk to `sdk-cdn.zerohash.com`.
 *
 * The bridge contract matches the zerohash mobile web app:
 * inbound (web→native) `page-ready`, `content-ready`, `navigate`, `close`,
 * `error`, `event`, `deposit`, `deposit-status`, `crypto-withdrawal`,
 * `fund-withdrawal`, `transaction-failed`;
 * outbound (native→web) `jwt`, `config`.
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
         * Fallback constant kept for tests that don't supply an explicit
         * environment. Prefer [WebViewMessageHandler.targetOrigin] at runtime.
         */
        const val TARGET_ORIGIN = "https://sdk-cdn.zerohash.com"
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
                "deposit-status" -> handleDepositStatus(data)
                "crypto-withdrawal" -> handleCryptoWithdrawal(data)
                "fund-withdrawal" -> handleFundWithdrawal(data)
                "transaction-failed" -> handleTransactionFailed(data)
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
            // A failed crypto withdrawal deliberately emits `transaction-failed`
            // AND `error`, the latter kept only for hosts written before `onFailed`
            // existed. Halting rendering on that duplicate would freeze the
            // withdrawal-failed screen the user is looking at, so once the flow has
            // reported a terminal failure of its own, treat a following error as the
            // compatibility echo rather than a new fatal one.
            if (!terminalTransactionFailureSeen) {
                delegate?.onTerminalError()
            }
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

    /**
     * Status of a deposit funded from an external source, posted as
     * `deposit-status`. Its own message type because [handleDeposit] already means
     * the deposit *completed* — a status there would report that the money arrived
     * while the deposit is still verifying, or has failed. Non-terminal: it can
     * arrive more than once per deposit.
     */
    private fun handleDepositStatus(data: JSONObject?) {
        webView.post {
            callbackHandler.handleDepositStatus(data)
        }
    }

    private fun handleCryptoWithdrawal(data: JSONObject?) {
        webView.post {
            callbackHandler.handleCryptoWithdrawal(data)
        }
    }

    private fun handleFundWithdrawal(data: JSONObject?) {
        webView.post {
            callbackHandler.handleFundWithdrawal(data)
        }
    }

    /**
     * Terminal *failed* transaction, posted as `transaction-failed`. Deliberately
     * not routed through [handleError]: this is the flow's own outcome and carries
     * the transaction's details, so it reaches `onFailed` rather than `onError`
     * (and must not trip `onTerminalError`).
     */
    private fun handleTransactionFailed(data: JSONObject?) {
        terminalTransactionFailureSeen = true
        webView.post {
            callbackHandler.handleTransactionFailed(data)
        }
    }

    /**
     * Set once the flow has reported a terminal failure of its own. Suppresses the
     * rendering halt for the compatibility `error` that crypto-withdrawals sends
     * straight after it — see [handleError].
     */
    private var terminalTransactionFailureSeen = false

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
