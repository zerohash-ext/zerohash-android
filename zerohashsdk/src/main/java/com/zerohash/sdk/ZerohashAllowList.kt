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
        val DEFAULT =
            ZerohashAllowList(
                listOf(
                    "connect.xyz",
                    "zerohash.com",
                    "zerohash.eu",
                    "0hash.com",
                    // Dynamic powers the self-custody wallet list and connect flow.
                    // List the specific hosts, never the apex: the dot-suffix match
                    // would otherwise trust every subdomain of a third-party apex
                    // (e.g. any *.ton.org) as a loadable frame origin inside the
                    // WebView that hosts the native bridge.
                    "app.dynamicauth.com",
                    "logs.dynamicauth.com",
                    "relay.dynamicauth.com",
                    "iconic.dynamic-static-assets.com",
                    "www.dynamic.xyz",
                    // TON Connect wallet registry, required for TON wallets.
                    "config.ton.org",
                ),
            )
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
