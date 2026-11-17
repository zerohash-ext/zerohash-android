package com.zerohash.sdk.internal

import java.net.URI
import java.net.URISyntaxException

/**
 * Validation for the partner app link that brings the user back from another
 * app, such as a wallet after a WalletConnect approval.
 */
internal object AppRedirect {
    /** The URL as a [URI] when it is absolute (an App Link or a custom scheme), else null. */
    fun parse(url: String): URI? = try {
        URI(url).takeIf { !it.scheme.isNullOrEmpty() }
    } catch (e: URISyntaxException) {
        null
    }

    /** Another app can only open an absolute URL: an App Link or a custom scheme. */
    fun isValid(url: String): Boolean = parse(url) != null

    /** @throws IllegalArgumentException when [url] is set but not an absolute URL. */
    fun requireValid(url: String?) {
        require(url == null || isValid(url)) {
            "appRedirectUrl must be an absolute URL with a scheme: $url"
        }
    }
}
