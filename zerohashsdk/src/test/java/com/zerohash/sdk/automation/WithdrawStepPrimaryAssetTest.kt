package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Steps whose confirm button has no testid, advanced via its data-variant. */
class WithdrawStepPrimaryAssetTest {

    private val withdraw: String =
        File("src/main/assets/automation/withdraw.js").readText()
    private val deposit: String =
        File("src/main/assets/automation/get-deposit-address.js").readText()
    private val domHelpers: String =
        File("src/main/assets/automation/dom-helpers.js").readText()

    /** Comments stripped, so a guard never matches its own rationale. */
    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    private fun slice(src: String, from: String, to: String): String {
        val c = code(src)
        val start = c.indexOf(from)
        val end = c.indexOf(to)
        assertTrue("$from must exist", start >= 0)
        assertTrue("$to must follow $from", end > start)
        return c.substring(start, end)
    }

    @Test
    fun theSharedHelperScopesToTheStepAndRefusesAmbiguity() {
        val fn = slice(domHelpers, "function stepPrimaryButton(", "function clickableAncestor(")
        assertTrue(
            "it must read the design-system prop",
            fn.contains("""data-variant="primary""""),
        )
        assertTrue(
            "it must query the step, not the document",
            fn.contains("step.querySelectorAll("),
        )
        assertFalse("it must not query the document", fn.contains("document."))
        assertTrue("more than one candidate must throw", fn.contains("primaries.length > 1"))
        assertTrue("and say so by name", fn.contains("step-primary-ambiguous"))
    }

    @Test
    fun theDestinationTagStepNoLongerAdvancesOnAnEnglishLabel() {
        val fn = slice(withdraw, "async function fillDestinationTag(", "async function skipDestinationTag(")
        assertFalse(
            "the English \"Continue\" must be gone from the destination-tag confirm",
            fn.contains("\"Continue\""),
        )
        assertTrue("it must wait for the step's primary button", fn.contains("waitForStepPrimary"))
        assertTrue(
            "and report a named error when it never enables",
            fn.contains("withdraw/destination-tag-continue-not-found"),
        )
    }

    @Test
    fun theWaitKeepsTheAmbiguitySignalRatherThanFoldingItIntoATimeout() {
        // pollUntil swallows predicate exceptions, which would hide the ambiguity error.
        val fn = slice(withdraw, "async function waitForStepPrimary(", "async function fillDestinationTag(")
        assertFalse(
            "waitForStepPrimary must not be built on pollUntil",
            fn.contains("pollUntil"),
        )
        assertTrue("it must still honour the disabled state", fn.contains("isDisabled(btn)"))
    }

    @Test
    fun theSkipPathStaysOnItsTestid() {
        // It was already locale-safe; guard against collateral damage.
        val fn = slice(withdraw, "async function skipDestinationTag(", "SELECTION_PHASE_BUDGET_MS")
        assertTrue("skip must keep using its testid", fn.contains("SKIP_DESTINATION_TAG"))
    }

    @Test
    fun theLightningNuxIsDismissedByStructureNotByText() {
        val fn = slice(deposit, "function dismissInterstitials(", "var addressToggleClicked")
        assertFalse("the English \"Continue\" must be gone", fn.contains("\"Continue\""))
        assertFalse(
            "and the document-wide text matcher with it",
            fn.contains("findButtonByText"),
        )
        assertTrue(
            "the NUX step must be dismissed via its primary button",
            fn.contains("dismissStepViaPrimary(LIGHTNING_NUX_STEP)"),
        )
    }

    @Test
    fun theDepositFileNoLongerBindsTheTextMatcherAtAll() {
        assertFalse(
            "findButtonByText was the last English-text selector in this file",
            code(deposit).contains("findButtonByText"),
        )
    }

    @Test
    fun oneAmbiguousInterstitialCannotAbortTheAddressFetch() {
        // dismissInterstitials runs inside a tight poll loop, so a throw would end
        // the whole run. Each interstitial is isolated instead.
        val fn = slice(deposit, "function dismissStepViaPrimary(", "function dismissInterstitials(")
        assertTrue("each dismissal must be isolated", fn.contains("try {"))
        assertTrue("and must be scoped to its step", fn.contains("D.stepPrimaryButton(step)"))
    }

    @Test
    fun theRiskCancelButtonIsFoundByTestidNotByText() {
        val fn = slice(withdraw, "async function clickCancelTransfer(", "window.__zhWithdraw = ")
        assertFalse(
            "the English \"Cancel transfer\" match must be gone",
            fn.contains("RISK_CANCEL_TRANSFER_LABEL"),
        )
        assertTrue(
            "it must use the risk-gate cancel testid",
            fn.contains("SEL.RISK_CANCEL_TRANSFER"),
        )
        assertTrue(
            "the testid itself must be declared",
            code(withdraw).contains("risk-warning-v2-cancel-button"),
        )
    }

    @Test
    fun anAbsentCancelButtonIsStillAPlainFalse() {
        // Not a miss: often absent by design, and the caller depends on false.
        val fn = slice(withdraw, "async function clickCancelTransfer(", "window.__zhWithdraw = ")
        assertTrue("a missing step must return false", fn.contains("if (!step) return false"))
        assertTrue("a missing button must return false", fn.contains("if (!btn) return false"))
    }

    @Test
    fun noEnglishLabelConstantsRemainOnTheRiskGate() {
        assertFalse("\"Cancel transfer\" must be gone", code(withdraw).contains("Cancel transfer"))
        assertFalse("\"Start ID check\" must be gone", code(withdraw).contains("Start ID check"))
    }
}
