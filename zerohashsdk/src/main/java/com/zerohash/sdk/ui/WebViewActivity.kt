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

            // Pre-seed the cached URL so the legacy @JavascriptInterface origin
            // check passes for messages that arrive during initial load.
            messageHandler.onPageLoaded(url)

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

        // Per-frame origin filtering via WebMessageListener when supported — a
        // cross-origin frame inside the loaded page cannot reach the bridge.
        // Fall back to @JavascriptInterface (top-frame URL check) otherwise.
        val allowedOrigins = setOf(messageHandler.targetOrigin)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
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
        } else {
            Log.w(
                TAG,
                "WebMessageListener unsupported on this WebView build; falling back to " +
                    "@JavascriptInterface with top-frame origin check"
            )
            webView.addJavascriptInterface(messageHandler, WebViewMessageHandler.INTERFACE_NAME)
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
                messageHandler.onPageLoaded(url)
                if (BuildConfig.DEBUG) Log.d(TAG, "Page loaded")
            }

            /**
             * Network-level allow-list enforcement. Blocks every sub-resource
             * request (scripts, XHR, fetch, images, WebSockets) whose host is
             * not in [allowList]. Top-level navigation is already blocked by
             * [shouldOverrideUrlLoading] above, so this catches programmatic
             * resource loads initiated by the page JS.
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val host = request?.url?.host
                if (host != null && !allowList.contains(host)) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Blocked resource load: $host")
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
