package com.zerohash.sdk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for the wallet book parsing in [WalletSchemeStore]. */
class WalletSchemeStoreTest {
    private val walletBook = """
        {"wallets": {
          "okx": {"mobile": {"native": "okxwallet://", "universal": "https://www.okx.com/download"}},
          "binance": {"mobile": {"native": "bnc://app.binance.com/cedefi/wc"}},
          "spaced": {"mobile": {"native": " payperless://"}},
          "upper": {"mobile": {"native": "MyWallet://wc"}},
          "dotted": {"mobile": {"native": "app.dosivault://"}},
          "evil": {"mobile": {"native": "javascript:alert(1)"}},
          "phone": {"mobile": {"native": "tel:+1"}},
          "https": {"mobile": {"native": "https://example.com"}},
          "nocolon": {"mobile": {"native": "broken"}},
          "nomobile": {"desktop": {"universal": "https://example.com"}}
        }}
    """.trimIndent()

    @Test
    fun parseExtractsNormalizedNativeSchemes() {
        assertEquals(setOf("okxwallet", "bnc", "payperless", "mywallet", "app.dosivault"), WalletSchemeStore.parse(walletBook))
    }

    @Test
    fun parseIgnoresMalformedPayloads() {
        assertEquals(emptySet<String>(), WalletSchemeStore.parse("not json"))
        assertEquals(emptySet<String>(), WalletSchemeStore.parse("""{"groups":{}}"""))
    }

    @Test
    fun schemeOfRejectsReservedAndMalformedValues() {
        assertNull(WalletSchemeStore.schemeOf("javascript:alert(1)"))
        assertNull(WalletSchemeStore.schemeOf("intent://scan"))
        assertNull(WalletSchemeStore.schemeOf("1wallet://"))
        assertNull(WalletSchemeStore.schemeOf("broken"))
        assertNull(WalletSchemeStore.schemeOf(""))
        assertEquals("robinhood-wallet", WalletSchemeStore.schemeOf("robinhood-wallet://wc"))
    }
}
