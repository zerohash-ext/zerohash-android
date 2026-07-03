package com.zerohash.sdk.automation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the wire → [OverlayOptions] resolution and [Brand] normalization,
 * mirroring iOS `OverlayOptionsTests`. Pure JVM (org.json test dep), no device.
 */
class OverlayOptionsTest {

    @Test
    fun nullWire_returnsDefault() {
        assertEquals(OverlayOptions.DEFAULT, OverlayOptions.resolve(null))
    }

    @Test
    fun emptyTitlesAndSubtitles_fallBackToDefault() {
        val wire = JSONObject()
            .put("titles", JSONArray())
            .put("subtitles", JSONArray())
        val o = OverlayOptions.resolve(wire)
        assertEquals(OverlayOptions.DEFAULT.titles, o.titles)
        assertEquals(OverlayOptions.DEFAULT.subtitles, o.subtitles)
    }

    @Test
    fun providedValues_win() {
        val wire = JSONObject()
            .put("titles", JSONArray(listOf("A", "B")))
            .put("subtitles", JSONArray(listOf("x")))
            .put("cycleMs", 1234)
            .put("branding", "zerohash")
        val o = OverlayOptions.resolve(wire)
        assertEquals(listOf("A", "B"), o.titles)
        assertEquals(listOf("x"), o.subtitles)
        assertEquals(1234L, o.cycleMs)
        assertEquals(Brand.ZEROHASH, o.brand)
    }

    @Test
    fun cycleMs_zeroNegativeOrNonNumeric_clampToDefault() {
        // 0 and negative are present-but-invalid; a non-numeric string coerces to
        // 0 via optLong. All must clamp to the default rather than busy-loop.
        assertEquals(OverlayOptions.DEFAULT.cycleMs, OverlayOptions.resolve(JSONObject().put("cycleMs", 0)).cycleMs)
        assertEquals(OverlayOptions.DEFAULT.cycleMs, OverlayOptions.resolve(JSONObject().put("cycleMs", -5)).cycleMs)
        assertEquals(OverlayOptions.DEFAULT.cycleMs, OverlayOptions.resolve(JSONObject().put("cycleMs", "abc")).cycleMs)
    }

    @Test
    fun cycleMs_missing_usesDefault() {
        assertEquals(OverlayOptions.DEFAULT.cycleMs, OverlayOptions.resolve(JSONObject()).cycleMs)
    }

    @Test
    fun brandNormalize() {
        assertEquals(Brand.CONNECT, Brand.normalize("connect"))
        assertEquals(Brand.ZEROHASH, Brand.normalize("zerohash"))
        assertEquals(Brand.CONNECT, Brand.normalize("CONNECT")) // case-insensitive
        // DEFAULT is ZEROHASH for this SDK → unknown/null/empty normalize to it.
        assertEquals(Brand.ZEROHASH, Brand.normalize("unknown"))
        assertEquals(Brand.ZEROHASH, Brand.normalize(null))
        assertEquals(Brand.ZEROHASH, Brand.normalize(""))
    }
}
