package com.zerohash.sdk.automation

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.app.Activity
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
 * Pinned WebView User-Agent for the Coinbase-scraping WebViews (AUTH-3445).
 *
 * The stock Android WebView UA advertises itself as an embedded WebView — it
 * carries the `; wv` token and `Version/4.0`, plus (on emulators/many devices) an
 * unusual model string — which Coinbase's anti-automation can flag, degrading
 * scraping reliability. We pin a real, current Chrome-on-Android UA so the
 * scraping WebView presents like a mainstream mobile browser instead.
 *
 * Tracks the current Chrome-for-Android stable major (150 as of this change, per
 * Google's version-history API). Chrome's UA reduction freezes the version to
 * `<major>.0.0.0`, so `Chrome/150.0.0.0` matches a genuine Chrome-Android string.
 * This intentionally advertises current-stable Chrome, which may run ahead of the
 * WebView actually rendering the page (e.g. an emulator's bundled 145). Bump the
 * Chrome major when stable moves materially; keep the `Mobile Safari/537.36`
 * suffix intact.
 */
private const val TAG_WV = "ZHAutomationWebView"

internal const val AUTOMATION_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

/**
 * Apply the standard automation WebView settings: JS/DOM storage on for the SPA,
 * mixed content blocked, and local file/content access denied as defense-in-depth
 * (those default to `true` below API 30, and minSdk is 21 — these WebViews only
 * ever load Coinbase HTTPS subdomains, so local-file access is never needed). Also
 * opts the process-wide [CookieManager] into first- and third-party cookies, which
 * is what ties login → status → balance together across the runners. Pins a real
 * Chrome-on-Android [AUTOMATION_USER_AGENT] so Coinbase doesn't flag the default
 * embedded-WebView UA (AUTH-3445).
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.applyAutomationDefaults() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Let the liveness camera preview's MediaStream <video> autoplay inline
        // (else it stays black). iOS parity: `mediaTypesRequiringUserActionForPlayback = []`.
        mediaPlaybackRequiresUserGesture = false
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        userAgentString = AUTOMATION_USER_AGENT
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    setupExecutionContext()
    enableInspectionWhenDebuggable()
}

/**
 * Expose the automation WebView to `chrome://inspect`, debuggable hosts only.
 *
 * This WebView holds a live Coinbase session, so inspection must never ship —
 * anything that can reach the device's ADB socket could read it. Gated on the
 * HOST's `applicationInfo` rather than the SDK module's `BuildConfig.DEBUG`, which
 * is whatever build type CI published.
 */
internal fun WebView.enableInspectionWhenDebuggable() {
    val debuggable =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (!debuggable) return
    WebView.setWebContentsDebuggingEnabled(true)
}

/** Exact origin, not a wildcard: this grants script injection. */
private const val COINBASE_WWW_ORIGIN = "https://www.coinbase.com"

private const val SETUP_SCRIPT_ASSET = "automation/setup-execution-context.js"

/**
 * Install `setup-execution-context.js` at document start, so it runs before
 * Coinbase's bundle. Anything the automation needs in place before the page
 * renders belongs in that script.
 *
 * Document start is the requirement, not a preference: Coinbase reads the
 * app-upsell flag while rendering, and `evaluateJavascript` from `onPageStarted`
 * can be dropped because the new document's JS context may not exist yet.
 *
 * Needs WebView 83+, and minSdk here is 21, so on an un-updated WebView none of
 * the setup runs.
 */
private fun WebView.setupExecutionContext() {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        Log.i(TAG_WV, "DOCUMENT_START_SCRIPT unsupported; execution context not set up")
        return
    }
    val script = runCatching { context.readAutomationAsset(SETUP_SCRIPT_ASSET) }
        .getOrElse { Log.e(TAG_WV, "missing $SETUP_SCRIPT_ASSET", it); return }
    runCatching {
        WebViewCompat.addDocumentStartJavaScript(this, script, setOf(COINBASE_WWW_ORIGIN))
    }.onFailure { Log.e(TAG_WV, "could not set up the execution context", it) }
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
 * SECURITY INVARIANT (CWE-94): [argDecl] and [prelude] are spliced VERBATIM into
 * the evaluated source — unlike iOS `WKWebView.callAsyncJavaScript`, which
 * marshals arguments out-of-band, Android has no such API and interpolates. So
 * the value in [argDecl] MUST be produced by `org.json` serialization
 * (`JSONObject`/`JSONArray.toString()`), whose escaping is what makes splicing
 * web-supplied data (getDepositAddress / withdraw payloads) safe. Callers get
 * this for free because [AutomationBridge] re-serializes every inbound payload
 * via `JSONObject(...).toString()` before it reaches a runner; do NOT pass a
 * hand-built or caller-formatted string here. [prelude] must stay trusted,
 * compile-time-constant script (never web-supplied).
 *
 * The trailing `;` (and whitespace) of [rawExpr] is stripped so an IIFE expression
 * sits cleanly inside `Promise.resolve((…))` (an unstripped `;` inside the parens
 * is a syntax error); a bare entry call is unaffected. Matches iOS, which trims the
 * same trailing chars before wrapping.
 */
/**
 * @param telemetryInstall the telemetry.js installer, or "" when off. Non-empty
 *  installs the buffer and drains its drafts to native on settle (never breaks it).
 */
internal fun buildPromiseWrapper(
    bridge: String,
    callId: String,
    prelude: String,
    argDecl: String,
    rawExpr: String,
    telemetryInstall: String = "",
): String {
    val expr = rawExpr.trimEnd(';', '\n', '\r', ' ', '\t')
    val on = telemetryInstall.isNotEmpty()
    val install = if (on) "$telemetryInstall\nwindow.__zhTelemetry.enable(true);" else ""
    val drain = if (on) {
        """try { if (window.__zhTelemetry && $bridge.onEvents) $bridge.onEvents("$callId", JSON.stringify(window.__zhTelemetry.drain())); } catch (e) {}"""
    } else {
        ""
    }
    return """
        (function () {
          try {
            $install
            $prelude
            $argDecl
            Promise.resolve(($expr)).then(
              function (r) { $drain $bridge.onResolve("$callId", JSON.stringify(r === undefined ? null : r)); },
              function (e) { $drain $bridge.onReject("$callId", String((e && e.message) || e)); }
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

/**
 * Navigation host allowlist for the Coinbase automation WebViews. These carry the
 * user's live Coinbase session and inject scripts that type the withdrawal
 * destination address, relay the 2FA OTP, and click "Send now", so they must only
 * ever load Coinbase-owned origins in the main frame — mirroring iOS
 * `CoinbaseHostPolicy` and the navigation-trust controls elsewhere in the SDK.
 *
 * True when [host] is `coinbase.com` or a subdomain of it (covers `www.`, `login.`).
 */
internal fun isTrustedCoinbaseHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "coinbase.com" || h.endsWith(".coinbase.com")
}

/**
 * True when [host] belongs to a supported exchange. The shared origin gate for
 * the automation WebViews. Only Coinbase today — add exchanges here as an OR so
 * every gate stays in sync.
 */
internal fun isTrustedExchangeHost(host: String?): Boolean =
    isTrustedCoinbaseHost(host)

/**
 * True when [host] is the identity/liveness provider Coinbase embeds for the
 * withdraw ID check (Onfido). The liveness selfie/camera runs in a cross-origin
 * `sdk.onfido.com` iframe, so its getUserMedia request arrives with the Onfido
 * origin — not Coinbase's. Kept SEPARATE from [isTrustedCoinbaseHost] on purpose:
 * navigation and script injection stay Coinbase-only; this host is trusted for
 * camera/mic capture ALONE (see [isTrustedMediaCaptureHost]).
 */
internal fun isTrustedLivenessProviderHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "onfido.com" || h.endsWith(".onfido.com")
}

/**
 * Origin gate for camera/mic capture in the automation WebView. Broader than
 * [isTrustedExchangeHost] because the ID/liveness step captures inside the
 * provider's iframe ([isTrustedLivenessProviderHost]) rather than Coinbase's own
 * frame. Safe: the main frame is locked to Coinbase (blockOffCoinbaseNavigation +
 * the evaluateAsync host check), so any embedded capture iframe is one Coinbase
 * itself chose to load.
 */
internal fun isTrustedMediaCaptureHost(host: String?): Boolean =
    isTrustedExchangeHost(host) || isTrustedLivenessProviderHost(host)

/**
 * WebViewClient decision for whether to BLOCK a navigation to [url]: true means
 * "handled — don't load" (a main-frame navigation to a non-Coinbase host).
 * Sub-frames ([isMainFrame] false, e.g. the Cloudflare Turnstile widget) are
 * third-party by design and never blocked. [tag] is the caller's log tag.
 */
internal fun blockOffCoinbaseNavigation(url: String?, isMainFrame: Boolean, tag: String): Boolean {
    if (!isMainFrame) return false
    val host = hostOf(url)
    if (isTrustedCoinbaseHost(host)) return false
    Log.e(tag, "blocked off-Coinbase navigation host=$host")
    return true
}

/** The activity's root content [FrameLayout] (`android.R.id.content`) — where the
 *  automation WebViews attach themselves on top of the host UI. */
internal fun Activity.contentView(): FrameLayout =
    findViewById(android.R.id.content)
