package com.zerohash.sdk

import com.zerohash.sdk.cryptowithdrawals.CryptoWithdrawalsCallbacks
import com.zerohash.sdk.cryptowithdrawals.ZerohashCryptoWithdrawalsSession
import com.zerohash.sdk.fund.FundCallbacks
import com.zerohash.sdk.fund.ZerohashFundSession
import com.zerohash.sdk.fundwithdrawals.FundWithdrawalsCallbacks
import com.zerohash.sdk.fundwithdrawals.ZerohashFundWithdrawalsSession

/**
 * ZerohashSDK - Main entry point for the Zerohash SDK.
 *
 * Provides static factory methods to create authenticated sessions with the
 * zerohash platform. This build ships the **Fund** flow (account funding /
 * pay-to-settle), the **Crypto Withdrawals** flow (withdraw to an address chosen
 * in-flow), and the **Fund Withdrawals** flow (withdraw to the pre-linked
 * account carried in the JWT).
 */
object ZerohashSDK {

    /**
     * Configure and create a Fund session.
     *
     * @param jwt JWT token for authentication
     * @param environment Environment to connect to (default: PRODUCTION)
     * @param theme UI theme (default: SYSTEM)
     * @param allowList Hosts the embedded WebView may navigate to / load from
     * @param callbacks Callbacks for session events
     * @return [ZerohashFundSession] instance ready to be presented
     *
     * Example usage:
     * ```
     * val session = ZerohashSDK.configureFund(
     *     jwt = "your-jwt-token",
     *     environment = Environment.PRODUCTION,
     *     theme = Theme.SYSTEM,
     *     callbacks = object : FundCallbacks {
     *         override fun onClose() { /* handle close */ }
     *         override fun onError(error: ZerohashError) { /* handle error */ }
     *         override fun onEvent(event: GenericEvent) { /* handle event */ }
     *         override fun onCompleted(event: FundCompletedEvent) { /* funded */ }
     *     }
     * )
     * session.present(activity)
     * ```
     */
    fun configureFund(
        jwt: String,
        environment: Environment = Environment.PRODUCTION,
        theme: Theme = Theme.SYSTEM,
        allowList: ZerohashAllowList = ZerohashAllowList.DEFAULT,
        callbacks: FundCallbacks
    ): ZerohashFundSession {
        return ZerohashFundSession(
            jwt = jwt,
            environment = environment,
            theme = theme,
            allowList = allowList,
            callbacks = callbacks
        )
    }

    /**
     * Configure and create a Crypto Withdrawals session.
     *
     * @param jwt JWT token for authentication
     * @param environment Environment to connect to (default: PRODUCTION)
     * @param theme UI theme (default: SYSTEM)
     * @param allowList Hosts the embedded WebView may navigate to / load from
     * @param callbacks Callbacks for session events
     * @return [ZerohashCryptoWithdrawalsSession] instance ready to be presented
     *
     * Example usage:
     * ```
     * val session = ZerohashSDK.configureCryptoWithdrawals(
     *     jwt = "your-jwt-token",
     *     environment = Environment.PRODUCTION,
     *     theme = Theme.SYSTEM,
     *     callbacks = object : CryptoWithdrawalsCallbacks {
     *         override fun onClose() { /* handle close */ }
     *         override fun onError(error: ZerohashError) { /* handle error */ }
     *         override fun onEvent(event: GenericEvent) { /* handle event */ }
     *         override fun onCompleted(event: CryptoWithdrawalsCompletedEvent) { /* done */ }
     *     }
     * )
     * session.present(activity)
     * ```
     */
    fun configureCryptoWithdrawals(
        jwt: String,
        environment: Environment = Environment.PRODUCTION,
        theme: Theme = Theme.SYSTEM,
        allowList: ZerohashAllowList = ZerohashAllowList.DEFAULT,
        callbacks: CryptoWithdrawalsCallbacks
    ): ZerohashCryptoWithdrawalsSession {
        return ZerohashCryptoWithdrawalsSession(
            jwt = jwt,
            environment = environment,
            theme = theme,
            allowList = allowList,
            callbacks = callbacks
        )
    }

    /**
     * Configure and create a Fund Withdrawals session.
     *
     * Withdraws to the **pre-linked** destination carried in the JWT's
     * `external_account_id`, so there is no destination picker in the flow — a
     * different rail from [configureCryptoWithdrawals], which withdraws to an
     * address selected in-flow.
     *
     * @param jwt JWT token for authentication; must carry an `external_account_id`
     * @param environment Environment to connect to (default: PRODUCTION)
     * @param theme UI theme (default: SYSTEM)
     * @param allowList Hosts the embedded WebView may navigate to / load from
     * @param callbacks Callbacks for session events
     * @return [ZerohashFundWithdrawalsSession] instance ready to be presented
     *
     * Example usage:
     * ```
     * val session = ZerohashSDK.configureFundWithdrawals(
     *     jwt = "your-jwt-token",
     *     environment = Environment.PRODUCTION,
     *     theme = Theme.SYSTEM,
     *     callbacks = object : FundWithdrawalsCallbacks {
     *         override fun onClose() { /* handle close */ }
     *         override fun onError(error: ZerohashError) { /* handle error */ }
     *         override fun onEvent(event: GenericEvent) { /* handle event */ }
     *         override fun onCompleted(event: FundWithdrawalsCompletedEvent) { /* done */ }
     *     }
     * )
     * session.present(activity)
     * ```
     */
    fun configureFundWithdrawals(
        jwt: String,
        environment: Environment = Environment.PRODUCTION,
        theme: Theme = Theme.SYSTEM,
        allowList: ZerohashAllowList = ZerohashAllowList.DEFAULT,
        callbacks: FundWithdrawalsCallbacks
    ): ZerohashFundWithdrawalsSession {
        return ZerohashFundWithdrawalsSession(
            jwt = jwt,
            environment = environment,
            theme = theme,
            allowList = allowList,
            callbacks = callbacks
        )
    }
}
