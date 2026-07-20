package com.zerohash.sdk.automation

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager

/**
 * Best-effort cookie eviction for provider automation hosts.
 *
 * Provider WebViews share the process-wide Android [CookieManager]. The login
 * session set by the modal must be visible to the subsequent offscreen
 * `auth.status`/balance runs, so cookies persist through the workflow — but
 * they must not outlive it. [clearForHosts] is called at workflow teardown
 * ([AutomationBridge.dispose]) and on non-success login teardown
 * ([CoinbaseLoginActivity]) to evict cookies scoped to the given hosts without
 * touching cookies for other domains (the outer host WebView shares this jar).
 *
 * Enumerates cookies via [CookieManager.getCookie] and expires each on the
 * exact host and its eTLD+1, with and without leading dot, so cookies scoped
 * as `Domain=login.coinbase.com`, `Domain=coinbase.com`, and
 * `Domain=.coinbase.com` all clear. Deletion is best effort — unknown names
 * and already-expired cookies are silent no-ops. A final [CookieManager.flush]
 * forces persistence so nothing survives to a later cold start.
 */
internal object AutomationCookies {

    private const val TAG = "ZHAutomation"
    private const val EXPIRED = "Expires=Thu, 01 Jan 1970 00:00:00 GMT"

    /**
     * Expire every cookie visible under each URL in [hosts]. Each entry should
     * be a scheme+host URL (e.g. `"https://login.coinbase.com"`); the path is
     * ignored. Safe to call on any thread — [CookieManager] is thread-safe.
     */
    fun clearForHosts(hosts: Collection<String>) {
        if (hosts.isEmpty()) return
        val cookieManager = CookieManager.getInstance()
        var totalCleared = 0
        hosts.forEach { host ->
            val cookieHeader = cookieManager.getCookie(host) ?: return@forEach
            val hostname = Uri.parse(host).host ?: return@forEach
            val registrableDomain = eTldPlusOne(hostname)
            cookieHeader.split(";").forEach cookies@{ nameValuePair ->
                val cookieName = nameValuePair.substringBefore("=").trim()
                if (cookieName.isEmpty()) return@cookies
                cookieManager.setCookie(host, "$cookieName=; $EXPIRED; Path=/")
                cookieManager.setCookie(host, "$cookieName=; $EXPIRED; Path=/; Domain=$hostname")
                if (registrableDomain != hostname) {
                    cookieManager.setCookie(host, "$cookieName=; $EXPIRED; Path=/; Domain=$registrableDomain")
                    cookieManager.setCookie(host, "$cookieName=; $EXPIRED; Path=/; Domain=.$registrableDomain")
                }
                totalCleared++
            }
        }
        cookieManager.flush()
        Log.d(TAG, "cleared $totalCleared cookie(s) across ${hosts.size} host(s)")
    }

    /**
     * Approximate eTLD+1 by keeping the last two labels. Not a public-suffix
     * lookup — it treats `.co.uk` as `co.uk` — so only pass hosts that don't
     * live under a multi-label public suffix.
     */
    private fun eTldPlusOne(hostname: String): String {
        val labels = hostname.split('.')
        return if (labels.size >= 3) labels.takeLast(2).joinToString(".") else hostname
    }
}
