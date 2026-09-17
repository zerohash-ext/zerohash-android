package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Amount-rejection classification: reports that it could not classify, rather than guessing. */
class WithdrawAmountErrorAssetTest {

    private val withdraw: String =
        File("src/main/assets/automation/withdraw.js").readText()

    /** Comments stripped, so a guard never matches its own rationale. */
    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    private fun slice(from: String, to: String): String {
        val c = code(withdraw)
        val start = c.indexOf(from)
        val end = c.indexOf(to)
        assertTrue("$from must exist", start >= 0)
        assertTrue("$to must follow $from", end > start)
        return c.substring(start, end)
    }

    @Test
    fun anUnrecognisedRejectionClassifiesAsNothing() {
        val fn = slice("function classifyAmountError(", "var AMOUNT_REJECTED_UNKNOWN")
        assertTrue(
            "the fallthrough must be null, not an asserted bucket",
            fn.contains("return null"),
        )
        assertFalse(
            "it must not assert withdraw/amount-validation as a classification",
            fn.contains("withdraw/amount-validation"),
        )
    }

    @Test
    fun theSixEnglishPatternsAreStillHonoured() {
        // Preserved because removing them would be an unforced regression for en
        // accounts, not because they are known to be Coinbase's current copy.
        val fn = slice("function classifyAmountError(", "var AMOUNT_REJECTED_UNKNOWN")
        for (phrase in listOf(
            "add at least", "insufficient", "not enough",
            "exceeds your balance", "more than your balance",
        )) {
            assertTrue("the \"$phrase\" pattern must survive", fn.contains(phrase))
        }
        assertTrue("the minimum pattern must survive", fn.contains("\"minimum\""))
    }

    @Test
    fun noLocaleStemListsWereBoltedOn() {
        // Coinbase renders 16 locales; stem lists would be a permanent
        // half-measure that reads as coverage. The typed API response is the fix.
        val fn = slice("function classifyAmountError(", "var AMOUNT_REJECTED_UNKNOWN")
        for (stem in listOf("insuficiente", "mínimo", "minimo", "saldo")) {
            assertFalse("no \"$stem\" stem should be added here", fn.contains(stem))
        }
    }

    @Test
    fun theWireCodeStaysContractStable() {
        // An unmapped withdraw/* code becomes platform-ui-changed in zerohash-sdk,
        // which would blame our selectors for the venue's rejection.
        assertTrue(
            "the unknown-reason code must remain withdraw/amount-validation",
            code(withdraw).contains("""AMOUNT_REJECTED_UNKNOWN = "withdraw/amount-validation""""),
        )
    }

    @Test
    fun anUnclassifiedRejectionSaysSoInTheDetail() {
        val fn = slice("function readAmountValidationError(", "async function rejectIfAmountInvalid(")
        assertTrue(
            "the reader must mark an unclassified rejection as such",
            fn.contains("unclassified"),
        )
        assertTrue(
            "and still fall back to the contract-stable code",
            fn.contains("AMOUNT_REJECTED_UNKNOWN"),
        )
    }

    @Test
    fun theEnglishOnlyViewBalanceStripIsGone() {
        // The strip only ever matched the English phrase, never its translations.
        assertFalse(
            "the English-only trailing strip must be removed",
            code(withdraw).contains("View balance"),
        )
    }

    @Test
    fun thePendingTransferDecisionStaysOnItsTestid() {
        // The outcome is raised off the step's testid; no rendered text takes part.
        val race = slice(
            "async function awaitRecipientOrPendingBlock(",
            "async function typeRecipientAddress(",
        )
        assertTrue(
            "the block must be decided by the testid",
            race.contains("if (queryVisible(SEL.STEP_PREVIOUS_TRANSFER)) throw pendingTransferError("),
        )
    }

    @Test
    fun thePendingTransferDetailKeepsAllThreeKeys() {
        // Partner apps decode a fixed shape, so the keys outlive their values.
        val fn = slice("function readPendingTransfer(", "function fundsNotAvailableError(")
        for (key in listOf("amount: null", "recipient: null", "completeBefore: null")) {
            assertTrue("$key must remain in the reported shape", fn.contains(key))
        }
        assertFalse(
            "and no label may be consulted to fill them",
            fn.contains("readLabeledValue"),
        )
    }
}
