package com.zerohash.sdk.automation

import android.app.Activity
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coinbase automation platform. Android port of iOS `Coinbase.swift`. Implements
 * every flow capability (`id = "cbase"`); registered in [PlatformRegistry].
 *
 * The bridge owns the cross-platform plumbing (session lifecycle, registry,
 * visibility hand-off); this object only knows how to drive Coinbase's pages and
 * JS. Adding another platform means another [Platform] implementation, not bridge
 * changes.
 */
internal object Coinbase : AuthFlow, BalanceFlow, DepositFlow, WithdrawFlow {

    override val id = "cbase"

    /**
     * Hosts touched by the Coinbase automation, cleared on workflow teardown.
     * `appleid.apple.com` is included because Sign-in-with-Apple runs in a
     * child WebView that shares the process-wide cookie jar.
     */
    override val cookieHosts = listOf(
        "https://login.coinbase.com",
        "https://www.coinbase.com",
        "https://coinbase.com",
        "https://appleid.apple.com",
    )

    private const val TAG = "ZHAutomation"
    private const val HOME_URL = "https://www.coinbase.com/home"

    // Deep links into the Receive / Send flows. Skips the dashboard, where an
    // intermittent promo banner covered the entry controls and stalled the run.
    // These routes open with the flow's modal already up, so the click-through
    // openers in the JS assets are now only a fallback.
    private const val RECEIVE_URL = "https://www.coinbase.com/receive"
    private const val SEND_URL = "https://www.coinbase.com/send"
    private const val STATUS_TIMEOUT_MS = 30_000L
    private const val PROFILE_DEADLINE_MARGIN_MS = 3_000L
    private const val PROBE_TIMEOUT_PREFIX = "timeout"
    private const val PROBE_TIMEOUT_REASON = "timeout"
    private const val PROBE_TRANSIENT_REASON = "http_error"
    internal val STATUS_PRELUDE_ASSETS = listOf("automation/shared-dom-helpers.js")
    internal val PROFILE_ATTEMPT_LOG_KEYS = listOf("attempt", "outcome", "http_status", "latency_ms")
    internal val PROFILE_RESULT_LOG_KEYS = listOf("outcome", "attempts", "total_ms", "error")

    // Match iOS getBalance: a short normal budget, a long budget when the user is
    // solving a Cloudflare challenge on the revealed page.
    private const val BALANCE_TIMEOUT_MS = 10_000L
    private const val BALANCE_CHALLENGE_TIMEOUT_MS = 90_000L
    private const val DEPOSIT_ADDRESS_TIMEOUT_MS = 90_000L

    // Per-leg withdraw timeouts (slightly under the web client's, so native
    // answers first): web start=90s, continue=60s.
    private const val WITHDRAW_START_TIMEOUT_MS = 90_000L
    private const val WITHDRAW_CONTINUE_TIMEOUT_MS = 60_000L
    private const val WITHDRAW_CANCEL_TIMEOUT_MS = 15_000L

    /** Replayed from one warm page load — both in a single Cloudflare handshake. */
    private val BALANCE_OPS = listOf("CryptoQuery", "CashQuery")

    /** /send opens with the recipient step already up; `withdraw.js` waits for it. */
    override val withdrawUrl = SEND_URL

