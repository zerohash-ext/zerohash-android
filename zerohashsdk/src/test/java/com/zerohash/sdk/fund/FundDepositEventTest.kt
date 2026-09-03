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

    /**
     * CONFIRMED belongs here, not with success: the shared integrations flow shows
     * its success screen only at PROCESSED. Auth on connect-android is the one that
     * also accepts CONFIRMED, because its own success rule is profile-gated.
     */
    @Test
    fun isNotSuccessfulWhileNonTerminalOrFailed() {
        for (value in listOf("PENDING", "CONFIRMED", "FAILED", "ACCOUNT_VALIDATION_FAILED")) {
            assertFalse(value, FundDepositEvent.fromJSON(payload(value)).success)
        }
    }

    /**
     * The web flow checks account matching before the status, so a deposit still
     * verifying shows the verifying screen even once the status reads PROCESSED.
     */
    @Test
    fun isNotSuccessfulWhileAccountMatchingIsPending() {
        assertFalse(FundDepositEvent.fromJSON(payload("PROCESSED", "PENDING")).success)
    }

    /** INVALID and ERROR both send the web flow to the deposit-failed screen. */
    @Test
    fun isNotSuccessfulWhenAccountMatchingRejects() {
        for (validation in listOf("INVALID", "ERROR")) {
            assertFalse(
                validation,
                FundDepositEvent.fromJSON(payload("PROCESSED", validation)).success
            )
        }
    }

    /**
     * VALID passes through, and so does any value we don't recognise — web falls
     * through to the status check rather than treating it as a failure.
     */
    @Test
    fun isSuccessfulWhenAccountMatchingDoesNotBlock() {
        for (validation in listOf("VALID", "SKIPPED")) {
            assertTrue(
                validation,
                FundDepositEvent.fromJSON(payload("PROCESSED", validation)).success
            )
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
