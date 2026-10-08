package com.zerohash.sdk.automation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure, stateless decision logic the [AutomationBridge] relies
 * on: the withdraw-state terminality machine ([endsSession]), the error-retry
 * classifier ([isRetryable]), the coalescing policy ([isCoalescable]), and the
 * balance → wire serialization ([balancesToJson]). These are the pieces "most
 * likely to regress" called out in AUTH-3443.
 *
 * Pure JVM (org.json test dep), no device — mirrors [OverlayOptionsTest]. The
 * end-to-end async coalescing in [AutomationBridge.dispatch] (which needs a live
 * WebView/Activity + coroutine driver) is intentionally out of scope here; this
 * covers the coalescing *policy* seam ([isCoalescable]).
 */
class AutomationBridgeLogicTest {

    companion object {
        private const val FICTIONAL_USER_ID = "8b0c2f4e-1a2b-4c3d-9e8f-0a1b2c3d4e5f"
        private const val REQUEST_ID = "request-1"
        private const val SESSION_ID = "session-1"
        private val FICTIONAL_PROFILE = AuthProfile(FICTIONAL_USER_ID, "Jane Mary", "Doe")
        private val FICTIONAL_FAILURE = AuthProfileFailure(ERR_PROFILE_INDETERMINATE, "graphql_error")
        private val REPLY_KEYS = setOf("id", "role", "success", "data", "error", "sessionId", "retryable")
    }

    // ── endsSession: mirrors iOS WithdrawState.endsSession ──────────────────

    @Test
    fun submitted_isTerminal() {
        assertTrue(endsSession(JSONObject().put("state", "submitted")))
    }

    @Test
    fun rejected_isTerminal_exceptOtpRejection() {
        // A generic rejection ends the session…
        assertTrue(
            endsSession(JSONObject().put("state", "rejected").put("reason", "passkey_unsupported")),
        )
        // …but an OTP rejection is retriable, so the session stays open.
        assertFalse(
            endsSession(JSONObject().put("state", "rejected").put("reason", "otp_rejected")),
        )
        // A rejection with no reason is still terminal.
        assertTrue(endsSession(JSONObject().put("state", "rejected")))
    }

    @Test
    fun fundsNotAvailable_isTerminal() {
        assertTrue(
            endsSession(
                JSONObject().put("state", "rejected").put("reason", "funds_not_available"),
            ),
        )
    }

    @Test
    fun awaitingAndProcessing_areNonTerminal() {
        assertFalse(endsSession(JSONObject().put("state", "awaiting-input")))
        assertFalse(endsSession(JSONObject().put("state", "awaiting-user-action")))
        assertFalse(endsSession(JSONObject().put("state", "processing")))
    }

    @Test
    fun unknownOrMissingState_endsSession() {
        // An unrecognized/undecodable discriminant ends the session rather than
        // stranding a live one (iOS rejects an undecodable state outright).
        assertTrue(endsSession(JSONObject().put("state", "wat")))
        assertTrue(endsSession(JSONObject()))
    }

    @Test
    fun poll_doesNotNeedPagePresented() {
        assertFalse(needsPagePresented(JSONObject().put("kind", "poll").toString()))
    }

    @Test
    fun otp_needsPagePresented() {
        assertTrue(
            needsPagePresented(
                JSONObject().put("kind", "otp").put("code", "123456").toString(),
            ),
        )
    }

    @Test
    fun unknownOrMissingKind_needsPagePresented() {
        assertTrue(needsPagePresented(JSONObject().put("kind", "wat").toString()))
        assertTrue(needsPagePresented(JSONObject().toString()))
        assertTrue(needsPagePresented(""))
        assertTrue(needsPagePresented("not json"))
    }

    // ── isRetryable: BALANCES_INDETERMINATE prefix, CHALLENGE_UNSOLVED exact ──

