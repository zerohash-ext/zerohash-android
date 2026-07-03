package com.zerohash.sdk.internal

import org.json.JSONException
import org.json.JSONObject

/**
 * Client-side JWT structural and expiry validator.
 *
 * Provides a fast-feedback layer that catches common integration errors
 * (empty JWT, malformed structure, expired token, unsigned `alg: none`
 * token) before initiating a WebView session. This does NOT perform
 * cryptographic signature verification; the backend remains the
 * authoritative validator.
 */
internal object JwtValidator {

    private val BASE64URL_SEGMENT = Regex("^[A-Za-z0-9_\\-]+$")

    /**
     * Validates [jwt] structurally and checks the `exp` claim and `alg` header.
     *
     * @return [Result.success] when valid, [Result.failure] with a descriptive
     *         [IllegalArgumentException] when not.
     */
    fun validate(jwt: String): Result<Unit> {
        if (jwt.isBlank()) {
            return Result.failure(IllegalArgumentException("JWT must not be blank"))
        }

        val parts = jwt.split(".")
        if (parts.size != 3) {
            return Result.failure(
                IllegalArgumentException(
                    "JWT must have exactly 3 segments separated by '.', got ${parts.size}"
                )
            )
        }

        for ((index, part) in parts.withIndex()) {
            if (!BASE64URL_SEGMENT.matches(part)) {
                return Result.failure(
                    IllegalArgumentException(
                        "JWT segment ${index + 1} is empty or contains invalid Base64URL characters"
                    )
                )
            }
        }

        // Header: reject alg=="none" (unsigned tokens must never reach prod)
        val header = decodeSegmentToJson(parts[0], "header")
            .getOrElse { return Result.failure(it) }
        val alg = header.optString("alg", "").trim()
        if (alg.equals("none", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("JWT 'alg: none' is not permitted"))
        }

        val payload = decodeSegmentToJson(parts[1], "payload")
            .getOrElse { return Result.failure(it) }

        val exp = payload.optLong("exp", 0L)
        if (exp > 0L) {
            val nowSeconds = System.currentTimeMillis() / 1000L
            val skewSeconds = 30L
            if (nowSeconds > exp + skewSeconds) {
                return Result.failure(
                    IllegalArgumentException("JWT has expired (exp=$exp, now=$nowSeconds)")
                )
            }
        }

        return Result.success(Unit)
    }

    private fun decodeSegmentToJson(segment: String, label: String): Result<JSONObject> {
        val bytes = try {
            Base64Util.urlSafeDecode(segment)
        } catch (e: IllegalArgumentException) {
            return Result.failure(
                IllegalArgumentException("JWT $label segment could not be decoded: ${e.message}")
            )
        }
        if (bytes.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("JWT $label segment decoded to empty bytes")
            )
        }
        return try {
            Result.success(JSONObject(String(bytes, Charsets.UTF_8)))
        } catch (e: JSONException) {
            Result.failure(
                IllegalArgumentException("JWT $label is not valid JSON: ${e.message}")
            )
        }
    }
}
