package com.zerohash.sdk.ui

import com.zerohash.sdk.oauth.OAuthCallbackUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class WebViewConfigMessageTest {

    private val callbackUri = OAuthCallbackUri.buildForApp("com.partner.app")

    @Test
    fun `config carries the oauth callback uri`() {
        val config = WebViewMessageHandler.configMessage("dark", null, callbackUri)

        assertEquals("dark", config.getString("theme"))
        assertEquals(callbackUri, config.getString("oauthCallbackUri"))
        assertFalse(config.has("appRedirectUrl"))
    }

    @Test
    fun `config carries the app redirect url when set`() {
        val config = WebViewMessageHandler.configMessage("light", "partner://home", callbackUri)

        assertEquals("partner://home", config.getString("appRedirectUrl"))
    }

    @Test
    fun `the callback check accepts the uri the config sends`() {
        val sent = URI(WebViewMessageHandler.configMessage("light", null, callbackUri).getString("oauthCallbackUri"))

        assertTrue(OAuthCallbackUri.matches(sent.scheme, sent.host, callbackUri))
    }
}
