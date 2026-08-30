package com.zerohash.sdk.automation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards `setup-execution-context.js` and how it is installed (AUTH-4270). No JS
 *  engine in this suite, so guards assert on source text. */
class ExecutionContextSetupAssetTest {

    private val setup: String =
        File("src/main/assets/automation/setup-execution-context.js").readText()
    private val webViewKt: String =
        File("src/main/java/com/zerohash/sdk/automation/AutomationWebView.kt").readText()

    /** Cuts at the first `//`, truncating URLs, so origin checks use raw source. */
    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    @Test
    fun writesTheFlagCoinbaseGatesTheTrayOn() {
        assertTrue(
            "must set appUpsellDismissed to the string \"true\"",
            code(setup).contains("""localStorage.setItem("appUpsellDismissed", "true")"""),
        )
    }

    @Test
    fun doesNotJsonStringifyTheValue() {
        assertTrue(
            "the flag value must not be JSON.stringified",
            !code(setup).contains("JSON.stringify"),
        )
    }

    @Test
    fun oneFailingStepDoesNotStopTheOthers() {
        val body = code(setup).substringAfter("var steps")
        assertTrue("steps must be invoked in a loop", body.contains("for ("))
        assertTrue("each step must be individually guarded", body.contains("try {"))
    }

    @Test
    fun isInstalledFromTheSingleWebViewChokePoint() {
        val body = code(webViewKt)
            .substringAfter("fun WebView.applyAutomationDefaults()")
            .substringBefore("\n}")
        assertTrue(
            "applyAutomationDefaults must call setupExecutionContext()",
            body.contains("setupExecutionContext()"),
        )
    }

    @Test
    fun isInjectedAtDocumentStartNotOnPageFinished() {
        assertTrue(
            "must be injected via WebViewCompat.addDocumentStartJavaScript",
            code(webViewKt).contains("WebViewCompat.addDocumentStartJavaScript"),
        )
    }

    @Test
    fun isFeatureGuardedForOldWebViews() {
        assertTrue(
            "must check WebViewFeature.DOCUMENT_START_SCRIPT before injecting",
            code(webViewKt)
                .contains("WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)"),
        )
    }

    @Test
    fun isScopedToTheExactOriginItWasVerifiedOn() {
        assertTrue(
            "the origin must be exactly https://www.coinbase.com",
            webViewKt.contains(
                """private const val COINBASE_WWW_ORIGIN = "https://www.coinbase.com""""
            ),
        )
        assertTrue("the origin must not be a wildcard", !webViewKt.contains("*.coinbase.com"))
    }
}
