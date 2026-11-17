package com.zerohash.sdk.internal

import java.net.URI
import java.net.URLDecoder

/**
 * The deposit page opened in a wallet's in-app browser appends
 * `zh_wallet_return=sent&zh_tx=<id>` to the host's `appRedirectUrl` once the
 * wallet broadcast the transfer. The web SDK only uses the signal to pick its
 * waiting screen; the deposit itself is still confirmed by the backend.
 *
 * Pure JVM (java.net.URI) so it is unit-testable without Robolectric.
 */
internal object WalletReturn {
    const val SENT_PARAM = "zh_wallet_return"
    const val SENT_VALUE = "sent"
    const val TRANSACTION_PARAM = "zh_tx"

    private val EVM_HASH = Regex("^0x[0-9a-fA-F]{64}$")
    private val SOLANA_SIGNATURE = Regex("^[1-9A-HJ-NP-Za-km-z]{86,88}$")

    /**
     * The transaction id carried by [url], when [url] is the configured return URL
     * reopened by the deposit page after a broadcast. Anything else is null.
     */
    fun transactionId(url: String?, appRedirectUrl: String?): String? {
        if (url == null || appRedirectUrl == null) return null
        val incoming = AppRedirect.parse(url) ?: return null
        val expected = AppRedirect.parse(appRedirectUrl) ?: return null
        if (!isReturn(incoming, expected)) return null
        val query = queryParams(incoming.rawQuery)
        if (query[SENT_PARAM] != SENT_VALUE) return null
        val txId = query[TRANSACTION_PARAM] ?: return null
        return if (isTransactionId(txId)) txId else null
    }

    /** An EVM transaction hash (`0x` + 64 hex digits) or a Solana signature (64 bytes in base58: 86 to 88 digits). */
    fun isTransactionId(value: String): Boolean =
        EVM_HASH.matches(value) || SOLANA_SIGNATURE.matches(value)

    /**
     * Same scheme, host, port and path as the configured return URL. The query and the
     * fragment belong to the page, so they are not compared.
     */
    private fun isReturn(incoming: URI, expected: URI): Boolean =
        incoming.scheme.equals(expected.scheme, ignoreCase = true) &&
            (incoming.host ?: "").equals(expected.host ?: "", ignoreCase = true) &&
            incoming.port == expected.port &&
            trimmed(incoming.rawPath) == trimmed(expected.rawPath)

    // The first value of a repeated key wins, as on iOS.
    private fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val params = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            val key = decode(if (separator < 0) pair else pair.substring(0, separator))
            if (key in params) continue
            params[key] = decode(if (separator < 0) "" else pair.substring(separator + 1))
        }
        return params
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: IllegalArgumentException) {
        value
    }

    private fun trimmed(path: String?): String {
        val value = path ?: ""
        return if (value.endsWith("/")) value.dropLast(1) else value
    }
}
