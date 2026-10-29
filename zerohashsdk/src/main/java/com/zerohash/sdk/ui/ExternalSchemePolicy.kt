package com.zerohash.sdk.ui

/**
 * URL schemes the integrations WebView may hand to the system: https/http plus
 * the custom schemes of the wallets in Dynamic's wallet book ([WalletSchemeStore]).
 */
internal object ExternalSchemePolicy {
    val STANDARD_SCHEMES = setOf("https", "http")

    // System and browser schemes a wallet book entry can never unlock.
    val RESERVED_SCHEMES = setOf(
        "http", "https", "ws", "wss", "ftp", "file", "data", "blob", "about", "javascript",
        "tel", "sms", "mailto", "facetime", "facetime-audio",
        "itms", "itms-apps", "itms-services", "app-prefs", "prefs", "shortcuts",
        "content", "intent",
    )

    fun isAllowed(scheme: String?, walletSchemes: Set<String> = WalletSchemeStore.schemes): Boolean {
        val normalized = scheme?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (normalized in STANDARD_SCHEMES) return true
        return normalized !in RESERVED_SCHEMES && normalized in walletSchemes
    }
}
