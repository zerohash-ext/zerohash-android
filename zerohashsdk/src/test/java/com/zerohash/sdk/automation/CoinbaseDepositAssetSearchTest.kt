package com.zerohash.sdk.automation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoinbaseDepositAssetSearchTest {

    private val assetDir = File("src/main/assets/automation")
    private val depositJs: String = File(assetDir, "get-deposit-address.js").readText()
    private val withdrawJs: String = File(assetDir, "withdraw.js").readText()
    private val domHelpersJs: String = File(assetDir, "shared-dom-helpers.js").readText()

    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    @Test
    fun pickAssetFiltersTheAssetListInsteadOfOnlyPollingIt() {
        val body = code(depositJs)
            .substringAfter("async function pickAsset()")
            .substringBefore("\n  }")
        assertTrue(
            "pickAsset must type into the asset step's own filter box, or an asset that " +
                "is not already rendered can never be found however long we poll",
            body.contains("SEARCH_INPUT") && body.contains("setReactValue"),
        )
    }

    @Test
    fun theFilterBoxIsTheAssetStepsOwnNotTheSendFlowsAddressField() {
        assertTrue(
            "the anchor must be the asset step's search-input (its placeholder is " +
                "localized, so the testid is the only stable handle)",
            code(depositJs).contains("""var SEARCH_INPUT = '[data-testid="search-input"]'"""),
        )
        assertTrue(
            "withdraw.js's recipient-search-input is the send flow's destination ADDRESS " +
                "field and must not be confused for an asset filter",
            code(withdrawJs).contains("""var RECIPIENT_INPUT = '[data-testid="recipient-search-input"]'"""),
        )
    }

    @Test
    fun assetNotAvailableReportsWhatWasOnScreen() {
        assertTrue(
            "asset_not_available must enumerate the visible assets, or 'Coinbase does " +
                "not offer it' is indistinguishable from 'our filter never took effect'",
            code(depositJs).contains("visibleAssets()"),
        )
    }

    @Test
    fun theProbeBeforeFilteringIsShortEnoughToLeaveTimeToFilter() {
        val probeMs = Regex("""waitFor\(sel,\s*(\d+)\)""")
            .find(code(depositJs))?.groupValues?.get(1)?.toInt()
        assertTrue(
            "pickAsset's pre-filter probe (${probeMs}ms) must stay well under the run " +
                "deadline so the filter round-trip still fits",
            probeMs != null && probeMs <= 2000,
        )
    }

    @Test
    fun setReactValueIsSharedRatherThanDuplicated() {
        // The generated script publishes its surface with shorthand properties
        // (`setReactValue,`), where the hand-written one wrote them out in full
        // (`setReactValue: setReactValue`). Match either, so this keeps holding
        // whether the file came from the library or is still hand-maintained.
        assertTrue(
            "shared-dom-helpers.js must export setReactValue — both the deposit filter and the " +
                "withdraw recipient field depend on it",
            Regex("setReactValue(?:\\s*:\\s*setReactValue)?\\s*[,}]")
                .containsMatchIn(code(domHelpersJs)),
        )
        assertTrue(
            "withdraw.js must call the shared D.setReactValue (behind its screen-guard " +
                "primitive) instead of carrying its own native-setter copy",
            code(withdrawJs).contains("D.setReactValue(") &&
                !code(withdrawJs).contains("HTMLInputElement.prototype"),
        )
    }
}