    /**
     * Detects whether the embedded WebView session is logged in to Coinbase.
     *
     * Must be called from a coroutine (it suspends for the WebView round-trip).
     * Loads coinbase.com/home; a logged-out user is redirected to
     * login.coinbase.com (settled as `loggedIn=false` without running any DOM
     * probe), otherwise `auth-status.js` decides on the home host.
     *
     * Port of `Coinbase.status(ctx:)`.
     */
    override suspend fun status(activity: Activity): AuthStatusResult {
        Log.d(TAG, "status starting url=$HOME_URL")
        val raw = PromiseWebViewRunner(activity).run(
            url = HOME_URL,
            scriptAsset = "automation/auth-status.js",
            timeoutMs = STATUS_TIMEOUT_MS,
            preludeAssets = STATUS_PRELUDE_ASSETS,
            paramsJson = statusParamsJson(System.currentTimeMillis()),
        ) { host ->
            when (host) {
                "login.coinbase.com" -> SettleDecision.Answer(JSONObject().put("loggedIn", false))
                "www.coinbase.com", "coinbase.com" -> SettleDecision.Evaluate
                else -> SettleDecision.WaitMore
            }
        }

        val profileDiag = raw?.optJSONObject("profileDiag")
        if (profileDiag != null) {
            logProfileDiag(profileDiag)
        }

        val result = parseStatus(raw)
        Log.d(TAG, "status OK loggedIn=${result.loggedIn} profile=${result.profile != null}")

        val profileFailure = result.profileFailure
        if (profileFailure != null) {
            Log.w(TAG, "status profileFailure error=${profileFailure.error} reason=${profileFailure.reason}")
        }

        return result
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun statusParamsJson(nowMs: Long): String =
        JSONObject().put("profileDeadlineMs", nowMs + STATUS_TIMEOUT_MS - PROFILE_DEADLINE_MARGIN_MS).toString()

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun parseStatus(raw: JSONObject?): AuthStatusResult {
        // Match iOS: a missing/non-boolean loggedIn is a broken probe, not a
        // silent "logged out" — surface it as invalid rather than masking it.
        val obj = raw ?: throw PlatformException("invalid JS return")
        if (!obj.has("loggedIn") || obj.isNull("loggedIn")) {
            throw PlatformException("invalid JS return")
        }

        if (!obj.optBoolean("loggedIn")) {
            return AuthStatusResult(loggedIn = false)
        }

        val profileFailure = obj.optJSONObject("profileFailure")
        if (profileFailure != null) {
            return failedProfileStatus(profileFailure.optString("reason"))
        }

        val profileJson = obj.optJSONObject("profile")
        if (profileJson == null) {
            return failedProfileStatus(UNKNOWN_PROFILE_REASON)
        }

        val profile = AuthProfile.fromJson(profileJson)
        if (profile == null) {
            return failedProfileStatus(MISSING_FIELD_REASON)
        }

        return AuthStatusResult(loggedIn = true, profile = profile)
    }

    private fun failedProfileStatus(reason: String): AuthStatusResult =
        AuthStatusResult(loggedIn = true, profileFailure = AuthProfileFailure.forReason(reason))

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun describeProfileRow(row: JSONObject, keys: List<String>): String =
        keys.joinToString(" ") { key -> "$key=${logValueOrDash(row.opt(key))}" }

    private fun logValueOrDash(value: Any?): String {
        if (value == null || value == JSONObject.NULL) {
            return "-"
        }

        return value.toString()
    }

    private fun logProfileDiag(diag: JSONObject) {
        val attempts = diag.optJSONArray("attempts")
        if (attempts != null) {
            logFailedAttempts(attempts)
        }

        val result = diag.optJSONObject("result")
        if (result == null) {
            return
        }

        val line = "auth_profile_result ${describeProfileRow(result, PROFILE_RESULT_LOG_KEYS)}"
        if (result.isNull("error")) {
            Log.d(TAG, line)
        } else {
            Log.e(TAG, line)
        }
    }

    private fun logFailedAttempts(attempts: JSONArray) {
        for (index in 0 until attempts.length()) {
            val attempt = attempts.optJSONObject(index)
            if (attempt != null && attempt.optString("outcome") != "ok") {
                Log.w(TAG, "auth_profile_attempt ${describeProfileRow(attempt, PROFILE_ATTEMPT_LOG_KEYS)}")
            }
        }
    }

    /**
     * Scrapes the logged-in user's Coinbase balances from a single warm page load.
     *
     * Must be called from a coroutine. Loads coinbase.com/home; if redirected to
     * the login host, the user isn't logged in and this throws
     * `PlatformException("not logged in")`. Otherwise it injects the GraphQL query
     * catalogue (`coinbase-balance-queries.js`) and replays CryptoQuery + CashQuery
     * via `get-balance.js`, mapping the folded response into [AssetBalance]s.
     *
     * Port of `Coinbase.getBalance(ctx:overlay:showOverlay:)`.
     *
     * Runs in a VISIBLE, overlay-covered WebView (like iOS — the SPA renders
     * reliably only on screen). The balance script is a GraphQL `fetch`
     * (credentials:include cookies), so the overlay hides the page from the user.
     * On a Cloudflare `CHALLENGE_PRESENT`, it re-runs with the overlay lifted and a
     * long timeout so the user can solve the challenge, then replays; a second
     * challenge surfaces as the retryable `CHALLENGE_UNSOLVED`.
     */
    override suspend fun getBalance(
        activity: Activity,
        overlay: OverlayOptions,
        showOverlay: Boolean,
    ): List<AssetBalance> {
        Log.d(TAG, "getBalance starting url=$HOME_URL ops=$BALANCE_OPS")
        val paramsJson = JSONObject().put("ops", JSONArray(BALANCE_OPS)).toString()

        suspend fun attempt(show: Boolean, forChallengeRetry: Boolean): JSONObject? =
            VisibleWebViewRunner(activity).run(
                url = HOME_URL,
                scriptAsset = "automation/get-balance.js",
                timeoutMs = if (forChallengeRetry) BALANCE_CHALLENGE_TIMEOUT_MS else BALANCE_TIMEOUT_MS,
                overlayOptions = overlay,
                showOverlay = show,
                waitForChallengeClearance = forChallengeRetry,
                preludeAssets = listOf("automation/coinbase-balance-queries.js"),
                paramsJson = paramsJson,
            ) { host ->
                when (host) {
                    "login.coinbase.com" -> SettleDecision.Answer(null) // not logged in
                    "www.coinbase.com", "coinbase.com" -> SettleDecision.Evaluate
                    else -> SettleDecision.WaitMore
                }
            }

        val raw = try {
            attempt(show = showOverlay, forChallengeRetry = false)
        } catch (e: PlatformException) {
            if (e.message?.contains("CHALLENGE_PRESENT") != true) throw e
            Log.d(TAG, "getBalance challenge; revealing live page for one retry")
            try {
                attempt(show = false, forChallengeRetry = true)
            } catch (e2: PlatformException) {
                if (e2.message?.contains("CHALLENGE_PRESENT") == true) {
                    throw PlatformException("CHALLENGE_UNSOLVED")
                }
                throw e2
            }
        } ?: throw PlatformException("not logged in")

        val balances = parseBalances(raw)
        Log.d(TAG, "getBalance OK count=${balances.size}")
        return balances
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun parseBalances(obj: JSONObject): List<AssetBalance> {
        val rows = obj.optJSONArray("balances")
            ?: throw PlatformException("invalid balance JS return: no 'balances' array")
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            AssetBalance(
                key = row.getString("key"),
                label = row.getString("label"),
                amount = row.getString("amount"),
                notional = row.getString("notional"),
                currency = row.optStringOrNull("currency"),
                totalStakedPercent = row.optStringOrNull("totalStakedPercent"),
                precision = if (row.isNull("precision")) null else row.optInt("precision"),
                extractedAt = row.getString("extractedAt"),
            )
        }
    }

    /**
     * Scrapes a Coinbase deposit (Receive) address for the given asset/network.
     * Used by the Withdraw/Recovery SDKs (send funds FROM zerohash TO Coinbase),
     * NOT the Auth SDK (which uses [getBalance] + the withdraw flow).
     *
     * Must be called from a coroutine. Drives the Coinbase Receive **modal UI**
     * (open receive → pick asset/network → read address), so — like iOS, which
     * runs this via `runVisibleWebView` — it uses the visible [VisibleWebViewRunner]
     * (DOM automation is unreliable offscreen). One-shot: load → settle → evaluate →
     * dismiss; a redirect to the login host settles as "not logged in".
     *
     * [payloadJson] is the request payload `{ asset, network, amount? }`.
     * Returns the resolved object verbatim (`{ address, destinationTag, network,
     * asset, warnings, depositUri, amountSubmitted? }`); the web reads `address`
     * and `destinationTag`.
     *
     * Port of `Coinbase.getDepositAddress(ctx:payload:overlay:showOverlay:)`.
     */
    override suspend fun getDepositAddress(
        activity: Activity,
        payloadJson: String,
        overlay: OverlayOptions,
        showOverlay: Boolean,
    ): JSONObject {
        Log.d(TAG, "getDepositAddress starting url=$RECEIVE_URL")
        val raw = VisibleWebViewRunner(activity).run(
            url = RECEIVE_URL,
            scriptAsset = "automation/get-deposit-address.js",
            timeoutMs = DEPOSIT_ADDRESS_TIMEOUT_MS,
            overlayOptions = overlay,
            showOverlay = showOverlay,
            waitForChallengeClearance = true,
            preludeAssets = listOf(
                "automation/shared-dom-helpers.js",
                "automation/coinbase-idv-gate.js",
                "automation/coinbase-screens.js",
            ),
            paramsJson = payloadJson,
        ) { host ->
            when (host) {
                "www.coinbase.com", "coinbase.com" -> SettleDecision.Evaluate
                "login.coinbase.com" -> SettleDecision.Answer(null) // not logged in
                else -> SettleDecision.WaitMore
            }
        } ?: throw PlatformException("not logged in")
        return mapDepositResult(raw, payloadJson)
    }

    /**
     * Validate + normalize the deposit JS result, port of iOS `mapResult`: require
     * a non-empty `address` (a malformed scrape fails here rather than forwarding
     * garbage to the web), and default the optional fields the web tolerates
     * (`destinationTag`/`network`/`asset`/`depositUri`/`warnings`).
     *
     * ponytail: skips iOS's `amountSubmitted.requestedCurrency` enum validation —
     * the web reads `address`+`destinationTag`; `amountSubmitted` is passed through
     * verbatim. Add the enum check if a consumer starts relying on it.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun mapDepositResult(raw: JSONObject, payloadJson: String): JSONObject {
        val address = raw.optString("address")
        if (address.isEmpty()) throw PlatformException("invalid deposit result: missing address")
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull()
        val reqAsset = payload?.optString("asset").orEmpty()
        val reqNetwork = payload?.optString("network").orEmpty()
        return JSONObject()
            .put("address", address)
            .put("destinationTag", raw.optString("destinationTag", ""))
            .put("network", raw.optString("network").ifEmpty { reqNetwork })
            .put("asset", raw.optString("asset").ifEmpty { reqAsset })
            .put("warnings", raw.optJSONArray("warnings") ?: JSONArray())
            .put("depositUri", raw.optString("depositUri", ""))
            .apply { raw.optJSONObject("amountSubmitted")?.let { put("amountSubmitted", it) } }
    }

    /**
     * Runs the Coinbase login modal and resolves once it closes.
     *
     * Port of `Coinbase.login(ctx:)`.
     */
    override suspend fun login(activity: Activity): AuthLoginResult {
        Log.d(TAG, "login starting")
        val outcome = CoinbaseLoginActivity.present(activity)
        Log.d(TAG, "login modal closed outcome=$outcome")
        return when (outcome) {
            "success" -> loginProbe(activity)
            else -> AuthLoginResult(loggedIn = false, outcome = outcome)
        }
    }

    private suspend fun loginProbe(activity: Activity): AuthLoginResult {
        try {
            val probe = status(activity)

            return AuthLoginResult(
                loggedIn = probe.loggedIn,
                outcome = "success",
                profile = probe.profile,
                profileFailure = probe.profileFailure,
            )
        } catch (e: PlatformException) {
            val reason = loginProbeFailureReason(e.message)
            if (reason == null) {
                throw e
            }

            Log.w(TAG, "login status probe failed after sign-in; reporting profileFailure reason=$reason")
            return loginAfterProbeFailure(reason)
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun loginProbeFailureReason(message: String?): String? {
        if (message == null) {
            return null
        }

        if (message.startsWith(PROBE_TIMEOUT_PREFIX)) {
            return PROBE_TIMEOUT_REASON
        }

        if (TRANSIENT_PREFIXES.any { message.startsWith(it) }) {
            return PROBE_TRANSIENT_REASON
        }

        return null
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun loginAfterProbeFailure(reason: String): AuthLoginResult =
        AuthLoginResult(loggedIn = true, outcome = "success", profileFailure = AuthProfileFailure.forReason(reason))

    // ── WithdrawFlow ─────────────────────────────────────────────────────────
    //
    // withdraw.js installs `window.__zhWithdraw = { start, continue, cancel }`;
    // each entry returns a Promise of a WithdrawState-shaped object (or
    // `{ cancelled }`). The bridge supplies the live (already-loaded) session and
    // owns its lifecycle/visibility; here we just inject + invoke the right entry.

    override suspend fun startWithdraw(session: AutomationSession, payloadJson: String): JSONObject =
        session.evaluateAsync(
            prelude = withdrawPrelude(session),
            argName = "params",
            argJson = payloadJson,
            entryExpr = "window.__zhWithdraw.start(params)",
            timeoutMs = WITHDRAW_START_TIMEOUT_MS,
        ) ?: throw PlatformException("withdraw.start returned null")

    override suspend fun continueWithdraw(session: AutomationSession, payloadJson: String): JSONObject =
        session.evaluateAsync(
            prelude = withdrawPrelude(session),
            argName = "payload",
            argJson = payloadJson,
            entryExpr = "window.__zhWithdraw.continue(payload)",
            timeoutMs = WITHDRAW_CONTINUE_TIMEOUT_MS,
        ) ?: throw PlatformException("withdraw.continue returned null")

    override suspend fun cancelWithdraw(session: AutomationSession): JSONObject {
        val raw = session.evaluateAsync(
            prelude = withdrawPrelude(session),
            argName = null,
            argJson = null,
            entryExpr = "window.__zhWithdraw.cancel()",
            timeoutMs = WITHDRAW_CANCEL_TIMEOUT_MS,
        )
        // Strip to just { cancelled } (iOS extracts the bool), so no extra JS keys
        // leak through on the wire.
        return JSONObject().put("cancelled", raw?.optBoolean("cancelled", false) ?: false)
    }

    private fun withdrawPrelude(session: AutomationSession): String =
        session.asset("automation/shared-dom-helpers.js") + "\n" +
            session.asset("automation/coinbase-idv-gate.js") + "\n" +
            session.asset("automation/coinbase-screens.js") + "\n" +
            session.asset("automation/withdraw.js")
}
