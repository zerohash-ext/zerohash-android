package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Selectors and helpers that matched nothing in any language. Deleted, not translated. */
class WithdrawDeadSelectorAssetTest {

    private val withdraw: String =
        File("src/main/assets/automation/withdraw.js").readText()
    private val domHelpers: String =
        File("src/main/assets/automation/dom-helpers.js").readText()

    /** Comments stripped, so a guard never matches its own rationale. */
    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    @Test
    fun noSelectorIsShippedForTheStepCoinbaseNeverRenders() {
        assertFalse(
            "step-transactionDetailsStep-active is never rendered; the selector must be gone",
            code(withdraw).contains("transactionDetailsStep"),
        )
        assertFalse(
            "STEP_TRANSACTION_DETAILS must not remain on SEL",
            code(withdraw).contains("STEP_TRANSACTION_DETAILS"),
        )
    }

    @Test
    fun theTextMatchersLeftWithoutCallersAreGoneNotParked() {
        // All orphaned by this branch; a text matcher kept "just in case" invites reuse.
        assertFalse(
            "findButtonByText has no callers and must not be parked in dom-helpers",
            code(domHelpers).contains("findButtonByText"),
        )
        assertFalse(
            "waitForButtonByText has no callers and must not be parked in withdraw.js",
            code(withdraw).contains("waitForButtonByText"),
        )
        assertFalse(
            "findButtonByTextSync has no callers and must not be parked in withdraw.js",
            code(withdraw).contains("findButtonByTextSync"),
        )
    }

    @Test
    fun thePortScaffoldingToolboxIsGone() {
        // H was the only occurrence of itself in either repo — declared on port, never read.
        val c = code(withdraw)
        assertFalse("the unread toolbox must be gone", c.contains("var H ="))
        assertFalse("and nothing may read through it", Regex("""\bH\.""").containsMatchIn(c))
    }

    @Test
    fun theHelpersTheToolboxListedAreStillHereAndStillCalled() {
        // Guards against the deletion taking real code with it.
        val c = code(withdraw)
        for (fn in listOf(
            "isVisible", "queryVisible", "waitForAny", "pollUntil", "waitForElement",
            "humanDelay", "humanClick", "setReactValue", "typeLikeHuman", "isDisabled",
            "getInnerText",
        )) {
            assertTrue("$fn must survive", Regex("""\b$fn\s*\(""").containsMatchIn(c))
        }
    }

    @Test
    fun theNetworkWarningAcknowledgeCarriesNoLabelConstants() {
        // The acknowledge is resolved by its testid; its rendered label depends on
        // the account's language, so no list of texts may stand in for it.
        val c = code(withdraw)
        assertFalse("NETWORK_WARNING_ACK_TEXTS must be gone", c.contains("NETWORK_WARNING_ACK_TEXTS"))
        assertFalse(
            "NETWORK_WARNING_ACK_FRAGMENT must be gone",
            c.contains("NETWORK_WARNING_ACK_FRAGMENT"),
        )
        assertTrue(
            "network-warning-step-understand must remain the handle",
            c.contains("network-warning-step-understand"),
        )
    }

    @Test
    fun theTravelRuleStepCoinbaseDoesRenderIsStillReachable() {
        // Guards against over-deleting: transferDetailsStep is the real screen and
        // its controls must stay.
        val c = code(withdraw)
        assertTrue("TRANSFER_PURPOSE must survive", c.contains("transfer-purpose-select"))
        assertTrue(
            "TRANSFER_SUBMIT must survive",
            c.contains("transfer-details-step-submit-container"),
        )
    }

    @Test
    fun theRiskGateCarriesNoEnglishLabelConstant() {
        assertFalse(
            "RISK_START_ID_CHECK_LABEL had no consumers and must be deleted, not translated",
            code(withdraw).contains("RISK_START_ID_CHECK_LABEL"),
        )
        assertFalse(
            "the \"Start ID check\" string must be gone with it",
            code(withdraw).contains("Start ID check"),
        )
    }

    @Test
    fun thePriorTransferDetailCarriesNoEnglishLabelConstants() {
        // The three labels were matched against rendered text, so they resolved on
        // an English account only. Deleted with the helper that read them.
        val c = code(withdraw)
        for (name in listOf(
            "PENDING_AMOUNT_LABEL",
            "PENDING_TO_LABEL",
            "RISK_COMPLETE_BEFORE_LABEL",
            "readLabeledValue",
        )) {
            assertFalse("$name must be deleted, not translated", c.contains(name))
        }
        assertFalse("the \"Complete before\" string must go with them", c.contains("Complete before"))
    }

    @Test
    fun theBlockingPriorTransferStepIsStillFoundByItsTestid() {
        // The whole reason the labels were safe to delete.
        val c = code(withdraw)
        assertTrue(
            "step-previousTransfer-active must remain the handle",
            c.contains("step-previousTransfer-active"),
        )
        assertTrue(
            "and it must still be read off SEL by real logic",
            Regex("""SEL\.STEP_PREVIOUS_TRANSFER""").containsMatchIn(c),
        )
    }

    @Test
    fun theRiskGateIsStillFoundByItsTestid() {
        // The whole reason the label was safe to delete.
        assertTrue(
            "start-challenge-button must remain the handle for the risk gate",
            code(withdraw).contains("start-challenge-button"),
        )
        assertTrue(
            "and it must still be read off SEL by real logic",
            Regex("""SEL\.RISK_START_CHALLENGE""").containsMatchIn(code(withdraw)),
        )
    }
}
