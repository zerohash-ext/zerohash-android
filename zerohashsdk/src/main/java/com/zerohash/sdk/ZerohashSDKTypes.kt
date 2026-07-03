package com.zerohash.sdk

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Identifies the Zerohash app type rendered by the SDK.
 */
enum class ZerohashApp {
    FUND
}

/**
 * Theme configuration for the SDK UI.
 *
 * The embedded zerohash mobile web app ([apps/mobile]) expects the web theme
 * vocabulary `'auto' | 'light' | 'dark'`, so [SYSTEM] maps to `"auto"` (the web
 * app reads the OS preference for `auto`).
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
    PRODUCTION;

    /**
     * Native vocabulary forwarded to the web app via the `jwt` message
     * (`{ token, env }`). The mobile web app maps `production -> prod` and
     * `sandbox -> cert` for the inner Fund iframe.
     */
    fun toWebValue(): String = when (this) {
        SANDBOX -> "sandbox"
        PRODUCTION -> "production"
    }

    /**
     * Host of the embedded zerohash mobile web app for this environment.
     *
     * Single source of truth shared by the session base-URL builders and the
     * WebView's trusted-origin check. This is the host the WebView loads —
     * the zerohash-branded `apps/mobile` deployment (the same build artifact is
     * also served from the legacy `sdk.connect.xyz` / `sdk.sandbox.connect.xyz`
     * hosts). Its `#fund` route internally embeds the Fund web component + iframe.
     *
     * NOTE: on mobile, external-source OAuth (integrations-flow) returns through
     * the native bridge (Custom Tabs -> connectsdk-oauth://callback), NOT the
     * web popup path in `use-oauth-integration/utils.ts` — so that web origin
     * allowlist does not gate this host. The real OAuth dependency is that the
     * connection-service redirect URI (`connectsdk-oauth://callback`) is
     * configured for the Fund SDK (backend), independent of the web host.
     */
    internal val webHost: String
        get() = when (this) {
            SANDBOX -> "sdk-cdn.cert.zerohash.com"
            PRODUCTION -> "sdk-cdn.zerohash.com"
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
                else -> UnknownError(message)
            }
        }
    }
}

/**
 * Base callback interface for all Zerohash apps.
 */
interface AppCallbacks {
    /**
     * Called when the session is closed by user or programmatically.
     */
    fun onClose()

    /**
     * Called when an error occurs during the session.
     */
    fun onError(error: ZerohashError)

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
    fun handleDeposit(data: JSONObject?) {}
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
