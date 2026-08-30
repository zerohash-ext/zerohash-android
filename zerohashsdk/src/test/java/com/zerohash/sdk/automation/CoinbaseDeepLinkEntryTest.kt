package com.zerohash.sdk.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Send and receive are entered at their own URLs, not clicked through from `/home`.
 *  No JS engine in this suite, so JS behaviour is asserted on source text. */
class CoinbaseDeepLinkEntryTest {

    private val assetDir = File("src/main/assets/automation")
    private val withdraw: String = File(assetDir, "withdraw.js").readText()
    private val deposit: String = File(assetDir, "get-deposit-address.js").readText()
    private val coinbaseKt: String =
        File("src/main/java/com/zerohash/sdk/automation/Coinbase.kt").readText()

    /** Cuts at the first `//`, which truncates URLs, so URL assertions use raw source. */
    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    @Test
    fun withdrawStartsAtTheSendDeepLinkNotTheDashboard() {
        assertEquals("https://www.coinbase.com/send", Coinbase.withdrawUrl)
    }

    @Test
    fun getDepositAddressStartsAtTheReceiveDeepLinkNotTrade() {
        val fn = code(coinbaseKt)
            .substringAfter("override suspend fun getDepositAddress")
            .substringBefore("\n    override")
        assertTrue(
            "getDepositAddress must load RECEIVE_URL",
            fn.contains("url = RECEIVE_URL"),
        )
        assertTrue(
            "RECEIVE_URL must be coinbase.com/receive",
            coinbaseKt.contains("""RECEIVE_URL = "https://www.coinbase.com/receive""""),
        )
    }

    @Test
    fun startDrivesEnterSendFlowRatherThanTheOpenerDirectly() {
        val start = code(withdraw).substringAfter("start: async function")
            .substringBefore("continue:")
        assertTrue(
            "start must call enterSendFlow()",
            start.contains("await enterSendFlow()"),
        )
        assertTrue(
            "start must not call openSendModal() directly — that skips the deep-link path",
            !start.contains("await openSendModal()"),
        )
    }

    @Test
    fun enterSendFlowWaitsForTheStepAndDoesNotClickThrough() {
        val fn = code(withdraw).substringAfter("async function enterSendFlow()")
            .substringBefore("\n  }")
        assertTrue(
            "enterSendFlow must wait for the deep-landed recipient step",
            fn.contains("awaitRecipientOrPendingBlock()"),
        )
        assertTrue(
            "enterSendFlow must not click through — the openers are gone",
            !fn.contains("openSendModal"),
        )
    }

    @Test
    fun enterSendFlowDoesNotSwallowTheFailure() {
        val fn = code(withdraw).substringAfter("async function enterSendFlow()")
            .substringBefore("\n  }")
        assertTrue("enterSendFlow must not catch", !fn.contains("catch"))
    }

    @Test
    fun receiveEntryClearsInterstitialsWhileItWaits() {
        val fn = code(deposit).substringAfter("function awaitReceiveEntry()")
            .substringBefore("\n  }")
        assertTrue(
            "awaitReceiveEntry must sweep interstitials on each poll",
            fn.contains("dismissInterstitials()"),
        )
        assertTrue(
            "awaitReceiveEntry must not click through — the opener is gone",
            !fn.contains("openReceiveModal"),
        )
    }

    @Test
    fun theClickThroughOpenersAreGone() {
        for (fn in listOf("openSendModal", "openSendModalStandard", "openSendModalAdvance")) {
            assertTrue("$fn must be deleted, not kept as a fallback",
                !code(withdraw).contains("function $fn("))
        }
        assertTrue(
            "openReceiveModal must be deleted",
            !code(deposit).contains("function openReceiveModal("),
        )
        for (sel in listOf("QUICK_ACTION_SEND", "TRANSFER_DROPDOWN_BUTTON", "BOTTOM_DRAWER_BUTTON")) {
            assertTrue("$sel served only the click-through and must go with it",
                !code(withdraw).contains(sel))
        }
    }

    @Test
    fun failingToMountNamesWhatItWaitedFor() {
        assertTrue(
            "the receive entry failure must name the URL",
            code(deposit).contains("receive_entry_not_found:step") &&
                code(deposit).contains("RECEIVE_URL"),
        )
    }

    @Test
    fun entryPathIsBreadcrumbed() {
        assertTrue(
            "withdraw.js must breadcrumb the send entry",
            code(withdraw).contains("""bc("send-entry", "deep-link")"""),
        )
        assertTrue(
            "no fallback breadcrumb should remain",
            !code(withdraw).contains("fallback-openSendModal"),
        )
    }
}
