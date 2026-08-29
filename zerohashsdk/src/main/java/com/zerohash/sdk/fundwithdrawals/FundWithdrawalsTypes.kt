package com.zerohash.sdk.fundwithdrawals

import org.json.JSONObject
import com.zerohash.sdk.AppCallbacks
import com.zerohash.sdk.CallbackHandler
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.GenericEvent

/**
 * Fund Withdrawals-specific callbacks extending base [AppCallbacks].
 *
 * Mirrors the callbacks the zerohash mobile web app wires for the
 * `#fund-withdrawals` route: `onClose`, `onError`, `onEvent`, and the completion
 * (forwarded over the native bridge as a `fund-withdrawal` message).
 *
 * This is the **pre-linked destination** rail: the payout target is resolved from
 * the `external_account_id` carried in the JWT, so there is no in-flow
 * connection picker. It is a separate rail from
 * [com.zerohash.sdk.cryptowithdrawals.ZerohashCryptoWithdrawalsSession], which
 * withdraws to an address the user selects in-flow.
 *
 * There is deliberately **no `onFailed`**: the web contract for this route
 * (`FundWithdrawalsCallbacks` in `@zerohash/callbacks`) does not expose one, and
 * the mobile web app does not post a `transaction-failed` message for it — only
 * Fund and Crypto Withdrawals do. A callback that can never fire would be worse
 * than its absence. Request errors still arrive on [onError].
 */
interface FundWithdrawalsCallbacks : AppCallbacks {
    fun onCompleted(event: FundWithdrawalsCompletedEvent)
}

/**
 * Fund Withdrawals completion event with parsed fields.
 *
 * Field shape mirrors `FundWithdrawalsCompletedData` emitted by the Fund
 * Withdrawals SDK (`@zerohash/callbacks`): the payload is already a flat data
 * object (no `.data` wrapper) when it reaches the native bridge.
 */
data class FundWithdrawalsCompletedEvent(
    /** External account the funds were sent to — the resolved payout destination. */
    val externalAccountId: String?,
    val assetSymbol: String?,
    val amount: String?,
    val rawData: JSONObject?
) {
    companion object {
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        /**
         * Parse a Fund Withdrawals completion event from JSON data.
         */
        fun fromJSON(data: JSONObject?): FundWithdrawalsCompletedEvent {
            return FundWithdrawalsCompletedEvent(
                externalAccountId = data?.optStringOrNull("externalAccountId"),
                assetSymbol = data?.optStringOrNull("assetSymbol"),
                amount = data?.optStringOrNull("amount"),
                rawData = data
            )
        }
    }
}

/**
 * Handler that converts raw bridge data to typed Fund Withdrawals events.
 */
internal class FundWithdrawalsCallbackHandler(
    private val callbacks: FundWithdrawalsCallbacks
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

    override fun handleFundWithdrawal(data: JSONObject?) {
        val event = FundWithdrawalsCompletedEvent.fromJSON(data)
        callbacks.onCompleted(event)
    }
}
