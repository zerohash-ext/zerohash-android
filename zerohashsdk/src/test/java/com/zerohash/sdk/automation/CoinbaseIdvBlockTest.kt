package com.zerohash.sdk.automation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinbaseIdvBlockTest {

    @Test
    fun stillThrowsOnAScrapeWithNoAddressAndNoState() {
        val raw = JSONObject().put("destinationTag", "")
        val err = runCatching { Coinbase.mapDepositResult(raw, "{}") }.exceptionOrNull()
        assertTrue("a genuinely broken scrape must keep failing loudly", err is PlatformException)
    }

    @Test
    fun stillNormalizesASuccess() {
        val raw = JSONObject().put("address", "0xabc")
        val mapped = Coinbase.mapDepositResult(raw, """{"asset":"USDC","network":"base"}""")
        assertEquals("0xabc", mapped.optString("address"))
        assertEquals("base", mapped.optString("network"))
        assertEquals("USDC", mapped.optString("asset"))
    }

    @Test
    fun neitherIdvErrorCodeIsAutoRetried() {
        for (code in listOf("IDV_PENDING", "IDV_FAILED")) {
            assertFalse(
                "$code is terminal at the exchange, so the host must not silently re-issue",
                isRetryable(code),
            )
        }
    }

    @Test
    fun bothReasonsEndTheWithdrawSession() {
        for (reason in listOf("idv_pending", "idv_failed")) {
            val state = JSONObject().put("state", "rejected").put("reason", reason)
            assertTrue(
                "$reason must end the session so the bridge dismisses the WebView and mints no sessionId",
                endsSession(state),
            )
        }
    }
}
