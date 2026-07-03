package com.zerohash.sdk

/**
 * Allow-list of hosts the SDK is permitted to navigate to or load resources from.
 *
 * Integrators can supply their own list (e.g. fetched over the air) instead of
 * using the SDK default, by passing a [ZerohashAllowList] to
 * [ZerohashSDK.configureFund].
 *
 * A host matches an entry if it is **exactly equal** to the entry or if it ends
 * with `"." + entry`. This means `"connect.xyz"` covers `"sdk.connect.xyz"` and
 * `"sdk.sandbox.connect.xyz"` but NOT `"evilconnect.xyz"` or
 * `"connect.xyz.attacker.com"`.
 */
class ZerohashAllowList(val hosts: List<String>) {

    companion object {
        /**
         * Default allow-list shipped with the SDK.
         *
         * Covers the zerohash mobile web app host (`connect.xyz`), the zerohash
         * domains, and the Fund SDK CDN that the `#fund` route loads the web
         * component + iframe bundles from (`zerohash.eu` for EU-region JWTs).
         */
        @JvmField
        val DEFAULT = ZerohashAllowList(listOf("connect.xyz", "zerohash.com", "zerohash.eu", "0hash.com"))
    }

    /**
     * Returns `true` if [host] is permitted under this allow-list.
     */
    fun contains(host: String): Boolean {
        val lowered = host.lowercase()
        return hosts.any { entry ->
            val target = entry.lowercase()
            lowered == target || lowered.endsWith(".$target")
        }
    }
}
