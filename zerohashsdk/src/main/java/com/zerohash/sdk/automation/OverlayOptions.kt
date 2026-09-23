package com.zerohash.sdk.automation

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import org.json.JSONObject
import com.zerohash.sdk.R

/**
 * Vertical alignment of the footer prefix ("Powered by" / "Secured by")
 * against the brand mark. Single-line marks (`connect`, `zerohash`) sit
 * centered next to the label; the two-tier "Connect by zerohash" wordmark
 * used by `securedConnect` is taller than the label and pairs with a
 * top-aligned row instead (`align-items: flex-start` in the web overlay).
 */
internal enum class FooterAlignment { CENTER, TOP }

/**
 * The resolved theme for a brand: dot palette, footer mark drawable,
 * prefix label, mark height, and row alignment. Mirrors `BRANDING_THEMES`
 * on the web extension plus the CSS overrides in `overlay.ts` that vary
 * height and alignment per brand (single-line marks vs the two-tier
 * wordmark). Android counterpart of iOS `BrandTheme`.
 */
internal data class BrandTheme(
    @ColorInt val left: Int,
    @ColorInt val middle: Int,
    @ColorInt val right: Int,
    @DrawableRes val markRes: Int,
    val footerLabel: String,
    val markHeightDp: Int,
    val footerAlignment: FooterAlignment,
)

/**
 * The brand whose palette + footer lockup the loading overlay renders.
 * Android port of iOS `Brand`. The brand is the single source of truth for
 * the palette and footer lockup (mark + prefix): callers don't supply
 * colors directly, mirroring the web `resolveOverlayOptions`. `zerohash`
 * is the default for this SDK.
 *
 * `SECURED_CONNECT` mirrors zerohash-sdk's `SecuredByConnectFooter` — the
 * same Connect palette as `CONNECT`, but swaps the Connect mark for the
 * "Connect by zerohash" wordmark under a "Secured by" prefix. Its wire
 * value is the hyphenated `secured-connect` (matching the web contract),
 * hence the explicit `wireValue` — the Kotlin case name uses the idiomatic
 * `SECURED_CONNECT` form.
 */
internal enum class Brand(val wireValue: String) {
    CONNECT("connect"),
    ZEROHASH("zerohash"),
    SECURED_CONNECT("secured-connect");

    /** The resolved theme (palette + footer lockup) for this brand. */
    val theme: BrandTheme
        get() = when (this) {
            CONNECT -> BrandTheme(
                left = 0xFFFCFC99.toInt(),
                middle = 0xFFF2F07D.toInt(),
                right = 0xFFF0D53E.toInt(),
                markRes = R.drawable.zh_connect_mark,
                footerLabel = "Powered by",
                markHeightDp = 14,
                footerAlignment = FooterAlignment.CENTER,
            )
            ZEROHASH -> BrandTheme(
                left = 0xFFCCFFD0.toInt(),
                middle = 0xFFABF9B1.toInt(),
                right = 0xFF8FEB96.toInt(),
                markRes = R.drawable.zh_zerohash_mark,
                footerLabel = "Powered by",
                markHeightDp = 14,
                footerAlignment = FooterAlignment.CENTER,
            )
            // Same Connect palette as CONNECT; the difference is the two-tier
            // wordmark, the "Secured by" prefix, the taller (28dp) mark, and the
            // top-aligned row that pairs with a two-tier lockup.
            SECURED_CONNECT -> BrandTheme(
                left = 0xFFFCFC99.toInt(),
                middle = 0xFFF2F07D.toInt(),
                right = 0xFFF0D53E.toInt(),
                markRes = R.drawable.zh_connect_by_zerohash_mark,
                footerLabel = "Secured by",
                markHeightDp = 28,
                footerAlignment = FooterAlignment.TOP,
            )
        }

    companion object {
        val DEFAULT = ZEROHASH

        /**
         * Unknown/empty/absent → default (mirrors iOS `Brand.normalize`).
         * Wire values are matched case-insensitively against each brand's
         * declared [wireValue] — hosts send the hyphenated `secured-connect`,
         * not the camelCased Kotlin/Swift case name.
         */
        fun normalize(raw: String?): Brand {
            val lowered = raw?.lowercase() ?: return DEFAULT
            return values().firstOrNull { it.wireValue == lowered } ?: DEFAULT
        }
    }
}

/**
 * Resolved per-call customization for the branded loading overlay — the
 * *effective* (non-optional) values after merging the caller's partial wire
 * input against the defaults. Android port of iOS `OverlayOptions`.
 *
 * `titles`/`subtitles` cycle in parallel every [cycleMs]; [brand] selects the
 * dot palette and footer lockup.
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
