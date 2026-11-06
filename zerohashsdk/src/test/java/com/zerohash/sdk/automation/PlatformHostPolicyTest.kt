package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the automation navigation host allowlist ([isTrustedHost]),
 * mirroring iOS `CoinbaseHostPolicyTests`. Pure JVM, no device. (The
 * [blockOffPlatformNavigation] wrapper only adds a trivial main-frame branch +
 * Android logging on top of this predicate.)
 */
class PlatformHostPolicyTest {

    private val coinbaseDomains = setOf("coinbase.com")

    @Test
    fun trustsTheDomainAndSubdomainsCaseInsensitively() {
        assertTrue(isTrustedHost("coinbase.com", coinbaseDomains))
        assertTrue(isTrustedHost("www.coinbase.com", coinbaseDomains))
        assertTrue(isTrustedHost("login.coinbase.com", coinbaseDomains))
        assertTrue(isTrustedHost("WWW.COINBASE.COM", coinbaseDomains))
    }

    @Test
    fun rejectsUnrelatedAndLookalikeHosts() {
        assertFalse(isTrustedHost("evil.com", coinbaseDomains))
        assertFalse(isTrustedHost("challenges.cloudflare.com", coinbaseDomains))
        assertFalse(isTrustedHost("notcoinbase.com", coinbaseDomains))      // ends with "coinbase.com", not ".coinbase.com"
        assertFalse(isTrustedHost("coinbase.com.evil.com", coinbaseDomains)) // trusted string as a left label
        assertFalse(isTrustedHost("", coinbaseDomains))
        assertFalse(isTrustedHost(null, coinbaseDomains))
    }

    @Test
    fun trustsEachDomainOfAPlatformWithSeveralDomains() {
        val domains = setOf("kraken.com", "krakenfx.com")
        assertTrue(isTrustedHost("www.kraken.com", domains))
        assertTrue(isTrustedHost("auth.krakenfx.com", domains))
        assertFalse(isTrustedHost("www.coinbase.com", domains))
    }

    @Test
    fun trustsNothingForEmptyDomains() {
        assertFalse(isTrustedHost("www.coinbase.com", emptySet()))
    }
}
