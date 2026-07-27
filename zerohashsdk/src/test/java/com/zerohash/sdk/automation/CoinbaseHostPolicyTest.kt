package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Coinbase automation navigation host allowlist
 * ([isTrustedCoinbaseHost]), mirroring iOS `CoinbaseHostPolicyTests`. Pure JVM,
 * no device. (The [blockOffCoinbaseNavigation] wrapper only adds a trivial
 * main-frame branch + Android logging on top of this predicate.)
 */
class CoinbaseHostPolicyTest {

    @Test
    fun trustsCoinbaseAndSubdomainsCaseInsensitively() {
        assertTrue(isTrustedCoinbaseHost("coinbase.com"))
        assertTrue(isTrustedCoinbaseHost("www.coinbase.com"))
        assertTrue(isTrustedCoinbaseHost("login.coinbase.com"))
        assertTrue(isTrustedCoinbaseHost("WWW.COINBASE.COM"))
    }

    @Test
    fun rejectsUnrelatedAndLookalikeHosts() {
        assertFalse(isTrustedCoinbaseHost("evil.com"))
        assertFalse(isTrustedCoinbaseHost("challenges.cloudflare.com"))
        assertFalse(isTrustedCoinbaseHost("notcoinbase.com"))      // ends with "coinbase.com", not ".coinbase.com"
        assertFalse(isTrustedCoinbaseHost("coinbase.com.evil.com")) // trusted string as a left label
        assertFalse(isTrustedCoinbaseHost(""))
        assertFalse(isTrustedCoinbaseHost(null))
    }
}
