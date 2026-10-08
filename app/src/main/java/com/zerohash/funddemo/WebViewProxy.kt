package com.zerohash.funddemo

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

/**
 * Debug builds only: pins the app's WebViews to the device's HTTP proxy.
 *
 * Why (AT-891): on BrowserStack real devices the SDK's WebView sometimes
 * resolves sdk-cdn.gating.0hash.com itself instead of going through the
 * device proxy that leads to the BrowserStack Local tunnel. The request never
 * reaches the tunnel and fails with net::ERR_NAME_NOT_RESOLVED, because that
 * host only exists in internal DNS — while the app's own (native) mint call,
 * made a second earlier, always goes through. It happens on a cold start,
 * when the WebView engine is created and loads its first page within ~80ms.
 * Setting the proxy explicitly at app start, long before the widget opens,
 * means the first request can't go out without it.
 *
 * A no-op in release builds and when the device has no proxy set. Logs what
 * it found under [TAG] either way.
 */
object WebViewProxy {
    private const val TAG = "WebViewProxy"

    fun applyDeviceProxy(context: Context) {
        if (!BuildConfig.DEBUG) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "PROXY_OVERRIDE not supported by this WebView; leaving proxy as is")
            return
        }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val proxy = cm?.defaultProxy
        val host = proxy?.host?.takeIf { it.isNotBlank() } ?: System.getProperty("http.proxyHost")
        val port = proxy?.port?.takeIf { it > 0 } ?: System.getProperty("http.proxyPort")?.toIntOrNull()
        val pac = proxy?.pacFileUrl?.toString()?.takeIf { it.isNotBlank() && it != "about:blank" }

        if (host.isNullOrBlank() || port == null) {
            Log.i(TAG, "no device HTTP proxy (pac=$pac); WebView proxy left as is")
            return
        }

        val rule = "$host:$port"
        val config = ProxyConfig.Builder()
            .addProxyRule(rule)
            // Keep local traffic local, as the system proxy would.
            .addBypassRule("localhost")
            .addBypassRule("127.0.0.1")
            .build()
        ProxyController.getInstance().setProxyOverride(config, { it.run() }) {
            Log.i(TAG, "WebView proxy override applied: $rule (pac=$pac)")
        }
        Log.i(TAG, "WebView proxy override requested: $rule (pac=$pac)")
    }
}
