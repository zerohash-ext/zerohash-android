package com.zerohash.sdk.automation

import android.app.Activity
import org.json.JSONObject

/**
 * A scraping/automation platform (e.g. Coinbase). Identified by [id] — the
 * `platform` value on the wire (`ZeroAuthRequest.platform`, e.g. `"cbase"`).
 *
 * Capabilities are opted into by implementing the flow interfaces below; the
 * bridge dispatches an operation only if the resolved platform implements the
 * matching flow (otherwise it replies `unsupported`, like iOS). This mirrors
 * iOS's `PlatformIdentity` + `AuthFlow`/`BalanceFlow`/`DepositFlow`/`WithdrawFlow`.
 *
 * To add a platform: implement [Platform] + the relevant flows, then register it
 * in [PlatformRegistry].
 */
internal interface Platform {
    val id: String

    /**
     * Host URLs (scheme + host, no path) whose cookies belong to this provider's
     * automation. Cleared at workflow teardown ([AutomationBridge.dispose]) so
     * provider session material doesn't outlive the workflow in the process-wide
     * Android CookieManager. See [AutomationCookies.clearForHosts].
     */
    val cookieHosts: List<String> get() = emptyList()
}

/** auth.status / auth.login. */
internal interface AuthFlow : Platform {
    suspend fun status(activity: Activity): AuthStatusResult
    suspend fun login(activity: Activity): AuthLoginResult
}

/** getBalance. Runs in a visible, overlay-covered WebView (iOS runVisibleWebView). */
internal interface BalanceFlow : Platform {
    suspend fun getBalance(activity: Activity, overlay: OverlayOptions, showOverlay: Boolean): List<AssetBalance>
}

/** getDepositAddress (Withdraw/Recovery SDKs). Returns the resolved address object. */
internal interface DepositFlow : Platform {
    suspend fun getDepositAddress(
        activity: Activity,
        payloadJson: String,
        overlay: OverlayOptions,
        showOverlay: Boolean,
    ): JSONObject
}

/**
 * withdraw.start / .continue / .cancel — the long-lived send automation.
 *
 * The platform drives its own JS against a live [AutomationSession]; the session
 * lifecycle (create/load/dismiss), the sessionId registry, and the visibility
 * hand-off are platform-agnostic and owned by the bridge (iOS WithdrawCoordinator).
 * Each method returns the `WithdrawState`-shaped object the JS produces (or
 * `{ cancelled }` for cancel), passed straight back to the web.
 */
internal interface WithdrawFlow : Platform {
    /** URL the withdraw automation session loads before driving the send flow. */
    val withdrawUrl: String
    suspend fun startWithdraw(session: AutomationSession, payloadJson: String): JSONObject
    suspend fun continueWithdraw(session: AutomationSession, payloadJson: String): JSONObject
    suspend fun cancelWithdraw(session: AutomationSession): JSONObject
}

/**
 * Process-wide platform registry, keyed by [Platform.id]. Pre-seeded with the
 * SDK's built-in platforms (currently just [Coinbase]); host apps can add their
 * own via [register]. The bridge resolves platforms by `req.platform` with no
 * router changes.
 *
 * Mirrors iOS `PlatformRegistry` (`.shared`, seeded with `Coinbase()`, plus
 * `register`). Synchronized for the same reason iOS guards with an NSLock —
 * registration may race with dispatch lookups.
 */
internal object PlatformRegistry {
    private val platforms = mutableMapOf<String, Platform>(Coinbase.id to Coinbase)

    @Synchronized
    fun register(platform: Platform) {
        platforms[platform.id] = platform
    }

    @Synchronized
    operator fun get(id: String): Platform? = platforms[id]

    /** Snapshot of the registered platforms (order not guaranteed). Used by the
     *  bridge teardown to enumerate provider cookie hosts for clearing. */
    @Synchronized
    fun all(): List<Platform> = platforms.values.toList()
}
