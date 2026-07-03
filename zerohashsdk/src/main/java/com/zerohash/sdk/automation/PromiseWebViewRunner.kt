package com.zerohash.sdk.automation

import android.app.Activity
import android.os.Build
import android.util.Log
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
 * Loads a remote URL in a transient WebView, waits for the page to settle on a
 * URL the caller cares about, then injects a script and **awaits the Promise it
 * returns**, returning the decoded JSON object.
 *
 * This is the Android counterpart of iOS `OffscreenWebViewRunner`, but the core
 * mechanism is genuinely different and has no iOS analogue:
 *
 *   iOS `WKWebView.callAsyncJavaScript` natively awaits a returned Promise.
 *   Android `WebView.evaluateJavascript` does NOT — it returns the synchronous
 *   value (a Promise stringifies to `{}`). So we bridge the resolution back to
 *   native ourselves: a `@JavascriptInterface` (`__zhNative`) that the injected
 *   wrapper calls when the Promise settles, completing a [CompletableDeferred].
 *
 * One runner instance == one run. Not reusable (matches the transient-webview
 * model; we can pool later if cold-start churn shows up — see iOS
 * SharedWebViewConfiguration's long-lived runner).
 *
 * ponytail: offscreen == a 1x1, alpha-0 WebView attached to the activity's
 * content view so the SPA's JS actually executes (an unattached WebView won't
 * reliably run a real page). Upgrade path if Coinbase's SPA refuses to behave
 * at 1x1: a dedicated full-screen overlay Activity, like iOS's visible runner.
 */
internal class PromiseWebViewRunner(private val activity: Activity) {

    companion object {
        private const val TAG = "ZHAutomation"

        /** Name the injected wrapper calls back on. */
        private const val BRIDGE = "__zhNative"

        /** Single run per instance, so a constant correlation id is fine. */
        private const val CALL_ID = "1"
    }

    private var webView: WebView? = null

    /** Completed by the JS bridge (resolve/reject) or the navigation settle. */
    private val result = CompletableDeferred<JSONObject?>()

    /**
     * Whether the script has been injected. We inject ONCE, on the first
     * `Evaluate`, but do NOT lock the runner afterwards: settle is re-consulted
     * on every navigation so a later page (e.g. a logged-out www.coinbase.com →
     * login.coinbase.com redirect) can still terminate with an `Answer`. The
     * injected script's JS context dies on that navigation, so the redirect's
     * `Answer` is what resolves the logged-out case. Mirrors iOS.
     */
    private var injected = false

