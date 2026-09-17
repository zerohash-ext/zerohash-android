package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The fiat/asset toggle, found by its icon instead of a localized aria-label. */
class WithdrawCurrencyToggleAssetTest {

    private val withdraw: String =
        File("src/main/assets/automation/withdraw.js").readText()

    /** Comments stripped, so a guard never matches its own rationale. */
    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    private fun body(name: String): String =
        code(withdraw).substringAfter("function $name(").substringBefore("\n  }")

    @Test
    fun theToggleIsSelectedByItsIconNotItsLabel() {
        assertTrue(
            "the toggle must be declared as a data-icon-name handle",
            code(withdraw).contains("""CURRENCY_TOGGLE_ICON = '[data-icon-name="arrowsVertical"]'"""),
        )
        assertTrue(
            "findCurrencyToggle must resolve the icon off SEL",
            body("findCurrencyToggle").contains("SEL.CURRENCY_TOGGLE_ICON"),
        )
        assertTrue(
            "the icon must be walked up to the button that owns it",
            body("findCurrencyToggle").contains("""closest("button")"""),
        )
    }

    @Test
    fun nothingInTheAssetIsSelectedByAriaLabel() {
        // aria-label values are translated; data-icon-name values are not.
        assertFalse(
            "withdraw.js must not select anything by aria-label",
            code(withdraw).contains("aria-label=\""),
        )
    }

    @Test
    fun anAmbiguousScopeIsAnErrorNotAFirstMatchGuess() {
        // Two icons means the scope is wrong, and the other one drives the swap widget.
        val fn = body("findCurrencyToggle")
        assertTrue(
            "findCurrencyToggle must reject more than one candidate",
            fn.contains("icons.length > 1"),
        )
        assertTrue(
            "the ambiguity must throw a named error",
            fn.contains("withdraw/currency-toggle-ambiguous"),
        )
        assertFalse(
            "it must not silently take the first match",
            fn.contains("icons[0].closest") && !fn.contains("icons.length > 1"),
        )
    }

    @Test
    fun theAmountStepScopeIsRequiredRatherThanFallingBackToTheDocument() {
        // document.body was harmless while the lookup was a label match that found
        // nothing there. With an icon match it would be an ambiguous root.
        val fn = body("enterAmount")
        assertFalse(
            "enterAmount must not fall back to document.body for the toggle scope",
            fn.contains("document.body"),
        )
        assertTrue(
            "the active step must be resolved off SEL",
            fn.contains("input.closest(SEL.STEP_ACTIVE)"),
        )
        assertTrue(
            "a missing active step must be an explicit error",
            fn.contains("withdraw/amount-step-not-found"),
        )
    }

    @Test
    fun aMissingToggleIsNotReportedAsASingleCurrencyFlow() {
        // The old message blamed Coinbase for our own selector missing, which sent
        // anyone debugging a localized account down the wrong path.
        val fn = body("ensureCurrencyMode")
        assertTrue(
            "a missing toggle must throw a named, accurate error",
            fn.contains("withdraw/currency-toggle-not-found"),
        )
        assertFalse(
            "the misleading 'only offers' wording must be gone",
            code(withdraw).contains("only offers"),
        )
    }

    @Test
    fun theToggleClickUsesTheSharedSyntheticEventPath() {
        val fn = body("ensureCurrencyMode")
        assertTrue(
            "the toggle must be clicked through humanClick like every other control",
            fn.contains("humanClick(toggle)"),
        )
        assertFalse(
            "a bare toggle.click() bypasses the synthetic-event path",
            fn.contains("toggle.click()"),
        )
    }

    @Test
    fun theAssetDoesNotUseTheHasSelector() {
        // minSdk is 21 and :has() needs a modern Chromium, so an un-updated System
        // WebView would silently match nothing.
        assertFalse("must not use :has()", code(withdraw).contains(":has("))
    }
}
