package com.zerohash.sdk.fund

import org.json.JSONObject
import com.zerohash.sdk.AppCallbacks
import com.zerohash.sdk.CallbackHandler
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.GenericEvent
import com.zerohash.sdk.IntegrationsDepositEvent

/**
 * Fund-specific callbacks extending base [AppCallbacks].
 *
 * Mirrors the callbacks the zerohash mobile web app wires for the `#fund`
 * route: `onClose`, `onError`, `onEvent`, and `onCompleted` (forwarded over the
 * native bridge as a `deposit` message).
 */
interface FundCallbacks : AppCallbacks {
    /**
     * Called when the funding flow completes successfully.
     *
     * Fund "completes a deposit", so it reuses the native `deposit` channel.
     */
    fun onCompleted(event: FundCompletedEvent)

    /**
     * Called when a deposit reaches a terminal **failed** state. This is a flow
     * outcome, not an SDK error — [onError] is not called for it. Receives the
     * same event type as [onCompleted]; which callback fired tells you the
     * outcome.
     *
     * Default no-op so hosts that only care about success need not implement it.
     */
    fun onFailed(event: FundCompletedEvent) {}

    /**
     * Called with the status of a deposit funded from an external source (the
     * "connect an account" flow). Mirrors `onDeposit` on the Fund web SDK.
     *
     * **Not terminal.** It also fires while account matching is verifying, and can
     * arrive more than once for the same deposit — read the outcome off
     * [FundDepositEvent.status] / [FundDepositEvent.success] rather than treating
     * the call itself as completion. Deposits on this path report *only* here;
     * [onCompleted] and [onFailed] cover the manual and Pay paths.
     *
     * Default no-op so hosts that do not offer the external-source path need not
     * implement it.
     */
    fun onDeposit(event: FundDepositEvent) {}
}

/**
 * Fund completion event with parsed fields.
 *
 * Field shape mirrors the completed-deposit payload emitted by the Fund SDK:
 * already a flat data object (no `.data` wrapper) when it reaches the native
 * bridge.
 */
data class FundCompletedEvent(
    val depositAddress: String?,
    val network: String?,
    val assetSymbol: String?,
    val amount: String?,
    val transactionId: String?,
    val fundId: String?,
    val notionalAmount: String?,
    val rawData: JSONObject?
) {
    companion object {
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        /**
         * Parse a Fund completion event from JSON data.
         */
        fun fromJSON(data: JSONObject?): FundCompletedEvent {
            return FundCompletedEvent(
                depositAddress = data?.optStringOrNull("depositAddress"),
                network = data?.optStringOrNull("network"),
                assetSymbol = data?.optStringOrNull("assetSymbol"),
                amount = data?.optStringOrNull("amount"),
                transactionId = data?.optStringOrNull("transactionId"),
                fundId = data?.optStringOrNull("fundId"),
                notionalAmount = data?.optStringOrNull("notionalAmount"),
                rawData = data
            )
        }
    }
}

/**
 * Deposit status for the Fund flow. The payload is built by the shared
 * integrations hook, so it is identical across every SDK that embeds that flow —
 * see [IntegrationsDepositEvent], which Crypto Deposits also delivers.
 */
typealias FundDepositEvent = IntegrationsDepositEvent

/**
 * Handler that converts raw bridge data to typed Fund events.
 */
internal class FundCallbackHandler(
    private val callbacks: FundCallbacks
) : CallbackHandler {

    override fun handleClose() {
        callbacks.onClose()
    }

    override fun handleError(code: String?, message: String, data: JSONObject?) {
        val error = ZerohashError.fromWebError(code, message)
        callbacks.onError(error)
    }

    override fun handleEvent(type: String, data: JSONObject?) {
        val event = GenericEvent(type, data)
        callbacks.onEvent(event)
    }

    override fun handleLoaded() {
        callbacks.onLoaded()
    }

    override fun handleDeposit(data: JSONObject?) {
        val event = FundCompletedEvent.fromJSON(data)
        callbacks.onCompleted(event)
    }

    override fun handleTransactionFailed(data: JSONObject?) {
        val event = FundCompletedEvent.fromJSON(data)
        callbacks.onFailed(event)
    }

    override fun handleDepositStatus(data: JSONObject?) {
        val event = IntegrationsDepositEvent.fromJSON(data)
        callbacks.onDeposit(event)
    }
}