    /**
     * @param url            page to load (e.g. https://www.coinbase.com/home)
     * @param scriptAsset    asset path of the IIFE-expression script to run
     *                       (e.g. "automation/auth-status.js"); must evaluate to a
     *                       value or a Promise.
     * @param timeoutMs      wall-clock budget for the whole run.
     * @param preludeAssets  asset paths injected (in order) BEFORE [scriptAsset],
     *                       in the same function scope — used for trusted setup
     *                       scripts that install window globals the main script
     *                       reads (e.g. coinbase-balance-queries.js sets
     *                       `window.__zhCoinbaseQueries`).
     * @param paramsJson     when non-null, a JSON literal bound as the in-scope
     *                       `params` variable the main script reads. It is spliced
     *                       verbatim into the evaluated source, so it MUST be the
     *                       output of `org.json` serialization
     *                       (`JSONObject`/`JSONArray.toString()`) — that escaping is
     *                       the CWE-94 defense (see [buildPromiseWrapper]). The
     *                       balance flow passes a fixed org.json-encoded ops list.
     * @param settle         consulted on each page finish to decide what to do
     *                       with the current URL.
     * @return the decoded result object, or the [SettleDecision.Answer] payload.
     * @throws PlatformException if the script rejects, returns non-object, or
     *         the run times out / fails to load.
     */
    suspend fun run(
        url: String,
        scriptAsset: String,
        timeoutMs: Long,
        preludeAssets: List<String> = emptyList(),
        paramsJson: String? = null,
        settle: (host: String) -> SettleDecision,
    ): JSONObject? = withContext(Dispatchers.Main) {
        val script = activity.readAutomationAsset(scriptAsset)
        val prelude = preludeAssets.joinToString("\n") { activity.readAutomationAsset(it) }
        try {
            createAndLoad(url, script, prelude, paramsJson, settle)
            val value = withTimeoutOrNull(timeoutMs) { result.await() }
            if (!result.isCompleted) {
                throw PlatformException("timeout after ${timeoutMs}ms")
            }
            // withTimeoutOrNull returns null both on timeout AND on a genuine
            // null result; the isCompleted check above disambiguates.
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
        settle: (host: String) -> SettleDecision,
    ) {
        val wv = WebView(activity)
        webView = wv

        wv.applyAutomationDefaults()

        wv.addJavascriptInterface(PromiseBridge(), BRIDGE)

        wv.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, historyUrl: String?, isReload: Boolean) {
                // Settle on NAVIGATION for terminal (Answer) URLs, not just
                // onPageFinished. The logged-out signal is the redirect to
                // login.coinbase.com; its SPA doesn't reliably fire onPageFinished,
                // so waiting for it races the home page's own load and times out.
                // Evaluate still waits for onPageFinished (needs the page loaded to
                // inject); only Answer is claimed early here.
                if (result.isCompleted) return
                val host = hostOf(historyUrl)
                val decision = settle(host)
                if (decision is SettleDecision.Answer) {
                    Log.d(TAG, "settle($host) => answer (on navigation)")
                    result.complete(decision.value)
                }
            }

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                if (result.isCompleted) return
                val host = hostOf(finishedUrl)
                when (val decision = settle(host)) {
                    is SettleDecision.WaitMore ->
                        Log.d(TAG, "settle($host) => waitMore")
                    is SettleDecision.Answer -> {
                        // Terminal — wins even if a script was already injected on
                        // a prior page (its context died on this navigation).
                        Log.d(TAG, "settle($host) => answer (no script)")
                        result.complete(decision.value)
                    }
                    is SettleDecision.Evaluate -> {
                        // Inject once; don't lock — a later navigation can Answer.
                        if (!injected) {
                            Log.d(TAG, "settle($host) => evaluate")
                            injected = true
                            evaluate(view, script, prelude, paramsJson)
                        } else {
                            Log.d(TAG, "settle($host) => evaluate (already injected)")
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // Only the INITIAL main-frame load failing is fatal. Once the
                // script is injected we ignore main-frame errors: a redirect
                // (e.g. → login.coinbase.com) settles via onPageFinished, and a
                // genuine stall is caught by the outer timeout.
                if (request?.isForMainFrame == true && !injected && !result.isCompleted) {
                    // WebResourceError.getDescription() is API 23+. This 3-arg
                    // onReceivedError overload is itself only invoked on API 23+,
                    // but guard explicitly so lint (minSdk 21) is satisfied and
                    // API 21/22 is provably safe.
                    val reason =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description else null
                    result.completeExceptionally(
                        PlatformException("load failed: $reason")
                    )
                }
            }
        }

        Log.d(TAG, "loading $url")
        wv.loadUrl(url)

        // Attach 1x1 + invisible so the page renders/executes without grabbing
        // the screen. See class-level ponytail note on the upgrade path.
        val params = FrameLayout.LayoutParams(1, 1)
        wv.alpha = 0f
        activity.contentView().addView(wv, params)
    }

    /**
     * Wrap the IIFE-expression [script] so its Promise resolution is delivered
     * back to native.
     *
     * [prelude] (trusted setup, installs window globals) is injected first, then
     * `params` is bound as a local the script reads (see [run]'s paramsJson doc —
     * trusted constant data only), then the main script. All three share this
     * function scope, so the script's free `params` / prelude globals resolve.
     *
     * The script is an IIFE expression ending in `})();` — its trailing `;`
     * (and whitespace) is stripped so it sits cleanly inside `Promise.resolve((…))`
     * (an unstripped `;` inside the parens is a syntax error). Matches iOS, which
     * trims the same trailing chars before wrapping.
     */
    private fun evaluate(view: WebView?, script: String, prelude: String, paramsJson: String?) {
        val paramsDecl = if (paramsJson != null) "var params = $paramsJson;" else ""
        val wrapped = buildPromiseWrapper(BRIDGE, CALL_ID, prelude, paramsDecl, script)
        view?.evaluateJavascript(wrapped, null)
    }

    private inner class PromiseBridge {
        @JavascriptInterface
        fun onResolve(id: String, json: String?) {
            if (id != CALL_ID) return
            // Arrives on the JavaBridge thread; CompletableDeferred is safe to
            // complete from any thread.
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
        val wv = webView ?: return
        webView = null
        runCatching {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
    }
}
