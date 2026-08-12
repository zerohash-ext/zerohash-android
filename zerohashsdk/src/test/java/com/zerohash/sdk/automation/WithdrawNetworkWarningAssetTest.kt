package com.zerohash.sdk.automation

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Guards the AUTH-3960 fix in `withdraw.js`: the Coinbase BASE (and other
 * loss-prone network) acceptance-warning handling.
 *
 * The bug — after we click "Yes, it's supported", Coinbase re-stamps the
 * l2SelectionStep container `-inactive` but leaves it mounted and laid out. The
 * old `isVisible` check only looks at offsetParent + a non-zero rect (never
 * opacity), so the just-acknowledged button still read as "visible" and the old
 * warning-check-first ordering re-detected `networkWarning`, tripping
 * `withdraw/selection-phase-stalled: revisited networkWarning` (or a downstream
 * "Neither confirm screen nor amount screen appeared").
 *
 * The **faded-container regression** these tests defend is precisely: an
 * acknowledge control that is still laid out but sits inside a container Coinbase
 * has re-stamped `-inactive` must NOT be re-detected as a fresh warning.
 *
 * These are pure-JVM CONTENT checks — the Android unit-test suite has no JS engine
 * (`withdraw.js` uses async/await, which Rhino can't run and Nashorn is gone from
 * JDK 17), so unlike the iOS port (which executes the driver under `node --test`
 * against a mini-DOM) we assert on the driver's SOURCE that the regression-fixing
 * constructs are present and the buggy ones are gone. Gradle runs unit tests with
 * the module dir as the working directory — same convention as
 * [WithdrawResultAssetTest] / [AutomationAssetsTest].
 */
class WithdrawNetworkWarningAssetTest {

    private val js: String =
        File("src/main/assets/automation/withdraw.js").readText()

    // ── The core fix: live-scoped visibility that excludes faded containers ──

    @Test
    fun definesInactiveStepContainerSelector() {
        // The stale container the fix must skip: a step Coinbase re-stamped
        // `-inactive` while it fades out (still laid out, so isVisible is fooled).
        assertTrue(
            "withdraw.js must define the STEP_INACTIVE container selector",
            js.contains("""var STEP_INACTIVE = '[data-testid^="step-"][data-testid$="-inactive"]';"""),
        )
    }

    @Test
    fun queryVisibleLiveExcludesNodesInsideInactiveContainers() {
        // The crux of the regression: a visible node inside an `-inactive`
        // container must be treated as STALE (skipped), because isVisible alone
        // can't tell a fading container from a live one.
        assertTrue(
            "withdraw.js must define queryVisibleLive",
            js.contains("function queryVisibleLive("),
        )
        assertTrue(
            "queryVisibleLive must skip nodes inside a STEP_INACTIVE container via closest()",
            js.contains("if (nodes[i].closest(SEL.STEP_INACTIVE)) continue;"),
        )
    }

    // ── detectNextScreen: advance signals BEFORE the warning check ──

    @Test
    fun detectNextScreenDropsTheOldWarningFirstShortCircuit() {
        // The pre-fix line led detectNextScreen with a raw visibility check on the
        // acknowledge testid — the exact source of the re-detection. It must be gone.
        assertFalse(
            "withdraw.js must NOT lead detectNextScreen with the raw warning-first check",
            js.contains("""if (queryVisible(SEL.NETWORK_WARNING_CONTINUE)) return "networkWarning";"""),
        )
    }

    @Test
    fun detectNextScreenChecksAdvanceSignalsBeforeTheWarning() {
        // Advance-first ordering: the active-step mapping and the amount content
        // anchor must both be evaluated before the network-warning detection, so a
        // warning fading inside its container can't out-rank the screen we reached.
        val mappedStep = js.indexOf("var mapped = screenForStep(step);")
        val amountAnchor = js.indexOf("""if (queryVisibleLive(SEL.CURRENCY_INPUT)) return "amount";""")
        val warningCheck = js.indexOf("if (findNetworkWarningAck({ allowFallback: Date.now() - start >= fallbackAfterMs })) {")
        assertTrue("screenForStep mapping missing from detectNextScreen", mappedStep >= 0)
        assertTrue("amount content anchor missing from detectNextScreen", amountAnchor >= 0)
        assertTrue("network-warning detection missing from detectNextScreen", warningCheck >= 0)
        assertTrue(
            "detectNextScreen must map the active step before checking the warning",
            mappedStep < warningCheck,
        )
        assertTrue(
            "detectNextScreen must anchor on the amount input before checking the warning",
            amountAnchor < warningCheck,
        )
    }

