package com.zerohash.sdk.internal

import com.zerohash.sdk.GenericEvent
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.ZerohashSDK
import com.zerohash.sdk.fund.FundCallbacks
import com.zerohash.sdk.fund.FundCompletedEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRedirectTest {

    @Test
    fun `accepts an https App Link`() {
        assertTrue(AppRedirect.isValid("https://partner.example/wallet-return"))
    }

    @Test
    fun `accepts a custom scheme`() {
        assertTrue(AppRedirect.isValid("partnerapp://wallet-return"))
    }

    @Test
    fun `rejects a relative url`() {
        assertFalse(AppRedirect.isValid("wallet-return"))
    }

    @Test
    fun `rejects a url that does not parse`() {
        assertFalse(AppRedirect.isValid("partner app://wallet return"))
    }

    @Test
    fun `allows no url`() {
        AppRedirect.requireValid(null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `configure throws on an invalid url`() {
        ZerohashSDK.configureFund(
            jwt = "test-jwt",
            callbacks = object : FundCallbacks {
                override fun onClose() {}
                override fun onError(error: ZerohashError) {}
                override fun onCompleted(event: FundCompletedEvent) {}
                override fun onEvent(event: GenericEvent) {}
            },
            appRedirectUrl = "wallet-return"
        )
    }
}
