package com.zerohash.sdk.automation

import org.json.JSONObject

internal const val ERR_PROFILE_INDETERMINATE = "PROFILE_INDETERMINATE"
internal const val ERR_PROFILE_INCOMPLETE = "PROFILE_INCOMPLETE"
internal const val UNKNOWN_PROFILE_REASON = "invalid_response"
internal const val MISSING_FIELD_REASON = "missing_field"

internal val PROFILE_ERROR_REASONS = setOf(
    "http_error", UNKNOWN_PROFILE_REASON, "graphql_error", "timeout", MISSING_FIELD_REASON,
)

internal data class AuthProfile(
    val userId: String,
    val firstName: String,
    val lastName: String,
) {
    override fun toString(): String = "AuthProfile(<redacted>)"

    fun toJson(): JSONObject = JSONObject()
        .put("userId", userId)
        .put("firstName", firstName)
        .put("lastName", lastName)

    companion object {
        fun fromJson(obj: JSONObject?): AuthProfile? {
            if (obj == null) {
                return null
            }

            val userId = nonBlankString(obj, "userId")
            val firstName = nonBlankString(obj, "firstName")
            val lastName = nonBlankString(obj, "lastName")
            if (userId == null || firstName == null || lastName == null) {
                return null
            }

            return AuthProfile(userId, firstName, lastName)
        }

        private fun nonBlankString(obj: JSONObject, key: String): String? {
            val value = obj.opt(key)
            if (value !is String || value.isBlank()) {
                return null
            }

            return value
        }
    }
}

internal data class AuthProfileFailure(val error: String, val reason: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("error", error)
        .put("reason", reason)

    companion object {
        fun forReason(reason: String?): AuthProfileFailure {
            val knownReason = knownProfileReason(reason)

            return AuthProfileFailure(profileErrorCode(knownReason), knownReason)
        }

        private fun knownProfileReason(reason: String?): String {
            if (reason == null || reason !in PROFILE_ERROR_REASONS) {
                return UNKNOWN_PROFILE_REASON
            }

            return reason
        }

        private fun profileErrorCode(reason: String): String = when (reason) {
            MISSING_FIELD_REASON -> ERR_PROFILE_INCOMPLETE
            else -> ERR_PROFILE_INDETERMINATE
        }
    }
}

/**
 * Result of a Coinbase `auth.status` probe.
 *
 * Port of iOS `AuthStatusResult` (Platforms/AuthFlow.swift).
 */
internal data class AuthStatusResult(
    val loggedIn: Boolean,
    val profile: AuthProfile? = null,
    val profileFailure: AuthProfileFailure? = null,
)

/**
 * Result of a Coinbase `auth.login` flow. Port of iOS `AuthLoginResult`.
 *
 * [outcome] is one of `"success"`, `"user-closed"`, `"timeout"`, `"passkey-only"`
 * (the wire values the web's `auth.login` response expects) — all four are
 * produced by [CoinbaseLoginActivity], matching iOS.
 */
internal data class AuthLoginResult(
    val loggedIn: Boolean,
    val outcome: String,
    val profile: AuthProfile? = null,
    val profileFailure: AuthProfileFailure? = null,
)

/**
 * One asset row from a Coinbase `getBalance` scrape. Port of iOS `AssetBalance`
 * (Platforms/BalanceFlow.swift) — field-for-field the shape `get-balance.js`
 * emits.
 */
internal data class AssetBalance(
    val key: String,
    val label: String,
    val amount: String,
    val notional: String,
    val currency: String?,
    val totalStakedPercent: String?,
    val precision: Int?,
    val extractedAt: String,
)

/**
 * Decision returned by the navigation-settle predicate after each
 * `onPageFinished` of a scraping run. Port of iOS `OffscreenSettleDecision`.
 *
 * - [WaitMore]: not a terminal URL yet — keep waiting for the next page finish.
 * - [Evaluate]: this is the page we want — inject and run the script.
 * - [Answer]: short-circuit with this value WITHOUT running the script (e.g. a
 *   redirect to the login host means "logged out" — no DOM probe needed).
 */
internal sealed interface SettleDecision {
    object WaitMore : SettleDecision
    object Evaluate : SettleDecision
    data class Answer(val value: JSONObject?) : SettleDecision
}

/**
 * Thrown when a platform script returns something unusable, or rejects.
 * Port of iOS `PlatformError` / `JSException` — [message] carries the JS-thrown
 * text (e.g. `CHALLENGE_PRESENT`, `not logged in`) so callers can branch on it.
 */
internal class PlatformException(message: String) : Exception(message)
