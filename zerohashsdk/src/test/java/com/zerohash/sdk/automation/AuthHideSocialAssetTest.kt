package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the AUTH-3437 behavior of `auth-hide-social.js`: Sign in with Apple is
 * no longer hidden (it is now supported via [AuthPopupWindow] through the login
 * WebView's `onCreateWindow`), while Google and all passkey buttons remain
 * hidden (they cannot complete inside the embedded login WebView).
 *
 * Pure-JVM content check — Gradle runs unit tests with the module dir as the
 * working directory (same convention as [AutomationAssetsTest]).
 */
class AuthHideSocialAssetTest {

    private val js: String =
        File("src/main/assets/automation/auth-hide-social.js").readText()

    @Test
    fun appleSignInIsNotHidden() {
        assertFalse(
            "auth-hide-social.js must not hide Sign in with Apple (AUTH-3437)",
            js.contains("sign-in-with-apple"),
        )
    }

    @Test
    fun googleSignInRemainsHidden() {
        assertTrue(
            "auth-hide-social.js must still hide Sign in with Google",
            js.contains("sign-in-with-google"),
        )
    }

    @Test
    fun passkeyButtonsRemainHidden() {
        // Assert against the CSS selector, not the word "passkey" (which also
        // appears in the file's comment), so deleting the rule fails the test.
        assertTrue(
            "auth-hide-social.js must still hide passkey buttons",
            js.contains("button[data-testid*=\"passkey\" i]"),
        )
    }
}
