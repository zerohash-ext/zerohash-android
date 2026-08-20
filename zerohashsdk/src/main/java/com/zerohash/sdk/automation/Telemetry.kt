package com.zerohash.sdk.automation

import com.zerohash.sdk.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Telemetry for the scraping automation. Builds plain-JSON rows and hands them to
 *  the host, which forwards to Faro. Off by default; never carries PII. */

/** Master gate; default OFF. */
internal object TelemetryConfig {
    @Volatile
    var enabled: Boolean = false
}

/** Holds the collector for the current operation. The bridge sets it; vehicles write
 *  drafts to it via onEvents. One operation runs at a time. */
internal object TelemetryRouter {
    @Volatile
    var collector: TelemetryCollector? = null
}

/** Installs the telemetry.js buffer before every automation script. Call sites emit
 *  via `window.__zhTelemetry.emit({...})`; native stamps the rest ([TelemetryDims]). */
internal const val TELEMETRY_INSTALL_ASSET = "automation/telemetry.js"

/** Request-level dimensions only native knows, stamped onto every row. */
internal data class TelemetryDims(
    val requestId: String,
    /** Wire `platform`, e.g. `"cbase"` / `"kraken"` — see [Platform.id]. */
    val platformId: String,
    /** Operation name, e.g. `"getBalance"` / `"withdraw.start"`. */
    val operation: String,
    /** Withdraw session id when in a multi-step session, else null. */
    val zeroauthSessionId: String? = null,
    /** `single_shot` / `multi_step` / … or null. */
    val flow: String? = null,
    /** `tab` / `popup` on the extension; null on mobile. */
    val presentation: String? = null,
    /** `retail` / `advanced` / `unknown`, observed from the page when available. */
    val surface: String? = null,
)

/** The row a vehicle emits natively once per dispatch (the extension's
 *  extension_request_settled). Covers success, error, and timeout. */
internal fun settledRow(outcome: String, totalMs: Long): JSONObject =
    JSONObject()
        .put("event_name", "extension_request_settled")
        .put("outcome", outcome)
        .put("total_ms", totalMs)
        .put("dropped_events", 0)

/** Promotes DRAFT rows to full wire rows conforming to the extension's base envelope. */
internal object TelemetryStamper {
    const val SOURCE = "sdk-android" // Loki discriminator; the extension uses "extension"
    const val SCHEMA_VERSION = "1.0"
    const val BROWSER = "unknown" // existing enum member; platform is carried by SOURCE

    // background < content-script < injected — the extension's causal ordering.
    private val REALM_RANK = mapOf("background" to 0, "content-script" to 1, "injected" to 2)

    private fun isoFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /** Sort [drafts] into one timeline by `(at, realmRank, seq)`, renumber `seq`
     *  from 1, and stamp the base fields. */
    fun build(drafts: List<JSONObject>, dims: TelemetryDims): JSONArray {
        val iso = isoFormatter()
        val ordered = drafts.sortedWith(
            compareBy(
                { it.optLong("at", 0L) },
                { REALM_RANK[it.optString("realm", "injected")] ?: 2 },
                { it.optLong("seq", 0L) },
            ),
        )
        val out = JSONArray()
        ordered.forEachIndexed { i, draft ->
            val at = draft.optLong("at", 0L)
            val row = JSONObject()
            // Event-specific fields the draft carried (event_name + its own fields).
            for (key in draft.keys()) {
                if (key != "at" && key != "seq" && key != "realm") row.put(key, draft.get(key))
            }
            row.put("event_id", UUID.randomUUID().toString())
            row.put("source", SOURCE)
            row.put("schema_version", SCHEMA_VERSION)
            row.put("timestamp", iso.format(at))
            row.put("at", at)
            row.put("seq", i + 1)
            row.put("realm", draft.optString("realm", "injected"))
            row.put("request_id", dims.requestId)
            row.put("dispatch_id", draft.opt("dispatch_id") ?: JSONObject.NULL)
            row.put("extension_version", "android-${BuildConfig.SDK_VERSION}")
            row.put("browser", BROWSER)
            row.put("platform_id", dims.platformId)
            row.put("operation", dims.operation)
            row.put("flow", dims.flow ?: JSONObject.NULL)
            row.put("presentation", dims.presentation ?: JSONObject.NULL)
            row.put("surface", dims.surface ?: JSONObject.NULL)
            row.put("zeroauth_session_id", dims.zeroauthSessionId ?: JSONObject.NULL)
            row.put("tab_id", JSONObject.NULL)
            out.put(row)
        }
        return out
    }
}

/** Buffers this dispatch's rows. onEvents feeds [pushDraftsJson], native rows feed
 *  [emitNative], and [build] stamps them at the end. Thread-safe. */
internal class TelemetryCollector {
    private val rows = ArrayList<JSONObject>()
    private var nativeSeq = 0

    /** Adopt the injected realm's drained draft array (a JSON array string). */
    @Synchronized
    fun pushDraftsJson(json: String?) {
        if (json.isNullOrBlank()) return
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            (arr.opt(i) as? JSONObject)?.let { rows.add(it) }
        }
    }

    /** Emit a native (realm `background`) row — the settle row and the no-script /
     *  timeout / load-error paths. [fields] holds event_name + that event's fields. */
    @Synchronized
    fun emitNative(fields: JSONObject) {
        fields.put("at", System.currentTimeMillis())
        fields.put("seq", ++nativeSeq)
        fields.put("realm", "background")
        rows.add(fields)
    }

    @Synchronized
    fun isEmpty(): Boolean = rows.isEmpty()

    @Synchronized
    fun build(dims: TelemetryDims): JSONArray = TelemetryStamper.build(rows.toList(), dims)
}
