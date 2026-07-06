package com.zerohash.sdk.automation

import android.app.Activity
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.zerohash.sdk.BuildConfig
import java.util.UUID

/**
 * Routes scraping-bridge requests from the SDK's UI WebView to the native
 * platform flows, and posts replies back over the **exact same wire contract**
 * the iOS bridge and the browser-extension transport use. The web side
 * (`@zerohash/hooks` `use-scraping-client`) is platform-agnostic and posts to
 * both `NativeIOS` and `NativeAndroid`, so no web change is needed.
 *
 * Wire contract (canonical: connect-ios `Envelope.swift` / `contract.ts`):
 * - Request (web → native): `NativeAndroid.postMessage(json)` where json is a
 *   `ZeroAuthRequest` `{ id, role:"zeroauth-host", platform, operation,
 *   payload?, options?, sessionId? }`.
 * - Reply (native → web): `window.postMessage({ type, data })` with
 *   type `"scraping-webview-response"` (data = `ZeroAuthResponse`
 *   `{ id, role:"zeroauth-native", success, data?, error?, sessionId?,
 *   retryable }`) or `"scraping-webview-event"` (data = `BridgeEvent`).
 *
 * Port of iOS `AutomationWebViewMessageRouter` (+ `PostMessageReplySink`).
 *
 * Platforms are resolved from [PlatformRegistry] by `req.platform` and dispatched
 * via their flow interfaces — unknown platform → `not registered`, missing
 * capability → `unsupported` (the same `success:false` shape iOS replies with).
 */
