package com.zerohash.sdk

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Identifies the Zerohash app type rendered by the SDK.
 */
enum class ZerohashApp {
    FUND,
    CRYPTO_WITHDRAWALS
}

/**
 * Theme configuration for the SDK UI.
 *
 * The embedded zerohash mobile web app expects the web theme vocabulary
 * `'auto' | 'light' | 'dark'`, so [SYSTEM] maps to `"auto"` (the web app reads
 * the OS preference for `auto`).
 */
enum class Theme {
    LIGHT,
    DARK,
    SYSTEM;

    fun toWebValue(): String = when (this) {
        LIGHT -> "light"
        DARK -> "dark"
        SYSTEM -> "auto"
    }
}

/**
 * Environment configuration (sandbox or production).
 */
enum class Environment {
    SANDBOX,
    PRODUCTION,

    /**
     * INTERNAL TESTING ONLY — zerohash's pre-release gating environment, used by
     * the instrumentation e2e suite (AUTH-3838). Not for partner use.
     */
    GATING;

    /**
     * Native vocabulary forwarded to the web app via the `jwt` message
     * (`{ token, env }`). GATING sends `sandbox` — the web vocabulary only has
     * production/sandbox; the gating deployment's runtime env-config picks the hosts.
     */
    fun toWebValue(): String = when (this) {
        SANDBOX, GATING -> "sandbox"
        PRODUCTION -> "production"
    }

    /**
     * Host of the embedded zerohash mobile web app for this environment.
     *
     * Single source of truth shared by the session base-URL builders and the
     * WebView's trusted-origin check. This is the host the WebView loads —
     * the zerohash-branded mobile web app (the same build artifact is also
     * served from the legacy `sdk.connect.xyz` / `sdk.sandbox.connect.xyz`
     * hosts). Its `#fund` route internally embeds the Fund web component + iframe.
     *
     * NOTE: on mobile, external-source OAuth returns through the native bridge
     * (Custom Tabs -> connectsdk-oauth://callback), NOT the web popup path — so
     * that web origin allowlist does not gate this host. The real OAuth dependency is that the
     * connection-service redirect URI (`connectsdk-oauth://callback`) is
     * configured for the Fund SDK (backend), independent of the web host.
     */
    internal val webHost: String
        get() = when (this) {
            SANDBOX -> "sdk-cdn.cert.zerohash.com"
            PRODUCTION -> "sdk-cdn.zerohash.com"
            GATING -> "connect-sdk.gating.0hash.com"
        }
}

/**
 * Represents an active Zerohash session with lifecycle management.
 *
 * [_isActive] uses [AtomicBoolean] and [close] uses compareAndSet so that
 * concurrent calls to [close] are safe — the cleanup branch executes exactly
 * once regardless of how many threads call it simultaneously.
 */
class ZerohashSession internal constructor(
    val id: String = UUID.randomUUID().toString(),
    val app: ZerohashApp
) {
    private val _isActive = AtomicBoolean(true)
    private var onCloseCallback: (() -> Unit)? = null

    /**
     * Check if the session is currently active.
     */
    fun isActive(): Boolean = _isActive.get()

    /**
     * Close the session and trigger cleanup.
     *
     * Thread-safe: the callback is invoked at most once even if [close] is
     * called concurrently from multiple threads.
     */
    fun close() {
        if (_isActive.compareAndSet(true, false)) {
            onCloseCallback?.invoke()
        }
    }

    internal fun setOnCloseCallback(callback: () -> Unit) {
        onCloseCallback = callback
    }
}

/**
 * Comprehensive error types for the Zerohash SDK.
 *
 * The web codes mirror the Fund SDK `ErrorPayload.errorCode` values plus the
 * SDK-side errors raised natively (config/WebView/OAuth).
 */
