package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WithdrawFundsNotAvailableAssetTest {

    private val js: String =
        File("src/main/assets/automation/withdraw.js").readText()

    @Test
    fun detectsTheHoldStepByItsContainer() {
        assertTrue(
            "withdraw.js must carry the wblHoldStep container selector",
            js.contains("""[data-testid="step-wblHoldStep-active"]"""),
        )
    }

    @Test
    fun doesNotKeyOnTheGenericTitleTestid() {
        assertFalse(
            "detection must key on the step container, never the generic title testid",
            js.contains("no-crypto-title"),
        )
    }

    @Test
    fun racesTheHoldStepAgainstTheRecipientField() {
        val race = js.substringAfter("async function awaitRecipientOrPendingBlock()")
            .substringBefore("\n  }")
        assertTrue(
            "the recipient wait must check for the hold step",
            race.contains("isHoldModalPresent()"),
        )
        assertTrue(
            "the recipient wait must throw the funds-not-available tag on the hold",
            race.contains("fundsNotAvailableError()"),
        )
    }

    @Test
    fun classifiesTheRejectionWithTheSharedReason() {
        assertTrue(
            "the rejection must use the shared funds_not_available reason string",
            js.contains("""reason: "funds_not_available""""),
        )
        assertTrue(
            "start's catch must convert the tagged error into the rejection",
            js.contains("e.zhFundsNotAvailable"),
        )
    }

    @Test
    fun omitsFundsAvailabilityWhenNoFiguresWereCaptured() {
        assertFalse(
            "withdraw.js must not emit a null-filled fundsAvailability payload",
            js.contains("fundsAvailability"),
        )
    }

    @Test
    fun classifiesAHoldThatAppearsMidSession() {
        assertTrue(
            "continue must be wrapped so a mid-session hold is classified, not surfaced as a timeout",
            js.contains("continueInner(payload)"),
        )
        assertTrue(
            "the hold check must gate the rejection in the catches",
            js.contains("if (isHoldModalPresent()) {"),
        )
    }
}