    @Test
    fun balancesIndeterminate_isRetryable_asPrefix() {
        assertTrue(isRetryable("BALANCES_INDETERMINATE"))
        assertTrue(isRetryable("BALANCES_INDETERMINATE: partial fold"))
    }

    @Test
    fun challengeUnsolved_isRetryable_onlyWhenExact() {
        assertTrue(isRetryable("CHALLENGE_UNSOLVED"))
        assertFalse(isRetryable("CHALLENGE_UNSOLVED but not really"))
    }

    @Test
    fun otherErrors_areNotRetryable() {
        assertFalse(isRetryable("not logged in"))
        assertFalse(isRetryable(""))
        assertFalse(isRetryable("CHALLENGE_PRESENT"))
        assertFalse(isRetryable("invalid balance JS return: no 'balances' array"))
    }

    @Test
    fun runnerTimeout_isRetryable() {
        assertTrue(isRetryable("timeout after 10000ms"))
        assertTrue(isRetryable("timeout after 90000ms"))
    }

    @Test
    fun loadFailure_isRetryable() {
        assertTrue(isRetryable("load failed: net::ERR_TIMED_OUT"))
        assertTrue(isRetryable("load failed: net::ERR_INTERNET_DISCONNECTED"))
        assertTrue(isRetryable("load failed: null"))
    }

    // ── isSafeToRetry: only ops the web may re-issue on its own ─────────────

    @Test
    fun onlyIdempotentOps_areSafeToRetry() {
        assertTrue(isSafeToRetry("auth.status"))
        assertTrue(isSafeToRetry("auth.login"))
        assertTrue(isSafeToRetry("getBalance"))
        assertTrue(isSafeToRetry("core.ping"))
        assertTrue(isSafeToRetry("getDepositAddress"))

        assertFalse(isSafeToRetry("withdraw.start"))
        assertFalse(isSafeToRetry("withdraw.continue"))
        assertFalse(isSafeToRetry("withdraw.cancel"))
        assertFalse(isSafeToRetry("something.unknown"))
        assertFalse(isSafeToRetry(""))
    }

    @Test
    fun transientWithdrawFailure_isNotAdvertisedAsRetryable() {
        val msg = "timeout after 300000ms"
        assertTrue(isRetryable(msg))
        assertFalse(isRetryable(msg) && isSafeToRetry("withdraw.continue"))
        assertTrue(isRetryable(msg) && isSafeToRetry("getBalance"))
    }

    @Test
    fun idvBlockedDepositAddress_isNotAdvertisedAsRetryable() {
        // getDepositAddress is re-issuable now, so a terminal IDV block rests
        // entirely on the error being non-transient.
        for (code in listOf("IDV_PENDING", "IDV_FAILED")) {
            assertFalse(isRetryable(code) && isSafeToRetry("getDepositAddress"))
        }
    }

    // ── isCoalescable: only idempotent reads ────────────────────────────────

    @Test
    fun onlyAuthStatusAndGetBalance_areCoalescable() {
        assertTrue(isCoalescable("auth.status"))
        assertTrue(isCoalescable("getBalance"))
        assertFalse(isCoalescable("auth.login"))
        assertFalse(isCoalescable("getDepositAddress"))
        assertFalse(isCoalescable("withdraw.start"))
        assertFalse(isCoalescable("core.ping"))
    }

    // ── balancesToJson: field-for-field, nulls → JSON null ──────────────────

    @Test
    fun balancesToJson_serializesAllFields() {
        val arr = balancesToJson(
            listOf(
                AssetBalance(
                    key = "BTC",
                    label = "Bitcoin",
                    amount = "1.5",
                    notional = "90000",
                    currency = "USD",
                    totalStakedPercent = "10",
                    precision = 8,
                    extractedAt = "2024-01-01T00:00:00Z",
                ),
            ),
        )
        assertEquals(1, arr.length())
        val row = arr.getJSONObject(0)
        assertEquals("BTC", row.getString("key"))
        assertEquals("Bitcoin", row.getString("label"))
        assertEquals("1.5", row.getString("amount"))
        assertEquals("90000", row.getString("notional"))
        assertEquals("USD", row.getString("currency"))
        assertEquals("10", row.getString("totalStakedPercent"))
        assertEquals(8, row.getInt("precision"))
        assertEquals("2024-01-01T00:00:00Z", row.getString("extractedAt"))
    }

