package com.zerohash.sdk.oauth

import android.content.Context

/**
 * The custom-scheme URI the OAuth callback comes back on.
 *
 * Each host app receives it on `connectsdk-oauth://<applicationId>`: the
 * manifest declares that host with the `${applicationId}` placeholder, and the
 * SDK sends the same URI to the web layer, which passes it to the backend. So
 * when several apps on a device embed the SDK, the OS opens the app that
 * started the flow instead of showing an app chooser.
 *
 * Always build it with [buildForApp]. [WebViewActivity][com.zerohash.sdk.ui.WebViewActivity]
 * gives it to the config message and to [OAuthHandler]. The OS starts
 * [OAuthCallbackActivity][com.zerohash.sdk.ui.OAuthCallbackActivity] on its
 * own, possibly in a new process, so that activity builds it again from its
 * context.
 */
internal object OAuthCallbackUri {
    const val SCHEME = "connectsdk-oauth"

    /** Builds the app-scoped callback URI for the host app with [packageName]. */
    fun buildForApp(packageName: String): String = "$SCHEME://$packageName"

    /** Builds the app-scoped callback URI for the app that [context] belongs to. */
    fun buildForApp(context: Context): String = buildForApp(context.packageName)

    /**
     * Whether [scheme] and [host] point to [callbackUri].
     *
     * Case-insensitive: an applicationId can have uppercase letters, and a URL
     * parser between us and the backend can lowercase the host.
     */
    fun matches(scheme: String?, host: String?, callbackUri: String): Boolean =
        scheme.equals(SCHEME, ignoreCase = true) &&
            host != null &&
            buildForApp(host).equals(callbackUri, ignoreCase = true)
}
