package com.zerohash.sdk.automation

import org.junit.Assert.assertEquals
import org.junit.Test

/** The withdraw session must not evaluate `__zhWithdraw.start` on a Cloudflare
 *  challenge document (AUTH-4245). No Robolectric, so the decision lives here. */
class ChallengeGateTest {

    private fun gate(
        budgetMs: Long = 30_000,
        pollIntervalMs: Long = 500,
        clock: () -> Long,
    ) = ChallengeGate(budgetMs, pollIntervalMs, clock)

    @Test
    fun probeReportingFalseMeansTheChallengeIsClear() {
        val g = gate { 0L }
        g.start()
        assertEquals(ChallengeGate.Decision.Clear, g.onProbeResult("false"))
    }

    @Test
    fun probeReportingTrueMeansStillChallengedSoRetry() {
        val g = gate { 0L }
        g.start()
        assertEquals(ChallengeGate.Decision.Retry(500), g.onProbeResult("true"))
    }

    @Test
    fun nullProbeResultCountsAsChallengedNotClear() {
        val g = gate { 0L }
        g.start()
        assertEquals(ChallengeGate.Decision.Retry(500), g.onProbeResult(null))
    }

    @Test
    fun unrecognisedProbeResultCountsAsChallenged() {
        val g = gate { 0L }
        g.start()
        assertEquals(ChallengeGate.Decision.Retry(500), g.onProbeResult("undefined"))
        assertEquals(ChallengeGate.Decision.Retry(500), g.onProbeResult(""))
        assertEquals(ChallengeGate.Decision.Retry(500), g.onProbeResult("null"))
    }

    @Test
    fun retryUsesTheConfiguredPollInterval() {
        val g = gate(pollIntervalMs = 250) { 0L }
        g.start()
        assertEquals(ChallengeGate.Decision.Retry(250), g.onProbeResult("true"))
    }

    @Test
    fun gateExhaustsExplicitlyInsteadOfWaitingForever() {
        var t = 0L
        val g = gate(budgetMs = 1_000) { t }
        g.start()
        t = 1_001
        assertEquals(ChallengeGate.Decision.Exhausted, g.onProbeResult("true"))
    }

    @Test
    fun aClearArrivingAtTheDeadlineStillWins() {
        var t = 0L
        val g = gate(budgetMs = 1_000) { t }
        g.start()
        t = 5_000
        assertEquals(ChallengeGate.Decision.Clear, g.onProbeResult("false"))
    }

    @Test
    fun budgetIsMeasuredFromStartNotFromTheFirstProbe() {
        var t = 10_000L
        val g = gate(budgetMs = 1_000) { t }
        g.start()
        t = 10_500
        assertEquals(ChallengeGate.Decision.Retry(500), g.onProbeResult("true"))
        t = 11_001
        assertEquals(ChallengeGate.Decision.Exhausted, g.onProbeResult("true"))
    }

    @Test
    fun startIsIdempotentSoARepeatedPageFinishedDoesNotExtendTheBudget() {
        var t = 0L
        val g = gate(budgetMs = 1_000) { t }
        g.start()
        t = 900
        g.start()
        t = 1_001
        assertEquals(ChallengeGate.Decision.Exhausted, g.onProbeResult("true"))
    }
}
