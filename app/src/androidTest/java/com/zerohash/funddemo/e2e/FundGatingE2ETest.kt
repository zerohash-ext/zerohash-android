package com.zerohash.funddemo.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.zerohash.funddemo.e2e.utils.Gating
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fund SDK e2e against the real gating backend (AUTH-3838, mirrors the AUTH-3630 web
 * suite). Two gating platforms: Auth-enabled (integrations picker) and non-Auth
 * (ENG-6631 guard). See e2e/README.md.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class FundGatingE2ETest {

    private var harness: FundSessionHarness? = null

    @After
    fun tearDown() {
        harness?.tearDown()
        harness = null
    }

    @Test
    fun authEnabledJwt_rendersIntegrationsSourcePicker() {
        val jwt = Gating.mintJwt(Gating.AUTH_ENABLED)

        val h = FundSessionHarness(jwt).boot()
        harness = h

        h.acceptTermsIfPresent()

        // Auth on -> the source picker (or its manual-deposit escape hatch) must render.
        val picker = h.waitForText(FundSessionHarness.INTEGRATIONS_PICKER_TITLE)
        val manual = if (picker == null) {
            h.waitForText(FundSessionHarness.DEPOSIT_MANUALLY_TEXT, 10_000)
        } else {
            null
        }
        assertTrue(
            "Expected the integrations source picker " +
                "('${FundSessionHarness.INTEGRATIONS_PICKER_TITLE}' or " +
                "'${FundSessionHarness.DEPOSIT_MANUALLY_TEXT}') to render for an " +
                "auth_policy_enabled JWT. Errors so far: ${h.callbacks.errors}",
            picker != null || manual != null,
        )

        assertTrue(
            "SDK reported errors during Auth-enabled boot: ${h.callbacks.errors}",
            h.callbacks.errors.isEmpty(),
        )
    }

    @Test
    fun nonAuthJwt_bootsStraightToNativeFundFlow_eng6631Guard() {
        // No auth_policy_enabled claim — the exact ENG-6631 configuration.
        val jwt = Gating.mintJwt(Gating.NON_AUTH)

        val h = FundSessionHarness(jwt).boot()
        harness = h

        h.acceptTermsIfPresent()

        // Non-Auth -> gate stays off; the native Fund flow must render.
        val selectAsset = h.waitForText(FundSessionHarness.SELECT_ASSET_TITLE)
        assertTrue(
            "Expected '${FundSessionHarness.SELECT_ASSET_TITLE}' to render for a " +
                "non-Auth JWT (ENG-6631 guard). Errors so far: ${h.callbacks.errors}",
            selectAsset != null,
        )

        // ENG-6631 symptom was a terminal auth error on boot — assert none.
        assertTrue(
            "SDK reported errors during non-Auth boot (ENG-6631 regression?): " +
                "${h.callbacks.errors}",
            h.callbacks.errors.isEmpty(),
        )

        assertTrue(
            "Integrations source picker rendered for a non-Auth JWT — the " +
                "integrations gate should be off (ENG-6631).",
            !h.hasText(FundSessionHarness.INTEGRATIONS_PICKER_TITLE),
        )
    }
}