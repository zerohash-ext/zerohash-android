package com.zerohash.sdk.automation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AuthProfileTest {

    companion object {
        private const val FICTIONAL_USER_ID = "8b0c2f4e-1a2b-4c3d-9e8f-0a1b2c3d4e5f"
        private val EXPECTED_ERROR_BY_REASON = mapOf(
            "http_error" to ERR_PROFILE_INDETERMINATE,
            "graphql_error" to ERR_PROFILE_INDETERMINATE,
            "timeout" to ERR_PROFILE_INDETERMINATE,
            "missing_field" to ERR_PROFILE_INCOMPLETE,
        )
    }

    @Test
    fun fromJson_passesValuesThroughUnchanged() {
        val p = AuthProfile.fromJson(
            JSONObject()
                .put("userId", " $FICTIONAL_USER_ID ")
                .put("firstName", "jane MARY ")
                .put("lastName", "de la Cruz")
        )

        assertEquals(AuthProfile(" $FICTIONAL_USER_ID ", "jane MARY ", "de la Cruz"), p)
    }

    @Test
    fun fromJson_isNullWhenAnyFieldIsMissingBlankOrNotAString() {
        val full = { JSONObject().put("userId", FICTIONAL_USER_ID).put("firstName", "Jane").put("lastName", "Doe") }

        assertNull(AuthProfile.fromJson(null))
        assertNull(AuthProfile.fromJson(full().apply { remove("lastName") }))
        assertNull(AuthProfile.fromJson(full().put("firstName", "   ")))
        assertNull(AuthProfile.fromJson(full().put("userId", JSONObject.NULL)))
        assertNull(AuthProfile.fromJson(full().put("userId", 42)))
    }

    @Test
    fun toJson_hasExactlyTheThreeComplianceApprovedFields() {
        val json = AuthProfile(FICTIONAL_USER_ID, "Jane", "Doe").toJson()

        assertEquals(setOf("userId", "firstName", "lastName"), json.keys().asSequence().toSet())
    }

    @Test
    fun toString_neverPrintsProfileValues() {
        val p = AuthProfile(FICTIONAL_USER_ID, "Jane", "Doe")
        val printed = listOf(
            p.toString(),
            AuthStatusResult(loggedIn = true, profile = p).toString(),
            AuthLoginResult(loggedIn = true, outcome = "success", profile = p).toString(),
        )

        for (s in printed) {
            assertFalse(s, s.contains("Jane"))
            assertFalse(s, s.contains("Doe"))
            assertFalse(s, s.contains(FICTIONAL_USER_ID))
        }
    }

    @Test
    fun profileFailure_errorFollowsReason() {
        for ((reason, error) in EXPECTED_ERROR_BY_REASON) {
            assertEquals(AuthProfileFailure(error, reason), AuthProfileFailure.forReason(reason))
        }
    }

    @Test
    fun profileFailure_unknownOrMissingReasonBecomesIndeterminateInvalidResponse() {
        val invalidResponse = AuthProfileFailure(ERR_PROFILE_INDETERMINATE, "invalid_response")

        assertEquals(invalidResponse, AuthProfileFailure.forReason("invalid_response"))
        assertEquals(invalidResponse, AuthProfileFailure.forReason("Jane Doe"))
        assertEquals(invalidResponse, AuthProfileFailure.forReason(""))
        assertEquals(invalidResponse, AuthProfileFailure.forReason(null))
    }

    @Test
    fun profileFailure_toJsonHasExactlyErrorAndReason() {
        val json = AuthProfileFailure.forReason("timeout").toJson()

        assertEquals(setOf("error", "reason"), json.keys().asSequence().toSet())
        assertEquals(ERR_PROFILE_INDETERMINATE, json.getString("error"))
        assertEquals("timeout", json.getString("reason"))
    }
}
