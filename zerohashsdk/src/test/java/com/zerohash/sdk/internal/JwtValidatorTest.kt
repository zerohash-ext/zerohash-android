package com.zerohash.sdk.internal

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [JwtValidator.validate] — the structural/expiry gate every
 * session runs before a WebView is launched (`ZerohashFundSession.present` and
 * `ZerohashCryptoWithdrawalsSession.present` surface a failure as
 * `ZerohashError.ConfigurationError` and return null). A regression here means a
 * malformed or expired token reaches the web app instead of being rejected
 * locally, so each rejection branch is pinned individually.
 *
 * Pure JVM (org.json test dep), no device — mirrors [AutomationBridgeLogicTest].
 * Segments are built with the real [Base64Util.urlSafeEncode] rather than
 * hand-written base64, so these exercise the same decode path production does.
 */
class JwtValidatorTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun seg(raw: String): String =
        Base64Util.urlSafeEncode(raw.toByteArray(Charsets.UTF_8))

    /** A structurally valid, unexpired JWT unless a segment is overridden. */
    private fun token(
        header: String = """{"alg":"HS256","typ":"JWT"}""",
        payload: String = """{"sub":"user-1"}""",
        signature: String = "sig"
    ): String = "${seg(header)}.${seg(payload)}.$signature"

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun assertRejected(jwt: String, expectedMessageFragment: String) {
        val result = JwtValidator.validate(jwt)
        assertTrue("expected a rejection for: '$jwt'", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "message '$message' should mention '$expectedMessageFragment'",
            message.contains(expectedMessageFragment),
        )
    }

    private fun assertAccepted(jwt: String) {
        val result = JwtValidator.validate(jwt)
        assertTrue(
            "expected acceptance, got: ${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
    }

    // ── structure ───────────────────────────────────────────────────────────

    @Test
    fun blankJwt_isRejected() {
        assertRejected("", "must not be blank")
        assertRejected("   ", "must not be blank")
        assertRejected("\t\n", "must not be blank")
    }

    @Test
    fun wrongSegmentCount_isRejected() {
        assertRejected("onlyonesegment", "exactly 3 segments")
        assertRejected("${seg("{}")}.${seg("{}")}", "exactly 3 segments")
        assertRejected("${seg("{}")}.${seg("{}")}.sig.extra", "exactly 3 segments")
    }

    @Test
    fun invalidBase64UrlCharset_isRejected() {
        // '+' and '/' belong to standard base64, not base64url; '=' padding is
        // rejected too, since the encoder emits unpadded segments.
        assertRejected("ab+cd.${seg("{}")}.sig", "invalid Base64URL characters")
        assertRejected("${seg("{}")}.ab/cd.sig", "invalid Base64URL characters")
        assertRejected("${seg("{}")}.${seg("{}")}.sig=", "invalid Base64URL characters")
    }

    @Test
    fun emptySegment_isRejected() {
        // The charset regex requires 1+ characters, so an empty segment is caught
        // here rather than downstream. This is also why JwtValidator's
        // "decoded to empty bytes" guard is unreachable in practice: every input
        // that clears this check and decodes without throwing yields >= 1 byte.
        assertRejected(".${seg("{}")}.sig", "invalid Base64URL characters")
        assertRejected("${seg("{}")}..sig", "invalid Base64URL characters")
        assertRejected("${seg("{}")}.${seg("{}")}.", "invalid Base64URL characters")
    }

    @Test
    fun undecodableSegment_isRejected() {
        // Valid base64url alphabet, but length % 4 == 1 — Base64Util rejects it
        // instead of silently truncating, and the label says which segment failed.
        assertRejected("AAAAA.${seg("{}")}.sig", "header segment could not be decoded")
        assertRejected("${seg("{}")}.AAAAA.sig", "payload segment could not be decoded")
    }

    @Test
    fun invalidJsonSegment_isRejected() {
        assertRejected(token(header = "not-json-at-all"), "header is not valid JSON")
        assertRejected(token(payload = "not-json-at-all"), "payload is not valid JSON")
    }

    // ── alg header ──────────────────────────────────────────────────────────

    @Test
    fun algNone_isRejected() {
        assertRejected(token(header = """{"alg":"none"}"""), "alg: none")
        assertRejected(token(header = """{"alg":"None"}"""), "alg: none")
        assertRejected(token(header = """{"alg":"NONE"}"""), "alg: none")
        // Surrounding whitespace is trimmed before the comparison.
        assertRejected(token(header = """{"alg":"  none  "}"""), "alg: none")
    }

    @Test
    fun missingAlgHeader_isAccepted() {
        // Characterizes current behaviour: only an explicit `none` is refused. A
        // header with no `alg` at all passes, because the backend remains the
        // authoritative validator (see the KDoc on JwtValidator).
        assertAccepted(token(header = """{"typ":"JWT"}"""))
    }

    // ── exp claim ───────────────────────────────────────────────────────────

    @Test
    fun expiredBeyondSkew_isRejected() {
        assertRejected(token(payload = """{"exp":${nowSeconds() - 120}}"""), "has expired")
    }

    @Test
    fun expiredWithinSkew_isAccepted() {
        // 30s of clock skew is tolerated; without that allowance this would be
        // rejected, so this pins the skew rather than just the happy path.
        assertAccepted(token(payload = """{"exp":${nowSeconds() - 5}}"""))
    }

    @Test
    fun noExpClaim_isAccepted() {
        assertAccepted(token(payload = """{"sub":"user-1"}"""))
    }

    @Test
    fun nonPositiveExp_isTreatedAsAbsent() {
        assertAccepted(token(payload = """{"exp":0}"""))
        assertAccepted(token(payload = """{"exp":-1}"""))
    }

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    fun wellFormedUnexpiredJwt_isAccepted() {
        assertAccepted(token(payload = """{"exp":${nowSeconds() + 3600},"sub":"user-1"}"""))
    }

    @Test
    fun signatureContentIsNotVerified() {
        // Characterizes the documented boundary: the signature segment is only
        // checked against the base64url alphabet, never cryptographically. A
        // garbage-but-well-formed signature passes client-side validation.
        assertAccepted(token(signature = "not-a-real-signature"))
    }
}
