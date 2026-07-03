package com.zerohash.sdk.fund

import org.json.JSONObject
import com.zerohash.sdk.AppCallbacks
import com.zerohash.sdk.CallbackHandler
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.GenericEvent

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
    fun onFundCompleted(event: FundCompletedEvent)
}

/**
 * Fund completion event with parsed fields.
 *
 * Field shape mirrors `FundCompletedData` emitted by the Fund SDK
 * (`@zerohash/callbacks`): the payload is already a flat data object (no `.data`
 * wrapper) when it reaches the native bridge.
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

    override fun handleDeposit(data: JSONObject?) {
        val event = FundCompletedEvent.fromJSON(data)
        callbacks.onFundCompleted(event)
    }
}
