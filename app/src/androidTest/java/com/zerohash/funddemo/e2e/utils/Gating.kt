package com.zerohash.funddemo.e2e.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Gating JWT mint + per-config platform codes (AUTH-3838, mirrors the AUTH-3630 web
 * utils). Codes are gating-env platforms provisioned for this suite — hardcoded like
 * the web suite's; they are internal test codes, not secrets.
 */
object Gating {

    private const val MANAGER_HOST = "https://kyc-mock-platform-server.gating.0hash.com"

    /**
     * JWT-gateway host (jwt-trade-api). The public `api.<env>` host fronts
     * trade-api and rejects Bearer-JWT calls with a Kong "Missing API Key header"
     * 403; kong-api accepts the Connect JWT.
     */
    private const val TRADE_API_HOST = "https://kong-api.gating.0hash.com"

    data class Config(
        val platform: String,
        val participant: String,
        val permissions: List<String>,
        val authPolicyEnabled: Boolean,
    )

    /**
     * Payout details embedded in the JWT as `payload.withdrawal_details`. Fund
     * Withdrawals resolves its destination from this claim rather than from an
     * in-flow picker, so a token without it cannot drive the flow.
     */
    data class WithdrawalDetails(
        val externalAccountId: String,
        val quotedAsset: String = "USD",
        val withdrawalRequestAmount: String = "100",
    )

    /** Non-Auth Fund platform (fwc only) — the exact ENG-6631 configuration. */
    val NON_AUTH = Config(
        platform = "HSBCRW",
        participant = "JLXERM",
        permissions = listOf("fwc"),
        authPolicyEnabled = false,
    )

    /** Auth-enabled Fund platform (fwc + crypto-deposits + auth_policy_enabled; cbase/gemini/robinhood/gemini-fake integrations). */
    val AUTH_ENABLED = Config(
        platform = "BM3LDA",
        participant = "62LHRQ",
        permissions = listOf("fwc", "crypto-deposits"),
        authPolicyEnabled = true,
    )

    /**
     * Fund Withdrawals platform — the gating withdrawal participant / account-group
     * (live, approved). Mirrors `GATING_PLATFORMS` in the web suite. Distinct from
     * the Fund platforms above: minting `withdrawal_details` against those fails
     * with "account not found", since the external account lives on this one.
     */
    val FUND_WITHDRAWALS = Config(
        platform = "POZ6HT",
        participant = "CC3OQA",
        permissions = listOf("crypto-withdrawals"),
        authPolicyEnabled = false,
    )

    /** Permissions needed to *read* the participant's linked external accounts. */
    private val ACCOUNT_LOOKUP_PERMISSIONS = listOf("crypto-account-link", "crypto-withdrawals")

    /**
     * Mints a Fund Withdrawals JWT, resolving the payout destination at runtime.
     *
     * The manager validates `external_account_id` against real data — a made-up id
     * is rejected with "account not found" — so the id cannot be hardcoded here
     * without going stale the moment the gating account changes. Instead this
     * mirrors `getExternalAccountId` in the web suite: mint a lookup token, list
     * the participant's approved accounts, then mint the real token with the
     * account it found.
     *
     * @throws IllegalStateException when the participant has no approved external
     *         account, which no amount of retrying will fix — one has to be linked
     *         and approved first.
     */
    fun mintFundWithdrawalsJwt(config: Config = FUND_WITHDRAWALS): String {
        val lookupToken = mintJwt(config.copy(permissions = ACCOUNT_LOOKUP_PERMISSIONS))
        val externalAccountId = firstApprovedExternalAccount(lookupToken, config.participant)
        return mintJwt(config, WithdrawalDetails(externalAccountId = externalAccountId))
    }

    /**
     * First approved external account for [participant], preferring a crypto one.
     *
     * The route filters on `participants` (plural, comma-separated) — passing
     * `participant_code` leaves it undefined and the handler 500s.
     */
    fun firstApprovedExternalAccount(jwt: String, participant: String): String {
        val url = URL(
            "$TRADE_API_HOST/payments/external_accounts" +
                "?participants=$participant&account_status=approved"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $jwt")
        }

        val accounts = try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "external_accounts returned $status: $text" }
            JSONObject(text).optJSONArray("message")
                ?: throw IllegalStateException("external_accounts returned no `message` array")
        } finally {
            connection.disconnect()
        }

        var fallback: String? = null
        for (i in 0 until accounts.length()) {
            val account = accounts.optJSONObject(i) ?: continue
            val id = account.optString("external_account_id").takeIf { it.isNotBlank() } ?: continue
            if (account.optString("type") == "crypto") return id
            if (fallback == null) fallback = id
        }
        return fallback ?: throw IllegalStateException(
            "No approved external account on $participant — Fund Withdrawals cannot be " +
                "minted without one. Link and approve an account first."
        )
    }

    /** Mints a real gating JWT for [config] via the kyc-mock-platform-server. */
    fun mintJwt(config: Config, withdrawalDetails: WithdrawalDetails? = null): String {
        val query = if (config.authPolicyEnabled) "?auth_policy_enabled=true" else ""
        val url = URL("$MANAGER_HOST/manager/jwt$query")
        val body = JSONObject().apply {
            put("platform_code", config.platform)
            put("participant_code", config.participant)
            put("permissions", JSONArray(config.permissions))
            put("reference_id", UUID.randomUUID().toString())
            if (withdrawalDetails != null) {
                put(
                    "withdrawal_details",
                    JSONObject().apply {
                        put("external_account_id", withdrawalDetails.externalAccountId)
                        put("quoted_asset", withdrawalDetails.quotedAsset)
                        put("withdrawal_request_amount", withdrawalDetails.withdrawalRequestAmount)
                    }
                )
            }
        }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-platform-code", config.platform)
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "mintJwt returned $status: $responseText" }
            return JSONObject(responseText).getJSONObject("message").getString("token")
        } finally {
            connection.disconnect()
        }
    }
}