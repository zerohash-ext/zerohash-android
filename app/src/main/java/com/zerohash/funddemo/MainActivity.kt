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
import com.zerohash.sdk.fund.FundCallbacks
import com.zerohash.sdk.fund.FundCompletedEvent
import com.zerohash.sdk.fund.ZerohashFundSession

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var fundSession: ZerohashFundSession? = null

    companion object {
        private const val TAG = "ZerohashDemo"
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

        binding.etJwt.setText(DEMO_JWT)

        binding.btnFund.setOnClickListener {
            startFund()
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
                        addLog("Session closed")
                        showToast("Session closed")
                        fundSession = null
                    }

                    override fun onError(error: ZerohashError) {
                        Log.e(TAG, "Fund error: ${error.message}")
                        addLog("Error: ${error.message}")
                        showToast("Error: ${error.message}")
                    }

                    override fun onEvent(event: GenericEvent) {
                        addLog("Event: ${event.type}")
                    }

                    override fun onFundCompleted(event: FundCompletedEvent) {
                        addLog("Fund completed: ${event.transactionId} (${event.assetSymbol} ${event.amount})")
                        showToast("Funding completed")
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

    override fun onDestroy() {
        super.onDestroy()
        fundSession?.cancel()
    }
}
