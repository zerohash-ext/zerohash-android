package com.zerohash.sdk.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.zerohash.sdk.BuildConfig
import com.zerohash.sdk.CallbackHandler
import com.zerohash.sdk.ZerohashAllowList
import com.zerohash.sdk.oauth.OAuthHandler
import com.zerohash.sdk.internal.Constants
import com.zerohash.sdk.internal.padForSystemBarsAndKeyboard
import com.zerohash.sdk.automation.AutomationBridge
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Main view controller managing the embedded WebView.
 *
 * Never exported. OAuth callbacks reach it via [OAuthCallbackActivity], which is
 * the sole exported surface in the SDK.
 */
class WebViewActivity : AppCompatActivity(),
    WebViewMessageHandler.Delegate,
    WebViewOAuthManager.Delegate {

    companion object {
        private const val TAG = "WebViewActivity"

        // Intent extras
        const val EXTRA_URL = "extra_url"
        const val EXTRA_JWT = "extra_jwt"
        const val EXTRA_ENVIRONMENT = "extra_environment"
        const val EXTRA_THEME = "extra_theme"
        const val EXTRA_SESSION_ID = "extra_session_id"
        /** Environment-specific web host (e.g. sdk.sandbox.connect.xyz). */
        const val EXTRA_WEB_HOST = "extra_web_host"
        /** Allow-listed hosts for navigation and resource filtering. */
        const val EXTRA_ALLOW_HOSTS = "extra_allow_hosts"

        // ConcurrentHashMap for thread-safe handler access. Entries are
        // timestamped so a registered handler that is never consumed (e.g.
        // activity-start failed silently, process killed before onCreate) is
        // evicted on the next setCallbackHandler call.
        private const val HANDLER_TTL_MS = 5L * 60L * 1000L

        // Grace period after resuming from an OAuth Custom Tab before treating a
        // still-pending flow as a user cancel. Lets a real redirect callback land first.
        private const val OAUTH_CANCEL_GRACE_MS = 500L

        // Reported to the host when the device's WebView is too old to enforce the
        // bridge's per-frame origin check. Maps to ZerohashError.WebViewError.
        private const val ERROR_CODE_WEBVIEW_UNSUPPORTED = "webview_unsupported"

        // Generic-event mirror of onLoaded, kept for hosts that read it off
        // onEvent. Same identifier zerohash-ios emits.
        private const val EVENT_APP_LOADED = "APP_LOADED"

        // Host-less schemes the page may legitimately load: each carries its
        // payload inline (bundled images, fonts, object URLs) or is the empty
        // document, and none reaches the network, so the host allow-list has
        // nothing to say about them.
        private val INERT_SCHEMES = setOf("data", "blob", "about")

        private data class HandlerEntry(
            val handler: CallbackHandler,
            val createdAt: Long
        )

        private val callbackHandlers = ConcurrentHashMap<String, HandlerEntry>()

        internal fun setCallbackHandler(sessionId: String, handler: CallbackHandler) {
            evictStale()
            callbackHandlers[sessionId] = HandlerEntry(handler, System.currentTimeMillis())
        }

        private fun getCallbackHandler(sessionId: String): CallbackHandler? {
            return callbackHandlers.remove(sessionId)?.handler
        }

        internal fun removeCallbackHandler(sessionId: String) {
            callbackHandlers.remove(sessionId)
        }

        private fun evictStale() {
            val cutoff = System.currentTimeMillis() - HANDLER_TTL_MS
            callbackHandlers.entries.removeAll { it.value.createdAt < cutoff }
        }
    }

    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private lateinit var messageHandler: WebViewMessageHandler
    private lateinit var oauthManager: WebViewOAuthManager
    private lateinit var oauthHandler: OAuthHandler

    private var isDarkMode = false
    private var callbackHandler: CallbackHandler? = null
    private var sessionId: String? = null
    private var allowList: ZerohashAllowList = ZerohashAllowList.DEFAULT
    private var automationBridge: AutomationBridge? = null

    /**
     * Set once the web app surfaces a terminal error (see [onTerminalError]).
     * Keeps [onResume] from un-pausing a WebView we deliberately halted, so the
     * static error screen never resumes its GPU-pegging repaint.
     */
    private var renderingHaltedForError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) Log.d(TAG, "onCreate called")

        try {
            val url = intent.getStringExtra(EXTRA_URL) ?: run {
                Log.e(TAG, "URL is required")
                finish()
                return
            }

            if (BuildConfig.DEBUG) Log.d(TAG, "URL: $url")

            val jwt = intent.getStringExtra(EXTRA_JWT) ?: run {
                Log.e(TAG, "JWT is required")
                finish()
                return
            }

            val environment = intent.getStringExtra(EXTRA_ENVIRONMENT) ?: "production"
            val theme = intent.getStringExtra(EXTRA_THEME) ?: "auto"
            val sid = intent.getStringExtra(EXTRA_SESSION_ID) ?: run {
                Log.e(TAG, "Session ID is required")
                finish()
                return
            }
            sessionId = sid

            callbackHandler = getCallbackHandler(sid) ?: run {
                Log.e(TAG, "Callback handler not found for session: $sid")
                finish()
                return
            }

            // The bridge requires per-frame origin filtering. Without it the only
            // alternative is `addJavascriptInterface`, which injects into every
            // frame and can be validated no more precisely than "the top frame is
            // ours" — no use when an allow-listed third-party subframe is the
            // thing we're guarding against. This bridge drives credential
            // automation and the withdraw flow, so refuse rather than downgrade.
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                Log.e(TAG, "WEB_MESSAGE_LISTENER unsupported; refusing to start the session")
                callbackHandler?.handleError(
                    ERROR_CODE_WEBVIEW_UNSUPPORTED,
                    "This device's Android System WebView is too old to run the zerohash SDK. " +
                        "Please update it from the Play Store and try again.",
                    null
                )
                // finish() lands in onDestroy, which does not notify the host, and
                // onClose is what the documented host pattern uses to drop its
                // session reference (`fundSession = null`). Without this the host
                // holds a session that never closes and present() refuses forever.
                callbackHandler?.handleClose()
                finish()
                return
            }

            isDarkMode = shouldUseDarkMode(theme)
            if (BuildConfig.DEBUG) Log.d(TAG, "Dark mode: $isDarkMode")

            // Read allow-list and web host forwarded by the session.
            val allowHosts = intent.getStringArrayListExtra(EXTRA_ALLOW_HOSTS)
            if (!allowHosts.isNullOrEmpty()) {
                allowList = ZerohashAllowList(allowHosts)
            }
            val webHost = intent.getStringExtra(EXTRA_WEB_HOST) ?: "sdk-cdn.zerohash.com"

            setupUI(url, jwt, environment, theme, webHost)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            // Same reasoning as the unsupported-WebView bail above: if we got far
            // enough to resolve the handler, the host has to be told the flow is
            // gone or it can never present again. No-op when we failed earlier.
            callbackHandler?.handleClose()
            finish()
        }
    }

    private fun setupUI(url: String, jwt: String, environment: String, theme: String, webHost: String) {
        try {
            container = FrameLayout(this)
            container.setBackgroundColor(
                if (isDarkMode) Constants.COLOR_DARK_BACKGROUND else Color.WHITE
            )
            setContentView(container)

            configureStatusBar()
            container.padForSystemBarsAndKeyboard(TAG)

            oauthHandler = OAuthHandler(this)

            webView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(if (isDarkMode) Constants.COLOR_DARK_BACKGROUND else Color.WHITE)
                visibility = View.VISIBLE
            }

            messageHandler = WebViewMessageHandler(
                webView = webView,
                jwt = jwt,
                environment = environment,
                theme = theme,
                callbackHandler = callbackHandler!!,
                allowedHost = webHost
            ).apply {
                delegate = this@WebViewActivity
            }

            configureWebView()

            oauthManager = WebViewOAuthManager(
                activity = this,
                oauthHandler = oauthHandler
            ).apply {
                delegate = this@WebViewActivity
            }

            container.addView(webView)

            if (BuildConfig.DEBUG) Log.d(TAG, "Loading URL")
            webView.loadUrl(url)
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupUI", e)
            throw e
        }
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Hardening: these defaults differ across API levels — notably
            // allowUniversalAccessFromFileURLs defaults to TRUE on pre-API-30
            // devices, which is dangerous. Set explicitly.
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            saveFormData = false
        }

        // Per-frame origin filtering via WebMessageListener — a cross-origin frame
        // inside the loaded page cannot reach the bridge. This is the only bridge
        // path; onCreate refuses the session when the feature is unavailable, so
        // there is no `addJavascriptInterface` fallback to inject into every frame.
        val allowedOrigins = setOf(messageHandler.targetOrigin)
        WebViewCompat.addWebMessageListener(
            webView,
            WebViewMessageHandler.INTERFACE_NAME,
            allowedOrigins
        ) { _, message, _, _, _ ->
            // No isMainFrame gate: the fund app runs inside the `fund-iframe`
            // subframe, and the scraping bridge posts to NativeAndroid from
            // there. `allowedOrigins` already restricts the listener to the
            // trusted host, so a same-origin subframe is safe; a cross-origin
            // frame never reaches this callback.
            messageHandler.handleVerifiedMessage(message.data ?: return@addWebMessageListener)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?
            ): Boolean {
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (BuildConfig.DEBUG) Log.d(TAG, "Page loaded")
            }

            /**
             * Network-level allow-list enforcement. Blocks every sub-resource
             * request (scripts, XHR, fetch, images, WebSockets) whose host is
             * not in [allowList]. Top-level navigation is already blocked by
             * [shouldOverrideUrlLoading] above, so this catches programmatic
             * resource loads initiated by the page JS.
             *
             * Host-less URLs are judged by scheme instead: [INERT_SCHEMES] carry
             * their payload inline and reach no network, so the app's own inline
             * assets keep working, while anything else without a host is blocked
             * rather than waved through for lack of a host to check.
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val url = request?.url
                val host = url?.host
                val blocked = if (host == null) {
                    url?.scheme?.lowercase() !in INERT_SCHEMES
                } else {
                    !allowList.contains(host)
                }
                if (blocked) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Blocked resource load: $url")
                    return android.webkit.WebResourceResponse(
                        "text/plain", "UTF-8",
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    private fun configureStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                if (isDarkMode) {
                    controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )
                } else {
                    controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (isDarkMode) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }

        window.statusBarColor = if (isDarkMode) {
            Constants.COLOR_DARK_BACKGROUND
        } else {
            Color.WHITE
        }
    }

    private fun shouldUseDarkMode(theme: String): Boolean {
        return when (theme) {
            "dark" -> true
            "light" -> false
            // "auto" (and legacy "system") follow the OS night mode.
            "auto", "system" -> {
                val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMode == Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }
    }

    // WebViewMessageHandler.Delegate implementation

    override fun onContentReady() {
        if (BuildConfig.DEBUG) Log.d(TAG, "Content ready")
        callbackHandler?.handleLoaded()
        // Also emitted as a generic event, matching zerohash-ios: hosts that read
        // APP_LOADED off onEvent before onLoaded existed keep working, and the two
        // platforms report the same thing.
        callbackHandler?.handleEvent(EVENT_APP_LOADED, null)
    }

    override fun onNavigate(url: String, mobileTarget: String?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Navigate requested, target: $mobileTarget")

        when (mobileTarget) {
            "in_app" -> oauthManager.handleNavigation(url, "external")
            "oauth", "external" -> oauthManager.handleNavigation(url, mobileTarget)
            else -> oauthManager.handleNavigation(url, "external")
        }
    }

    override fun onSessionClose() {
        if (BuildConfig.DEBUG) Log.d(TAG, "Session closed")
        finish()
    }

    override fun onTerminalError() {
        if (BuildConfig.DEBUG) Log.d(TAG, "Terminal error surfaced; halting WebView rendering")
        if (::webView.isInitialized) {
            renderingHaltedForError = true
            // onPause() pauses animations (stops the endless repaint of the
            // error screen) but does NOT pause JavaScript — the close button and
            // the bridge keep working. Per-instance, so any concurrent
            // automation WebView is unaffected (pauseTimers() would not be).
            webView.onPause()
        }
    }

    override fun onAutomationRequest(request: JSONObject) {
        // The offscreen status/balance WebViews attach to this activity's content
        // (1x1, behind the UI WebView); login presents its own modal activity.
        val bridge = automationBridge ?: AutomationBridge(
            activity = this,
            webView = webView,
            targetOrigin = messageHandler.targetOrigin,
        ).also { automationBridge = it }
        bridge.handle(request)
    }

    // WebViewOAuthManager.Delegate implementation

    override fun onOAuthSuccess(connectionId: String?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "OAuth success received")
        messageHandler.sendOAuthSuccess(connectionId)
    }

    override fun onOAuthError(error: String) {
        Log.e(TAG, "OAuth error: $error")
        messageHandler.sendOAuthError(error)
    }

    override fun onOAuthCancel() {
        if (BuildConfig.DEBUG) Log.d(TAG, "OAuth cancelled")
        messageHandler.sendOAuthError("User cancelled")
    }

    // Activity lifecycle

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (oauthManager.handleOAuthCallback(intent)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "OAuth callback handled")
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop the WebView's animations/rendering while backgrounded (e.g. during
        // Chrome Custom Tabs OAuth). Per-instance onPause() — NOT the
        // process-global pauseTimers() — so a Coinbase automation WebView running
        // in another activity keeps its JS timers alive.
        if (::webView.isInitialized) {
            webView.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        // Don't un-pause a WebView we halted for a terminal error — the error
        // screen is static and would just peg the GPU again on its animation.
        if (::webView.isInitialized && !renderingHaltedForError) {
            webView.onResume()
        }
        // Chrome Custom Tabs give no "dismissed" callback. If we resume with an
        // OAuth flow still pending, the user backed out of the tab without
        // completing it — tell the web SDK so its waitForConnectionId resolves
        // and the "Continue" spinner resets (otherwise it hangs). The short delay
        // lets a real redirect callback (onNewIntent → handleCallback) win the
        // race and clear the pending flow first.
        if (::webView.isInitialized && oauthManager.hasPendingOAuth) {
            webView.postDelayed({
                if (oauthManager.hasPendingOAuth) {
                    oauthManager.cancelPendingOAuth()
                }
            }, OAUTH_CANCEL_GRACE_MS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            automationBridge?.dispose()
            if (::oauthHandler.isInitialized) {
                oauthHandler.clear()
            }
            if (::webView.isInitialized) {
                try {
                    webView.clearCache(true)
                } catch (e: Exception) {
                    Log.w(TAG, "Error clearing WebView cache", e)
                }
                try {
                    webView.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying WebView", e)
                }
            }
        } finally {
            sessionId?.let { removeCallbackHandler(it) }
        }
    }

    override fun onBackPressed() {
        callbackHandler?.handleClose()
        super.onBackPressed()
    }
}
