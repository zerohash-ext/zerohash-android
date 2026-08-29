package com.zerohash.funddemo

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zerohash.funddemo.databinding.ActivityMainBinding
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.ZerohashSDK
import com.zerohash.sdk.Environment
import com.zerohash.sdk.GenericEvent
import com.zerohash.sdk.Theme
import com.zerohash.sdk.cryptowithdrawals.CryptoWithdrawalsCallbacks
import com.zerohash.sdk.cryptowithdrawals.CryptoWithdrawalsCompletedEvent
import com.zerohash.sdk.cryptowithdrawals.ZerohashCryptoWithdrawalsSession
import com.zerohash.sdk.fund.FundCallbacks
import com.zerohash.sdk.fund.FundCompletedEvent
import com.zerohash.sdk.fund.ZerohashFundSession
import com.zerohash.sdk.fundwithdrawals.FundWithdrawalsCallbacks
import com.zerohash.sdk.fundwithdrawals.FundWithdrawalsCompletedEvent
import com.zerohash.sdk.fundwithdrawals.ZerohashFundWithdrawalsSession

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var fundSession: ZerohashFundSession? = null
    private var cryptoWithdrawalsSession: ZerohashCryptoWithdrawalsSession? = null
    private var fundWithdrawalsSession: ZerohashFundWithdrawalsSession? = null

    companion object {
        private const val TAG = "ZerohashDemo"
        private const val PREFS = "demo-prefs"
        private const val KEY_DEV_MODE = "devModeEnabled"
        private const val DEMO_JWT = "your-jwt-token-here"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        supportActionBar?.subtitle = BuildConfig.ZEROHASH_SDK_SOURCE
        binding.tvSdkSource.text = "SDK source: ${BuildConfig.ZEROHASH_SDK_SOURCE}"

        setupDevMode()

        binding.etJwt.setText(DEMO_JWT)

        binding.btnFund.setOnClickListener {
            startFund()
        }

        binding.btnCryptoWithdrawals.setOnClickListener {
            startCryptoWithdrawals()
        }

        binding.btnFundWithdrawals.setOnClickListener {
            startFundWithdrawals()
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = ""
        }
    }

    private fun startFund() {
        val jwt = resolveJwt()
        val environment = selectedEnvironment()
        val theme = selectedTheme()

        addLog("SDK source: ${BuildConfig.ZEROHASH_SDK_SOURCE}")
        addLog("Environment: ${environment.toWebValue()}")
        addLog("Theme: ${theme.toWebValue()}")

        try {
            addLog("Starting Fund session...")
            fundSession = ZerohashSDK.configureFund(
                jwt = jwt,
                environment = environment,
                theme = theme,
                callbacks = object : FundCallbacks {
                    override fun onClose() {
                        addLog("onClose")
                        showToast("Session closed")
                        fundSession = null
                    }

                    override fun onError(error: ZerohashError) {
                        Log.e(TAG, "Fund error: ${error.message}")
                        addLog("onError: $error")
                        showToast("Error: ${error.message}")
                    }

                    override fun onEvent(event: GenericEvent) {
                        addLog("onEvent: $event")
                    }

                    override fun onLoaded() {
                        addLog("onLoaded")
                    }

                    override fun onCompleted(event: FundCompletedEvent) {
                        addLog("onCompleted: $event")
                        showToast("Funding completed")
                    }

                    override fun onFailed(event: FundCompletedEvent) {
                        addLog("onFailed: $event")
                        showToast("Funding failed")
                    }
                }
            )
            fundSession?.present(this)
            addLog("Fund session presented")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Fund", e)
            addLog("Exception: ${e.message}")
            showToast("Failed to start: ${e.message}")
        }
    }

    private fun startCryptoWithdrawals() {
        val jwt = resolveJwt()
        val environment = selectedEnvironment()
        val theme = selectedTheme()

        addLog("SDK source: ${BuildConfig.ZEROHASH_SDK_SOURCE}")
        addLog("Environment: ${environment.toWebValue()}")
        addLog("Theme: ${theme.toWebValue()}")

        try {
            addLog("Starting Crypto Withdrawals session...")
            cryptoWithdrawalsSession = ZerohashSDK.configureCryptoWithdrawals(
                jwt = jwt,
                environment = environment,
                theme = theme,
                callbacks = object : CryptoWithdrawalsCallbacks {
                    override fun onClose() {
                        addLog("onClose")
                        showToast("Session closed")
                        cryptoWithdrawalsSession = null
                    }

                    override fun onError(error: ZerohashError) {
                        Log.e(TAG, "Crypto Withdrawals error: ${error.message}")
                        addLog("onError: $error")
                        showToast("Error: ${error.message}")
                    }

                    override fun onEvent(event: GenericEvent) {
                        addLog("onEvent: $event")
                    }

                    override fun onLoaded() {
                        addLog("onLoaded")
                    }

                    override fun onCompleted(event: CryptoWithdrawalsCompletedEvent) {
                        addLog("onCompleted: $event")
                        showToast("Withdrawal completed")
                    }

                    override fun onFailed(event: CryptoWithdrawalsCompletedEvent) {
                        addLog("onFailed: $event")
                        showToast("Withdrawal failed")
                    }
                }
            )
            cryptoWithdrawalsSession?.present(this)
            addLog("Crypto Withdrawals session presented")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Crypto Withdrawals", e)
            addLog("Exception: ${e.message}")
            showToast("Failed to start: ${e.message}")
        }
    }

    private fun startFundWithdrawals() {
        val jwt = resolveJwt()
        val environment = selectedEnvironment()
        val theme = selectedTheme()

        addLog("SDK source: ${BuildConfig.ZEROHASH_SDK_SOURCE}")
        addLog("Environment: ${environment.toWebValue()}")
        addLog("Theme: ${theme.toWebValue()}")

        try {
            addLog("Starting Fund Withdrawals session...")
            fundWithdrawalsSession = ZerohashSDK.configureFundWithdrawals(
                jwt = jwt,
                environment = environment,
                theme = theme,
                callbacks = object : FundWithdrawalsCallbacks {
                    override fun onClose() {
                        addLog("Session closed")
                        showToast("Session closed")
                        fundWithdrawalsSession = null
                    }

                    override fun onError(error: ZerohashError) {
                        Log.e(TAG, "Fund Withdrawals error: ${error.message}")
                        addLog("Error: ${error.message}")
                        showToast("Error: ${error.message}")
                    }

                    override fun onEvent(event: GenericEvent) {
                        addLog("Event: ${event.type}")
                    }

                    override fun onCompleted(event: FundWithdrawalsCompletedEvent) {
                        addLog(
                            "Fund withdrawal completed: ${event.externalAccountId} " +
                                "(${event.assetSymbol} ${event.amount})"
                        )
                        showToast("Fund withdrawal completed")
                    }
                }
            )
            fundWithdrawalsSession?.present(this)
            addLog("Fund Withdrawals session presented")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Fund Withdrawals", e)
            addLog("Exception: ${e.message}")
            showToast("Failed to start: ${e.message}")
        }
    }

    private fun resolveJwt(): String {
        val jwt = binding.etJwt.text.toString().trim()
        return if (jwt.isBlank() || jwt == DEMO_JWT) {
            addLog("Using dummy JWT for testing (will fail authentication)")
            "test-jwt-token-for-ui-testing"
        } else {
            jwt
        }
    }

    private fun selectedEnvironment(): Environment = when (binding.rgEnvironment.checkedRadioButtonId) {
        R.id.rbSandbox -> Environment.SANDBOX
        else -> Environment.PRODUCTION
    }

    private fun selectedTheme(): Theme = when (binding.rgTheme.checkedRadioButtonId) {
        R.id.rbLight -> Theme.LIGHT
        R.id.rbDark -> Theme.DARK
        else -> Theme.SYSTEM
    }

    private fun addLog(message: String) {
        // Mirrored to Logcat because the SDK runs in its own Activity, which covers
        // this one: the on-screen log is only readable after the flow closes, so
        // `adb logcat -s $TAG` is the only way to watch events as they fire.
        Log.d(TAG, message)
        DevPanel.log(message)
        runOnUiThread {
            val currentLog = binding.tvLog.text.toString()
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val newLog = "[$timestamp] $message\n$currentLog"
            binding.tvLog.text = newLog
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system overlay-permission screen: attach now that it
        // may have been granted. Never requests — that would bounce straight back
        // out to settings and trap the app in a loop.
        if (binding.cbDevMode.isChecked) DevPanel.setEnabled(this, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel every flow, not just Fund — only one runs at a time, but the
        // host cannot know which, and a missed cancel leaks the session's
        // callback handler.
        fundSession?.cancel()
        cryptoWithdrawalsSession?.cancel()
        fundWithdrawalsSession?.cancel()
        DevPanel.detach()
    }

    /**
     * Dev mode gates the floating event log. Off by default and persisted, so the
     * app can be demoed with no debug UI on screen.
     */
    private fun setupDevMode() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        binding.cbDevMode.isChecked = prefs.getBoolean(KEY_DEV_MODE, false)
        DevPanel.setEnabled(this, binding.cbDevMode.isChecked)

        binding.cbDevMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_DEV_MODE, checked).apply()
            // Only the toggle may send the user to the permission screen.
            DevPanel.setEnabled(this, checked, requestPermission = true)
        }
    }
}
