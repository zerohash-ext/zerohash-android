package com.zerohash.sdk.automation

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Provider-agnostic host for a WebView popup opened via `window.open` — the
 * Android counterpart of iOS `PopupWebViewController`.
 *
 * Coinbase (and, later, Kraken) drive "Sign in with Apple" through a
 * `window.open` popup on `appleid.apple.com` that posts its result back to the
 * opener. Android WebView only delivers that `window.open` if the parent enables
 * [WebSettings.setSupportMultipleWindows] and its [WebChromeClient] implements
 * `onCreateWindow`. This class services that callback: it hosts the popup in a
 * child [WebView] layered over [activity]'s content view and shares the
 * process-wide cookie jar so popup and opener stay in one session.
 *
 * Not tied to any provider — the opener page decides what `window.open`s; this
 * class only owns the popup WebView's lifecycle and presentation. One popup at a
 * time; a second open replaces the first. The popup is torn down when the
 * provider fires `window.close()` ([WebChromeClient.onCloseWindow]) or when the
 * hosting activity is destroyed ([dismiss]).
 */
internal class AuthPopupWindow(private val activity: Activity) {

    private var popup: WebView? = null

    /**
     * Service a parent `WebChromeClient.onCreateWindow`. Creates the child
     * WebView, presents it full-screen over the activity's content view, hands
     * it back through [resultMsg]'s [WebView.WebViewTransport], and returns true.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun onCreateWindow(resultMsg: Message): Boolean {
        dismiss() // one popup at a time

        val child = WebView(activity)
        child.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // The popup should not itself spawn further windows.
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }
        // Keep navigation inside the popup (don't escape to an external browser).
        child.webViewClient = WebViewClient()
        // appleid.apple.com is third-party relative to the opener; share cookies.
        CookieManager.getInstance().setAcceptThirdPartyCookies(child, true)
        child.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) {
                // Provider JS calls window.close() when auth finishes/cancels.
                dismiss()
            }
        }
        popup = child
        // Layered directly over the activity's content view — matching the
        // verified single-WebView presentation (no extra chrome).
        contentView().addView(
            child,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = child
        resultMsg.sendToTarget()
        return true
    }

    /** Tear down the popup. Idempotent; safe from any lifecycle exit. */
    fun dismiss() {
        val p = popup ?: return
        popup = null
        activity.runOnUiThread {
            runCatching {
                p.stopLoading()
                // Detach from the content view before destroying.
                (p.parent as? ViewGroup)?.removeView(p)
                p.destroy()
            }
        }
    }

    private fun contentView(): FrameLayout =
        activity.findViewById(android.R.id.content)
}
