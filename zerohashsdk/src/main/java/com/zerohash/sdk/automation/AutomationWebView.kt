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
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
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
 * embedded-WebView UA (AUTH-3445). The setup script runs only on [allowedOrigins].
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.applyAutomationDefaults(allowedOrigins: Set<String>) {
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
    setupExecutionContext(allowedOrigins)
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
 * the setup runs. [allowedOrigins] must be exact origins, not wildcards: this
 * grants script injection.
 */
private fun WebView.setupExecutionContext(allowedOrigins: Set<String>) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        Log.i(TAG_WV, "DOCUMENT_START_SCRIPT unsupported; execution context not set up")
        return
    }
    val script = runCatching { context.readAutomationAsset(SETUP_SCRIPT_ASSET) }
        .getOrElse { Log.e(TAG_WV, "missing $SETUP_SCRIPT_ASSET", it); return }
    runCatching {
        WebViewCompat.addDocumentStartJavaScript(this, script, allowedOrigins)
    }.onFailure { Log.e(TAG_WV, "could not set up the execution context", it) }
}

private val callIdRandom = SecureRandom()

/**
 * A new 128-bit random call id, as 32 hex characters. A script that did not start
 * the call cannot guess it, so it cannot complete the call.
 */
internal fun newCallId(): String {
    val bytes = ByteArray(16)
    callIdRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** `scheme://host[:port]` of [url], or "" when it's null/unparseable. */
internal fun originOf(url: String?): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return ""
    val scheme = uri.scheme?.lowercase() ?: return ""
    val host = uri.host?.lowercase() ?: return ""
    if (uri.port == -1) {
        return "$scheme://$host"
    }
    return "$scheme://$host:${uri.port}"
}

internal enum class BridgeMessageKind { RESOLVE, REJECT, EVENTS }

/**
 * One message the [buildPromiseWrapper] wrapper posts. [payload] is the JSON
 * result for [BridgeMessageKind.RESOLVE], the error message for
 * [BridgeMessageKind.REJECT], and the telemetry drafts JSON for
 * [BridgeMessageKind.EVENTS].
 */
internal data class BridgeMessage(
    val callId: String,
    val kind: BridgeMessageKind,
    val payload: String?,
)

/**
 * Parse a posted bridge message, or null when it is not valid JSON, has no id,
 * or has an unknown kind.
 */
internal fun parseBridgeMessage(raw: String?): BridgeMessage? {
    val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    val callId = json.optString("id").ifEmpty { return null }
    val kind = when (json.optString("kind")) {
        "resolve" -> BridgeMessageKind.RESOLVE
        "reject" -> BridgeMessageKind.REJECT
        "events" -> BridgeMessageKind.EVENTS
        else -> return null
    }
    if (json.isNull("payload")) {
        return BridgeMessage(callId, kind, null)
    }
    return BridgeMessage(callId, kind, json.optString("payload"))
}

/**
 * Expose [objectName] to the main frame of [allowedOrigins] only, and deliver each
 * parsed message to [onMessage] on the UI thread.
 *
 * `addJavascriptInterface` injects into every frame, so a third-party iframe could
 * post a result. A web message listener exists only in frames on [allowedOrigins],
 * and the main-frame check also drops a same-origin iframe. Call it before
 * `loadUrl`: only documents created after the call get the object.
 */
internal fun WebView.addAutomationResultListener(
    objectName: String,
    allowedOrigins: Set<String>,
    onMessage: (BridgeMessage) -> Unit,
) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(this, objectName, allowedOrigins) { _, message, _, isMainFrame, _ ->
            if (!isMainFrame) return@addWebMessageListener
            parseBridgeMessage(message.data)?.let(onMessage)
        }
    } else {
        throw PlatformException("web message listener unsupported")
    }
}

/** Push telemetry drafts, or settle this call with the result in [message]. */
internal fun CompletableDeferred<JSONObject?>.applyBridgeMessage(message: BridgeMessage) {
    when (message.kind) {
        BridgeMessageKind.EVENTS -> TelemetryRouter.collector?.pushDraftsJson(message.payload)
        BridgeMessageKind.RESOLVE ->
            try {
                complete(decodeJsResult(message.payload))
            } catch (e: PlatformException) {
                completeExceptionally(e)
            }
        BridgeMessageKind.REJECT -> {
            if (message.payload == null) {
                completeExceptionally(PlatformException("JS rejected"))
            } else {
                completeExceptionally(PlatformException(message.payload))
            }
        }
    }
}

