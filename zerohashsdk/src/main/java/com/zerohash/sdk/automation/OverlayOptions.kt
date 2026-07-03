package com.zerohash.sdk.automation

import androidx.annotation.DrawableRes
import org.json.JSONObject
import com.zerohash.sdk.R

/**
 * The brand whose dot palette + "Powered by" mark the loading overlay renders.
 * Android port of iOS `Brand`. The brand is the single source of truth for the
 * palette and footer logo (callers don't supply colors directly), mirroring the
 * web `resolveOverlayOptions`. `zerohash` is the default for this SDK.
 */
internal enum class Brand(
    val left: Int,
    val middle: Int,
    val right: Int,
    @DrawableRes val markRes: Int,
) {
    // Connect Yellow palette.
    CONNECT(0xFFFCFC99.toInt(), 0xFFF2F07D.toInt(), 0xFFF0D53E.toInt(), R.drawable.zh_connect_mark),
    // ZH Green palette.
    ZEROHASH(0xFFCCFFD0.toInt(), 0xFFABF9B1.toInt(), 0xFF8FEB96.toInt(), R.drawable.zh_zerohash_mark);

    companion object {
        val DEFAULT = ZEROHASH

        /** Unknown/empty/absent → default (mirrors iOS `Brand.normalize`). */
        fun normalize(raw: String?): Brand = when (raw?.lowercase()) {
            "connect" -> CONNECT
            "zerohash" -> ZEROHASH
            else -> DEFAULT
        }
    }
}

/**
 * Resolved per-call customization for the branded loading overlay — the
 * *effective* (non-optional) values after merging the caller's partial wire
 * input against the defaults. Android port of iOS `OverlayOptions`.
 *
 * `titles`/`subtitles` cycle in parallel every [cycleMs]; [brand] selects the
 * dot palette and footer mark.
 */
internal data class OverlayOptions(
    val titles: List<String>,
    val subtitles: List<String>,
    val cycleMs: Long,
    val brand: Brand,
) {
    companion object {
        /** Defaults mirror iOS `OverlayOptions.default` (curly apostrophe U+2019). */
        val DEFAULT = OverlayOptions(
            titles = listOf("Almost there"),
            subtitles = listOf("We’re securely accessing your account."),
            cycleMs = 5000L,
            brand = Brand.DEFAULT,
        )

        /**
         * Resolve the inbound wire `overlayOptions` object against the defaults,
         * mirroring iOS `OverlayOptions(resolving:)` / web `resolveOverlayOptions`:
         * a non-empty titles/subtitles array wins (else default); `cycleMs` falls
         * back to default; `branding` normalizes to a known brand.
         */
        fun resolve(wire: JSONObject?): OverlayOptions {
            if (wire == null) return DEFAULT
            val titles = wire.optJSONArray("titles").toStringListOrNull()
            val subtitles = wire.optJSONArray("subtitles").toStringListOrNull()
            val cycleMs = wire.optLongOrNull("cycleMs")
            return OverlayOptions(
                titles = titles?.takeIf { it.isNotEmpty() } ?: DEFAULT.titles,
                subtitles = subtitles?.takeIf { it.isNotEmpty() } ?: DEFAULT.subtitles,
                // Clamp to > 0: optLong yields 0 for a missing/non-numeric wire
                // value, and a 0/negative cycle busy-loops LoadingOverlayView's
                // self-reposting postDelayed on the main thread.
                cycleMs = cycleMs?.takeIf { it > 0 } ?: DEFAULT.cycleMs,
                brand = Brand.normalize(wire.optStringOrNull("branding")),
            )
        }

        private fun org.json.JSONArray?.toStringListOrNull(): List<String>? {
            if (this == null) return null
            return (0 until length()).map { optString(it) }
        }
    }
}