    @Test
    fun balancesToJson_nullableFields_becomeJsonNull() {
        val arr = balancesToJson(
            listOf(
                AssetBalance(
                    key = "ETH",
                    label = "Ethereum",
                    amount = "2",
                    notional = "6000",
                    currency = null,
                    totalStakedPercent = null,
                    precision = null,
                    extractedAt = "2024-01-01T00:00:00Z",
                ),
            ),
        )
        val row = arr.getJSONObject(0)
        assertTrue("currency should be JSON null", row.isNull("currency"))
        assertTrue("totalStakedPercent should be JSON null", row.isNull("totalStakedPercent"))
        assertTrue("precision should be JSON null", row.isNull("precision"))
    }

    @Test
    fun balancesToJson_emptyList_isEmptyArray() {
        assertEquals(0, balancesToJson(emptyList()).length())
    }

    @Test
    fun authStatusJson_signedOutHasNeitherProfileNorProfileFailure() {
        val json = authStatusToJson(AuthStatusResult(loggedIn = false))

        assertEquals(setOf("loggedIn"), json.keys().asSequence().toSet())
    }

    @Test
    fun authStatusJson_carriesTheProfile() {
        val json = authStatusToJson(AuthStatusResult(loggedIn = true, profile = FICTIONAL_PROFILE))
        val p = json.getJSONObject("profile")

        assertTrue(json.getBoolean("loggedIn"))
        assertEquals(setOf("userId", "firstName", "lastName"), p.keys().asSequence().toSet())
        assertEquals("Jane Mary", p.getString("firstName"))
    }

    @Test
    fun authLoginJson_keepsOutcome_andProfileOnlyWhenSet() {
        val closed = authLoginToJson(AuthLoginResult(loggedIn = false, outcome = "user-closed"))
        assertEquals(setOf("loggedIn", "outcome"), closed.keys().asSequence().toSet())

        val ok = authLoginToJson(AuthLoginResult(loggedIn = true, outcome = "success", profile = FICTIONAL_PROFILE))
        assertEquals("success", ok.getString("outcome"))
        assertEquals("Doe", ok.getJSONObject("profile").getString("lastName"))
    }

    @Test
    fun authStatusJson_carriesTheProfileFailureInsteadOfAProfile() {
        val result = AuthStatusResult(loggedIn = true, profileFailure = FICTIONAL_FAILURE)

        val json = authStatusToJson(result)
        val failure = json.getJSONObject("profileFailure")

        assertEquals(setOf("loggedIn", "profileFailure"), json.keys().asSequence().toSet())
        assertTrue(json.getBoolean("loggedIn"))
        assertEquals(setOf("error", "reason"), failure.keys().asSequence().toSet())
        assertEquals(ERR_PROFILE_INDETERMINATE, failure.getString("error"))
        assertEquals("graphql_error", failure.getString("reason"))
    }

    @Test
    fun authLoginJson_carriesOutcomeAndTheProfileFailureInsteadOfAProfile() {
        val result = AuthLoginResult(loggedIn = true, outcome = "success", profileFailure = FICTIONAL_FAILURE)

        val json = authLoginToJson(result)
        val failure = json.getJSONObject("profileFailure")

        assertEquals(setOf("loggedIn", "outcome", "profileFailure"), json.keys().asSequence().toSet())
        assertTrue(json.getBoolean("loggedIn"))
        assertEquals("success", json.getString("outcome"))
        assertEquals(setOf("error", "reason"), failure.keys().asSequence().toSet())
        assertEquals(ERR_PROFILE_INDETERMINATE, failure.getString("error"))
        assertEquals("graphql_error", failure.getString("reason"))
    }

