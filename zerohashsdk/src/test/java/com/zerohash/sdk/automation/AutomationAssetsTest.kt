package com.zerohash.sdk.automation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Build-time guard that every injected automation script referenced by the
 * Kotlin layer is actually packaged under src/main/assets/automation/. Catches a
 * renamed/missing/shrunk asset before it becomes a runtime FileNotFoundException.
 * Gradle runs unit tests with the module dir as the working directory.
 */
class AutomationAssetsTest {

    private val assetDir = File("src/main/assets/automation")

    @Test
    fun allReferencedScriptsExist() {
        val required = listOf(
            "auth-status.js",
            "auth-hide-social.js",
            "auth-choose-2fa-method.js",
            "auth-detect-unsupported-2fa.js",
            "coinbase-balance-queries.js",
            "dom-helpers.js",
            "get-balance.js",
            "get-deposit-address.js",
            "withdraw.js",
        )
        for (name in required) {
            val f = File(assetDir, name)
            // Non-empty too, so a renamed/shrunk/zeroed asset also fails (not just
            // a missing one).
            assertTrue("missing automation asset: $name", f.isFile)
            assertTrue("empty automation asset: $name", f.length() > 0)
        }
    }
}
