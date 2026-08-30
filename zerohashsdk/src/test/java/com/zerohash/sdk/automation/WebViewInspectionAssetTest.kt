package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Remote inspection must stay gated on the host app being debuggable. No Robolectric
 *  here, so guards read source text. */
class WebViewInspectionAssetTest {

    private val webView: String =
        File("src/main/java/com/zerohash/sdk/automation/AutomationWebView.kt").readText()

    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    private fun defaults(): String =
        code(webView).substringAfter("fun WebView.applyAutomationDefaults()")
            .substringBefore("\n}")

    @Test
    fun inspectionIsEnabledFromTheSharedChokePoint() {
        assertTrue(
            "applyAutomationDefaults must enable inspection",
            defaults().contains("enableInspectionWhenDebuggable()"),
        )
    }

    @Test
    fun inspectionIsGatedOnTheHostAppBeingDebuggable() {
        val body = code(webView).substringAfter("fun WebView.enableInspectionWhenDebuggable()")
            .substringBefore("\n}")
        assertTrue(
            "inspection must be gated on FLAG_DEBUGGABLE",
            body.contains("FLAG_DEBUGGABLE"),
        )
        assertTrue(
            "the gate must return early rather than enable unconditionally",
            body.contains("return"),
        )
    }

    @Test
    fun inspectionIsNeverEnabledUnconditionally() {
        val calls = Regex("""setWebContentsDebuggingEnabled""")
            .findAll(code(webView)).count()
        assertTrue("expected the enabling call to exist", calls > 0)
        assertTrue(
            "setWebContentsDebuggingEnabled must appear exactly once, inside the gate; found $calls",
            calls == 1,
        )

        val gate = code(webView).substringAfter("fun WebView.enableInspectionWhenDebuggable()")
            .substringBefore("\n}")
        assertTrue(
            "the single call must live inside enableInspectionWhenDebuggable",
            gate.contains("setWebContentsDebuggingEnabled"),
        )
    }

    @Test
    fun theGateReadsTheHostAppFlagsNotTheSdkBuildType() {
        val body = code(webView).substringAfter("fun WebView.enableInspectionWhenDebuggable()")
            .substringBefore("\n}")
        assertTrue(
            "the gate must read the host app's applicationInfo",
            body.contains("applicationInfo"),
        )
        assertFalse(
            "the gate must not key off the SDK module's own BuildConfig.DEBUG",
            body.contains("BuildConfig.DEBUG"),
        )
    }
}
