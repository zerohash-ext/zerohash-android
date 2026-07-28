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

    data class Config(
        val platform: String,
        val participant: String,
        val permissions: List<String>,
        val authPolicyEnabled: Boolean,
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

    /** Mints a real gating JWT for [config] via the kyc-mock-platform-server. */
    fun mintJwt(config: Config): String {
        val query = if (config.authPolicyEnabled) "?auth_policy_enabled=true" else ""
        val url = URL("$MANAGER_HOST/manager/jwt$query")
        val body = JSONObject().apply {
            put("platform_code", config.platform)
            put("participant_code", config.participant)
            put("permissions", JSONArray(config.permissions))
            put("reference_id", UUID.randomUUID().toString())
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