package com.zerohash.sdk.automation

/**
 * Decides when a Coinbase page is safe to drive, i.e. when no Cloudflare challenge
 * is in front of it (AUTH-4245).
 *
 * The decision lives in its own class because the unit suite has no Robolectric:
 * nothing inside a `WebViewClient` callback can be executed by a test.
 *
 * Single-threaded by contract, driven from the main thread like the WebView.
 *
 * @param budgetMs how long to wait for a challenge to clear before giving up.
 * @param pollIntervalMs delay between probes.
 * @param now clock, injected for tests.
 */
internal class ChallengeGate(
    private val budgetMs: Long,
    private val pollIntervalMs: Long,
    private val now: () -> Long,
) {
    sealed interface Decision {
        data object Clear : Decision

        data class Retry(val delayMs: Long) : Decision

        /**
         * Never cleared inside the budget. Callers must surface this as a failure and
         * not proceed: driving a still-challenged page reports a misleading selector
         * error minutes later instead.
         */
        data object Exhausted : Decision
    }

    private var deadline: Long? = null

    /** Idempotent: Coinbase's SPA fires `onPageFinished` more than once, and
     *  restarting would hold the budget open indefinitely. */
    fun start() {
        if (deadline == null) deadline = now() + budgetMs
    }

    /** [raw] is what `evaluateJavascript` hands back for [CHALLENGE_PROBE]. */
    fun onProbeResult(raw: String?): Decision {
        // Only the exact "false" proves the page is clean. null matters most:
        // evaluateJavascript yields null while a document is torn down and replaced,
        // which is exactly when Cloudflare solves. Reading that as "clear" was the bug.
        if (raw == CLEAR) return Decision.Clear
        val end = deadline ?: return Decision.Retry(pollIntervalMs)
        if (now() >= end) return Decision.Exhausted
        return Decision.Retry(pollIntervalMs)
    }

    internal companion object {
        private const val CLEAR = "false"

        /**
         * One definition, shared by every runner.
         *
         * iOS matches five further markers. Widening to match is a separate change:
         * it also affects the balance path, which has its own retry choreography.
         */
        const val CHALLENGE_PROBE =
            "(!!(window._cf_chl_opt || document.querySelector('div[class=\"ch-title-zone\"]')))"
    }
}
