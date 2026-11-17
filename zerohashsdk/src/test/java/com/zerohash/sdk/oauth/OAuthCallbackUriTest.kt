package com.zerohash.sdk.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCallbackUriTest {

    private val callbackUri = OAuthCallbackUri.buildForApp("com.partner.app")

    @Test
    fun `builds the app-scoped callback uri from the package name`() {
        assertEquals("connectsdk-oauth://com.partner.app", callbackUri)
    }

    @Test
    fun `accepts the app-scoped callback`() {
        assertTrue(OAuthCallbackUri.matches("connectsdk-oauth", "com.partner.app", callbackUri))
    }

    @Test
    fun `accepts the callback when a hop changes the case`() {
        val mixedCase = OAuthCallbackUri.buildForApp("com.Partner.App")
        assertTrue(OAuthCallbackUri.matches("connectsdk-oauth", "com.partner.app", mixedCase))
        assertTrue(OAuthCallbackUri.matches("CONNECTSDK-OAUTH", "COM.PARTNER.APP", callbackUri))
    }

    @Test
    fun `rejects the shared legacy callback`() {
        assertFalse(OAuthCallbackUri.matches("connectsdk-oauth", "callback", callbackUri))
    }

    @Test
    fun `rejects another app's callback`() {
        assertFalse(OAuthCallbackUri.matches("connectsdk-oauth", "com.other.app", callbackUri))
    }

    @Test
    fun `rejects another scheme`() {
        assertFalse(OAuthCallbackUri.matches("https", "com.partner.app", callbackUri))
    }

    @Test
    fun `rejects a missing scheme or host`() {
        assertFalse(OAuthCallbackUri.matches(null, "com.partner.app", callbackUri))
        assertFalse(OAuthCallbackUri.matches("connectsdk-oauth", null, callbackUri))
    }
}
