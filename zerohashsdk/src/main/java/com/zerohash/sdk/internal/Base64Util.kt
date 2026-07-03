package com.zerohash.sdk.internal

/**
 * Pure-Kotlin Base64URL encode/decode utility.
 *
 * Implemented without android.util.Base64 or java.util.Base64 so the same
 * code path runs correctly on all Android API levels (21+) and in JVM unit
 * tests without Robolectric.
 *
 * Decoder is strict: invalid input throws [IllegalArgumentException] rather
 * than silently producing truncated output. This matters for JWT validation
 * where a malformed payload must be rejected, not interpreted as an empty
 * payload.
 */
internal object Base64Util {

    private const val STANDARD_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Encodes [bytes] to Base64URL without padding (RFC 4648 §5). */
    fun urlSafeEncode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0

            sb.append(URL_SAFE_CHARS[b0 ushr 2])
            sb.append(URL_SAFE_CHARS[((b0 and 0x3) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) sb.append(URL_SAFE_CHARS[((b1 and 0xF) shl 2) or (b2 ushr 6)])
            if (i + 2 < bytes.size) sb.append(URL_SAFE_CHARS[b2 and 0x3F])

            i += 3
        }
        return sb.toString()
    }

    /**
     * Decodes a Base64URL string (with or without padding, '-'/'_' or '+'/'/').
     *
     * @throws IllegalArgumentException if [input] contains characters outside the
     *         Base64URL alphabet, or if its length (after padding normalization)
     *         is not a multiple of 4.
     */
    fun urlSafeDecode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        val normalized = input.replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            0 -> normalized
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> throw IllegalArgumentException(
                "Invalid Base64URL length: ${input.length} (mod 4 = 1)"
            )
        }
        return decode(padded)
    }

    private fun decode(padded: String): ByteArray {
        require(padded.length % 4 == 0) {
            "Internal error: padded Base64 must be a multiple of 4"
        }
        val output = ArrayList<Byte>(padded.length * 3 / 4)
        var i = 0
        while (i < padded.length) {
            val c0 = decodeChar(padded[i], allowPad = false)
            val c1 = decodeChar(padded[i + 1], allowPad = false)
            val c2 = decodeChar(padded[i + 2], allowPad = true)
            val c3 = decodeChar(padded[i + 3], allowPad = true)

            output.add(((c0 shl 2) or (c1 ushr 4)).toByte())
            if (c2 >= 0) {
                output.add((((c1 and 0xF) shl 4) or (c2 ushr 2)).toByte())
                if (c3 >= 0) {
                    output.add((((c2 and 0x3) shl 6) or c3).toByte())
                }
            } else if (c3 >= 0) {
                // Padding must be contiguous at the end
                throw IllegalArgumentException("Invalid Base64URL padding at index $i")
            }

            i += 4
        }
        return output.toByteArray()
    }

    private fun decodeChar(ch: Char, allowPad: Boolean): Int {
        if (ch == '=') {
            if (!allowPad) throw IllegalArgumentException("Unexpected '=' in Base64URL input")
            return -1
        }
        val idx = STANDARD_CHARS.indexOf(ch)
        if (idx < 0) throw IllegalArgumentException("Invalid Base64URL character: '$ch'")
        return idx
    }
}
