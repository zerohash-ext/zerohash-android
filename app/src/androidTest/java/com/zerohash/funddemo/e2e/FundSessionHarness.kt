package com.zerohash.funddemo.e2e

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.zerohash.funddemo.MainActivity
import com.zerohash.sdk.Environment
import com.zerohash.sdk.GenericEvent
import com.zerohash.sdk.Theme
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.ZerohashSDK
import com.zerohash.sdk.fund.FundCallbacks
import com.zerohash.sdk.fund.FundCompletedEvent
import com.zerohash.sdk.fund.ZerohashFundSession
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records every SDK callback for assertions — the native stand-in for the web
 * suite's network assertions (instrumentation cannot sniff WebView traffic).
 */
class RecordingCallbacks : FundCallbacks {
    val errors = CopyOnWriteArrayList<ZerohashError>()
    val events = CopyOnWriteArrayList<GenericEvent>()
    val completions = CopyOnWriteArrayList<FundCompletedEvent>()

    @Volatile
    var closed = false
        private set

    override fun onClose() {
        closed = true
    }

    override fun onError(error: ZerohashError) {
        errors.add(error)
    }

    override fun onEvent(event: GenericEvent) {
        events.add(event)
    }

    override fun onFundCompleted(event: FundCompletedEvent) {
        completions.add(event)
    }
}

/**
 * Boots a real Fund session against GATING (MainActivity -> configureFund ->
 * WebViewActivity). Calls `configureFund` directly — the demo form has no GATING option.
 */
class FundSessionHarness(private val jwt: String) {

    val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val callbacks = RecordingCallbacks()

    private var scenario: ActivityScenario<MainActivity>? = null
    private var session: ZerohashFundSession? = null

    fun boot(): FundSessionHarness {
        scenario = ActivityScenario.launch(MainActivity::class.java).also { s ->
            s.onActivity { activity ->
                session = ZerohashSDK.configureFund(
                    jwt = jwt,
                    environment = Environment.GATING,
                    theme = Theme.LIGHT,
                    callbacks = callbacks,
                )
                session?.present(activity)
            }
        }
        return this
    }

    fun tearDown() {
        session?.cancel()
        session = null
        scenario?.close()
        scenario = null
    }

    fun waitForText(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): UiObject2? {
        device.wait(Until.hasObject(By.textContains(text)), timeoutMs)
        return device.findObject(By.textContains(text))
    }

    fun hasText(text: String): Boolean = device.hasObject(By.textContains(text))

    fun tapText(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val obj = waitForText(text, timeoutMs) ?: return false
        obj.click()
        return true
    }

    /** T&Cs may already be signed for a real gating participant — fall through if absent. */
    fun acceptTermsIfPresent(timeoutMs: Long = TERMS_TIMEOUT_MS) {
        val accept = waitForText(TERMS_ACCEPT_TEXT, timeoutMs) ?: return
        accept.click()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val TERMS_TIMEOUT_MS = 20_000L

        // Semantic anchors — same strings the AUTH-3630 web page objects assert on.
        const val TERMS_ACCEPT_TEXT = "Accept"
        const val INTEGRATIONS_PICKER_TITLE = "Select source"
        const val DEPOSIT_MANUALLY_TEXT = "Deposit manually"
        const val SELECT_ASSET_TITLE = "Select asset"
    }
}