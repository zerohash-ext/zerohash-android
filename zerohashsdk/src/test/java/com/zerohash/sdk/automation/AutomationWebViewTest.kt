package com.zerohash.sdk.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for the Promise-bridge decode path ([decodeJsResult]): the helper
 * that turns the JSON string a settled JS Promise hands back over the
 * `@JavascriptInterface` into a [org.json.JSONObject] (or null / an error). This
 * is the shared seam every automation runner funnels its result through, so a
 * regression here breaks every scraping op — one of the pieces AUTH-3443 flags.
 *
 * Pure JVM (org.json test dep), no device.
 */
class AutomationWebViewTest {

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
