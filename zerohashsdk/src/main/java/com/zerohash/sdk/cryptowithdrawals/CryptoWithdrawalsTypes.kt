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
 * `onWithdrawalCompleted` (forwarded over the native bridge as a
 * `crypto-withdrawal` message).
 */
interface CryptoWithdrawalsCallbacks : AppCallbacks {
    /**
     * Called when the withdrawal completes successfully.
     */
    fun onWithdrawalCompleted(event: CryptoWithdrawalsCompletedEvent)
}

/**
 * Withdrawal completion event with parsed fields.
 *
 * Field shape mirrors `CryptoWithdrawalsCompletedData` emitted by the Crypto
 * Withdrawals SDK (`@zerohash/callbacks`): the payload is already a flat data
 * object (no `.data` wrapper) when it reaches the native bridge.
 */
data class CryptoWithdrawalsCompletedEvent(
    val withdrawalRequestId: String?,
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

    override fun handleCryptoWithdrawal(data: JSONObject?) {
        val event = CryptoWithdrawalsCompletedEvent.fromJSON(data)
        callbacks.onWithdrawalCompleted(event)
    }
}