/**
 * Wrap an IIFE-expression [rawExpr] so the Promise it resolves to is delivered
 * back to native as a [BridgeMessage] posted to [bridge] (correlated by [callId]).
 * See [addAutomationResultListener].
 *
 * Android's `evaluateJavascript` does NOT await Promises (a Promise stringifies to
 * `{}`), so the injected wrapper posts a `resolve` or `reject` message when the
 * Promise settles. [prelude] (trusted setup that installs window globals) is injected
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
        """try { if (window.__zhTelemetry) __zhPost("events", JSON.stringify(window.__zhTelemetry.drain())); } catch (e) {}"""
    } else {
        ""
    }
    return """
        (function () {
          var __zhPost = function (kind, payload) {
            $bridge.postMessage(JSON.stringify({ id: "$callId", kind: kind, payload: payload }));
          };
          try {
            $install
            $prelude
            $argDecl
            Promise.resolve(($expr)).then(
              function (r) { $drain __zhPost("resolve", JSON.stringify(r === undefined ? null : r)); },
              function (e) { $drain __zhPost("reject", String((e && e.message) || e)); }
            );
          } catch (e) {
            __zhPost("reject", String((e && e.message) || e));
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
 * Navigation host allowlist for the automation WebViews. These carry the user's
 * live platform session and inject scripts that type the withdrawal destination
 * address, relay the 2FA OTP, and click "Send now", so they must only ever load
 * the platform's domains in the main frame — mirroring iOS `CoinbaseHostPolicy`
 * and the navigation-trust controls elsewhere in the SDK.
 *
 * True when [host] is one of [trustedDomains] or a subdomain of one.
 */
internal fun isTrustedHost(host: String?, trustedDomains: Set<String>): Boolean {
    if (host == null) {
        return false
    }
    val normalizedHost = host.lowercase()
    return trustedDomains.any { domain -> normalizedHost == domain || normalizedHost.endsWith(".$domain") }
}

/**
 * True when [host] is the identity/liveness provider Coinbase embeds for the
 * withdraw ID check (Onfido). The liveness selfie/camera runs in a cross-origin
 * `sdk.onfido.com` iframe, so its getUserMedia request arrives with the Onfido
 * origin — not Coinbase's. Kept SEPARATE from [isTrustedHost] on purpose:
 * navigation and script injection stay platform-only; this host is trusted for
 * camera/mic capture ALONE (see [isTrustedMediaCaptureHost]).
 */
internal fun isTrustedLivenessProviderHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "onfido.com" || h.endsWith(".onfido.com")
}

/**
 * Origin gate for camera/mic capture in the automation WebView. Broader than
 * [isTrustedHost] because the ID/liveness step captures inside the provider's
 * iframe ([isTrustedLivenessProviderHost]) rather than the platform's own frame.
 * Safe: the main frame is locked to [navigationDomains] ([blockOffPlatformNavigation]
 * + the evaluateAsync origin check), so any embedded capture iframe is one the
 * platform itself chose to load.
 */
internal fun isTrustedMediaCaptureHost(host: String?, navigationDomains: Set<String>): Boolean =
    isTrustedHost(host, navigationDomains) || isTrustedLivenessProviderHost(host)

/**
 * WebViewClient decision for whether to BLOCK a navigation to [url]: true means
 * "handled — don't load" (a main-frame navigation to a host outside
 * [navigationDomains]). Sub-frames ([isMainFrame] false, e.g. the Cloudflare
 * Turnstile widget) are third-party by design and never blocked. [tag] is the
 * caller's log tag.
 */
internal fun blockOffPlatformNavigation(
    url: String?,
    isMainFrame: Boolean,
    navigationDomains: Set<String>,
    tag: String,
): Boolean {
    if (!isMainFrame) return false
    val host = hostOf(url)
    if (isTrustedHost(host, navigationDomains)) return false
    Log.e(tag, "blocked off-platform navigation host=$host")
    return true
}

/** The activity's root content [FrameLayout] (`android.R.id.content`) — where the
 *  automation WebViews attach themselves on top of the host UI. */
internal fun Activity.contentView(): FrameLayout =
    findViewById(android.R.id.content)
