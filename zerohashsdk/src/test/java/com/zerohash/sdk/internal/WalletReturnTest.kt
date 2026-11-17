package com.zerohash.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletReturnTest {

    private val evmHash = "0x9b1f6a4f0a9d7c4b2e8d1f3a5c7e9b0d2f4a6c8e0b1d3f5a7c9e1b3d5f7a9c1e"
    private val solanaSignature =
        "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW"
    private val redirect = "acmeapp://redirect"

    private fun returnUrl(query: String, base: String = redirect) = "$base?$query"

    @Test
    fun `reads the transaction id from the configured return url`() {
        val url = returnUrl("zh_wallet_return=sent&zh_tx=$evmHash")
        assertEquals(evmHash, WalletReturn.transactionId(url, redirect))
    }

    @Test
    fun `accepts a solana signature`() {
        val url = returnUrl("zh_wallet_return=sent&zh_tx=$solanaSignature")
        assertEquals(solanaSignature, WalletReturn.transactionId(url, redirect))
    }

    @Test
    fun `keeps the host app's own query and ignores case`() {
        val url = returnUrl("screen=wallet&zh_wallet_return=sent&zh_tx=$evmHash", base = "AcmeApp://Redirect")
        assertEquals(evmHash, WalletReturn.transactionId(url, redirect))
    }

    @Test
    fun `matches an https app link by path`() {
        val appLink = "https://acme.test/return"
        assertEquals(
            evmHash,
            WalletReturn.transactionId(returnUrl("zh_wallet_return=sent&zh_tx=$evmHash", "https://acme.test/return/"), appLink)
        )
        assertNull(
            WalletReturn.transactionId(returnUrl("zh_wallet_return=sent&zh_tx=$evmHash", "https://acme.test/other"), appLink)
        )
    }

    @Test
    fun `accepts the rare shorter solana signature`() {
        for (length in 86..88) assertTrue("$length", WalletReturn.isTransactionId(solanaSignature.take(length)))
        assertFalse(WalletReturn.isTransactionId(solanaSignature.take(85)))
    }

    @Test
    fun `compares the port`() {
        val appLink = "https://acme.test/return"
        val query = "zh_wallet_return=sent&zh_tx=$evmHash"
        assertNull(WalletReturn.transactionId(returnUrl(query, "https://acme.test:8443/return"), appLink))
        assertEquals(evmHash, WalletReturn.transactionId(returnUrl(query, "https://acme.test:8443/return"), "https://acme.test:8443/return"))
    }

    @Test
    fun `keeps the first transaction id when the query repeats it`() {
        val url = returnUrl("zh_wallet_return=sent&zh_tx=$evmHash&zh_tx=0x00")
        assertEquals(evmHash, WalletReturn.transactionId(url, redirect))
    }

    @Test
    fun `is null without a configured return url`() {
        assertNull(WalletReturn.transactionId(returnUrl("zh_wallet_return=sent&zh_tx=$evmHash"), null))
        assertNull(WalletReturn.transactionId(null, redirect))
    }

    @Test
    fun `is null for another scheme or host`() {
        assertNull(WalletReturn.transactionId(returnUrl("zh_wallet_return=sent&zh_tx=$evmHash", "otherapp://redirect"), redirect))
        assertNull(WalletReturn.transactionId(returnUrl("zh_wallet_return=sent&zh_tx=$evmHash", "acmeapp://elsewhere"), redirect))
    }

    @Test
    fun `is null without the sent marker`() {
        assertNull(WalletReturn.transactionId(returnUrl("zh_tx=$evmHash"), redirect))
        assertNull(WalletReturn.transactionId(returnUrl("zh_wallet_return=cancelled&zh_tx=$evmHash"), redirect))
        assertNull(WalletReturn.transactionId(redirect, redirect))
    }

    @Test
    fun `rejects anything that is not a transaction id`() {
        for (value in listOf("", "sent", "0x1234", evmHash + "ff", "javascript:alert(1)", solanaSignature.take(40))) {
            assertFalse(value, WalletReturn.isTransactionId(value))
            assertNull(value, WalletReturn.transactionId(returnUrl("zh_wallet_return=sent&zh_tx=$value"), redirect))
        }
    }

    @Test
    fun `is null for a url that does not parse`() {
        assertNull(WalletReturn.transactionId("acme app://redirect?zh_wallet_return=sent", redirect))
    }
}
