package com.zerohash.sdk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ExternalSchemePolicy] and the [WebViewOAuthManager.isAllowedUrl]
 * helper built on it, mirroring iOS `ExternalSchemePolicyTests`. Pure JVM.
 */
class ExternalSchemePolicyTest {

    @Test
    fun allowsStandardSchemes() {
        for (scheme in listOf("https", "http")) {
            assertTrue(scheme, ExternalSchemePolicy.isAllowed(scheme, emptySet()))
        }
    }

    @Test
    fun snapshotCoversWalletsWithoutUniversalLink() {
        for (scheme in listOf("imtokenv2", "okxwallet", "tpoutside", "tangem", "krakenwallet", "keplrwallet", "rabby", "robinhood-wallet", "argent")) {
            assertTrue(scheme, scheme in WalletSchemeSnapshot.SCHEMES)
            assertTrue(scheme, ExternalSchemePolicy.isAllowed(scheme))
        }
    }

    @Test
    fun allowsSchemesFromTheWalletBook() {
        assertTrue(ExternalSchemePolicy.isAllowed("newwallet", setOf("newwallet")))
        assertFalse(ExternalSchemePolicy.isAllowed("newwallet", emptySet()))
    }

    @Test
    fun reservedSchemesStayBlockedEvenWhenListed() {
        for (scheme in listOf("javascript", "file", "intent", "content", "tel", "sms", "shortcuts")) {
            assertFalse(scheme, ExternalSchemePolicy.isAllowed(scheme, setOf(scheme)))
        }
    }

    @Test
    fun isCaseInsensitive() {
        assertTrue(ExternalSchemePolicy.isAllowed("OKXWallet"))
        assertTrue(ExternalSchemePolicy.isAllowed("HTTPS"))
    }

    @Test
    fun rejectsUnknownSchemes() {
        assertFalse(ExternalSchemePolicy.isAllowed("javascript"))
        assertFalse(ExternalSchemePolicy.isAllowed("file"))
        assertFalse(ExternalSchemePolicy.isAllowed("intent"))
        assertFalse(ExternalSchemePolicy.isAllowed("okxwallet.evil"))
        assertFalse(ExternalSchemePolicy.isAllowed(""))
        assertFalse(ExternalSchemePolicy.isAllowed(null))
    }

    @Test
    fun isAllowedUrlFollowsThePolicy() {
        assertTrue(WebViewOAuthManager.isAllowedUrl("https://metamask.app.link/wc?uri=wc%3Aabc"))
        assertTrue(WebViewOAuthManager.isAllowedUrl("okxwallet://wc?uri=wc%3Aabc"))
        assertFalse(WebViewOAuthManager.isAllowedUrl("javascript:alert(1)"))
        assertFalse(WebViewOAuthManager.isAllowedUrl("not a url"))
        assertFalse(WebViewOAuthManager.isAllowedUrl(""))
    }
}
