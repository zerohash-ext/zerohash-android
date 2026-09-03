package com.zerohash.funddemo

import com.zerohash.sdk.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Mints a Fund SDK JWT from the `kyc-mock-platform-server` for a chosen
 * [Environment], so the tester never has to paste a token by hand. This mirrors
 * the on-device `Gating.mintJwt` helper used by the instrumentation e2e suite
 * (`app/src/androidTest/.../e2e/fund/Gating.kt`), generalized to any env.
 *
 * The gating/dev hosts live behind the corporate VPN (private VPC), so a mint
 * against those only succeeds from an in-network device/emulator with the
 * Netskope CA trusted. Production is Okta-gated and not mintable from here.
 */
object MintClient {

    /** Base URL of the kyc-mock-platform-server that mints JWTs for [env]. */
    fun managerHost(env: Environment): String = when (env) {
        Environment.PRODUCTION -> "https://kyc-mock-platform-server.prod.0hash.com"
        Environment.SANDBOX -> "https://kyc-mock-platform-server.entrypoint.cert.zerohash.com"
        Environment.GATING -> "https://kyc-mock-platform-server.gating.0hash.com"
        Environment.DEV -> "https://kyc-mock-platform-server.dev.0hash.com"
    }

    data class Params(
        val env: Environment,
        val platform: String,
        val participant: String,
        val permissions: List<String>,
        val authPolicyEnabled: Boolean,
        /** Optional per-platform identity (some flows require them); sent only when non-blank. */
        val applicationId: String = "",
        val deviceId: String = "",
    )

    /**
     * Mints and returns the JWT. Blocking network call — invoke off the main
     * thread. Throws [IllegalStateException] on a non-2xx response.
     */
    fun mint(p: Params): String {
        val query = if (p.authPolicyEnabled) "?auth_policy_enabled=true" else ""
        val urlStr = "${managerHost(p.env)}/manager/jwt$query"
        val body = JSONObject().apply {
            put("platform_code", p.platform)
            put("participant_code", p.participant)
            put("permissions", JSONArray(p.permissions))
            put("reference_id", UUID.randomUUID().toString())
            if (p.applicationId.isNotBlank()) put("application_id", p.applicationId)
            if (p.deviceId.isNotBlank()) put("device_id", p.deviceId)
        }

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-platform-code", p.platform)
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "HTTP $status from POST $urlStr\n$text" }
            return JSONObject(text).getJSONObject("message").getString("token")
        } catch (e: java.io.IOException) {
            // Connection/TLS failures surface here (before a status). Add the URL
            // so the caller can see exactly which host/endpoint was attempted.
            throw java.io.IOException("POST $urlStr failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}