sealed class ZerohashError : Exception() {
    data class NetworkError(override val message: String) : ZerohashError()
    data class AuthenticationError(override val message: String) : ZerohashError()
    data class ValidationError(override val message: String) : ZerohashError()
    data class NotFoundError(override val message: String) : ZerohashError()
    data class ServerError(override val message: String) : ZerohashError()
    data class ClientError(override val message: String) : ZerohashError()
    data class ConfigurationError(override val message: String) : ZerohashError()
    data class WebViewError(override val message: String) : ZerohashError()
    data class OAuthError(override val message: String) : ZerohashError()
    data class UnknownError(override val message: String) : ZerohashError()

    companion object {
        /**
         * Convert Fund web error codes ([ErrorPayload.errorCode]) to typed errors.
         *
         * `webview_unsupported` is the exception: it originates natively, not from
         * the web app, when the device's WebView cannot enforce the bridge's
         * per-frame origin check. Kept here so hosts get one typed error surface.
         */
        fun fromWebError(code: String?, message: String): ZerohashError {
            return when (code) {
                "network_error" -> NetworkError(message)
                "auth_error" -> AuthenticationError(message)
                "validation_error" -> ValidationError(message)
                "not_found_error" -> NotFoundError(message)
                "server_error" -> ServerError(message)
                "client_error" -> ClientError(message)
                "config_error" -> ConfigurationError(message)
                "oauth_error" -> OAuthError(message)
                "webview_unsupported" -> WebViewError(message)
                else -> UnknownError(message)
            }
        }
    }
}

/**
 * Base callback interface for all Zerohash apps.
 *
 * Names match the zerohash web SDK's callback contract so a partner integrating
 * on web and native writes the same handlers.
 */
interface AppCallbacks {
    /**
     * Called when the session is closed by user or programmatically.
     */
    fun onClose()

    /**
     * Called when an SDK or request error occurs (network, auth, validation,
     * config). A flow's own terminal failure is *not* an error — it arrives on
     * the flow's `onFailed` with the transaction's details instead.
     */
    fun onError(error: ZerohashError)

    /**
     * Called once the flow has finished loading and is ready — the point at
     * which the loading indicator gives way to the flow's first screen.
     *
     * Default no-op so existing hosts need not implement it.
     */
    fun onLoaded() {}

    /**
     * Called for generic events from the web application.
     */
    fun onEvent(event: GenericEvent)
}

/**
 * Internal protocol for handling callbacks with raw data.
 */
internal interface CallbackHandler {
    fun handleClose()
    fun handleError(code: String?, message: String, data: JSONObject?)
    fun handleEvent(type: String, data: JSONObject?)
    fun handleLoaded() {}
    fun handleDeposit(data: JSONObject?) {}

    /**
     * Status of a deposit funded from an external source, posted as
     * `deposit-status`. Separate from [handleDeposit], which means the deposit
     * completed. Default no-op so flows without an external-source path (crypto
     * withdrawals) need not implement it.
     */
    fun handleDepositStatus(data: JSONObject?) {}

    /**
     * Crypto Withdrawals completion, posted by the mobile web app as a
     * `crypto-withdrawal` message. Default no-op so deposit-only flows (Fund)
     * need not implement it.
     */
    fun handleCryptoWithdrawal(data: JSONObject?) {}

    /**
     * Terminal *failed* transaction, posted as `transaction-failed`. Shared by
     * both flows — a session only ever runs one, and the payload is that flow's
     * failure data.
     */
    fun handleTransactionFailed(data: JSONObject?) {}
}

/**
 * Generic event wrapper with convenience accessors.
 */
data class GenericEvent(
    val type: String,
    val data: JSONObject?
) {
    fun getString(key: String): String? = data?.optString(key)
    fun getInt(key: String): Int? = data?.optInt(key)
    fun getBool(key: String): Boolean? = data?.optBoolean(key)
    fun getObject(key: String): JSONObject? = data?.optJSONObject(key)
    fun getDouble(key: String): Double? = data?.optDouble(key)
}
