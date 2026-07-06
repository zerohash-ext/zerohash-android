package com.zerohash.sdk.automation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the AUTH-3444 behavior of `withdraw.js`: the submitted-withdraw outcome
 * must carry the on-chain `referenceId` / `sendUuid`, read from the intercepted
 * Coinbase `useCommitSendMutation` GraphQL response (the browser-extension's
 * authoritative source) rather than left null. The success-screen headline stays
 * as a fallback.
 *
 * Pure-JVM content check (the unit-test suite has no JS engine) — Gradle runs
 * unit tests with the module dir as the working directory, same convention as
 * [AuthHideSocialAssetTest] / [AutomationAssetsTest].
 */
class WithdrawResultAssetTest {

    private val js: String =
        File("src/main/assets/automation/withdraw.js").readText()

    @Test
    fun capturesTheCommitSendMutation() {
        assertTrue(
            "withdraw.js must key its interceptor on the useCommitSendMutation op (AUTH-3444)",
            js.contains("useCommitSendMutation"),
        )
    }

    @Test
    fun patchesNetworkTransportsToInterceptTheResponse() {
        // A response body isn't available from Android's shouldInterceptRequest, so
        // the ids must be captured in-page by patching fetch + XHR.
        assertTrue("withdraw.js must patch window.fetch", js.contains("window.fetch"))
        assertTrue("withdraw.js must patch XMLHttpRequest", js.contains("XMLHttpRequest"))
    }

    @Test
    fun readsReferenceIdAndSendUuidFromTheInterceptedSend() {
        assertTrue(
            "waitForResult must read referenceId from the intercepted send object",
            js.contains("send.referenceId"),
        )
        assertTrue(
            "waitForResult must read the send uuid from the intercepted send object",
            js.contains("send.uuid"),
        )
    }

    @Test
    fun evictsPriorWithdrawalsCommitResponseAtStart() {
        // The session/page is reused across sends, so a stale commit response would
        // otherwise surface the previous send's ids. start() must evict it first.
        assertTrue(
            "withdraw.js must forget a prior commit response before a new send",
            js.contains("forgetCommittedSend"),
        )
    }
}
