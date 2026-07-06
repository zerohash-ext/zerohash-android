package com.zerohash.sdk.automation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure, stateless decision logic the [AutomationBridge] relies
 * on: the withdraw-state terminality machine ([endsSession]), the error-retry
 * classifier ([isRetryable]), the coalescing policy ([isCoalescable]), and the
 * balance → wire serialization ([balancesToJson]). These are the pieces "most
 * likely to regress" called out in AUTH-3443.
 *
 * Pure JVM (org.json test dep), no device — mirrors [OverlayOptionsTest]. The
 * end-to-end async coalescing in [AutomationBridge.dispatch] (which needs a live
 * WebView/Activity + coroutine driver) is intentionally out of scope here; this
 * covers the coalescing *policy* seam ([isCoalescable]).
 */
class AutomationBridgeLogicTest {

    // ── endsSession: mirrors iOS WithdrawState.endsSession ──────────────────

    @Test
    fun submitted_isTerminal() {
        assertTrue(endsSession(JSONObject().put("state", "submitted")))
    }

    @Test
    fun rejected_isTerminal_exceptOtpRejection() {
        // A generic rejection ends the session…
        assertTrue(
            endsSession(JSONObject().put("state", "rejected").put("reason", "passkey_unsupported")),
        )
        // …but an OTP rejection is retriable, so the session stays open.
        assertFalse(
            endsSession(JSONObject().put("state", "rejected").put("reason", "otp_rejected")),
        )
        // A rejection with no reason is still terminal.
        assertTrue(endsSession(JSONObject().put("state", "rejected")))
    }

    @Test
    fun awaitingAndProcessing_areNonTerminal() {
        assertFalse(endsSession(JSONObject().put("state", "awaiting-input")))
        assertFalse(endsSession(JSONObject().put("state", "awaiting-user-action")))
        assertFalse(endsSession(JSONObject().put("state", "processing")))
    }

    @Test
    fun unknownOrMissingState_endsSession() {
        // An unrecognized/undecodable discriminant ends the session rather than
        // stranding a live one (iOS rejects an undecodable state outright).
        assertTrue(endsSession(JSONObject().put("state", "wat")))
        assertTrue(endsSession(JSONObject()))
    }

    // ── isRetryable: BALANCES_INDETERMINATE prefix, CHALLENGE_UNSOLVED exact ──

    @Test
    fun balancesIndeterminate_isRetryable_asPrefix() {
        assertTrue(isRetryable("BALANCES_INDETERMINATE"))
        assertTrue(isRetryable("BALANCES_INDETERMINATE: partial fold"))
    }

    @Test
    fun challengeUnsolved_isRetryable_onlyWhenExact() {
        assertTrue(isRetryable("CHALLENGE_UNSOLVED"))
        assertFalse(isRetryable("CHALLENGE_UNSOLVED but not really"))
    }

    @Test
    fun otherErrors_areNotRetryable() {
        assertFalse(isRetryable("not logged in"))
        assertFalse(isRetryable(""))
        assertFalse(isRetryable("CHALLENGE_PRESENT"))
    }

    // ── isCoalescable: only idempotent reads ────────────────────────────────

    @Test
    fun onlyAuthStatusAndGetBalance_areCoalescable() {
        assertTrue(isCoalescable("auth.status"))
        assertTrue(isCoalescable("getBalance"))
        assertFalse(isCoalescable("auth.login"))
        assertFalse(isCoalescable("getDepositAddress"))
        assertFalse(isCoalescable("withdraw.start"))
        assertFalse(isCoalescable("core.ping"))
    }

    // ── balancesToJson: field-for-field, nulls → JSON null ──────────────────

    @Test
    fun balancesToJson_serializesAllFields() {
        val arr = balancesToJson(
            listOf(
                AssetBalance(
                    key = "BTC",
                    label = "Bitcoin",
                    amount = "1.5",
                    notional = "90000",
                    currency = "USD",
                    totalStakedPercent = "10",
                    precision = 8,
                    extractedAt = "2024-01-01T00:00:00Z",
                ),
            ),
        )
        assertEquals(1, arr.length())
        val row = arr.getJSONObject(0)
        assertEquals("BTC", row.getString("key"))
        assertEquals("Bitcoin", row.getString("label"))
        assertEquals("1.5", row.getString("amount"))
        assertEquals("90000", row.getString("notional"))
        assertEquals("USD", row.getString("currency"))
        assertEquals("10", row.getString("totalStakedPercent"))
        assertEquals(8, row.getInt("precision"))
        assertEquals("2024-01-01T00:00:00Z", row.getString("extractedAt"))
    }

    @Test
    fun balancesToJson_nullableFields_becomeJsonNull() {
        val arr = balancesToJson(
            listOf(
                AssetBalance(
                    key = "ETH",
                    label = "Ethereum",
                    amount = "2",
                    notional = "6000",
                    currency = null,
                    totalStakedPercent = null,
                    precision = null,
                    extractedAt = "2024-01-01T00:00:00Z",
                ),
            ),
        )
        val row = arr.getJSONObject(0)
        assertTrue("currency should be JSON null", row.isNull("currency"))
        assertTrue("totalStakedPercent should be JSON null", row.isNull("totalStakedPercent"))
        assertTrue("precision should be JSON null", row.isNull("precision"))
    }

    @Test
    fun balancesToJson_emptyList_isEmptyArray() {
        assertEquals(0, balancesToJson(emptyList()).length())
    }
}
