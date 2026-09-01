package com.zerohash.sdk.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RiskGateCloseButtonAssetTest {

    private val setup: String =
        File("src/main/assets/automation/setup-execution-context.js").readText()

    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    private val js: String get() = code(setup)

    private fun step(): String =
        js.substringAfter("function hideRiskGateCloseButton()").substringBefore("\n  }")

    @Test
    fun hidesTheRiskGateCloseButton() {
        assertTrue(
            "the rule must use display:none !important",
            step().contains("display:none !important"),
        )
    }

    @Test
    fun isScopedToTheRiskStepContainer() {
        assertTrue(
            "the selector must carry the risk-step scope, not target buttons globally",
            step().contains("""[data-testid="step-riskSelfServeStep-active"] button.cds-IconButton"""),
        )
    }

    @Test
    fun doesNotHideTheExitsTheUserNeeds() {
        assertFalse(
            "must not target cds-Button — that is Start ID check and Cancel transfer",
            step().contains("cds-Button"),
        )
        assertFalse(
            "must not target the cancel-transfer button",
            step().contains("risk-warning-v2-cancel-button"),
        )
        assertFalse(
            "must not target the start-challenge button",
            step().contains("start-challenge-button"),
        )
    }

    @Test
    fun doesNotDependOnEnglishLabels() {
        assertFalse(
            "must not select the X by its English aria-label",
            step().contains("aria-label"),
        )
    }

    @Test
    fun doesNotRequireTheHasSelector() {
        assertFalse("must not use :has()", step().contains(":has("))
    }

    @Test
    fun survivesDocumentStartWhenHeadDoesNotExistYet() {
        assertTrue(
            "at document start <html> exists but <head> is not parsed, so document.head is " +
                "null and the append must fall back to documentElement. Simplifying this to " +
                "document.head.appendChild throws TypeError, the steps loop swallows it, and " +
                "the fix is silently disabled",
            step().contains("(document.head || document.documentElement).appendChild"),
        )
    }

    @Test
    fun isRegisteredAsASetupStep() {
        val steps = js.substringAfter("var steps").substringBefore("]")
        assertTrue(
            "hideRiskGateCloseButton must be in the steps array",
            steps.contains("hideRiskGateCloseButton"),
        )
    }

    @Test
    fun injectsAStylesheetOnceRatherThanMutatingNodes() {
        assertTrue(
            "must inject a <style> element, not set inline styles on a node",
            step().contains("""createElement("style")"""),
        )
        assertTrue(
            "must guard against injecting twice",
            step().contains("getElementById"),
        )
        assertTrue(
            "the step must actually call inject() — a declared-but-uncalled inject is inert",
            step().contains("\n    inject();"),
        )
    }
}
