package com.zerohash.funddemo

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
    private val mintExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var currentStep = 0

    companion object {
        private const val TAG = "ZerohashDemo"
        private const val PREFS = "demo-prefs"
        private const val KEY_DEV_MODE = "devModeEnabled"

        private const val STEP_ENVIRONMENT = 0
        private const val STEP_FLOW = 1
        private const val STEP_TOKEN = 2

        private val STEP_TITLES = arrayOf(
            "Step 1 of 3 · Environment",
            "Step 2 of 3 · Flow",
            "Step 3 of 3 · Token",
        )

        // Permissions are derived per flow (see flowPermissions) rather than
        // picked from a checkbox grid.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupUI()
    }

    /**
     * Edge-to-edge is mandatory at targetSdk 35+ (this app targets 36), so the
     * window draws behind the system bars. Inset the header below the status bar
     * and — the reported bug — lift the persistent footer's buttons above the
     * navigation/gesture bar so they aren't clipped or untappable on devices with
     * a bottom system bar.
     */
    private fun applyWindowInsets() {
        val footerPadBottom = binding.llFooter.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.root.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.llFooter.updatePadding(bottom = footerPadBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupUI() {
        setupDevMode()

        binding.tvBuildNote.text = if (BuildConfig.DEBUG) {
            "Debug build — all environments enabled. Gating and Dev also need the " +
                "corporate VPN and the Netskope CA installed on this device."
        } else {
            "Release build — Gating and Dev are disabled; install a debug build to " +
                "test internal environments. Cert and Production work normally."
        }

        setupAutomationLocators()

        // Platform/participant are 6-char all-caps codes.
        val codeFilters = arrayOf<android.text.InputFilter>(
            android.text.InputFilter.AllCaps(),
            android.text.InputFilter.LengthFilter(6),
        )
        binding.etPlatform.filters = codeFilters
        binding.etParticipant.filters = codeFilters

        binding.btnBack.setOnClickListener { goTo(currentStep - 1) }
        binding.btnPrimary.setOnClickListener { onPrimary() }
        binding.btnClearLog.setOnClickListener { binding.tvLog.text = "" }
        binding.btnClearJwt.setOnClickListener {
            binding.etJwt.setText("")   // text-watcher flips the footer back to "Mint & Open"
        }
        binding.btnCopyJwt.setOnClickListener {
            val jwt = binding.etJwt.text.toString().trim()
            if (jwt.isBlank()) {
                showError("No JWT to copy")
            } else {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("jwt", jwt))
                addLog("Copied JWT (${jwt.length} chars) to clipboard")
                showToast("JWT copied")
            }
        }

        // Two token modes: mint a JWT on the fly, or paste an existing one. The
        // toggle swaps which inputs are shown and what the footer button does.
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) applyTokenMode(checkedId == R.id.btnModeMint)
        }
        binding.toggleMode.check(R.id.btnModeMint)

        goTo(STEP_ENVIRONMENT)
    }

    /** Switches to [step] and reconfigures the persistent footer for it. */
    private fun goTo(step: Int) {
        currentStep = step
        binding.vfSteps.displayedChild = step
        binding.tvStepHeader.text = STEP_TITLES[step]
        binding.btnBack.visibility = if (step == STEP_ENVIRONMENT) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnPrimary.text = if (step == STEP_TOKEN) tokenPrimaryLabel() else "Next"
    }

    /**
     * Stamps stable, semantic accessibility IDs (content-descriptions) on every
     * automatable control so Appium (`~env-dev`) and Maestro (`id: env-dev`) can
     * locate elements independently of their visible text — which changes with
     * env/flow copy and the primary button's Next / Mint & Open / Open label.
     * Resource-ids stay the primary Appium locator; these are the label-proof hook.
     */
    private fun setupAutomationLocators() {
        binding.rbProduction.contentDescription = "env-production"
        binding.rbSandbox.contentDescription = "env-sandbox"
        binding.rbGating.contentDescription = "env-gating"
        binding.rbDev.contentDescription = "env-dev"
        binding.rbFlowFund.contentDescription = "flow-fund"
        binding.rbFlowCryptoWd.contentDescription = "flow-crypto-withdrawals"
        binding.rbFlowFundWd.contentDescription = "flow-fund-withdrawals"
        binding.etPlatform.contentDescription = "input-platform"
        binding.etParticipant.contentDescription = "input-participant"
        binding.etApplicationId.contentDescription = "input-application-id"
        binding.etDeviceId.contentDescription = "input-device-id"
        binding.btnModeMint.contentDescription = "mode-mint"
        binding.btnModePaste.contentDescription = "mode-paste"
        binding.cbAuthPolicy.contentDescription = "toggle-auth-policy"
        binding.etJwt.contentDescription = "input-jwt"
        binding.btnCopyJwt.contentDescription = "action-copy-jwt"
        binding.btnClearJwt.contentDescription = "action-clear-jwt"
        binding.btnClearLog.contentDescription = "action-clear-log"
        binding.btnBack.contentDescription = "action-back"
        binding.btnPrimary.contentDescription = "action-primary"
    }

    /** Token-step primary label depends on the selected mode. */
    private fun tokenPrimaryLabel(): String =
        if (binding.toggleMode.checkedButtonId == R.id.btnModePaste) "Open" else "Mint & Open"

    /** Show the inputs for the chosen token mode and refresh the footer action. */
    private fun applyTokenMode(mintMode: Boolean) {
        binding.llMintMode.visibility = if (mintMode) android.view.View.VISIBLE else android.view.View.GONE
        binding.llPasteMode.visibility = if (mintMode) android.view.View.GONE else android.view.View.VISIBLE
        if (currentStep == STEP_TOKEN) binding.btnPrimary.text = tokenPrimaryLabel()
    }

    /** Primary footer action, per the current step. */
    private fun onPrimary() {
        when (currentStep) {
            STEP_ENVIRONMENT -> goTo(STEP_FLOW)
            STEP_FLOW -> {
                applyDefaultsForMint()
                goTo(STEP_TOKEN)
            }
            STEP_TOKEN ->
                if (binding.toggleMode.checkedButtonId == R.id.btnModePaste) {
                    if (binding.etJwt.text.toString().isBlank()) {
                        showError("Paste a JWT first")
                    } else {
                        launchSelectedFlow()
                    }
                } else {
                    // Mint a fresh JWT from the inputs, then open on success
                    // (failures surface inline + as a red error bar).
                    startMint(openOnSuccess = true)
                }
        }
    }

    private fun launchSelectedFlow() {
        when (binding.rgFlow.checkedRadioButtonId) {
            R.id.rbFlowCryptoWd -> startCryptoWithdrawals()
            R.id.rbFlowFundWd -> startFundWithdrawals()
            else -> startFund()
        }
    }

    /** Default mint inputs for a chosen (environment, flow). */
    private data class MintDefaults(
        val platform: String,
        val participant: String,
        val permissions: List<String>,
        val authPolicy: Boolean,
    )

    /** Permission set a flow needs (used when no env-specific default exists). */
    private fun flowPermissions(flowId: Int): List<String> = when (flowId) {
        R.id.rbFlowCryptoWd -> listOf("crypto-withdrawals")
        R.id.rbFlowFundWd -> listOf("crypto-withdrawals")
        else -> listOf("fwc") // Fund
    }

    /**
     * Default mint inputs per (environment, flow). Codes are non-PII internal test
     * data from two sources:
     *  - Gating Fund: android SDK fund gating suite (zerohash-android/.env.gating,
     *    verified 2026-08-21) — auth platform BM3LDA/62LHRQ (fwc + crypto-deposits
     *    + auth_policy); non-auth platform is HSBCRW/JLXERM (flip auth_policy off).
     *  - Everything else: sdk-ui-apps per-env TEST_DATA
     *    (local-testing/data/test-data.js) — defaultPlatform/participant for Fund,
     *    payoutPlatform/participant for Fund Withdrawals.
     * Blank where no code exists yet; every field stays editable.
     */
    private fun defaultsFor(env: Environment, flowId: Int): MintDefaults {
        val fund = flowId == R.id.rbFlowFund
        val fwd = flowId == R.id.rbFlowFundWd
        val perms = flowPermissions(flowId)
        return when (env) {
            Environment.SANDBOX -> when {
                fund -> MintDefaults("UW6VWU", "T0A4YI", listOf("fwc"), authPolicy = true)
                fwd -> MintDefaults("MECSTB", "D4G1I3", perms, authPolicy = false)
                else -> MintDefaults("UW6VWU", "6GLSCW", perms, authPolicy = false)
            }
            Environment.GATING -> when {
                fund -> MintDefaults("BM3LDA", "62LHRQ", listOf("fwc", "crypto-deposits"), authPolicy = true)
                fwd -> MintDefaults("MECSTB", "D4G1I3", perms, authPolicy = false)
                else -> MintDefaults("D2VWYF", "0O7L9E", perms, authPolicy = false)
            }
            Environment.DEV -> when {
                fund -> MintDefaults("H552SV", "ZHH1NA", listOf("fwc"), authPolicy = true)
                else -> MintDefaults("H552SV", "ZHH1NA", perms, authPolicy = false)
            }
            Environment.PRODUCTION -> MintDefaults("", "", perms, authPolicy = fund)
        }
    }

    /** Prefills the Token step from [defaultsFor] the chosen env + flow. */
    private fun applyDefaultsForMint() {
        val d = defaultsFor(selectedEnvironment(), binding.rgFlow.checkedRadioButtonId)
        // Start each run with a clean JWT so we always re-mint for the chosen
        // env/flow (footer resets to "Mint & Open" via the text-watcher).
        binding.etJwt.setText("")
        binding.toggleMode.check(R.id.btnModeMint)
        applyTokenMode(true)
        binding.tvFlowPermissions.text = d.permissions.joinToString(", ")
        binding.tvMintStatus.text = "“Mint & Open” generates a JWT from these values and opens the flow."
        binding.etPlatform.setText(d.platform)
        binding.etParticipant.setText(d.participant)
        binding.cbAuthPolicy.isChecked = d.authPolicy
        addLog(
            "Defaults ${selectedEnvironment().name}/${flowLabel()}: " +
                "platform=${d.platform.ifBlank { "—" }} participant=${d.participant.ifBlank { "—" }} " +
                "perms=${d.permissions} auth=${d.authPolicy}"
        )
    }

    private fun flowLabel(): String = when (binding.rgFlow.checkedRadioButtonId) {
        R.id.rbFlowCryptoWd -> "CryptoWithdrawals"
        R.id.rbFlowFundWd -> "FundWithdrawals"
        else -> "Fund"
    }

    // Permissions are derived from the selected flow (flowPermissions) and shown
    // read-only in tvFlowPermissions — no runtime checkbox grid.

    /**
     * Mints a JWT for the selected environment from its kyc-mock-platform-server
     * and drops it into the JWT field. Runs off the main thread; gating/dev only
     * succeed on an in-network device.
     */
    private fun startMint(openOnSuccess: Boolean = false) {
        val env = selectedEnvironment()
        val platform = binding.etPlatform.text.toString().trim()
        val participant = binding.etParticipant.text.toString().trim()
        val permissions = flowPermissions(binding.rgFlow.checkedRadioButtonId)
        val authPolicy = binding.cbAuthPolicy.isChecked
        val applicationId = binding.etApplicationId.text.toString().trim()
        val deviceId = binding.etDeviceId.text.toString().trim()

        if (platform.isEmpty() || participant.isEmpty()) {
            showError("Enter platform and participant codes")
            return
        }

        val url = "${MintClient.managerHost(env)}/manager/jwt${if (authPolicy) "?auth_policy_enabled=true" else ""}"
        addLog("POST $url")
        addLog(
            "body: platform=$platform participant=$participant perms=$permissions" +
                (if (applicationId.isNotBlank()) " appId=$applicationId" else "") +
                (if (deviceId.isNotBlank()) " deviceId=$deviceId" else "")
        )
        binding.btnPrimary.isEnabled = false
        binding.tvMintStatus.text = "Minting… → $url"
        mintExecutor.execute {
            val result = runCatching {
                MintClient.mint(
                    MintClient.Params(
                        env, platform, participant, permissions, authPolicy, applicationId, deviceId,
                    )
                )
            }
            runOnUiThread {
                binding.btnPrimary.isEnabled = true
                result
                    .onSuccess { token ->
                        binding.etJwt.setText(token)
                        binding.tvMintStatus.text = "✓ Minted ${token.length}-char JWT from\n$url"
                        addLog("Minted JWT (${token.length} chars)")
                        showToast("JWT minted")
                        if (currentStep == STEP_TOKEN) binding.btnPrimary.text = tokenPrimaryLabel()
                        if (openOnSuccess) launchSelectedFlow()
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Mint failed", e)
                        val detail = "${e.javaClass.simpleName}: ${e.message}"
                        val isTls = (e.message ?: "").contains("Trust anchor", true) ||
                            (e.message ?: "").contains("SSL", true) ||
                            (e.cause?.message ?: "").contains("Trust anchor", true)
                        val hint = if (isTls) {
                            "\n\nTLS not trusted. cert/gat/dev sit behind Netskope — install the " +
                                "Netskope CA on this device (Settings ▸ Security ▸ Encryption & " +
                                "credentials ▸ Install a certificate ▸ CA), then retry."
                        } else ""
                        binding.tvMintStatus.text = "✗ Mint failed\n$detail$hint"
                        addLog("Mint failed: $detail")
                        if (isTls) addLog("Hint: install the Netskope CA on the device (TLS trust anchor missing).")
                        showError("Mint failed: $detail")
                    }
            }
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
                        runOnUiThread { goTo(STEP_ENVIRONMENT) }
                    }

                    override fun onError(error: ZerohashError) {
                        Log.e(TAG, "Fund error: ${error.message}")
                        addLog("onError: $error")
                        showError("Error: ${error.message}")
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
                        showError("Funding failed")
                    }
                }
            )
            fundSession?.present(this)
            addLog("Fund session presented")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Fund", e)
            addLog("Exception: ${e.message}")
            showError("Failed to start: ${e.message}")
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
                        runOnUiThread { goTo(STEP_ENVIRONMENT) }
                    }

                    override fun onError(error: ZerohashError) {
                        Log.e(TAG, "Crypto Withdrawals error: ${error.message}")
                        addLog("onError: $error")
                        showError("Error: ${error.message}")
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
                        showError("Withdrawal failed")
                    }
                }
            )
            cryptoWithdrawalsSession?.present(this)
            addLog("Crypto Withdrawals session presented")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Crypto Withdrawals", e)
            addLog("Exception: ${e.message}")
            showError("Failed to start: ${e.message}")
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
                        runOnUiThread { goTo(STEP_ENVIRONMENT) }
                    }

                    override fun onError(error: ZerohashError) {
                        Log.e(TAG, "Fund Withdrawals error: ${error.message}")
                        addLog("Error: ${error.message}")
                        showError("Error: ${error.message}")
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
            showError("Failed to start: ${e.message}")
        }
    }

    private fun resolveJwt(): String {
        return binding.etJwt.text.toString().trim().ifBlank {
            addLog("No JWT set — using dummy (will fail authentication)")
            "test-jwt-token-for-ui-testing"
        }
    }

    private fun selectedEnvironment(): Environment = when (binding.rgEnvironment.checkedRadioButtonId) {
        R.id.rbSandbox -> Environment.SANDBOX
        R.id.rbGating -> Environment.GATING
        R.id.rbDev -> Environment.DEV
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

    // Info messages use a neutral Snackbar (not a system Toast): Android 12+
    // stamps the app icon onto every toast and we can't restyle it, so toasts
    // rendered our logo oddly. Snackbars are in-app views with no icon.
    private fun showToast(message: String) {
        runOnUiThread {
            val sb = com.google.android.material.snackbar.Snackbar.make(
                binding.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT,
            )
            sb.setBackgroundTint(android.graphics.Color.parseColor("#1C2A23"))
            sb.setTextColor(android.graphics.Color.parseColor("#E7EFEA"))
            sb.show()
        }
    }

    /**
     * Errors use a red Snackbar that stays until dismissed (not a fleeting toast),
     * so a failure is easy to notice and read.
     */
    private fun showError(message: String) {
        runOnUiThread {
            val sb = com.google.android.material.snackbar.Snackbar.make(
                binding.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE,
            )
            sb.setBackgroundTint(android.graphics.Color.parseColor("#C5362E"))
            sb.setTextColor(android.graphics.Color.WHITE)
            sb.setActionTextColor(android.graphics.Color.WHITE)
            sb.setAction("Dismiss") { sb.dismiss() }
            sb.view.findViewById<android.widget.TextView>(
                com.google.android.material.R.id.snackbar_text,
            )?.maxLines = 6
            sb.show()
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
        mintExecutor.shutdownNow()
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
