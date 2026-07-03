package com.zerohash.sdk.automation

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CompletableDeferred

/**
 * SDK-owned visible WebView that renders the Coinbase login page — the Android
 * counterpart of iOS `presentModalWebView` (ModalViewController) for `auth.login`.
 *
 * The scraping WebView is owned by the SDK, NOT the host app (per the iOS model:
 * a separate automation WebView talks to Coinbase; the host app only triggers it
 * and communicates over the bridge).
 *
 * Parity with iOS `Coinbase.login` + `ModalViewController` + `ModalAutoClose`:
 * - **documentStart injection** ([loginModalJS] = hide-social + choose-2fa-method):
 *   Android has no WKUserScript documentStart hook, so we inject on every
 *   [WebViewClient.onPageStarted] (and again on finish as a backstop). The
 *   scripts are idempotent and self-persist (CSS rule + MutationObserver), so
 *   they survive the login SPA's client-side re-renders.
 * - **passkey-only auto-close** ([passkeyOnlyJS]): polled every
 *   [PROBE_INTERVAL_MS]; after [PROBE_REQUIRED_HITS] consecutive positive reads
 *   the modal closes with outcome `passkey-only` (iOS `.conditionMet`).
 * - **timeout** ([TIMEOUT_MS] ceiling): closes with outcome `timeout`.
 * - **success / user-closed**: redirect to [SUCCESS_HOST] == success (iOS
 *   `successHosts`); closing before that == user-closed.
 *
 * Sign in with Apple is supported: Coinbase opens it via `window.open`, and
 * [AuthPopupWindow] (via [WebChromeClient.onCreateWindow]) hosts that popup in a
 * child WebView sharing the login cookie jar — the Android counterpart of iOS
 * `PopupWebViewController`.
 *
 * Never exported — launched only in-process from [present].
 */
class CoinbaseLoginActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private val authPopup by lazy { AuthPopupWindow(this) }
    private var done = false

    private val handler = Handler(Looper.getMainLooper())
    private var probeHits = 0

    /** hide-social + choose-2fa-method, read once (idempotent; re-injected per nav). */
    private val loginModalJS: String by lazy {
        asset("automation/auth-hide-social.js") + "\n" + asset("automation/auth-choose-2fa-method.js")
    }
    private val passkeyOnlyJS: String by lazy { asset("automation/auth-detect-unsupported-2fa.js") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wv = WebView(this)
        webView = wv
        setContentView(wv)

        wv.applyAutomationDefaults()
        // Popup support for provider social login (Apple): Coinbase opens Apple
        // via window.open on appleid.apple.com and reads the result back from
        // window.opener. onCreateWindow (below) hosts that popup in a child
        // WebView sharing the process-wide cookie jar (set up by
        // applyAutomationDefaults). iOS keeps popups for Apple via
        // PopupWebViewController; this is the Android counterpart.
        wv.settings.setSupportMultipleWindows(true)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // documentStart-equivalent: inject before/as the page renders so
                // unsupported buttons are hidden and Password 2FA auto-advances.
                view?.evaluateJavascript(loginModalJS, null)
                // Coinbase redirects to www.coinbase.com once authenticated.
                if (!done && hostOf(url) == SUCCESS_HOST) {
                    Log.d(TAG, "login success (redirect to $SUCCESS_HOST)")
                    finishWith("success")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Backstop in case onPageStarted ran before documentElement existed.
                view?.evaluateJavascript(loginModalJS, null)
                // Also check success here (iOS checks on didFinish): covers a
                // success surfaced without a fresh onPageStarted host change.
                if (!done && hostOf(url) == SUCCESS_HOST) {
                    Log.d(TAG, "login success (finished on $SUCCESS_HOST)")
                    finishWith("success")
                }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // Only honor popups from a real user tap (the Apple button).
                if (!isUserGesture) return false
                return authPopup.onCreateWindow(resultMsg)
            }
        }
        wv.loadUrl(LOGIN_URL)

        scheduleTimeout()
        startPasskeyOnlyProbe()
    }

    /**
     * Polls [passkeyOnlyJS] while the modal is open; closes with `passkey-only`
     * after [PROBE_REQUIRED_HITS] consecutive positive reads. Evaluates first
     * then re-posts, so the interval is the confirm gap between reads. A transient
     * half-rendered DOM resets the streak (mirrors iOS ModalAutoClose).
     */
    private fun startPasskeyOnlyProbe() {
        val tick = object : Runnable {
            override fun run() {
                if (done) return
                webView?.evaluateJavascript(passkeyOnlyJS) { result ->
                    if (done) return@evaluateJavascript
                    if (result == "true") {
                        probeHits++
                        if (probeHits >= PROBE_REQUIRED_HITS) {
                            Log.d(TAG, "passkey-only detected; closing modal")
                            finishWith("passkey-only")
                            return@evaluateJavascript
                        }
                    } else {
                        probeHits = 0
                    }
                    handler.postDelayed(this, PROBE_INTERVAL_MS)
                }
            }
        }
        handler.postDelayed(tick, PROBE_INTERVAL_MS)
    }

    /** Force-closes with `timeout` if the user neither completes nor dismisses. */
    private fun scheduleTimeout() {
        handler.postDelayed({
            if (!done) {
                Log.d(TAG, "login modal timed out after ${TIMEOUT_MS}ms")
                finishWith("timeout")
            }
        }, TIMEOUT_MS)
    }

    private fun finishWith(outcome: String) {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        pending?.complete(outcome)
        pending = null
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // If the modal is torn down before a success redirect, the user backed out.
        if (!done) {
            done = true
            pending?.complete("user-closed")
            pending = null
        }
        authPopup.dismiss()
        webView?.destroy()
        webView = null
    }

    // Graceful: a missing/unreadable asset (packaging regression) injects empty JS
    // (a no-op) rather than crashing the modal from a WebViewClient callback.
    private fun asset(path: String): String =
        runCatching { readAutomationAsset(path) }
            .getOrElse { Log.e(TAG, "missing automation asset: $path", it); "" }

    companion object {
        private const val TAG = "ZHAutomation"
        const val LOGIN_URL = "https://login.coinbase.com/signin"
        private const val SUCCESS_HOST = "www.coinbase.com"

        // Mirror iOS: 300s modal ceiling; passkey-only probe every 100ms, 2 hits.
        private const val TIMEOUT_MS = 300_000L
        private const val PROBE_INTERVAL_MS = 100L
        private const val PROBE_REQUIRED_HITS = 2

        /**
         * In-flight login completion. One modal at a time (matches the UX); set by
         * [present], completed by the activity with the outcome string. ponytail:
         * a single global is fine — there's never more than one login modal up.
         */
        internal var pending: CompletableDeferred<String>? = null

        /** Launch the login modal and suspend until it closes; returns the outcome. */
        suspend fun present(activity: Activity): String {
            // One login modal at a time. auth.login is non-coalescable, so two
            // overlapping requests would otherwise clobber `pending` and strand the
            // first waiter. Mirrors the single-session withdraw guard.
            if (pending?.isCompleted == false) {
                throw PlatformException("login already in progress")
            }
            val deferred = CompletableDeferred<String>()
            pending = deferred
            activity.startActivity(Intent(activity, CoinbaseLoginActivity::class.java))
            return deferred.await()
        }
    }
}
