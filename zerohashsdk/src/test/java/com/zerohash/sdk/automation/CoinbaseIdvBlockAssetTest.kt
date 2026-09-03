package com.zerohash.sdk.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoinbaseIdvBlockAssetTest {

    private val gateJs: String =
        File("src/main/assets/automation/coinbase-idv-gate.js").readText()
    private val withdrawJs: String =
        File("src/main/assets/automation/withdraw.js").readText()
    private val depositJs: String =
        File("src/main/assets/automation/get-deposit-address.js").readText()

    @Test
    fun probesCoinbasesOwnAuthorizationEndpoint() {
        assertTrue(
            "the gate must ask verify-authorization, the endpoint Coinbase's own client uses",
            gateJs.contains("https://login.coinbase.com/api/uis/v1/verify-authorization"),
        )
        assertTrue(
            "the body must match the SPA's so the call is indistinguishable from the flow's own",
            gateJs.contains("df_pro_sealed_result"),
        )
        assertTrue("the session cookie must ride along", gateJs.contains("""credentials: "include""""))
        assertTrue(
            "omitting a header Coinbase's client always sends is a needless way to earn a 4xx",
            gateJs.contains(""""x-cb-platform": "web""""),
        )
    }

    @Test
    fun mapsEachFlowToTheActionCoinbaseExpects() {
        assertTrue(
            "our deposit drives Coinbase's SEND flow",
            withdrawJs.contains("""idvBlockedReasonForAction("sends")"""),
        )
        assertTrue(
            "our withdrawal needs a Coinbase RECEIVE address",
            depositJs.contains("""idvBlockedErrorCodeForAction("receives")"""),
        )
    }

    @Test
    fun blocksOnlyOnTheNamedIdentityComponent() {
        assertTrue(
            "the identity-document requirement is the single gate condition",
            gateJs.contains("COMPONENT_EXPERIENCE_IDENTITY_DOCUMENT_VERIFICATION"),
        )
        assertFalse(
            "an uncharacterised requirement (region, 2FA, tax) must not be relabelled as an identity problem",
            gateJs.contains("COMPONENT_EXPERIENCE_BLOCKING_MESSAGE"),
        )
    }

    @Test
    fun treatsTheCompleteStatusAsNotBlocked() {
        assertTrue(
            "a cleared authorization must let the flow run untouched",
            gateJs.contains("ACTION_AUTHORIZATION_STATUS_COMPLETE"),
        )
        assertTrue(gateJs.contains("AUTHORIZATION_CLEAR"))
    }

    @Test
    fun failsOpenWhenTheProbeCannotBeRead() {
        assertTrue(
            "an unreadable answer must be its own state, never mistaken for 'clear' or 'blocked'",
            gateJs.contains("AUTHORIZATION_UNKNOWN"),
        )
        assertTrue(
            "a Cloudflare interstitial is not an answer",
            gateJs.contains("isCloudflareChallenge"),
        )
        assertTrue("the probe must be time-bounded", gateJs.contains("AbortController"))
        assertTrue(
            "the gate must never throw: a gate that throws fails a flow it cannot judge",
            gateJs.contains("fetchJsonWithoutThrowing"),
        )
    }

    @Test
    fun depositFailsOnTheErrorChannelWithATypedCode() {
        assertTrue(
            "getDepositAddress has no state machine, so the block travels as an error code",
            gateJs.contains(""""IDV_PENDING""""),
        )
        assertTrue(gateJs.contains(""""IDV_FAILED""""))
        assertTrue(
            "both tokens must come from one place so the two channels cannot drift",
            gateJs.contains("function errorCodeForReason("),
        )
        assertFalse(
            "a rejection inside data would let an un-updated consumer read an undefined address",
            depositJs.contains("""state": "rejected"""),
        )
        assertFalse(
            "the deposit runner must not resolve a rejection",
            depositJs.contains("return run().catch"),
        )
    }

    @Test
    fun splitsTheTwoReasonsOnTheAttemptCount() {
        assertTrue(
            "the attempt count is the only signal that distinguishes never-submitted from failed",
            gateJs.contains("https://login.coinbase.com/api/v2/identity-verifications/attempts-remaining"),
        )
        assertTrue(gateJs.contains("number_of_idv_attempts"))
        assertTrue(gateJs.contains(""""idv_pending""""))
        assertTrue(gateJs.contains(""""idv_failed""""))
    }

    @Test
    fun theFailedReasonNeedsPositiveEvidenceOfAPriorAttempt() {
        for (signal in listOf(
            "priorAttemptFromBlockLabel",
            "priorAttemptFromVisibleScreen",
            "priorAttemptFromAttemptCount",
        )) {
            assertTrue("$signal must be one of the evidence sources", gateJs.contains("function $signal"))
        }
        val decision = gateJs.substringAfter("async function reasonForBlock(").substringBefore("\n  }")
        assertEquals(
            "every REASON_IDV_FAILED must sit behind an evidence check",
            3,
            Regex("return REASON_IDV_FAILED").findAll(decision).count(),
        )
    }

    @Test
    fun theAbsenceOfEvidenceYieldsThePendingReason() {
        assertTrue(
            "'verify your identity' is true in both states, so an unresolved block must not claim a document was rejected",
            gateJs.contains("REASON_WITHOUT_PRIOR_ATTEMPT_EVIDENCE = REASON_IDV_PENDING"),
        )
        val decision = gateJs.substringAfter("async function reasonForBlock(").substringBefore("\n  }")
        assertTrue(
            "the fall-through must return the no-evidence reason",
            decision.trimEnd().endsWith("return REASON_WITHOUT_PRIOR_ATTEMPT_EVIDENCE;"),
        )
    }

    @Test
    fun anUnreadableAttemptCountIsNotEvidence() {
        val counter = gateJs.substringAfter("async function priorAttemptFromAttemptCount(")
            .substringBefore("\n  }")
        assertEquals(
            "an unreadable or malformed count must return null, never false-as-evidence",
            2,
            Regex("return null").findAll(counter).count(),
        )
        assertTrue(counter.contains("number_of_idv_attempts > 0"))
    }

    @Test
    fun readsCoinbasesOwnLabelForThePriorAttempt() {
        assertTrue(
            "the guidance-screen component is Coinbase's own marker for a retry, captured in b6b922cd",
            gateJs.contains("COMPONENT_EXPERIENCE_GUIDANCE_SCREEN"),
        )
        assertTrue(
            "it rides in the probe response we already have, so it costs no extra request",
            gateJs.contains("priorAttemptFromBlockLabel(authorization.nextSteps)") ||
                gateJs.contains("reasonForBlock(authorization.nextSteps)"),
        )
    }

    @Test
    fun readsTheRetryScreenFromTheDom() {
        assertTrue(gateJs.contains("onboarding-guidance-container"))
        assertTrue(gateJs.contains("guid-action-btn-"))
        assertTrue(gateJs.contains("PRIOR_ATTEMPT_ANCHORS = ["))
    }

    @Test
    fun matchesTheEnforcerByPrefixSoAVersionBumpStillDetects() {
        assertTrue(
            "the captured testid is versioned; a -v3 bump must not silently un-detect the block",
            gateJs.contains("""[data-testid^="policy-restriction-enforcer"]"""),
        )
        assertFalse(
            "the version suffix must not be pinned in a selector",
            gateJs.contains("""data-testid="policy-restriction-enforcer"""),
        )
    }

    @Test
    fun carriesTheTakeoverChromeAsFallbackAnchors() {
        for (anchor in listOf(
            "identity-access-view-wrapper",
            "onboarding-guidance-container",
            "post-onboarding-navbar-actions",
            "onboarding_fs_loader",
        )) {
            assertTrue(
                "$anchor is takeover chrome the block can present without the in-flow enforcer",
                gateJs.contains(anchor),
            )
        }
    }

    @Test
    fun queriesAnchorsOneAtATimeSoEachIsObservable() {
        assertTrue(gateJs.contains("IDV_BLOCK_ANCHORS = ["))
        assertFalse(
            "a joined selector is one opaque string to any matcher, making 'which anchor fired' untestable",
            gateJs.contains("IDV_BLOCK_ANCHORS.join("),
        )
    }

    @Test
    fun withdrawClassifiesTheBlockOnlyAfterTheRecipientWaitExpired() {
        val race = withdrawJs.substringAfter("async function awaitRecipientOrPendingBlock()")
            .substringBefore("\n  }")
        assertTrue(
            "the block check belongs in the deadline branch",
            race.substringAfter("if (Date.now() >= deadline)").contains("idvBlockedReasonFromDom"),
        )
        assertFalse(
            "identity-access-view-wrapper also mounts during a HEALTHY send's liveness step, so racing the anchors would reject working withdrawals",
            race.substringBefore("if (Date.now() >= deadline)").contains("idvBlockedReasonFromDom"),
        )
    }

    @Test
    fun depositClassifiesTheBlockAtEveryPointItsWaitsCanFail() {
        val entry = depositJs.substringAfter("async function awaitReceiveEntry()")
            .substringBefore("\n  }")
        assertTrue(
            "the entry wait fails when the enforcer replaces the whole flow",
            entry.substringAfter("if (!step)").contains("idvBlockedErrorCodeFromDom"),
        )

        val pick = depositJs.substringAfter("async function pickAsset()").substringBefore("\n  }")
        assertTrue(
            "step-assetSelection-active DOES mount on a blocked account, so the ASSET wait is what fails and asset_not_available would read as markup drift",
            pick.substringAfter("if (!el)").contains("idvBlockedErrorCodeFromDom"),
        )

        val addressLoop = depositJs.substringAfter("var amountWasSubmitted = false;")
            .substringBefore("return run()")
        assertTrue(
            "the takeover is a portal sibling, so it can mount over a step already advanced past",
            addressLoop.substringAfter("await sleep(250);").contains("idvBlockedErrorCodeFromDom"),
        )
    }

    @Test
    fun withdrawSurfacesTheReasonCarriedByTheTaggedError() {
        assertTrue(withdrawJs.contains("e.zhIdvBlocked"))
        assertTrue(
            "the tag carries WHICH reason, so one code path serves both account states",
            withdrawJs.contains("reason: e.zhIdvReason"),
        )
    }

    @Test
    fun bothFlowsCheckBeforeDrivingAnyUi() {
        val start = withdrawJs.substringAfter("start: async function (params)")
            .substringBefore("await enterSendFlow()")
        assertTrue(
            "a blocked account must not spend the 6s grace plus 15s recipient wait",
            start.contains("idvBlockedReasonForAction"),
        )

        val run = depositJs.substringAfter("async function run()")
            .substringBefore("await awaitReceiveEntry()")
        assertTrue(run.contains("idvBlockedErrorCodeForAction"))
    }

    @Test
    fun theCheckDoesNotSpendTheDepositFlowsOwnBudget() {
        assertTrue(
            "DEADLINE caps every wait in the file, so a slow-but-successful check would shorten the flow's budget and could turn a working request into a timeout",
            depositJs.contains("DEADLINE += "),
        )
    }

    @Test
    fun bothFlowsDegradeToTodaysBehaviourWhenTheGateIsAbsent() {
        for ((name, js) in listOf("withdraw.js" to withdrawJs, "get-deposit-address.js" to depositJs)) {
            assertTrue(
                "$name must resolve the gate through an accessor",
                js.contains("function idvGate()"),
            )
            assertFalse(
                "$name dereferencing the gate directly would throw a TypeError and break EVERY transfer if it ever stopped being prepended",
                js.contains("window.__zhCoinbaseIdv."),
            )
        }
    }

    @Test
    fun theGateIsPrependedToBothFlows() {
        val kt = File("src/main/java/com/zerohash/sdk/automation/Coinbase.kt").readText()
        assertEquals(
            "the gate must be in the withdraw prelude and the deposit prelude",
            2,
            Regex("automation/coinbase-idv-gate\\.js").findAll(kt).count(),
        )
    }
}