    @Test
    fun authJson_withAProfileHasNoProfileFailureKey() {
        val status = authStatusToJson(AuthStatusResult(loggedIn = true, profile = FICTIONAL_PROFILE))
        val login = authLoginToJson(AuthLoginResult(loggedIn = true, outcome = "success", profile = FICTIONAL_PROFILE))

        assertEquals(setOf("loggedIn", "profile"), status.keys().asSequence().toSet())
        assertEquals(setOf("loggedIn", "outcome", "profile"), login.keys().asSequence().toSet())
    }

    @Test
    fun successReply_forAStatusProfileFailure_isSuccessfulAndNotRetryable() {
        val data = authStatusToJson(AuthStatusResult(loggedIn = true, profileFailure = FICTIONAL_FAILURE))

        val reply = replyJson(REQUEST_ID, success = true, data = data, error = null)
        val replyData = reply.getJSONObject("data")

        assertEquals(REPLY_KEYS, reply.keys().asSequence().toSet())
        assertEquals(REQUEST_ID, reply.getString("id"))
        assertEquals("zeroauth-native", reply.getString("role"))
        assertTrue(reply.getBoolean("success"))
        assertFalse(reply.getBoolean("retryable"))
        assertEquals(JSONObject.NULL, reply.get("error"))
        assertEquals(JSONObject.NULL, reply.get("sessionId"))
        assertEquals(setOf("loggedIn", "profileFailure"), replyData.keys().asSequence().toSet())
        assertTrue(replyData.getBoolean("loggedIn"))
        assertEquals("graphql_error", replyData.getJSONObject("profileFailure").getString("reason"))
    }

    @Test
    fun successReply_forALoginProfileFailure_isSuccessfulAndNotRetryable() {
        val login = AuthLoginResult(loggedIn = true, outcome = "success", profileFailure = FICTIONAL_FAILURE)

        val reply = replyJson(REQUEST_ID, success = true, data = authLoginToJson(login), error = null)
        val replyData = reply.getJSONObject("data")

        assertEquals(REPLY_KEYS, reply.keys().asSequence().toSet())
        assertTrue(reply.getBoolean("success"))
        assertFalse(reply.getBoolean("retryable"))
        assertEquals(JSONObject.NULL, reply.get("error"))
        assertEquals(setOf("loggedIn", "outcome", "profileFailure"), replyData.keys().asSequence().toSet())
        assertTrue(replyData.getBoolean("loggedIn"))
        assertEquals("success", replyData.getString("outcome"))
        assertEquals(ERR_PROFILE_INDETERMINATE, replyData.getJSONObject("profileFailure").getString("error"))
    }

    @Test
    fun errorReply_carriesTheErrorSessionAndTelemetry_andNullData() {
        val telemetry = JSONArray().put(JSONObject().put("event_name", "auth_profile_result"))

        val reply = replyJson(REQUEST_ID, false, null, "timeout after 30000ms", true, SESSION_ID, telemetry)

        assertEquals(REPLY_KEYS + "telemetry", reply.keys().asSequence().toSet())
        assertFalse(reply.getBoolean("success"))
        assertTrue(reply.getBoolean("retryable"))
        assertEquals(JSONObject.NULL, reply.get("data"))
        assertEquals("timeout after 30000ms", reply.getString("error"))
        assertEquals(SESSION_ID, reply.getString("sessionId"))
        assertEquals(1, reply.getJSONArray("telemetry").length())
    }

    @Test
    fun profileCodes_areNotRetryableByMessagePrefix() {
        assertFalse(isRetryable(ERR_PROFILE_INDETERMINATE))
        assertFalse(isRetryable(ERR_PROFILE_INCOMPLETE))
    }
}
