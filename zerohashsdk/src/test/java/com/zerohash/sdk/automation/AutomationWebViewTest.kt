package com.zerohash.sdk.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the Promise-bridge helpers: the call id, the origin check input,
 * the posted-message parser, and [decodeJsResult], which turns the JSON string a
 * settled JS Promise posts back into a [org.json.JSONObject] (or null / an error).
 * Every automation runner funnels its result through these, so a regression here
 * breaks every scraping op.
 *
 * Pure JVM (org.json test dep), no device.
 */
class AutomationWebViewTest {

    @Test
    fun newCallId_has128BitsAsHex() {
        assertTrue(newCallId().matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun newCallId_differsOnEachCall() {
        assertNotEquals(newCallId(), newCallId())
    }

    @Test
    fun parseBridgeMessage_readsResolve() {
        val message = parseBridgeMessage("""{"id":"abc","kind":"resolve","payload":"{\"ok\":true}"}""")
        assertEquals(BridgeMessage("abc", BridgeMessageKind.RESOLVE, """{"ok":true}"""), message)
    }

    @Test
    fun parseBridgeMessage_rejectsMalformedMessages() {
        assertNull(parseBridgeMessage(null))
        assertNull(parseBridgeMessage("not json"))
        assertNull(parseBridgeMessage("""{"kind":"resolve","payload":"null"}"""))
        assertNull(parseBridgeMessage("""{"id":"abc","kind":"done","payload":"null"}"""))
    }

    @Test
    fun originOf_dropsPathAndQuery() {
        assertEquals("https://www.coinbase.com", originOf("https://www.coinbase.com/send?x=1"))
    }

    @Test
    fun originOf_keepsExplicitPort() {
        assertEquals("https://www.coinbase.com:8443", originOf("https://www.coinbase.com:8443/home"))
    }

    @Test
    fun originOf_isEmptyForNullOrUnparseableUrl() {
        assertEquals("", originOf(null))
        assertEquals("", originOf("https://exa mple.com"))
        assertEquals("", originOf("about:blank"))
    }

    @Test
    fun decodesJsonObject() {
        val obj = decodeJsResult("""{"loggedIn":true}""")
        assertEquals(true, obj!!.getBoolean("loggedIn"))
    }

    @Test
    fun nullString_decodesToNull() {
        // The bridge stringifies `undefined`/`null` results to the literal "null".
        assertNull(decodeJsResult("null"))
    }

    @Test
    fun kotlinNull_decodesToNull() {
        assertNull(decodeJsResult(null))
    }

    @Test
    fun nonObjectResult_throws() {
        // A primitive/array that won't parse as an object is a broken return.
        try {
            decodeJsResult("42")
            fail("expected PlatformException for a non-object JS return")
        } catch (e: PlatformException) {
            assertTrue(e.message!!.contains("non-object JS return"))
        }
    }
}
