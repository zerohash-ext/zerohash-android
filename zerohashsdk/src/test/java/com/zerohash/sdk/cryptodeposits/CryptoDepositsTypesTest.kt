package com.zerohash.sdk.cryptodeposits

import com.zerohash.sdk.GenericEvent
import com.zerohash.sdk.IntegrationsDepositEvent
import com.zerohash.sdk.ZerohashError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Crypto Deposits bridge → callback layer: the payload
 * flattening in [CryptoDepositsCompletedEvent.fromJSON] and the routing in
 * [CryptoDepositsCallbackHandler].
 *
 * The handler is the seam where a bridge message becomes a host-visible
 * callback. This flow has two funding paths that report on different callbacks,
 * so a mistake here silently sends a deposit to the wrong one — or nowhere —
 * with nothing else failing. Pure JVM (org.json test dep), no device.
 */
class CryptoDepositsTypesTest {

    // ── CryptoDepositsCompletedEvent.fromJSON ───────────────────────────────

    @Test
    fun fromJSON_flattensEveryField() {
        val payload = JSONObject()
            .put("depositId", "dep-123")
            .put("assetSymbol", "USDC")
            .put("network", "ethereum")
            .put("amount", "25.00")

        val event = CryptoDepositsCompletedEvent.fromJSON(payload)

        assertEquals("dep-123", event.depositId)
        assertEquals("USDC", event.assetSymbol)
        assertEquals("ethereum", event.network)
        assertEquals("25.00", event.amount)
        assertSame("rawData should expose the untouched payload", payload, event.rawData)
    }

    @Test
    fun fromJSON_toleratesMissingData() {
        val event = CryptoDepositsCompletedEvent.fromJSON(null)

        assertNull(event.depositId)
        assertNull(event.assetSymbol)
        assertNull(event.network)
        assertNull(event.amount)
        assertNull(event.rawData)
    }

    /** A JSON null must read as absent, not as the string "null". */
    @Test
    fun fromJSON_treatsJsonNullAsAbsent() {
        val payload = JSONObject().put("depositId", JSONObject.NULL)

        assertNull(CryptoDepositsCompletedEvent.fromJSON(payload).depositId)
    }

    // ── CryptoDepositsCallbackHandler routing ───────────────────────────────

    private class RecordingCallbacks : CryptoDepositsCallbacks {
        var closed = false
        var error: ZerohashError? = null
        var event: GenericEvent? = null
        var loaded = false
        var completed: CryptoDepositsCompletedEvent? = null
        var failed: CryptoDepositsCompletedEvent? = null
        var deposit: IntegrationsDepositEvent? = null

        override fun onClose() { closed = true }
        override fun onError(error: ZerohashError) { this.error = error }
        override fun onEvent(event: GenericEvent) { this.event = event }
        override fun onLoaded() { loaded = true }
        override fun onCompleted(event: CryptoDepositsCompletedEvent) { completed = event }
        override fun onFailed(event: CryptoDepositsCompletedEvent) { failed = event }
        override fun onDeposit(event: IntegrationsDepositEvent) { deposit = event }
    }

    @Test
    fun cryptoDepositRoutesToOnCompleted() {
        val callbacks = RecordingCallbacks()
        val handler = CryptoDepositsCallbackHandler(callbacks)

        handler.handleCryptoDeposit(JSONObject().put("depositId", "dep-1").put("amount", "5.00"))

        assertEquals("dep-1", callbacks.completed?.depositId)
        assertNull("A completion must not also report as a failure", callbacks.failed)
        assertNull("A completion must not reach onError", callbacks.error)
    }

    /**
     * A business failure reaches `onFailed`, never `onError` — hosts render "the
     * money movement failed" differently from "the SDK broke".
     */
    @Test
    fun transactionFailedRoutesToOnFailedNotOnError() {
        val callbacks = RecordingCallbacks()
        val handler = CryptoDepositsCallbackHandler(callbacks)

        handler.handleTransactionFailed(JSONObject().put("depositId", "dep-2"))

        assertEquals("dep-2", callbacks.failed?.depositId)
        assertNull("A failed deposit must not also surface as an SDK error", callbacks.error)
        assertNull(callbacks.completed)
    }

    /**
     * The connected-account path reports the shared integrations status on
     * `deposit-status`, and must not be mistaken for a terminal outcome.
     */
    @Test
    fun depositStatusRoutesToOnDepositWithTheSharedEvent() {
        val callbacks = RecordingCallbacks()
        val handler = CryptoDepositsCallbackHandler(callbacks)

        handler.handleDepositStatus(
            JSONObject()
                .put("depositId", "dep-3")
                .put(
                    "status",
                    JSONObject().put("value", "PROCESSED").put("details", "ok").put("occurredAt", "now")
                )
                .put("assetId", "USDC")
                .put("networkId", "ethereum")
                .put("amount", "25.00")
        )

        val deposit = callbacks.deposit
        assertEquals("dep-3", deposit?.depositId)
        assertEquals("PROCESSED", deposit?.status)
        assertEquals("25.00", deposit?.amount)
        assertTrue("PROCESSED with no matching hold is a success", deposit?.success == true)
        assertNull("A status is not a terminal outcome", callbacks.completed)
        assertNull(callbacks.failed)
    }

    /** Pending account matching holds the deposit back even at PROCESSED. */
    @Test
    fun depositStatusIsNotSuccessfulWhileAccountMatchingIsPending() {
        val callbacks = RecordingCallbacks()
        val handler = CryptoDepositsCallbackHandler(callbacks)

        handler.handleDepositStatus(
            JSONObject()
                .put("status", JSONObject().put("value", "PROCESSED"))
                .put("accountMatchingValidation", JSONObject().put("status", "PENDING"))
        )

        assertFalse(callbacks.deposit?.success == true)
        assertEquals("PENDING", callbacks.deposit?.accountMatchingStatus)
    }

    @Test
    fun errorRoutesToTypedOnError() {
        val callbacks = RecordingCallbacks()
        val handler = CryptoDepositsCallbackHandler(callbacks)

        handler.handleError("server_error", "boom", null)

        assertTrue(callbacks.error is ZerohashError.ServerError)
        assertEquals("boom", callbacks.error?.message)
    }

    @Test
    fun closeEventAndLoadedRouteThrough() {
        val callbacks = RecordingCallbacks()
        val handler = CryptoDepositsCallbackHandler(callbacks)

        handler.handleClose()
        handler.handleLoaded()
        handler.handleEvent("SOME_EVENT", JSONObject().put("k", "v"))

        assertTrue(callbacks.closed)
        assertTrue(callbacks.loaded)
        assertEquals("SOME_EVENT", callbacks.event?.type)
        assertEquals("v", callbacks.event?.getString("k"))
    }
}
