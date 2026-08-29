package com.zerohash.funddemo.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.zerohash.funddemo.e2e.utils.Gating
import com.zerohash.funddemo.e2e.utils.TestJwt
import com.zerohash.sdk.ZerohashError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fund Withdrawals SDK e2e against the real gating backend — the Android
 * counterpart of the flow zerohash-ios shipped in AUTH-2380.
 *
 * Unlike [CryptoWithdrawalsGatingE2ETest], the happy path here is runnable: the
 * gating withdrawal participant (`Gating.FUND_WITHDRAWALS`) has an approved
 * external account, and [Gating.mintFundWithdrawalsJwt] resolves it at mint time.
 * Minted tokens are short-lived (~5 min), which is exactly why the mint happens
 * in-test rather than from a pasted token.
 *
 * See e2e/README.md.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class FundWithdrawalsGatingE2ETest {

    private var harness: FundWithdrawalsSessionHarness? = null

    @After
    fun tearDown() {
        harness?.tearDown()
        harness = null
    }

    // ── the flow itself ─────────────────────────────────────────────────────

    @Test
    fun provisionedJwt_bootsIntoWithdrawalFlow() {
        val jwt = Gating.mintFundWithdrawalsJwt()

        val h = FundWithdrawalsSessionHarness(jwt).boot()
        harness = h

        assertNotNull(
            "present() should return a session for a freshly minted gating JWT. " +
                "Errors: ${h.callbacks.errors}",
            h.presentedSession,
        )

        assertTrue(
            "SDK reported errors during Fund Withdrawals boot: ${h.callbacks.errors}",
            h.callbacks.errors.isEmpty(),
        )

        // `errors.isEmpty()` alone is NOT evidence the flow loaded: the SDK
        // registers no WebViewClient error callbacks, so a failed page load — a TLS
        // failure behind a corporate proxy, say — renders the web app's own
        // "Something went wrong" card and reaches no native callback at all. The
        // load has to be asserted positively, or this spec passes on a blank flow.
        val loaded = h.waitForText(
            LOADED_ANCHOR,
            FundWithdrawalsSessionHarness.DEFAULT_TIMEOUT_MS,
        )
        assertNotNull(
            "The flow never rendered '$LOADED_ANCHOR'. If the WebView shows " +
                "'${WEB_LOAD_ERROR_ANCHOR}', the device could not load " +
                "$WEB_APP_HOST — check TLS interception on the device's network " +
                "rather than the SDK.",
            loaded,
        )
        assertTrue(
            "The WebView is showing the web app's load-failure card, so the flow " +
                "did not start.",
            !h.hasText(WEB_LOAD_ERROR_ANCHOR),
        )
    }

    @Test
    fun mintResolvesAnApprovedExternalAccount() {
        // Guards the mint chain itself: if the participant loses its approved
        // account, mintFundWithdrawalsJwt throws with a message that says so,
        // rather than the flow failing later with an opaque backend error.
        val jwt = Gating.mintFundWithdrawalsJwt()

        assertTrue("minted JWT should be a 3-segment token", jwt.split(".").size == 3)
        assertTrue("minted JWT should not be blank", jwt.isNotBlank())
    }

    // ── client-side JWT gate: the flow must not start ────────────────────────

    @Test
    fun malformedJwt_isRejectedAndFlowDoesNotStart() {
        assertRejectedBeforeLaunch(TestJwt.malformed(), "malformed")
    }

    @Test
    fun expiredJwt_isRejectedAndFlowDoesNotStart() {
        // Directly relevant here: minted gating tokens expire in ~5 minutes, so a
        // stale token is the most likely failure a developer hits.
        assertRejectedBeforeLaunch(TestJwt.expired(), "expired")
    }

    @Test
    fun algNoneJwt_isRejectedAndFlowDoesNotStart() {
        assertRejectedBeforeLaunch(TestJwt.algNone(), "alg:none")
    }

    private fun assertRejectedBeforeLaunch(jwt: String, label: String) {
        val h = FundWithdrawalsSessionHarness(jwt).boot()
        harness = h

        assertNull(
            "present() must return null for a $label JWT. Errors: ${h.callbacks.errors}",
            h.presentedSession,
        )
        assertEquals(
            "Expected exactly one error for a $label JWT, got: ${h.callbacks.errors}",
            1,
            h.callbacks.errors.size,
        )
        assertTrue(
            "A rejected JWT must surface as ConfigurationError, got: " +
                "${h.callbacks.errors.first()}",
            h.callbacks.errors.first() is ZerohashError.ConfigurationError,
        )

        Thread.sleep(FundWithdrawalsSessionHarness.NO_LAUNCH_SETTLE_MS)
        assertTrue(
            "No bridge events should arrive for a $label JWT — their presence means a " +
                "WebView was launched anyway. Events: ${h.callbacks.events}",
            h.callbacks.events.isEmpty(),
        )
        assertTrue(
            "No withdrawal should complete for a $label JWT: ${h.callbacks.completions}",
            h.callbacks.completions.isEmpty(),
        )
    }

    // ── session lifecycle ───────────────────────────────────────────────────

    @Test
    fun cancelAfterPresent_firesOnCloseOnce() {
        // Only needs a structurally valid token: the close contract is native, so
        // it holds regardless of what the web app does with the token.
        val h = FundWithdrawalsSessionHarness(TestJwt.valid()).boot()
        harness = h
        assertNotNull("present() should return a session", h.presentedSession)

        h.cancel()
        h.cancel()

        assertEquals(
            "cancel() must fire onClose exactly once, even when called twice",
            1,
            h.callbacks.closeCount,
        )
        assertTrue(
            "Session should not report active after cancel()",
            !h.presentedSession!!.isActive(),
        )
    }

    private companion object {
        /**
         * Text the loaded flow renders. Deliberately broad: the withdrawal screens
         * are still pending design (AUTH-3214), so pinning an exact title would
         * fail for the wrong reason once they land. Narrow it once the screens are
         * final.
         */
        const val LOADED_ANCHOR = "Withdraw"

        /**
         * The web app's own load-failure card. Its presence means the page could
         * not fetch what it needs — a network/TLS problem on the device, not an SDK
         * defect — so the spec names it explicitly instead of just timing out.
         */
        const val WEB_LOAD_ERROR_ANCHOR = "Something went wrong"

        const val WEB_APP_HOST = "connect-sdk.gating.0hash.com"
    }
}
