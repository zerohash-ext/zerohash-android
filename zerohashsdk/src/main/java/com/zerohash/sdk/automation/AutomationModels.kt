package com.zerohash.sdk.automation

import org.json.JSONObject

/**
 * Result of a Coinbase `auth.status` probe.
 *
 * Port of iOS `AuthStatusResult` (Platforms/AuthFlow.swift).
 */
internal data class AuthStatusResult(val loggedIn: Boolean)

/**
 * Result of a Coinbase `auth.login` flow. Port of iOS `AuthLoginResult`.
 *
 * [outcome] is one of `"success"`, `"user-closed"`, `"timeout"`, `"passkey-only"`
 * (the wire values the web's `auth.login` response expects) — all four are
 * produced by [CoinbaseLoginActivity], matching iOS.
 */
internal data class AuthLoginResult(val loggedIn: Boolean, val outcome: String)

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
