package com.zerohash.sdk.cryptowithdrawals

import org.json.JSONObject
import com.zerohash.sdk.AppCallbacks
import com.zerohash.sdk.CallbackHandler
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.GenericEvent

/**
 * Crypto Withdrawals-specific callbacks extending base [AppCallbacks].
 *
 * Mirrors the callbacks the zerohash mobile web app wires for the
 * `#crypto-withdrawals` route: `onClose`, `onError`, `onEvent`, and
 * `onCompleted` (forwarded over the native bridge as a
 * `crypto-withdrawal` message).
 */
interface CryptoWithdrawalsCallbacks : AppCallbacks {
    /**
     * Called when the withdrawal completes successfully.
     */
    fun onCompleted(event: CryptoWithdrawalsCompletedEvent)

    /**
     * Called when a withdrawal reaches a terminal **failed** state. This is a
     * flow outcome rather than an SDK error. Receives the same event type as
     * [onCompleted]; which callback fired tells you the outcome.
     *
     * Note that a failed withdrawal currently invokes [onError] **as well**, for
     * backwards compatibility with hosts written before [onFailed] existed
     * ([onError] used to be this flow's only failure signal). Build against
     * [onFailed]; if you handle both, guard against counting one failure twice.
     * The compatibility [onError] is deprecated and will be removed in a future
     * major version. Fund does not do this — only crypto withdrawals.
     *
     * Default no-op so hosts that only care about success need not implement it.
     */
    fun onFailed(event: CryptoWithdrawalsCompletedEvent) {}
}

/**
 * Withdrawal completion event with parsed fields.
 *
 * Field shape mirrors the completed-withdrawal payload emitted by the Crypto
 * Withdrawals SDK: already a flat data object (no `.data` wrapper) when it
 * reaches the native bridge.
 */
data class CryptoWithdrawalsCompletedEvent(
    val withdrawalRequestId: String?,
    /** Terminal status value, e.g. `CONFIRMED` or `FAILED`. */
    val status: String? = null,
    /**
     * Human-readable reason for the status. On a failure this is the only
     * explanation available anywhere in the stack, so prefer it over reporting a
     * bare id.
     */
    val statusDetails: String? = null,
    /** Asset identifier (e.g. `btc`, `eth`). */
    val assetId: String? = null,
    /** Network identifier (e.g. `bitcoin`, `ethereum`). */
    val networkId: String? = null,
    /** Amount withdrawn. */
    val amount: String? = null,
    val rawData: JSONObject?
) {
    companion object {
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        /**
         * Parse a withdrawal completion event from JSON data.
         */
        fun fromJSON(data: JSONObject?): CryptoWithdrawalsCompletedEvent {
            return CryptoWithdrawalsCompletedEvent(
                withdrawalRequestId = data?.optStringOrNull("withdrawalRequestId"),
                status = data?.optStringOrNull("status"),
                statusDetails = data?.optStringOrNull("statusDetails"),
                assetId = data?.optStringOrNull("assetId"),
                networkId = data?.optStringOrNull("networkId"),
                amount = data?.optStringOrNull("amount"),
                rawData = data
            )
        }
    }
}

/**
 * Handler that converts raw bridge data to typed Crypto Withdrawals events.
 */
internal class CryptoWithdrawalsCallbackHandler(
    private val callbacks: CryptoWithdrawalsCallbacks
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

    override fun handleCryptoWithdrawal(data: JSONObject?) {
        val event = CryptoWithdrawalsCompletedEvent.fromJSON(data)
        callbacks.onCompleted(event)
    }

    override fun handleTransactionFailed(data: JSONObject?) {
        val event = CryptoWithdrawalsCompletedEvent.fromJSON(data)
        callbacks.onFailed(event)
    }
}