    // ── findNetworkWarningAck: live-only, refuses a live network list ──

    @Test
    fun ackFinderResolvesLiveAndRefusesWhenNetworkListStillPresent() {
        assertTrue("withdraw.js must define findNetworkWarningAck", js.contains("function findNetworkWarningAck("))
        assertTrue(
            "findNetworkWarningAck must resolve the live ack control (queryVisibleLive)",
            js.contains("var direct = queryVisibleLive(SEL.NETWORK_WARNING_CONTINUE);"),
        )
        assertTrue(
            "findNetworkWarningAck must refuse while the l2 container still holds network cells",
            js.contains("if (root.querySelector(SEL.NETWORK_ITEMS_ANY)) return null;"),
        )
    }

    @Test
    fun ackLabelFallbackUsesTheCurlyApostrophe() {
        // Coinbase ships the acknowledge label with a CURLY apostrophe (U+2019); an
        // ASCII-only fallback would silently miss it on a testid drift.
        assertTrue(
            "the label fallback must include the curly-apostrophe form Coinbase renders",
            js.contains("\"Yes, it’s supported\""),
        )
    }

    // ── dismissNetworkWarning: live finder, confirm, never throw ──

    @Test
    fun dismissResolvesViaLiveFinderNotRawWaitForElement() {
        val dismiss = sliceFunction("async function dismissNetworkWarning(")
        assertTrue(
            "dismissNetworkWarning must resolve the ack via the live finder",
            dismiss.contains("findNetworkWarningAck({ allowFallback: true })"),
        )
        assertFalse(
            "dismissNetworkWarning must NOT resolve via the raw, unfiltered waitForElement",
            dismiss.contains("waitForElement(SEL.NETWORK_WARNING_CONTINUE"),
        )
        assertTrue(
            "dismissNetworkWarning must confirm dismissal via settledPastWarning",
            dismiss.contains("settledPastWarning("),
        )
        assertFalse(
            "dismissNetworkWarning must NOT throw — the selection loop owns the retry budget",
            dismiss.contains("throw "),
        )
    }

    // ── runSelectionPhase: per-screen caps + wall-clock budget + DI seam ──

    @Test
    fun selectionCapsAllowRepeatedWarningButSingleShotEverythingElse() {
        assertTrue(
            "networkWarning must tolerate a few re-detects; coin/network/destinationTag single-shot",
            js.contains("var SCREEN_ATTEMPT_CAP = { coin: 1, network: 1, networkWarning: 3, destinationTag: 1 };"),
        )
    }

    @Test
    fun selectionPhaseHasWallClockBudget() {
        assertTrue(
            "runSelectionPhase must enforce a wall-clock budget as defence in depth",
            js.contains("var SELECTION_PHASE_BUDGET_MS = 120000;"),
        )
        assertTrue(
            "budget exhaustion must be reported as a stall",
            js.contains("withdraw/selection-phase-stalled: budget exhausted"),
        )
    }

    @Test
    fun selectionPhaseInjectionSeamDefaultsToRealCollaborators() {
        // Test-only DI seam: production must run the real detect/handlers when no
        // opts are passed, so the default path is unchanged (RESTRICCIONES).
        val phase = sliceFunction("async function runSelectionPhase(payload, opts)")
        assertTrue("runSelectionPhase must accept an opts seam", phase.isNotEmpty())
        assertTrue(
            "detect must default to the real detectNextScreen",
            phase.contains("var detect = opts.detect || detectNextScreen;"),
        )
        assertTrue(
            "handlers must default to the real SELECTION map",
            phase.contains("var SELECTION = opts.handlers ||"),
        )
        assertTrue(
            "budgetMs must default to the real wall-clock budget",
            phase.contains("opts.budgetMs || SELECTION_PHASE_BUDGET_MS"),
        )
    }

    /**
     * Return the source of the function whose declaration starts with [signature],
     * up to (not including) the start of the next top-level `function `/`async
     * function ` declaration. Good enough to scope an assertion to one function
     * without a JS parser.
     */
    private fun sliceFunction(signature: String): String {
        val start = js.indexOf(signature)
        if (start < 0) return ""
        val from = start + signature.length
        val candidates = listOf(
            js.indexOf("\n  function ", from),
            js.indexOf("\n  async function ", from),
        ).filter { it >= 0 }
        val next = candidates.minOrNull() ?: js.length
        return js.substring(start, next)
    }
}
