package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the pinned Coinbase-scraping User-Agent [AUTOMATION_USER_AGENT]
 * (AUTH-3445). The point of pinning is to present as a real Chrome-on-Android
 * browser, NOT the stock embedded-WebView UA that Coinbase's anti-automation can
 * flag — so these assertions fail loudly if a future edit reintroduces the
 * embedded-WebView tells (`; wv`, `Version/4.0`) or breaks the Chrome shape.
 *
 * Pure JVM: `const val` inlines at compile time, so no Android classes load.
 */
class AutomationUserAgentTest {

    @Test
    fun looksLikeRealChromeAndroid() {
        val ua = AUTOMATION_USER_AGENT
        assertTrue("UA must start with Mozilla/5.0", ua.startsWith("Mozilla/5.0"))
        assertTrue("UA must identify as Chrome", ua.contains("Chrome/"))
        assertTrue("UA must be an Android Linux UA", ua.contains("Linux; Android"))
        assertTrue(
            "UA must end with the mobile Safari suffix",
            ua.endsWith("Mobile Safari/537.36"),
        )
    }

    @Test
    fun doesNotAdvertiseEmbeddedWebView() {
        val ua = AUTOMATION_USER_AGENT
        // The two stock-WebView tells Coinbase can key off of.
        assertFalse("UA must not carry the '; wv' WebView marker", ua.contains("; wv"))
        assertFalse("UA must not carry the WebView 'Version/4.0' token", ua.contains("Version/4.0"))
    }

    @Test
    fun usesFrozenChromeVersionForm() {
        // Chrome's UA reduction reports the version as <major>.0.0.0; a pinned UA
        // that doesn't follow that form would stand out as synthetic.
        assertTrue(
            "Chrome token must use the reduced <major>.0.0.0 form",
            Regex("""Chrome/\d+\.0\.0\.0""").containsMatchIn(AUTOMATION_USER_AGENT),
        )
    }
}
