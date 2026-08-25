package com.zerohash.sdk.fund

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FundDepositEvent.fromJSON], which parses the `deposit-status`
 * bridge payload. The status and the account-matching validation both arrive
 * nested and `success` is derived rather than sent, so a silent shape change here
 * would hand hosts an empty event.
 *
 * Pure JVM (org.json test dep), no device.
 */
class FundDepositEventTest {

    private fun payload(
        statusValue: String,
        validationStatus: String? = null,
        validationReason: String? = null
    ) = JSONObject()
        .put("depositId", "dep-1")
        .put("assetId", "USDC")
        .put("networkId", "ethereum")
        .put("amount", "10")
        .put(
            "status",
            JSONObject()
                .put("value", statusValue)
                .put("details", "some detail")
                .put("occurredAt", "2026-08-19T00:00:00Z")
        )
        .apply {
            if (validationStatus != null) {
                put(
                    "accountMatchingValidation",
                    JSONObject().put("status", validationStatus).apply {
                        if (validationReason != null) put("reason", validationReason)
                    }
                )
            }
        }

    @Test
    fun flattensTheNestedStatusAndDerivesSuccess() {
        val event = FundDepositEvent.fromJSON(payload("PROCESSED"))

        assertEquals("dep-1", event.depositId)
        assertEquals("PROCESSED", event.status)
        assertEquals("some detail", event.statusDetails)
        assertEquals("2026-08-19T00:00:00Z", event.statusOccurredAt)
        assertEquals("USDC", event.assetId)
        assertEquals("ethereum", event.networkId)
        assertEquals("10", event.amount)
        assertTrue(event.success)
    }

    @Test
    fun isNotSuccessfulWhileNonTerminalOrFailed() {
        for (value in listOf("PENDING", "CONFIRMED", "FAILED", "ACCOUNT_VALIDATION_FAILED")) {
            assertFalse(value, FundDepositEvent.fromJSON(payload(value)).success)
        }
    }

    @Test
    fun keepsTheAccountMatchingReason() {
        val event = FundDepositEvent.fromJSON(payload("CONFIRMED", "INVALID", "name mismatch"))

        assertEquals("INVALID", event.accountMatchingStatus)
        assertEquals("name mismatch", event.accountMatchingReason)
    }

    @Test
    fun toleratesAnAbsentValidationAndAbsentData() {
        val withoutValidation = FundDepositEvent.fromJSON(payload("PROCESSED"))
        assertNull(withoutValidation.accountMatchingStatus)
        assertNull(withoutValidation.accountMatchingReason)

        val empty = FundDepositEvent.fromJSON(null)
        assertNull(empty.depositId)
        assertNull(empty.status)
        assertFalse(empty.success)
    }
}
