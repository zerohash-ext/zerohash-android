package com.zerohash.sdk.automation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Scam-warning risk variant must report a gate; unreported the poll burned ~10060ms.
 *  No JS engine: guards read source via `code()`, which truncates URLs at the first `//`. */
class WithdrawScamWarningAssetTest {

    private val withdraw: String =
        File("src/main/assets/automation/withdraw.js").readText()

    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    /** Body of `riskIdVerificationSettled`; the delimiter is its 2-space closing brace. */
    private fun settled(): String =
        code(withdraw).substringAfter("function riskIdVerificationSettled()")
            .substringBefore("\n  }")

    @Test
    fun theScamWarningCountsAsASettledRiskState() {
        assertTrue(
            "riskIdVerificationSettled must accept RISK_SCAM_INTRO, else the gate is never reported",
            settled().contains("RISK_SCAM_INTRO"),
        )
    }

    @Test
    fun theScamWarningSelectorIsNoLongerDeadCode() {
        val reads = Regex("""SEL\.RISK_SCAM_INTRO""").findAll(code(withdraw)).count()
        assertTrue(
            "RISK_SCAM_INTRO must be read off SEL by real logic (found $reads reads)",
            reads >= 1,
        )
    }

    @Test
    fun theBareRiskContainerIsStillNotEnoughOnItsOwn() {
        assertTrue(
            "the container must still be required",
            settled().contains("STEP_RISK_VERIFICATION"),
        )
        assertTrue(
            "the container alone must still return false",
            settled().contains("return false"),
        )
    }

    @Test
    fun theScamWarningIsReportedAsAGateNotAsProcessing() {
        val poll = code(withdraw).substringAfter("async function pollFor2faResolution()")
            .substringBefore("\n  }")
        assertTrue(
            "the poll must gate on riskIdVerificationSettled()",
            poll.contains("riskIdVerificationSettled()"),
        )
        assertTrue(
            "the settled risk state must return id-verification, not fall through to processing",
            poll.substringAfter("riskIdVerificationSettled()").contains("\"id-verification\""),
        )
    }

    @Test
    fun noTemporaryDebugInstrumentationIsLeftInTheAsset() {
        assertTrue(
            "__zhHoldDebug instrumentation must be removed",
            !withdraw.contains("__zhHoldDebug"),
        )
    }
}
