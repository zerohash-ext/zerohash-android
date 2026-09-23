package com.zerohash.sdk.automation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cover [Brand.theme] per brand — the palette, footer prefix, mark height,
 * and row alignment that [LoadingOverlayView] renders from. Mirrors iOS
 * `BrandTests`. `markRes` isn't asserted here: R fields resolve to 0 in
 * default AGP unit tests, so any equality check between R IDs would be
 * tautological. The mark asset is exercised by the instrumented UI tests
 * that render [LoadingOverlayView].
 */
class BrandTest {

    @Test
    fun connectTheme_isPoweredByPalette14dpCentered() {
        val t = Brand.CONNECT.theme
        assertEquals(0xFFFCFC99.toInt(), t.left)
        assertEquals(0xFFF2F07D.toInt(), t.middle)
        assertEquals(0xFFF0D53E.toInt(), t.right)
        assertEquals("Powered by", t.footerLabel)
        assertEquals(14, t.markHeightDp)
        assertEquals(FooterAlignment.CENTER, t.footerAlignment)
    }

    @Test
    fun zerohashTheme_isPoweredByPalette14dpCentered() {
        val t = Brand.ZEROHASH.theme
        assertEquals(0xFFCCFFD0.toInt(), t.left)
        assertEquals(0xFFABF9B1.toInt(), t.middle)
        assertEquals(0xFF8FEB96.toInt(), t.right)
        assertEquals("Powered by", t.footerLabel)
        assertEquals(14, t.markHeightDp)
        assertEquals(FooterAlignment.CENTER, t.footerAlignment)
    }

    @Test
    fun securedConnectTheme_isSecuredByConnectPalette28dpTopAligned() {
        val t = Brand.SECURED_CONNECT.theme
        // Same Connect palette as CONNECT — the two lockups differ in the
        // mark + prefix + height + alignment, not in the dot colors.
        assertEquals(Brand.CONNECT.theme.left, t.left)
        assertEquals(Brand.CONNECT.theme.middle, t.middle)
        assertEquals(Brand.CONNECT.theme.right, t.right)
        assertEquals("Secured by", t.footerLabel)
        assertEquals(28, t.markHeightDp)
        assertEquals(FooterAlignment.TOP, t.footerAlignment)
    }

    @Test
    fun wireValues_matchTheWebContract() {
        // Hosts send the hyphenated wire values — these are the strings
        // OverlayOptions.resolve()/Brand.normalize() must recognize.
        assertEquals("connect", Brand.CONNECT.wireValue)
        assertEquals("zerohash", Brand.ZEROHASH.wireValue)
        assertEquals("secured-connect", Brand.SECURED_CONNECT.wireValue)
    }

    @Test
    fun defaultBrand_isZerohashForThisSdk() {
        // zerohash-android defaults to the zerohash lockup; a future flip
        // (to CONNECT or SECURED_CONNECT) would need to be deliberate.
        assertEquals(Brand.ZEROHASH, Brand.DEFAULT)
    }
}
