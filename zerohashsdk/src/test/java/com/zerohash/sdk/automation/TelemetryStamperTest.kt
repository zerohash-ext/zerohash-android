package com.zerohash.sdk.automation

import com.zerohash.sdk.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryStamperTest {

    @Test
    fun build_stampsTheAndroidSdkAndItsVersion() {
        val rows = TelemetryStamper.build(
            listOf(JSONObject().put("event_name", "extension_handler_phase_reached").put("at", 1000L)),
            TelemetryDims(requestId = "request-1", platformId = "cbase", operation = "getBalance"),
        )
        val row = rows.getJSONObject(0)

        assertEquals("sdk-android", row.getString("source"))
        assertEquals("android", row.getString("sdk"))
        assertEquals(BuildConfig.SDK_VERSION, row.getString("version"))
    }
}
