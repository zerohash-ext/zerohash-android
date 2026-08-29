package com.zerohash.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ZerohashError.fromWebError] — the single translation point
 * between the web app's `error` bridge payload (`{errorCode, reason}`) and the
 * typed error a host app receives on `onError`. Every flow's callback handler
 * routes through it, so a wrong or dropped mapping silently downgrades a
 * specific error to `UnknownError` for every SDK at once.
 *
 * Pure JVM, no device — mirrors [com.zerohash.sdk.automation.OverlayOptionsTest].
 */
class ZerohashErrorMappingTest {

    private companion object {
        const val MESSAGE = "something went wrong"
    }

    private fun map(code: String?): ZerohashError = ZerohashError.fromWebError(code, MESSAGE)

    // ── known codes ─────────────────────────────────────────────────────────

    @Test
    fun networkError_maps() {
        assertTrue(map("network_error") is ZerohashError.NetworkError)
    }

    @Test
    fun authError_mapsToAuthenticationError() {
        // Note the asymmetry: the web code is `auth_error`, the native type is
        // AuthenticationError.
        assertTrue(map("auth_error") is ZerohashError.AuthenticationError)
    }

    @Test
    fun validationError_maps() {
        assertTrue(map("validation_error") is ZerohashError.ValidationError)
    }

    @Test
    fun notFoundError_maps() {
        assertTrue(map("not_found_error") is ZerohashError.NotFoundError)
    }

    @Test
    fun serverError_maps() {
        assertTrue(map("server_error") is ZerohashError.ServerError)
    }

    @Test
    fun clientError_maps() {
        assertTrue(map("client_error") is ZerohashError.ClientError)
    }

    @Test
    fun configError_mapsToConfigurationError() {
        assertTrue(map("config_error") is ZerohashError.ConfigurationError)
    }

    @Test
    fun oauthError_maps() {
        assertTrue(map("oauth_error") is ZerohashError.OAuthError)
    }

    @Test
    fun webviewUnsupported_mapsToWebViewError() {
        // The one code that originates natively rather than from the web app —
        // raised when the device's WebView cannot enforce per-frame origin
        // filtering. Routed through the same mapping so hosts get one error
        // surface.
        assertTrue(map("webview_unsupported") is ZerohashError.WebViewError)
    }

    // ── fallback ────────────────────────────────────────────────────────────

    @Test
    fun unrecognizedCode_mapsToUnknownError() {
        assertTrue(map("some_code_we_never_shipped") is ZerohashError.UnknownError)
    }

    @Test
    fun nullAndEmptyCode_mapToUnknownError() {
        // The bridge's legacy `{code, message}` shape can omit the code entirely.
        assertTrue(map(null) is ZerohashError.UnknownError)
        assertTrue(map("") is ZerohashError.UnknownError)
    }

    @Test
    fun codeMatchingIsCaseSensitive() {
        // Characterizes the `when` being an exact match: a differently-cased code
        // falls through to UnknownError rather than mapping. Worth knowing before
        // anyone "fixes" a casing mismatch on the web side.
        assertTrue(map("NETWORK_ERROR") is ZerohashError.UnknownError)
        assertTrue(map("Network_Error") is ZerohashError.UnknownError)
    }

    @Test
    fun codeIsNotTrimmed() {
        // Same reasoning: no normalization happens, so stray whitespace loses the
        // mapping.
        assertTrue(map(" network_error") is ZerohashError.UnknownError)
        assertTrue(map("network_error ") is ZerohashError.UnknownError)
    }

    // ── message plumbing ────────────────────────────────────────────────────

    @Test
    fun messageIsPreservedVerbatim_forEveryCode() {
        val codes = listOf(
            "network_error",
            "auth_error",
            "validation_error",
            "not_found_error",
            "server_error",
            "client_error",
            "config_error",
            "oauth_error",
            "webview_unsupported",
            "unrecognized",
            null,
        )
        for (code in codes) {
            assertEquals("message lost for code=$code", MESSAGE, map(code).message)
        }
    }

    @Test
    fun blankMessageIsPreserved() {
        assertEquals("", ZerohashError.fromWebError("network_error", "").message)
    }
}
