package com.zerohash.funddemo.e2e.utils

import android.util.Base64
import org.json.JSONObject

/**
 * Locally-built JWTs for the specs that exercise the SDK's **client-side** JWT
 * gate (`JwtValidator`, run inside `present()` before any WebView is created).
 *
 * These deliberately carry a fake signature. The SDK does not verify signatures
 * client-side — the backend remains the authoritative validator — so a
 * structurally valid token is enough to drive `present()` down its accept path,
 * and no network call or provisioned gating platform is involved. Use
 * [Gating.mintJwt] instead for anything that needs the web app to actually
 * authenticate.
 */
object TestJwt {

    private const val FAKE_SIGNATURE = "testsignature"

    /** Structurally valid and unexpired — `present()` proceeds to the WebView. */
    fun valid(expSecondsFromNow: Long = 3600L): String =
        build(JSONObject().put("sub", "e2e-user").put("exp", nowSeconds() + expSecondsFromNow))

    /** Structurally valid but past the validator's 30s skew allowance. */
    fun expired(): String =
        build(JSONObject().put("sub", "e2e-user").put("exp", nowSeconds() - 120L))

    /** Not a JWT at all — fails the segment-count check. */
    fun malformed(): String = "this-is-not-a-jwt"

    /** Valid structure, but an unsigned token the SDK must refuse outright. */
    fun algNone(): String = build(
        payload = JSONObject().put("sub", "e2e-user"),
        header = JSONObject().put("alg", "none").put("typ", "JWT"),
    )

    private fun build(
        payload: JSONObject,
        header: JSONObject = JSONObject().put("alg", "HS256").put("typ", "JWT"),
    ): String = "${encode(header)}.${encode(payload)}.$FAKE_SIGNATURE"

    private fun encode(json: JSONObject): String = Base64.encodeToString(
        json.toString().toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
}
