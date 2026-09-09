package com.zerohash.sdk.cryptodeposits

import org.json.JSONObject
import com.zerohash.sdk.AppCallbacks
import com.zerohash.sdk.CallbackHandler
import com.zerohash.sdk.GenericEvent
import com.zerohash.sdk.IntegrationsDepositEvent
import com.zerohash.sdk.ZerohashError

/**
 * Crypto Deposits-specific callbacks extending base [AppCallbacks].
 *
 * Mirrors the callbacks the zerohash mobile web app wires for the
 * `#crypto-deposits` route: `onClose`, `onError`, `onEvent`, `onLoaded`,
 * `onCompleted` (forwarded as a `crypto-deposit` message), `onFailed`
 * (`transaction-failed`) and `onDeposit` (`deposit-status`).
 *
 * The flow has two funding paths and they report on different callbacks — the
 * same split as Fund. A deposit made through the flow's own screens reaches
 * [onCompleted] or [onFailed]; one funded from a connected external account (the
 * shared "auth as a feature" integrations path) reaches [onDeposit] and does not
 * touch the other two.
 *
 * Where the deposit lands is decided by the JWT, not by the host: a
 * `deposit_details.to_address` claim routes it to that platform-owned address
 * (**external mode**), and its absence routes it to a Zero Hash internal wallet
 * (**internal mode**). The event fields are the same either way.
 */
interface CryptoDepositsCallbacks : AppCallbacks {
    /**
     * Called when a deposit made through the flow's own screens completes.
     */
    fun onCompleted(event: CryptoDepositsCompletedEvent)

    /**
     * Called when a deposit reaches a terminal **failed** state. This is a flow
     * outcome, not an SDK error — [onError] is not called for it. Receives the
     * same event type as [onCompleted]; which callback fired tells you the
     * outcome.
     *
     * Default no-op so hosts that only care about success need not implement it.
     */
    fun onFailed(event: CryptoDepositsCompletedEvent) {}

    /**
     * Called with the status of a deposit funded from a connected external
     * account. Mirrors `onDeposit` on the Crypto Deposits web SDK.
     *
     * **Not terminal.** It also fires while account matching is verifying, and can
     * arrive more than once for the same deposit — read the outcome off
     * [IntegrationsDepositEvent.status] / [IntegrationsDepositEvent.success]
     * rather than treating the call itself as completion. Deposits on this path
     * report *only* here; [onCompleted] and [onFailed] cover the flow's own
     * screens.
     *
     * Same event type the Fund flow delivers — the payload is built by the shared
     * web hook, not by either SDK.
     *
     * Default no-op so hosts that do not offer the external-source path need not
     * implement it.
     */
    fun onDeposit(event: IntegrationsDepositEvent) {}
}

/**
 * Crypto Deposits completion event with parsed fields.
 *
 * Field shape mirrors the completed-deposit payload emitted by the Crypto
 * Deposits SDK: already a flat data object (no `.data` wrapper) when it reaches
 * the native bridge. It rides its own `crypto-deposit` message rather than
 * `deposit`, which already means Fund's completion and carries a different shape
 * (`transactionId`/`fundId`).
 */
data class CryptoDepositsCompletedEvent(
    /** The deposit ID returned from the API. */
    val depositId: String?,
    /** Asset symbol (e.g. `USDC`). */
    val assetSymbol: String?,
    /** Network identifier (e.g. `ethereum`). */
    val network: String?,
    /** Amount deposited. */
    val amount: String?,
    val rawData: JSONObject?
) {
    companion object {
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        /**
         * Parse a Crypto Deposits completion event from JSON data.
         */
        fun fromJSON(data: JSONObject?): CryptoDepositsCompletedEvent {
            return CryptoDepositsCompletedEvent(
                depositId = data?.optStringOrNull("depositId"),
                assetSymbol = data?.optStringOrNull("assetSymbol"),
                network = data?.optStringOrNull("network"),
                amount = data?.optStringOrNull("amount"),
                rawData = data
            )
        }
    }
}

/**
 * Handler that converts raw bridge data to typed Crypto Deposits events.
 */
internal class CryptoDepositsCallbackHandler(
    private val callbacks: CryptoDepositsCallbacks
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

    override fun handleCryptoDeposit(data: JSONObject?) {
        val event = CryptoDepositsCompletedEvent.fromJSON(data)
        callbacks.onCompleted(event)
    }

    override fun handleTransactionFailed(data: JSONObject?) {
        val event = CryptoDepositsCompletedEvent.fromJSON(data)
        callbacks.onFailed(event)
    }

    override fun handleDepositStatus(data: JSONObject?) {
        val event = IntegrationsDepositEvent.fromJSON(data)
        callbacks.onDeposit(event)
    }
}
