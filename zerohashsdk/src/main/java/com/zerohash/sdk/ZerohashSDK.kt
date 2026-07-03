package com.zerohash.sdk

import com.zerohash.sdk.fund.FundCallbacks
import com.zerohash.sdk.fund.ZerohashFundSession

/**
 * ZerohashSDK - Main entry point for the Zerohash SDK.
 *
 * Provides static factory methods to create authenticated sessions with the
 * zerohash platform. This build ships the **Fund** flow (account funding /
 * pay-to-settle).
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
     *         override fun onFundCompleted(event: FundCompletedEvent) { /* funded */ }
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
}
