package com.zerohash.sdk.internal

import java.net.URI
import java.net.URISyntaxException

/**
 * Validation for the partner app link that brings the user back from another
 * app, such as a wallet after a WalletConnect approval.
 */
internal object AppRedirect {
    /** Another app can only open an absolute URL: an App Link or a custom scheme. */
    fun isValid(url: String): Boolean {
        return try {
            !URI(url).scheme.isNullOrEmpty()
        } catch (e: URISyntaxException) {
            false
        }
    }

    /** @throws IllegalArgumentException when [url] is set but not an absolute URL. */
    fun requireValid(url: String?) {
        require(url == null || isValid(url)) {
            "appRedirectUrl must be an absolute URL with a scheme: $url"
        }
    }
}
