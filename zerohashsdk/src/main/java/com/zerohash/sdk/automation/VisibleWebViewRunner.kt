package com.zerohash.sdk.automation

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Single-shot, VISIBLE, full-screen WebView covered by a branded
 * [LoadingOverlayView]: loads a URL, runs one automation script once the page
 * settles, awaits the Promise it returns, then always tears down.
 *
 * Android counterpart of iOS `AutomatedWebViewController` (the `runVisibleWebView`
 * path). Used for `getBalance` and `getDepositAddress` — both of which iOS runs
 * VISIBLE (not offscreen), because the Coinbase SPA renders reliably only in a
 * real-sized, on-screen WebView, and because the Cloudflare-challenge retry needs
 * the live page on screen so the user can solve it.
 *
 * The Promise bridge is the same as [PromiseWebViewRunner]'s: Android's
 * `evaluateJavascript` doesn't await Promises, so an injected wrapper resolves
 * back to native via a `@JavascriptInterface`.
 */
internal class VisibleWebViewRunner(private val activity: Activity) {

    private var root: FrameLayout? = null
    private var webView: WebView? = null
    private var overlay: LoadingOverlayView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val result = CompletableDeferred<JSONObject?>()
    /** Locked on the first settle Evaluate/Answer so a later didFinish can't re-fire. */
    private var started = false

    /**
     * @param waitForChallengeClearance when true, defer the script until a
     *   Cloudflare interstitial clears (polled on the live page), then cover the
     *   page with the overlay and run it once. Drives the visible challenge-solve
     *   retry; see [Coinbase.getBalance].
     */
    suspend fun run(
        url: String,
        scriptAsset: String,
        timeoutMs: Long,
        overlayOptions: OverlayOptions,
        showOverlay: Boolean,
        waitForChallengeClearance: Boolean = false,
        preludeAssets: List<String> = emptyList(),
        paramsJson: String? = null,
        settle: (host: String) -> SettleDecision,
    ): JSONObject? = withContext(Dispatchers.Main) {
        val script = activity.readAutomationAsset(scriptAsset)
        val prelude = preludeAssets.joinToString("\n") { activity.readAutomationAsset(it) }
        try {
            createAndLoad(url, script, prelude, paramsJson, overlayOptions, showOverlay, waitForChallengeClearance, settle)
            val value = withTimeoutOrNull(timeoutMs) { result.await() }
            if (!result.isCompleted) {
                throw PlatformException("timeout after ${timeoutMs}ms")
            }
            value
        } finally {
            teardown()
        }
    }

    private fun createAndLoad(
        url: String,
        script: String,
        prelude: String,
        paramsJson: String?,
        overlayOptions: OverlayOptions,
        showOverlay: Boolean,
        waitForChallengeClearance: Boolean,
        settle: (host: String) -> SettleDecision,
    ) {
        val wv = WebView(activity)
        webView = wv
        wv.applyAutomationDefaults()

        wv.addJavascriptInterface(PromiseBridge(), BRIDGE)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                if (result.isCompleted || started) return
                val host = hostOf(finishedUrl)
                when (val decision = settle(host)) {
                    is SettleDecision.WaitMore -> Log.d(TAG, "settle($host) => waitMore")
                    is SettleDecision.Answer -> {
                        Log.d(TAG, "settle($host) => answer (no script)")
                        started = true
                        result.complete(decision.value)
                    }
                    is SettleDecision.Evaluate -> {
                        // Lock now so the post-solve reload's didFinish can't kick
                        // off a second path.
                        started = true
                        if (waitForChallengeClearance) {
                            pollUntilChallengeClears(script, prelude, paramsJson, overlayOptions)
                        } else {
                            evaluate(view, script, prelude, paramsJson)
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true && !started && !result.isCompleted) {
                    // WebResourceError.getDescription() is API 23+. This 3-arg
                    // onReceivedError overload is itself only invoked on API 23+,
                    // but guard explicitly so lint (minSdk 21) is satisfied and
                    // API 21/22 is provably safe.
                    val reason =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description else null
                    result.completeExceptionally(
                        PlatformException("load failed: $reason"),
                    )
                }
            }
        }

        // Full-screen real-sized WebView so the SPA renders, with the branded
        // overlay on top (when showOverlay) so the user never sees the page.
        val container = FrameLayout(activity)
        container.addView(wv, matchParent())
        if (showOverlay) presentOverlay(container, overlayOptions)
        root = container
        activity.contentView().addView(container, matchParent())

        Log.d(TAG, "loading $url")
        wv.loadUrl(url)
    }

    /** Adds + starts the branded cover over the WebView, unless one already exists. */
    private fun presentOverlay(container: FrameLayout, overlayOptions: OverlayOptions) {
        if (overlay != null) return
        val cover = LoadingOverlayView(activity, overlayOptions)
        overlay = cover
        container.addView(cover.getView(), matchParent())
        cover.start()
    }

    /**
     * Polls the live page until no Cloudflare challenge is present, then covers
     * the page with the overlay and evaluates the script ONCE. Each poll is an
     * independent evaluateJavascript, so this survives the post-solve reload that
     * would destroy an in-page wait. Bounded by the outer run timeout.
     */
    private fun pollUntilChallengeClears(
        script: String,
        prelude: String,
        paramsJson: String?,
        overlayOptions: OverlayOptions,
    ) {
        val tick = object : Runnable {
            override fun run() {
                if (result.isCompleted) return
                webView?.evaluateJavascript(CHALLENGE_PROBE) { r ->
                    if (result.isCompleted) return@evaluateJavascript
                    // Mid-navigation/reload (typical right after a solve) returns
                    // null → treat as still challenged and keep polling.
                    val challenged = r != "false"
                    if (!challenged) {
                        Log.d(TAG, "challenge cleared; covering and replaying")
                        root?.let { presentOverlay(it, overlayOptions) }
                        evaluate(webView, script, prelude, paramsJson)
                    } else {
                        handler.postDelayed(this, CHALLENGE_POLL_MS)
                    }
                }
            }
        }
        handler.post(tick)
    }

    private fun evaluate(view: WebView?, script: String, prelude: String, paramsJson: String?) {
        val paramsDecl = if (paramsJson != null) "var params = $paramsJson;" else ""
        val wrapped = buildPromiseWrapper(BRIDGE, CALL_ID, prelude, paramsDecl, script)
        view?.evaluateJavascript(wrapped, null)
    }

    private inner class PromiseBridge {
        @JavascriptInterface
        fun onResolve(id: String, json: String?) {
            if (id != CALL_ID) return
            try {
                result.complete(decodeJsResult(json))
            } catch (e: PlatformException) {
                result.completeExceptionally(e)
            }
        }

        @JavascriptInterface
        fun onReject(id: String, message: String?) {
            if (id != CALL_ID) return
            result.completeExceptionally(PlatformException(message ?: "JS rejected"))
        }
    }

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
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
    }

    companion object {
        private const val TAG = "ZHAutomation"
        private const val BRIDGE = "__zhVisible"
        private const val CALL_ID = "1"
        private const val CHALLENGE_POLL_MS = 500L

        /** Truthy while a Cloudflare interstitial/Turnstile is on the page (iOS parity). */
        private const val CHALLENGE_PROBE =
            "(!!(window._cf_chl_opt || document.querySelector('div[class=\"ch-title-zone\"]')))"
    }
}
