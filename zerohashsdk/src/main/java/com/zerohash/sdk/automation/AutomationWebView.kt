package com.zerohash.sdk.automation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared plumbing for the Coinbase-scraping WebViews. These helpers were
 * duplicated across [AutomationSession], [PromiseWebViewRunner],
 * [VisibleWebViewRunner], and [CoinbaseLoginActivity]; consolidating them keeps
 * the security-relevant WebView hardening and the Promise-bridge contract in one
 * place (AUTH-3433).
 */

/**
 * Apply the standard automation WebView settings: JS/DOM storage on for the SPA,
 * mixed content blocked, and local file/content access denied as defense-in-depth
 * (those default to `true` below API 30, and minSdk is 21 — these WebViews only
 * ever load Coinbase HTTPS subdomains, so local-file access is never needed). Also
 * opts the process-wide [CookieManager] into first- and third-party cookies, which
 * is what ties login → status → balance together across the runners.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.applyAutomationDefaults() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

/**
 * Wrap an IIFE-expression [rawExpr] so the Promise it resolves to is delivered
 * back to native over the [bridge] `@JavascriptInterface` (correlated by [callId]).
 *
 * Android's `evaluateJavascript` does NOT await Promises (a Promise stringifies to
 * `{}`), so the injected wrapper calls `bridge.onResolve/onReject` when the Promise
 * settles. [prelude] (trusted setup that installs window globals) is injected
 * first, then [argDecl] (e.g. `var params = …;`) binds an in-scope local the
 * script reads; all three share the wrapper's function scope.
 *
 * The trailing `;` (and whitespace) of [rawExpr] is stripped so an IIFE expression
 * sits cleanly inside `Promise.resolve((…))` (an unstripped `;` inside the parens
 * is a syntax error); a bare entry call is unaffected. Matches iOS, which trims the
 * same trailing chars before wrapping.
 */
internal fun buildPromiseWrapper(
    bridge: String,
    callId: String,
    prelude: String,
    argDecl: String,
    rawExpr: String,
): String {
    val expr = rawExpr.trimEnd(';', '\n', '\r', ' ', '\t')
    return """
        (function () {
          try {
            $prelude
            $argDecl
            Promise.resolve(($expr)).then(
              function (r) { $bridge.onResolve("$callId", JSON.stringify(r === undefined ? null : r)); },
              function (e) { $bridge.onReject("$callId", String((e && e.message) || e)); }
            );
          } catch (e) {
            $bridge.onReject("$callId", String((e && e.message) || e));
          }
        })();
    """.trimIndent()
}

/**
 * Decode a bridge JSON string into a [JSONObject], or `null` for a genuine null
 * result ([json] is `null` or the literal string `"null"`). Throws
 * [PlatformException] when the JS returned a non-object (a primitive/array that
 * won't parse as an object) — mirrors the iOS "non-object JS return" guard.
 */
internal fun decodeJsResult(json: String?): JSONObject? {
    if (json == null || json == "null") return null
    return runCatching { JSONObject(json) }.getOrNull()
        ?: throw PlatformException("non-object JS return: $json")
}

/** `FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)` — the full-bleed params
 *  used for every automation WebView / overlay / container. */
internal fun matchParent(): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

/** Process-wide cache of asset contents; automation assets are immutable and the
 *  same scripts are read across every runner/session, so we read each once. */
private val assetCache = ConcurrentHashMap<String, String>()

/**
 * Read a bundled automation asset as UTF-8 text, cached process-wide. Throws if the
 * asset is missing; callers that must degrade gracefully (e.g. injecting from a
 * WebViewClient callback) should wrap this in `runCatching`.
 */
internal fun Context.readAutomationAsset(path: String): String =
    assetCache.getOrPut(path) {
        assets.open(path).bufferedReader().use { it.readText() }
    }

/** Host of [url], or "" when it's null/unparseable. */
internal fun hostOf(url: String?): String =
    runCatching { URI(url).host ?: "" }.getOrDefault("")

/** The activity's root content [FrameLayout] (`android.R.id.content`) — where the
 *  automation WebViews attach themselves on top of the host UI. */
internal fun Activity.contentView(): FrameLayout =
    findViewById(android.R.id.content)
