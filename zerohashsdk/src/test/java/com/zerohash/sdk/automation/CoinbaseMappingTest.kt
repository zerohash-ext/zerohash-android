package com.zerohash.sdk.automation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [Coinbase]'s pure JSON mapping: [Coinbase.parseBalances] (the
 * get-balance.js response → [AssetBalance] rows) and [Coinbase.mapDepositResult]
 * (the get-deposit-address.js result → the normalized wire object). Both are the
 * kind of silently-breakable mapping AUTH-3443 calls out.
 *
 * Pure JVM (org.json test dep), no device — mirrors [OverlayOptionsTest].
 */
class CoinbaseMappingTest {

    // ── parseBalances ───────────────────────────────────────────────────────

    @Test
    fun parseBalances_mapsRowsFieldForField() {
        val obj = JSONObject().put(
            "balances",
            JSONArray().put(
                JSONObject()
                    .put("key", "BTC")
                    .put("label", "Bitcoin")
                    .put("amount", "1.5")
                    .put("notional", "90000")
                    .put("currency", "USD")
                    .put("totalStakedPercent", "10")
                    .put("precision", 8)
                    .put("extractedAt", "2024-01-01T00:00:00Z"),
            ),
        )
        val rows = Coinbase.parseBalances(obj)
        assertEquals(1, rows.size)
        val b = rows[0]
        assertEquals("BTC", b.key)
        assertEquals("Bitcoin", b.label)
        assertEquals("1.5", b.amount)
        assertEquals("90000", b.notional)
        assertEquals("USD", b.currency)
        assertEquals("10", b.totalStakedPercent)
        assertEquals(8, b.precision)
        assertEquals("2024-01-01T00:00:00Z", b.extractedAt)
    }

    @Test
    fun parseBalances_nullOptionalFields_becomeKotlinNull() {
        val obj = JSONObject().put(
            "balances",
            JSONArray().put(
                JSONObject()
                    .put("key", "ETH")
                    .put("label", "Ethereum")
                    .put("amount", "2")
                    .put("notional", "6000")
                    .put("currency", JSONObject.NULL)
                    .put("totalStakedPercent", JSONObject.NULL)
                    .put("precision", JSONObject.NULL)
                    .put("extractedAt", "2024-01-01T00:00:00Z"),
            ),
        )
        val b = Coinbase.parseBalances(obj).single()
        assertNull(b.currency)
        assertNull(b.totalStakedPercent)
        assertNull(b.precision)
    }

    @Test
    fun parseBalances_emptyArray_isEmptyList() {
        assertTrue(Coinbase.parseBalances(JSONObject().put("balances", JSONArray())).isEmpty())
    }

    @Test
    fun parseBalances_missingBalancesArray_throws() {
        try {
            Coinbase.parseBalances(JSONObject())
            fail("expected PlatformException for missing 'balances' array")
        } catch (e: PlatformException) {
            assertTrue(e.message!!.contains("balances"))
        }
    }

    // ── mapDepositResult ─────────────────────────────────────────────────────

    @Test
    fun mapDepositResult_passesThroughAndDefaults() {
        val raw = JSONObject()
            .put("address", "0xabc")
            .put("destinationTag", "tag1")
            .put("network", "ethereum")
            .put("asset", "ETH")
            .put("depositUri", "ethereum:0xabc")
        val out = Coinbase.mapDepositResult(raw, JSONObject().toString())
        assertEquals("0xabc", out.getString("address"))
        assertEquals("tag1", out.getString("destinationTag"))
        assertEquals("ethereum", out.getString("network"))
        assertEquals("ETH", out.getString("asset"))
        assertEquals("ethereum:0xabc", out.getString("depositUri"))
        assertEquals(0, out.getJSONArray("warnings").length())
    }

    @Test
    fun mapDepositResult_fallsBackToRequestedAssetAndNetwork() {
        // raw omits asset/network → the requested payload values fill them in.
        val raw = JSONObject().put("address", "0xabc")
        val payload = JSONObject().put("asset", "USDC").put("network", "base").toString()
        val out = Coinbase.mapDepositResult(raw, payload)
        assertEquals("USDC", out.getString("asset"))
        assertEquals("base", out.getString("network"))
        // Defaults for the fields neither side provided.
        assertEquals("", out.getString("destinationTag"))
        assertEquals("", out.getString("depositUri"))
    }

    @Test
    fun mapDepositResult_missingAddress_throws() {
        try {
            Coinbase.mapDepositResult(JSONObject(), JSONObject().toString())
            fail("expected PlatformException for missing address")
        } catch (e: PlatformException) {
            assertTrue(e.message!!.contains("address"))
        }
    }

    @Test
    fun mapDepositResult_passesAmountSubmittedThroughWhenPresent() {
        val raw = JSONObject()
            .put("address", "0xabc")
            .put("amountSubmitted", JSONObject().put("value", "5").put("currency", "USDC"))
        val out = Coinbase.mapDepositResult(raw, JSONObject().toString())
        assertTrue(out.has("amountSubmitted"))
        assertEquals("5", out.getJSONObject("amountSubmitted").getString("value"))
    }
}