internal class AutomationBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val targetOrigin: String,
) {
    /** Own scope (no lifecycle-ktx dep here); cancelled via [dispose]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Set in [dispose]; guards a reply that was already queued via webView.post
     *  from running evaluateJavascript on a destroyed WebView (teardown race).
     *  Main-thread only, so a plain flag is safe. */
    private var disposed = false

    /**
     * One open withdraw session + whether it's currently stepped aside (host
     * visible). Drives whether the next continue/cancel `resume()`s the page or
     * merely re-covers the overlay — mirrors iOS WithdrawCoordinator.steppedAside.
     */
    private class WithdrawSession(val session: AutomationSession) {
        var steppedAside = false
    }

    /**
     * Open withdraw sessions: sessionId → live automation WebView. Reused across
     * withdraw.continue/cancel. All access is on the Main dispatcher (handle()
     * launches there) so a plain map is safe.
     */
    private val sessions = HashMap<String, WithdrawSession>()

    /**
     * True while a withdraw.start is between its single-session guard and storing
     * the session. `sessions` is only populated AFTER the `session.load()` +
     * `startWithdraw` suspension, so without this an overlapping start (a fast
     * double-tap) would pass the empty-map guard during that window and open a
     * second full-screen session. Claimed synchronously on the Main dispatcher
     * right after the guard, so check-and-claim is atomic. Mirrors iOS
     * WithdrawCoordinator.starting.
     */
    private var withdrawStarting = false

    /** An operation's reply payload plus the optional session id to echo back. */
    private class OpResult(val data: Any?, val sessionId: String? = null)

    /** Handle one inbound `role:"zeroauth-host"` request (already JSON-parsed). */
    fun handle(request: JSONObject) {
        val id = request.optString("id")
        val platform = request.optString("platform")
        val operation = request.optString("operation")
        Log.d(TAG, "inbound id=$id platform=$platform op=$operation")

        scope.launch {
            try {
                val result = dispatch(platform, operation, request)
                sendResponse(id, success = true, data = result.data, error = null, sessionId = result.sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "operation failed id=$id op=$operation", e)
                val msg = e.message ?: "operation failed"
                sendResponse(id, success = false, data = null, error = msg, retryable = isRetryable(msg))
            }
        }
    }

    /**
     * Coalesce concurrent duplicate idempotent reads (auth.status / getBalance):
     * a second request for the same platform+op while one is in flight shares the
     * same result Task and replies with the same payload (tagged with its own id).
     * Prevents two full-screen overlays / two offscreen runs from a double-fire.
     * Mirrors iOS `AutomationWebViewMessageRouter.dispatchCoalesced`. All map
     * access is on the Main dispatcher (handle() launches there).
     */
    private val inFlight = HashMap<String, Deferred<OpResult>>()

    private suspend fun dispatch(platformId: String, operation: String, request: JSONObject): OpResult {
        if (!isCoalescable(operation)) return runOperation(platformId, operation, request)
        val key = "$platformId/$operation"
        inFlight[key]?.let { return it.await() }
        val deferred = scope.async {
            try {
                runOperation(platformId, operation, request)
            } finally {
                inFlight.remove(key)
            }
        }
        inFlight[key] = deferred
        return deferred.await()
    }

    private suspend fun runOperation(platformId: String, operation: String, request: JSONObject): OpResult {
        // core.ping is a transport health check — no platform needed.
        if (operation == "core.ping") {
            return OpResult(JSONObject().put("ok", true).put("version", VERSION))
        }

        val platform = PlatformRegistry[platformId]
            ?: throw PlatformException("platform '$platformId' is not registered")

        // Per-call overlay customization rides alongside `payload` (not inside it),
        // mirroring the wire `ZeroAuthRequestOptions`. `initialOverlay` defaults to
        // true (the extension default); other keys (presentation, …) are ignored.
        val options = request.optJSONObject("options")
        val overlay = OverlayOptions.resolve(options?.optJSONObject("overlayOptions"))
        val showOverlay = options?.optBooleanOrNull("initialOverlay") ?: true

        return when (operation) {
            "auth.status" -> {
                val p = platform.requireFlow<AuthFlow>(operation)
                OpResult(JSONObject().put("loggedIn", p.status(activity).loggedIn))
            }

            "auth.login" -> {
                val p = platform.requireFlow<AuthFlow>(operation)
                val r = p.login(activity)
                OpResult(JSONObject().put("loggedIn", r.loggedIn).put("outcome", r.outcome))
            }

            "getBalance" -> {
                val p = platform.requireFlow<BalanceFlow>(operation)
                OpResult(balancesToJson(p.getBalance(activity, overlay, showOverlay)))
            }

            "getDepositAddress" -> {
                val p = platform.requireFlow<DepositFlow>(operation)
                val payload = (request.optJSONObject("payload") ?: JSONObject()).toString()
                OpResult(p.getDepositAddress(activity, payload, overlay, showOverlay))
            }

            "withdraw.start" -> {
                val p = platform.requireFlow<WithdrawFlow>(operation)
                withdrawStart(p, (request.optJSONObject("payload") ?: JSONObject()).toString(), overlay, showOverlay)
            }

            "withdraw.continue" -> {
                val p = platform.requireFlow<WithdrawFlow>(operation)
                withdrawContinue(p, request.optString("sessionId").ifEmpty { null }, (request.optJSONObject("payload") ?: JSONObject()).toString())
            }

            "withdraw.cancel" -> {
                val p = platform.requireFlow<WithdrawFlow>(operation)
                withdrawCancel(p, request.optString("sessionId").ifEmpty { null })
            }

            // Matches iOS dispatch `default` — unimplemented ops reply success:false.
            else -> throw PlatformException("operation '$operation' not supported on platform '$platformId'")
        }
    }

    /** Cast to the flow this op needs, or reply `unsupported` (iOS `as? Flow else`). */
    private inline fun <reified T> Platform.requireFlow(operation: String): T =
        this as? T ?: throw PlatformException("operation '$operation' not supported on platform '$id'")

    // ── Withdraw (long-lived automation session) ────────────────────────────
    //
    // Port of iOS WithdrawCoordinator: this owns the session lifecycle, the
    // sessionId registry, and the visibility hand-off — all platform-agnostic.
    // The platform ([WithdrawFlow]) drives its own JS against the live session.

    private suspend fun withdrawStart(
        platform: WithdrawFlow,
        payloadJson: String,
        overlay: OverlayOptions,
        showOverlay: Boolean,
    ): OpResult {
        // Exactly one in-flight withdraw at a time (iOS WithdrawCoordinator). The
        // guard + claim are synchronous before the first suspension, so a double
        // withdraw.start can't stack two full-screen sessions.
        if (sessions.isNotEmpty() || withdrawStarting) {
            throw PlatformException("withdraw already in progress")
        }
        withdrawStarting = true
        try {
            val session = AutomationSession(activity, platform.withdrawUrl, overlay, showOverlay)
            session.load() // covered by the branded loading state while the send automates
            val state = try {
                platform.startWithdraw(session, payloadJson)
            } catch (e: Exception) {
                session.dismiss()
                throw e
            }

            if (endsSession(state)) {
                session.dismiss()
                return OpResult(state)
            }
            val sessionId = UUID.randomUUID().toString()
            val ws = WithdrawSession(session)
            sessions[sessionId] = ws
            handOff(ws, state) // step aside so connect-auth can show the OTP screen
            Log.d(TAG, "withdraw.start opened sessionId=$sessionId state=${state.optString("state")}")
            return OpResult(state, sessionId)
        } finally {
            withdrawStarting = false
        }
    }

    private suspend fun withdrawContinue(platform: WithdrawFlow, sessionId: String?, payloadJson: String): OpResult {
        val ws = sessions[sessionId]
            ?: throw PlatformException("no active withdraw session")
        // Bring the page back on screen (overlay up) before driving it.
        resumeScraping(ws)
        val state = try {
            platform.continueWithdraw(ws.session, payloadJson)
        } catch (e: Exception) {
            ws.session.dismiss()
            sessions.remove(sessionId)
            throw e
        }

        if (endsSession(state)) {
            ws.session.dismiss()
            sessions.remove(sessionId)
        } else {
            handOff(ws, state)
        }
        return OpResult(state, sessionId)
    }

    private suspend fun withdrawCancel(platform: WithdrawFlow, sessionId: String?): OpResult {
        val ws = sessions.remove(sessionId)
            ?: throw PlatformException("no active withdraw session")
        val result = try {
            // Bring the page back so the risk-step "Cancel transfer" button is
            // visible/queryable, then cancel and tear down.
            resumeScraping(ws)
            platform.cancelWithdraw(ws.session)
        } finally {
            ws.session.dismiss()
        }
        return OpResult(result, sessionId)
    }

    /** iOS handOff: the session is now parked waiting on the user, so suspend its
     *  wall-clock ceiling (the wait is unbounded). Reveal the platform page only
     *  when the user must act there (id-verification); otherwise step aside so the
     *  host (connect-auth) shows its OTP/processing screen. */
    private fun handOff(ws: WithdrawSession, state: JSONObject) {
        ws.session.pauseTimeout()
        val surfacesPlatform = state.optString("state") == "awaiting-user-action" &&
            state.optString("kind") == "id-verification"
        if (surfacesPlatform) {
            ws.session.revealOverlay(true)
            ws.steppedAside = false
        } else {
            ws.session.stepAside()
            ws.steppedAside = true
        }
    }

    /** iOS resumeScraping: before driving the next leg, give it a fresh wall-clock
     *  budget and get the page back, covered by the overlay. If stepped aside,
     *  re-present it; otherwise (revealed for id-verification) re-cover it. */
    private fun resumeScraping(ws: WithdrawSession) {
        ws.session.restartTimeout()
        if (ws.steppedAside) ws.session.resume() else ws.session.revealOverlay(false)
    }

    private fun sendResponse(
        id: String,
        success: Boolean,
        data: Any?,
        error: String?,
        retryable: Boolean = false,
        sessionId: String? = null,
    ) {
        val response = JSONObject()
            .put("id", id)
            .put("role", ROLE_NATIVE)
            .put("success", success)
            .put("data", data ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
            .put("sessionId", sessionId ?: JSONObject.NULL)
            .put("retryable", retryable)

        val envelope = JSONObject()
            .put("type", RESPONSE_TYPE)
            .put("data", response)

        // The web correlates by data.id and accepts the reply because it arrives
        // on its own origin (we post with the exact targetOrigin == page origin).
        val script = "window.postMessage($envelope, '$targetOrigin');"
        webView.post {
            // The activity may have torn down the WebView between queuing this
            // runnable and it running — calling evaluateJavascript on a destroyed
            // WebView throws/logs noise.
            if (disposed) return@post
            webView.evaluateJavascript(script) { r ->
                Log.d(TAG, "reply id=$id success=$success result=$r")
            }
        }
    }

    fun dispose() {
        disposed = true
        sessions.values.forEach { it.session.dismiss() }
        sessions.clear()
        scope.cancel()
    }

    companion object {
        private const val TAG = "ZHAutomation"
        private const val ROLE_NATIVE = "zeroauth-native"
        private const val RESPONSE_TYPE = "scraping-webview-response"
        // Platform-prefixed SDK version, mirroring iOS "ios-<version>".
        private val VERSION = "android-${BuildConfig.SDK_VERSION}"
    }
}

// ── Pure, stateless bridge decision logic ───────────────────────────────────
//
// These are file-level `internal` helpers rather than private members of
// [AutomationBridge] so they can be unit-tested on the JVM without constructing a
// bridge (which needs an Activity/WebView + the Main dispatcher). They hold no
// bridge state; the class calls them unqualified. See [AutomationBridgeLogicTest].

/** Mirrors iOS `WithdrawState.endsSession`: submitted is terminal; rejected is
 *  terminal UNLESS it's an OTP rejection (the web lets the user retry); the
 *  awaiting/processing pauses are non-terminal. An UNRECOGNIZED state ends the
 *  session — iOS rejects an undecodable discriminant outright, so we dismiss
 *  rather than strand a live session waiting on the 300s ceiling. */
internal fun endsSession(state: JSONObject): Boolean = when (state.optString("state")) {
    "submitted" -> true
    "rejected" -> state.optString("reason") != "otp_rejected"
    "awaiting-input", "awaiting-user-action", "processing" -> false
    else -> true
}

/** Mirrors iOS exactly: BALANCES_INDETERMINATE is matched as a prefix, and
 *  CHALLENGE_UNSOLVED as the whole message — nothing else is retryable. */
internal fun isRetryable(msg: String): Boolean =
    msg.startsWith("BALANCES_INDETERMINATE") || msg == "CHALLENGE_UNSOLVED"

/** Only the idempotent reads coalesce; every mutating/one-shot op runs on its
 *  own. Mirrors iOS `AutomationWebViewMessageRouter.dispatchCoalesced`. */
internal fun isCoalescable(operation: String): Boolean =
    operation == "auth.status" || operation == "getBalance"

/** Serialize balances to the wire `JSONArray`, nulls → JSON null (iOS parity). */
internal fun balancesToJson(balances: List<AssetBalance>): JSONArray {
    val arr = JSONArray()
    for (b in balances) {
        arr.put(
            JSONObject()
                .put("key", b.key)
                .put("label", b.label)
                .put("amount", b.amount)
                .put("notional", b.notional)
                .put("currency", b.currency ?: JSONObject.NULL)
                .put("totalStakedPercent", b.totalStakedPercent ?: JSONObject.NULL)
                .put("precision", b.precision ?: JSONObject.NULL)
                .put("extractedAt", b.extractedAt)
        )
    }
    return arr
}
