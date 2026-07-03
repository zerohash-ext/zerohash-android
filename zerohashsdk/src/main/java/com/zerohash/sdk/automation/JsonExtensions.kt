package com.zerohash.sdk.automation

import org.json.JSONObject

/**
 * `optX`-or-null helpers for the scraping wire contract, where a key that is
 * absent or JSON `null` must collapse to a Kotlin `null` (so a caller can fall
 * back to a default) rather than to `optX`'s 0/false/"" sentinel. [JSONObject.isNull]
 * is true for both an absent key and an explicit `null`, so a single guard covers
 * both. Consolidated from the wire parsers in AUTH-3433.
 */

internal fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (isNull(key)) null else optBoolean(key)

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else optLong(key)

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key)
