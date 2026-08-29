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
import com.zerohash.sdk.ZerohashSession
import com.zerohash.sdk.fundwithdrawals.FundWithdrawalsCallbacks
import com.zerohash.sdk.fundwithdrawals.FundWithdrawalsCompletedEvent
import com.zerohash.sdk.fundwithdrawals.ZerohashFundWithdrawalsSession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records every Fund Withdrawals callback for assertions. Its own type rather
 * than a shared one, because each flow's callback interface differs.
 */
class RecordingFundWithdrawalsCallbacks : FundWithdrawalsCallbacks {
    val errors = CopyOnWriteArrayList<ZerohashError>()
    val events = CopyOnWriteArrayList<GenericEvent>()
    val completions = CopyOnWriteArrayList<FundWithdrawalsCompletedEvent>()

    private val _closeCount = AtomicInteger(0)
    val closeCount: Int get() = _closeCount.get()
    val closed: Boolean get() = closeCount > 0

    override fun onClose() {
        _closeCount.incrementAndGet()
    }

    override fun onError(error: ZerohashError) {
        errors.add(error)
    }

    override fun onEvent(event: GenericEvent) {
        events.add(event)
    }

    override fun onCompleted(event: FundWithdrawalsCompletedEvent) {
        completions.add(event)
    }
}

/**
 * Boots a real Fund Withdrawals session (MainActivity ->
 * configureFundWithdrawals -> WebViewActivity), mirroring [FundSessionHarness].
 * Calls the SDK factory directly rather than driving the demo form, which has no
 * GATING option.
 *
 * [presentedSession] captures what `present()` returned — documented as null when
 * the JWT fails client-side validation, which is the contract the JWT specs
 * assert on.
 */
class FundWithdrawalsSessionHarness(
    private val jwt: String,
    private val environment: Environment = Environment.GATING,
) {

    val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val callbacks = RecordingFundWithdrawalsCallbacks()

    @Volatile
    var presentedSession: ZerohashSession? = null
        private set

    private var scenario: ActivityScenario<MainActivity>? = null
    private var session: ZerohashFundWithdrawalsSession? = null

    fun boot(): FundWithdrawalsSessionHarness {
        scenario = ActivityScenario.launch(MainActivity::class.java).also { s ->
            // onActivity blocks until the main-thread block completes, so
            // presentedSession is populated by the time boot() returns.
            s.onActivity { activity ->
                session = ZerohashSDK.configureFundWithdrawals(
                    jwt = jwt,
                    environment = environment,
                    theme = Theme.LIGHT,
                    callbacks = callbacks,
                )
                presentedSession = session?.present(activity)
            }
        }
        return this
    }

    fun cancel() {
        session?.cancel()
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

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /**
         * How long to let a rejected session sit before asserting nothing was
         * launched. Short on purpose: the rejection is synchronous inside
         * `present()`, so this only needs to outlast an accidental async launch.
         */
        const val NO_LAUNCH_SETTLE_MS = 3_000L
    }
}
