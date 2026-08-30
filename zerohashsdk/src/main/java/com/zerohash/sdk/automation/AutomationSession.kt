package com.zerohash.sdk.automation

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A long-lived, full-screen WebView that drives a multi-step Coinbase automation
 * (the withdraw/send flow: start → continue(otp) → continue(poll)* → cancel).
 *
 * Android counterpart of iOS `AutomationSessionViewController` + the
 * `AutomationSessionHandle` protocol. Unlike [VisibleWebViewRunner] (one-shot),
 * this WebView STAYS ALIVE across many [evaluateAsync] calls on the same loaded
 * page — so the Coinbase send flow keeps its DOM/JS state between bridge calls.
 *
 * Visibility choreography (the iOS overlay/stepAside/resume dance):
 * - Created VISIBLE & full-screen on top of the host (connect-auth) WebView, with
 *   a branded [LoadingOverlayView] covering it, so Coinbase's SPA renders for real
 *   while the user only sees the loading state.
 * - [stepAside] sets the session INVISIBLE so the host's OTP screen shows AND
 *   receives touches (INVISIBLE keeps JS alive but doesn't draw/eat input).
 * - [resume] re-shows it (the page rides along, overlay still covering).
 * - [revealOverlay] lifts (true) / restores (false) the cover — lifted for a step
 *   the user completes IN Coinbase (id-verification).
 * - [pauseTimeout]/[restartTimeout] manage the 300s wall-clock ceiling: paused
 *   while parked on user input, restarted before each automation leg.
 */
internal class AutomationSession(
    private val activity: Activity,
    private val url: String,
    private val overlayOptions: OverlayOptions,
    private val showOverlay: Boolean,
) {
    private var root: FrameLayout? = null
    private var webView: WebView? = null
    private var overlay: LoadingOverlayView? = null

    private val initialLoad = CompletableDeferred<Unit>()

    private val timeoutHandler = Handler(Looper.getMainLooper())

    /** Separate from [timeoutHandler] so gate polls and the session ceiling can't
     *  interfere via removeCallbacks. */
    private val gateHandler = Handler(Looper.getMainLooper())
    private val gate = ChallengeGate(CHALLENGE_BUDGET_MS, CHALLENGE_POLL_MS) {
        SystemClock.elapsedRealtime()
    }
    private var gateStarted = false
    private val timeoutRunnable = Runnable {
        Log.d(TAG, "session timeout after ${SESSION_TIMEOUT_MS}ms; dismissing")
        dismiss()
    }
    private var didDismiss = false

    /** id → awaiter for in-flight evaluateAsync calls (completed by [Bridge]). */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject?>>()
    private var seq = 0


    /**
     * Hold [initialLoad] until no Cloudflare challenge owns the page (AUTH-4245).
     *
     * Each probe is an independent `evaluateJavascript` against whatever document is
     * live at that moment, so it survives Cloudflare's post-solve navigation where an
     * in-page wait would be destroyed. Ported from iOS `beginReadinessGate`.
     */
    private fun beginReadinessGate() {
        if (initialLoad.isCompleted || gateStarted) return
        gateStarted = true
        gate.start()
        probeForChallenge()
    }

    private fun probeForChallenge() {
        if (initialLoad.isCompleted || didDismiss) return
        webView?.evaluateJavascript(ChallengeGate.CHALLENGE_PROBE) { raw ->
            if (initialLoad.isCompleted || didDismiss) return@evaluateJavascript
            when (val decision = gate.onProbeResult(raw)) {
                is ChallengeGate.Decision.Clear -> {
                    Log.d(TAG, "no Cloudflare challenge; page ready to drive")
                    initialLoad.complete(Unit)
                }
                is ChallengeGate.Decision.Retry ->
                    gateHandler.postDelayed({ probeForChallenge() }, decision.delayMs)
                is ChallengeGate.Decision.Exhausted -> {
                    Log.e(TAG, "Cloudflare challenge unsolved after ${CHALLENGE_BUDGET_MS}ms")
                    // Driving a challenged page surfaces as a misleading selector
                    // error long after the real cause.
                    initialLoad.completeExceptionally(
                        PlatformException("withdraw/cloudflare-challenge-unsolved")
                    )
                }
            }
        }
    }

    suspend fun load() = withContext(Dispatchers.Main) {
        val wv = WebView(activity)
        webView = wv
        wv.applyAutomationDefaults()

        wv.addJavascriptInterface(Bridge(), BRIDGE)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, u: String?) {
                // Not initialLoad.complete(): with a challenge in front of the page
                // this callback belongs to the CHALLENGE document. Let the gate decide.
                beginReadinessGate()
            }

            // Navigation host allowlist: this session drives the Coinbase withdrawal
            // automation, so only Coinbase-owned origins may load in the main frame.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                blockOffCoinbaseNavigation(request?.url?.toString(), request?.isForMainFrame != false, TAG)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                blockOffCoinbaseNavigation(url, isMainFrame = true, TAG)
        }
        wv.webChromeClient = MediaCaptureChromeClient(activity)

        val container = FrameLayout(activity)
        container.addView(wv, matchParent())
        // Branded loading cover ON TOP of the WebView — hides the live Coinbase
        // automation behind the SDK's loading state. Lifted by [revealOverlay].
        if (showOverlay) {
            val cover = LoadingOverlayView(activity, overlayOptions)
            overlay = cover
            container.addView(cover.getView(), matchParent())
            cover.start()
        }
        root = container
        // Added after the host's content view → drawn on top while VISIBLE.
        activity.contentView().addView(container, matchParent())

        Log.d(TAG, "loading $url")
        wv.loadUrl(url)
        scheduleTimeout()
        // Proceed even if the initial load is slow — withdraw.js's own waitFor
        // polling tolerates a page that's still settling.
        withTimeoutOrNull(INITIAL_LOAD_TIMEOUT_MS) { initialLoad.await() }
        Unit
    }

    /**
     * Inject [prelude] (idempotent setup that installs window globals), optionally
     * bind `argName = argJson`, then await the Promise from [entryExpr].
     */
    suspend fun evaluateAsync(
        prelude: String,
        argName: String?,
        argJson: String?,
        entryExpr: String,
        timeoutMs: Long,
    ): JSONObject? = withContext(Dispatchers.Main) {
        val id = (++seq).toString()
        val deferred = CompletableDeferred<JSONObject?>()
        pending[id] = deferred

        val argDecl = if (argName != null) "var $argName = $argJson;" else ""
        val telemetryInstall = if (TelemetryRouter.collector != null) asset(TELEMETRY_INSTALL_ASSET) else ""
        val wrapped = buildPromiseWrapper(BRIDGE, id, prelude, argDecl, entryExpr, telemetryInstall)

        // Refuse to run money-movement automation if the page has somehow landed off Coinbase,
        // even if a navigation slipped past the WebViewClient allowlist.
        val currentHost = hostOf(webView?.url)
        if (!isTrustedCoinbaseHost(currentHost)) {
            pending.remove(id)
            throw PlatformException("withdraw/untrusted-host")
        }

        Log.d(TAG, "evaluateAsync id=$id ${entryExpr.take(60)}")
        webView?.evaluateJavascript(wrapped, null)
        val value = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id)
        // null is returned both on timeout AND on a genuine null result;
        // isCompleted disambiguates.
        if (!deferred.isCompleted) {
            throw PlatformException("evaluateAsync timeout after ${timeoutMs}ms ($entryExpr)")
        }
        value
    }

    /** Read a bundled asset as UTF-8 text (a platform's injected scripts). */
    fun asset(path: String): String = activity.readAutomationAsset(path)

    /** INVISIBLE: host (connect-auth) shows and receives touches; WebView stays alive. */
    fun stepAside() = activity.runOnUiThread { root?.visibility = View.INVISIBLE }

    /** Re-present after [stepAside] — the same WebView/page rides along (overlay still on). */
    fun resume() = activity.runOnUiThread { root?.visibility = View.VISIBLE }

    /**
     * Lift (true) or restore (false) the branded overlay. No-op when no overlay
     * exists. Lift it for a step the user must complete IN Coinbase (id-verification).
     */
    fun revealOverlay(revealed: Boolean) = activity.runOnUiThread {
        overlay?.getView()?.visibility = if (revealed) View.GONE else View.VISIBLE
        if (revealed) root?.visibility = View.VISIBLE
    }

    /** Suspend the wall-clock ceiling while parked on user input (OTP / id step). */
    fun pauseTimeout() = timeoutHandler.removeCallbacks(timeoutRunnable)

    /** Give the next automation leg a fresh wall-clock budget. */
    fun restartTimeout() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        scheduleTimeout()
    }

    private fun scheduleTimeout() {
        timeoutHandler.postDelayed(timeoutRunnable, SESSION_TIMEOUT_MS)
    }

    fun dismiss() {
        if (didDismiss) return
        didDismiss = true
        timeoutHandler.removeCallbacks(timeoutRunnable)
        val wv = webView
        val r = root
        val cover = overlay
        webView = null
        root = null
        overlay = null
        activity.runOnUiThread {
            runCatching {
                cover?.stop()
                wv?.stopLoading()
                (r?.parent as? ViewGroup)?.removeView(r)
                wv?.destroy()
            }
        }
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private inner class Bridge {
        /** Injected-realm drafts drained by the wrapper → the in-flight dispatch collector. */
        @Suppress("UNUSED_PARAMETER")
        @JavascriptInterface
        fun onEvents(id: String, json: String?) {
            TelemetryRouter.collector?.pushDraftsJson(json)
        }

        @JavascriptInterface
        fun onResolve(id: String, json: String?) {
            val d = pending[id] ?: return
            try {
                d.complete(decodeJsResult(json))
            } catch (e: PlatformException) {
                d.completeExceptionally(e)
            }
        }

        @JavascriptInterface
        fun onReject(id: String, message: String?) {
            pending[id]?.completeExceptionally(PlatformException(message ?: "JS rejected"))
        }
    }

    companion object {
        private const val TAG = "ZHAutomation"
        private const val BRIDGE = "__zhAuto"

        /**
         * How long [load] waits for the readiness gate. It PROCEEDS on expiry, since a
         * merely slow page can still be driven — which is why [CHALLENGE_BUDGET_MS]
         * must stay below it. `internal` so the invariant test can read it.
         */
        internal const val INITIAL_LOAD_TIMEOUT_MS = 30_000L

        /**
         * How long to wait for a challenge to clear before failing the leg. Must stay
         * under [INITIAL_LOAD_TIMEOUT_MS]: if that expired first, [load] would be
         * released and the automation injected into the challenge document.
         */
        internal const val CHALLENGE_BUDGET_MS = 25_000L

        /** Delay between challenge probes (iOS polls at 500ms). */
        private const val CHALLENGE_POLL_MS = 500L

        /** Wall-clock ceiling for one automation leg (iOS 300_000ms). */
        private const val SESSION_TIMEOUT_MS = 300_000L
    }
}
