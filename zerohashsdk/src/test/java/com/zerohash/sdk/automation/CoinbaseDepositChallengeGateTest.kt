package com.zerohash.sdk.automation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoinbaseDepositChallengeGateTest {

    private val coinbaseKt: String =
        File("src/main/java/com/zerohash/sdk/automation/Coinbase.kt").readText()

    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    private fun depositRunnerCall(): String =
        code(coinbaseKt)
            .substringAfter("override suspend fun getDepositAddress")
            .substringBefore("return mapDepositResult")

    @Test
    fun depositAddressGatesOnCloudflareClearanceBeforeInjecting() {
        assertTrue(
            "getDepositAddress must pass waitForChallengeClearance = true. A Cloudflare " +
                "interstitial for /receive is served FROM www.coinbase.com, so settle() " +
                "returns Evaluate for the challenge document and the automation is " +
                "injected into it; `started` then latches, so the post-solve reload can " +
                "never re-inject and the run dies as a bogus selector error.",
            depositRunnerCall().contains("waitForChallengeClearance = true"),
        )
    }

    @Test
    fun depositAddressCeilingCanOutlastAHumanSolvingAChallenge() {
        val budget = Regex("""DEPOSIT_ADDRESS_TIMEOUT_MS\s*=\s*(\d+)_000L""")
            .find(code(coinbaseKt))?.groupValues?.get(1)?.toInt()
        assertTrue(
            "DEPOSIT_ADDRESS_TIMEOUT_MS (${budget}s) must be at least 90s, or the gate " +
                "expires before the user can solve the challenge",
            budget != null && budget >= 90,
        )
    }

    @Test
    fun theChallengeProbeMatchesEveryMarkerIosMatches() {
        val probe = ChallengeGate.CHALLENGE_PROBE
        for (marker in listOf(
            "_cf_chl_opt",
            "#challenge-running",
            "#cf-challenge-running",
            "#challenge-stage",
            "#cf-chl-widget",
            "challenges.cloudflare.com",
            "ch-title-zone",
        )) {
            assertTrue("the Cloudflare probe no longer matches $marker", probe.contains(marker))
        }
    }

    @Test
    fun theRunnerLiftsTheOverlayWhileAChallengeIsUp() {
        val runner = code(
            File("src/main/java/com/zerohash/sdk/automation/VisibleWebViewRunner.kt").readText()
        )
        val poll = runner.substringAfter("private fun pollUntilChallengeClears")
        assertTrue(
            "the challenge poll must lift the overlay: the user cannot solve a Turnstile " +
                "hidden behind the branded cover",
            poll.contains("revealOverlay(true)"),
        )
        assertTrue(
            "the overlay must be restored before the script runs, so the live page is " +
                "never on screen while the automation drives it",
            poll.contains("revealOverlay(false)"),
        )
    }
}
