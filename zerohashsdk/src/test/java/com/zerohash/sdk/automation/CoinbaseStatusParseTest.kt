package com.zerohash.sdk.automation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class CoinbaseStatusParseTest {

    companion object {
        private const val FICTIONAL_USER_ID = "8b0c2f4e-1a2b-4c3d-9e8f-0a1b2c3d4e5f"
    }

    private fun profileJson() = JSONObject()
        .put("userId", FICTIONAL_USER_ID)
        .put("firstName", "Jane Mary")
        .put("lastName", "Doe")

    private fun scriptFailure(error: String, reason: String) = JSONObject()
        .put("loggedIn", true)
        .put("profileFailure", JSONObject().put("error", error).put("reason", reason))

    private fun failedStatus(error: String, reason: String) =
        AuthStatusResult(loggedIn = true, profileFailure = AuthProfileFailure(error, reason))

    @Test
    fun signedOut_hasNoProfile_evenIfTheScriptSentOne() {
        val r = Coinbase.parseStatus(JSONObject().put("loggedIn", false).put("profile", profileJson()))

        assertEquals(AuthStatusResult(loggedIn = false), r)
    }

    @Test
    fun signedIn_returnsTheProfileUnchanged() {
        val r = Coinbase.parseStatus(JSONObject().put("loggedIn", true).put("profile", profileJson()))

        assertEquals(AuthStatusResult(true, AuthProfile(FICTIONAL_USER_ID, "Jane Mary", "Doe")), r)
    }

    @Test
    fun brokenProbe_isInvalidJsReturn_notAProfileError() {
        for (raw in listOf(null, JSONObject(), JSONObject().put("loggedIn", JSONObject.NULL))) {
            try {
                Coinbase.parseStatus(raw)
                fail("expected PlatformException")
            } catch (e: PlatformException) {
                assertEquals("invalid JS return", e.message)
            }
        }
    }

    @Test
    fun scriptProfileFailure_isReturnedAsTheFailureForItsReason() {
        val indeterminate = Coinbase.parseStatus(scriptFailure(ERR_PROFILE_INDETERMINATE, "graphql_error"))
        val incomplete = Coinbase.parseStatus(scriptFailure(ERR_PROFILE_INCOMPLETE, "missing_field"))

        assertEquals(failedStatus(ERR_PROFILE_INDETERMINATE, "graphql_error"), indeterminate)
        assertEquals(failedStatus(ERR_PROFILE_INCOMPLETE, "missing_field"), incomplete)
    }

    @Test
    fun scriptProfileFailure_derivesTheErrorFromTheReasonAndNormalisesUnknownReasons() {
        val mismatched = Coinbase.parseStatus(scriptFailure(ERR_PROFILE_INDETERMINATE, "missing_field"))
        val unknown = Coinbase.parseStatus(scriptFailure(ERR_PROFILE_INCOMPLETE, "Jane Doe"))
        val noReason = Coinbase.parseStatus(
            JSONObject().put("loggedIn", true).put("profileFailure", JSONObject().put("error", ERR_PROFILE_INCOMPLETE)),
        )

        assertEquals(failedStatus(ERR_PROFILE_INCOMPLETE, "missing_field"), mismatched)
        assertEquals(failedStatus(ERR_PROFILE_INDETERMINATE, "invalid_response"), unknown)
        assertEquals(failedStatus(ERR_PROFILE_INDETERMINATE, "invalid_response"), noReason)
    }

    @Test
    fun scriptProfileFailure_winsOverAProfileSentAlongsideIt() {
        val raw = scriptFailure(ERR_PROFILE_INDETERMINATE, "timeout").put("profile", profileJson())

        assertEquals(failedStatus(ERR_PROFILE_INDETERMINATE, "timeout"), Coinbase.parseStatus(raw))
    }

    @Test
    fun signedInWithNeitherProfileNorFailure_isIndeterminateInvalidResponse() {
        val r = Coinbase.parseStatus(JSONObject().put("loggedIn", true))

        assertEquals(failedStatus(ERR_PROFILE_INDETERMINATE, "invalid_response"), r)
    }

    @Test
    fun signedInWithAnIncompleteProfile_isIncomplete() {
        val blankName = profileJson().put("lastName", " ")
        val missingName = profileJson().apply { remove("firstName") }
        val nonStringId = profileJson().put("userId", 42)

        for (profile in listOf(blankName, missingName, nonStringId)) {
            val r = Coinbase.parseStatus(JSONObject().put("loggedIn", true).put("profile", profile))

            assertEquals(failedStatus(ERR_PROFILE_INCOMPLETE, "missing_field"), r)
        }
    }

    @Test
    fun describeProfileRow_keepsOnlyAllowlistedKeys_andDashesNulls() {
        val row = JSONObject().put("attempt", 2).put("outcome", "timeout").put("http_status", JSONObject.NULL)
            .put("latency_ms", 4001).put("firstName", "Jane")

        assertEquals(
            "attempt=2 outcome=timeout http_status=- latency_ms=4001",
            Coinbase.describeProfileRow(row, Coinbase.PROFILE_ATTEMPT_LOG_KEYS),
        )

        val result = JSONObject().put("outcome", "timeout").put("attempts", 3).put("total_ms", 13600)
            .put("error", "PROFILE_INDETERMINATE").put("attemptsList", JSONArray())

        assertEquals(
            "outcome=timeout attempts=3 total_ms=13600 error=PROFILE_INDETERMINATE",
            Coinbase.describeProfileRow(result, Coinbase.PROFILE_RESULT_LOG_KEYS),
        )
    }

    @Test
    fun describeProfileRow_printsValuesRaw_andDashesMissingKeys() {
        val row = JSONObject().put("attempt", 9).put("outcome", "unexpected_outcome").put("latency_ms", 1.5)

        assertEquals(
            "attempt=9 outcome=unexpected_outcome http_status=- latency_ms=1.5",
            Coinbase.describeProfileRow(row, Coinbase.PROFILE_ATTEMPT_LOG_KEYS),
        )
    }

    @Test
    fun statusParamsJson_carriesOnlyTheProfileDeadline_marginBeforeTheRunnerTimeout() {
        val params = JSONObject(Coinbase.statusParamsJson(1_000L))

        assertEquals(listOf("profileDeadlineMs"), params.keys().asSequence().toList())
        assertEquals(28_000L, params.getLong("profileDeadlineMs"))
    }

    @Test
    fun loginProbeFailureReason_runnerTimeoutIsTimeout() {
        assertEquals("timeout", Coinbase.loginProbeFailureReason("timeout after 30000ms"))
    }

    @Test
    fun loginProbeFailureReason_loadFailureIsHttpError() {
        assertEquals("http_error", Coinbase.loginProbeFailureReason("load failed: net::ERR_CONNECTION_RESET"))
    }

    @Test
    fun loginProbeFailureReason_nonTransientFailuresStayErrors() {
        assertNull(Coinbase.loginProbeFailureReason("invalid JS return"))
        assertNull(Coinbase.loginProbeFailureReason(null))
    }

    @Test
    fun loginAfterProbeFailure_isASignedInSuccessWithOnlyAProfileFailure() {
        val json = authLoginToJson(Coinbase.loginAfterProbeFailure("timeout"))

        assertEquals(listOf("loggedIn", "outcome", "profileFailure"), json.keys().asSequence().toList().sorted())
        assertTrue(json.getBoolean("loggedIn"))
        assertEquals("success", json.getString("outcome"))
        assertEquals("PROFILE_INDETERMINATE", json.getJSONObject("profileFailure").getString("error"))
        assertEquals("timeout", json.getJSONObject("profileFailure").getString("reason"))
    }

    @Test
    fun statusPrelude_installsTheSharedProfileHelpers() {
        assertEquals(listOf("automation/shared-dom-helpers.js"), Coinbase.STATUS_PRELUDE_ASSETS)

        val helpers = File("src/main/assets/automation/shared-dom-helpers.js").readText()
        for (name in listOf("trimmedText", "parseJsonOrNull", "fetchTextWithTimeout", "sleep")) {
            assertTrue(name, helpers.contains("    $name,\n"))
        }
    }
}
