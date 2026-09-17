package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Coinbase's Central Resolution Hub banner, hidden at document start. Not clicked: its dismiss button is a real control on the user's account. */
class ResolutionHubBannerAssetTest {

    private val setup: String =
        File("src/main/assets/automation/setup-execution-context.js").readText()

    /** Comments stripped, so a guard never matches its own rationale. */
    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    private val js: String get() = code(setup)

    private fun step(): String =
        js.substringAfter("function hideResolutionHubBanner()").substringBefore("\n  }")

    @Test
    fun hidesTheBannerWithDisplayNone() {
        assertTrue(
            "the rule must use display:none !important",
            step().contains("display:none !important"),
        )
        assertTrue(
            "and must target the banner's testid",
            step().contains("""[data-testid="system-alert-banner"]"""),
        )
    }

    @Test
    fun coversEverySeverityRatherThanOne() {
        // All three variants share the one testid, so a testid-only rule covers them.
        assertFalse(
            "must not narrow to a single severity",
            step().contains("relevanttags"),
        )
    }

    @Test
    fun doesNotHideThePortalThatHostsIt() {
        // The portal hosts other content that must keep rendering.
        assertFalse("must not hide the alert portal", step().contains("portal-alert-container"))
        assertFalse("must not hide the modal portal", step().contains("portal-modal-container"))
    }

    @Test
    fun leavesCoinbasesOtherBannersAlone() {
        assertFalse("the cookie notice is not ours to hide", step().contains("banner-container"))
        assertFalse("nor the BR onboarding modal", step().contains("brazil-onboarding-modal"))
        assertFalse("nor the mobile cookie banner", step().contains("mobile-cookie-banner"))
    }

    @Test
    fun theBannerIsHiddenNeverClicked() {
        // Clicking Coinbase's dismiss button mutates account state we do not own.
        // Mirrors withdraw.js's cancel, which likewise refuses to click their button.
        assertFalse(
            "must not click Coinbase's dismiss control",
            js.contains("undefined-dismiss-btn"),
        )
        assertFalse("must not click anything at all", js.contains(".click()"))
    }

    @Test
    fun doesNotDependOnEnglishLabels() {
        // The dismiss button carries aria-label="close", which Coinbase localizes.
        assertFalse("must not select by aria-label", step().contains("aria-label"))
    }

    @Test
    fun doesNotRequireTheHasSelector() {
        // minSdk 21: an un-updated System WebView would silently match nothing.
        assertFalse("must not use :has()", step().contains(":has("))
    }

    @Test
    fun survivesDocumentStartWhenHeadDoesNotExistYet() {
        assertTrue(
            "at document start <head> is not parsed yet, so the append must fall back " +
                "to documentElement; document.head.appendChild would throw and the steps " +
                "loop would swallow it, silently disabling the fix",
            step().contains("(document.head || document.documentElement).appendChild"),
        )
    }

    @Test
    fun injectsAStylesheetOnceRatherThanMutatingNodes() {
        assertTrue(
            "must inject a <style> element, not set inline styles",
            step().contains("""createElement("style")"""),
        )
        assertTrue("must guard against injecting twice", step().contains("getElementById"))
        assertTrue(
            "the step must actually call inject() — a declared-but-uncalled inject is inert",
            step().contains("\n    inject();"),
        )
    }

    @Test
    fun usesItsOwnStyleIdSoItCannotClashWithTheRiskGateSheet() {
        assertTrue(
            "each concern needs a distinct STYLE_ID or one guard suppresses the other",
            step().contains("zh-hide-resolution-hub-banner"),
        )
    }

    @Test
    fun isRegisteredAsASetupStep() {
        val steps = js.substringAfter("var steps").substringBefore("]")
        assertTrue(
            "hideResolutionHubBanner must be in the steps array or it never runs",
            steps.contains("hideResolutionHubBanner"),
        )
    }
}
