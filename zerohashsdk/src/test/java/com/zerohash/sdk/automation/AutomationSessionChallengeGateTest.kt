package com.zerohash.sdk.automation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the wiring of [ChallengeGate] into [AutomationSession] (AUTH-4245). No
 *  Robolectric, so most guards read source text. */
class AutomationSessionChallengeGateTest {

    private val sessionKt: String =
        File("src/main/java/com/zerohash/sdk/automation/AutomationSession.kt").readText()
    private val bridgeKt: String =
        File("src/main/java/com/zerohash/sdk/automation/AutomationBridge.kt").readText()

    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    @Test
    fun challengeBudgetMustFitInsideTheInitialLoadTimeout() {
        assertTrue(
            "CHALLENGE_BUDGET_MS (${AutomationSession.CHALLENGE_BUDGET_MS}) must be < " +
                "INITIAL_LOAD_TIMEOUT_MS (${AutomationSession.INITIAL_LOAD_TIMEOUT_MS}) " +
                "or the gate is bypassed on expiry",
            AutomationSession.CHALLENGE_BUDGET_MS < AutomationSession.INITIAL_LOAD_TIMEOUT_MS,
        )
    }

    @Test
    fun theChallengeProbeStringLiteralExistsInExactlyOnePlace() {
        val owners = File("src/main/java/com/zerohash/sdk/automation")
            .listFiles { f -> f.name.endsWith(".kt") }
            .orEmpty()
            .filter { code(it.readText()).contains("_cf_chl_opt") }
            .map { it.name }
        assertTrue(
            "the Cloudflare probe string must live in exactly one file; found in $owners",
            owners.size == 1,
        )
    }

    @Test
    fun pageFinishedDelegatesToTheGateInsteadOfCompletingTheLoadDirectly() {
        val body = code(sessionKt)
            .substringAfter("override fun onPageFinished")
            .substringBefore("}")
        assertTrue(
            "onPageFinished must start the readiness gate",
            body.contains("beginReadinessGate()"),
        )
        assertTrue(
            "onPageFinished must NOT complete initialLoad directly — that is the bug",
            !body.contains("initialLoad.complete"),
        )
    }

    @Test
    fun anUnsolvedChallengeFailsTheLoadInsteadOfProceeding() {
        assertTrue(
            "an exhausted gate must complete initialLoad exceptionally",
            code(sessionKt).contains("initialLoad.completeExceptionally"),
        )
    }

    @Test
    fun aFailedLoadDismissesTheSessionSoItCannotLeakOnScreen() {
        val block = code(bridgeKt)
            .substringAfter("withdrawStarting = true")
            .substringBefore("private suspend fun withdrawContinue")
        val guardedTry = block.substringAfter("val state = try {").substringBefore("} catch")
        val dismissingCatch = block.substringAfter("} catch").substringBefore("}")
        assertTrue(
            "session.load() must sit INSIDE the try whose catch dismisses the session",
            guardedTry.contains("session.load()"),
        )
        assertTrue(
            "that catch must dismiss the session and rethrow",
            dismissingCatch.contains("session.dismiss()") && dismissingCatch.contains("throw e"),
        )
    }
}
