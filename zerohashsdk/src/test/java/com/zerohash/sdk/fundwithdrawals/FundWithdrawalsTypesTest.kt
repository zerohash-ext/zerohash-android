package com.zerohash.sdk.fundwithdrawals

import com.zerohash.sdk.GenericEvent
import com.zerohash.sdk.ZerohashError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Fund Withdrawals bridge → callback layer: the payload
 * flattening in [FundWithdrawalsCompletedEvent.fromJSON] and the routing in
 * [FundWithdrawalsCallbackHandler].
 *
 * The handler is the seam where a bridge message becomes a host-visible
 * callback, so a mistake here means a completed withdrawal is reported wrong (or
 * not at all) with nothing else failing. Pure JVM (org.json test dep), no device.
 */
class FundWithdrawalsTypesTest {

    // ── FundWithdrawalsCompletedEvent.fromJSON ──────────────────────────────

    @Test
    fun fromJSON_flattensEveryField() {
        val payload = JSONObject()
            .put("externalAccountId", "ext-acct-123")
            .put("assetSymbol", "USDC")
            .put("amount", "10.00")

        val event = FundWithdrawalsCompletedEvent.fromJSON(payload)

        assertEquals("ext-acct-123", event.externalAccountId)
        assertEquals("USDC", event.assetSymbol)
        assertEquals("10.00", event.amount)
        assertSame("rawData should expose the untouched payload", payload, event.rawData)
    }

    @Test
    fun fromJSON_nullData_yieldsAllNullEvent() {
        val event = FundWithdrawalsCompletedEvent.fromJSON(null)

        assertNull(event.externalAccountId)
        assertNull(event.assetSymbol)
        assertNull(event.amount)
        assertNull(event.rawData)
    }

    @Test
    fun fromJSON_missingFields_areNull() {
        // A partial payload must not throw — only externalAccountId is present.
        val event = FundWithdrawalsCompletedEvent.fromJSON(
            JSONObject().put("externalAccountId", "ext-acct-123"),
        )

        assertEquals("ext-acct-123", event.externalAccountId)
        assertNull(event.assetSymbol)
        assertNull(event.amount)
    }

    @Test
    fun fromJSON_jsonNullField_becomesKotlinNull() {
        // JSONObject.NULL must map to null, not to the string "null" that
        // getString() would otherwise hand back.
        val event = FundWithdrawalsCompletedEvent.fromJSON(
            JSONObject()
                .put("externalAccountId", JSONObject.NULL)
                .put("assetSymbol", "USDC"),
        )

        assertNull(event.externalAccountId)
        assertEquals("USDC", event.assetSymbol)
    }

    @Test
    fun fromJSON_keepsUnknownKeysInRawData() {
        // Forward compatibility: a field the native type does not model yet must
        // still be reachable by the host.
        val event = FundWithdrawalsCompletedEvent.fromJSON(
            JSONObject().put("externalAccountId", "ext-1").put("futureField", "kept"),
        )

        assertEquals("kept", event.rawData?.optString("futureField"))
    }

    // ── FundWithdrawalsCallbackHandler routing ──────────────────────────────

    private class Recording : FundWithdrawalsCallbacks {
        var closeCount = 0
        val errors = mutableListOf<ZerohashError>()
        val events = mutableListOf<GenericEvent>()
        val completions = mutableListOf<FundWithdrawalsCompletedEvent>()

        override fun onClose() {
            closeCount++
        }

        override fun onError(error: ZerohashError) {
            errors.add(error)
        }

        override fun onEvent(event: GenericEvent) {
            events.add(event)
        }

        override fun onCompleted(event: FundWithdrawalsCompletedEvent) {
            completions.add(event)
        }
    }

    @Test
    fun handleFundWithdrawal_invokesOnFundWithdrawalCompletedWithParsedEvent() {
        val callbacks = Recording()

        FundWithdrawalsCallbackHandler(callbacks).handleFundWithdrawal(
            JSONObject().put("externalAccountId", "ext-1").put("amount", "5"),
        )

        assertEquals(1, callbacks.completions.size)
        assertEquals("ext-1", callbacks.completions.single().externalAccountId)
        assertEquals("5", callbacks.completions.single().amount)
        assertEquals("no error should be reported on success", 0, callbacks.errors.size)
    }

    @Test
    fun siblingChannels_areIgnored() {
        // Fund Withdrawals shares CallbackHandler with the other flows, so every
        // channel it does not implement must stay inert — otherwise another flow's
        // message would be misreported as a fund withdrawal. `transaction-failed`
        // matters most: this route never emits it, so it must not surface here.
        val callbacks = Recording()
        val handler = FundWithdrawalsCallbackHandler(callbacks)

        handler.handleCryptoWithdrawal(JSONObject().put("withdrawalRequestId", "wr-1"))
        handler.handleDeposit(JSONObject().put("transactionId", "tx-1"))
        handler.handleDepositStatus(JSONObject().put("depositId", "dep-1"))
        handler.handleTransactionFailed(JSONObject().put("withdrawalRequestId", "wr-2"))

        assertEquals(0, callbacks.completions.size)
        assertEquals(0, callbacks.errors.size)
        assertEquals(0, callbacks.events.size)
        assertEquals(0, callbacks.closeCount)
    }

    @Test
    fun handleError_mapsCodeToTypedError() {
        val callbacks = Recording()

        FundWithdrawalsCallbackHandler(callbacks)
            .handleError("network_error", "offline", null)

        assertEquals(1, callbacks.errors.size)
        assertTrue(
            "expected NetworkError, got ${callbacks.errors.single()}",
            callbacks.errors.single() is ZerohashError.NetworkError,
        )
        assertEquals("offline", callbacks.errors.single().message)
    }

    @Test
    fun handleEvent_forwardsTypeAndData() {
        val callbacks = Recording()
        val data = JSONObject().put("eventType", "FUND_WITHDRAWALS_APP_LOADED")

        FundWithdrawalsCallbackHandler(callbacks).handleEvent("app-loaded", data)

        assertEquals(1, callbacks.events.size)
        assertEquals("app-loaded", callbacks.events.single().type)
        assertSame(data, callbacks.events.single().data)
    }

    @Test
    fun handleClose_forwardsOnce() {
        val callbacks = Recording()

        FundWithdrawalsCallbackHandler(callbacks).handleClose()

        assertEquals(1, callbacks.closeCount)
    }
}
