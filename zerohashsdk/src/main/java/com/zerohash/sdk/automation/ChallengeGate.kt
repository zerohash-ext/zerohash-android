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
         * Matches iOS `AutomatedWebViewController.challengeProbe` marker for
         * marker. It used to check only two of the six, leaving the interstitial
         * and Turnstile-iframe forms undetected — a gate that misses the
         * challenge is not a gate, and the deposit-address flow now depends on
         * this firing rather than merely retrying.
         *
         * Widening can only make a probe MORE likely to report "challenged",
         * which on every consumer means "keep polling, don't inject yet" — so it
         * cannot make the balance path's retry choreography less safe.
         */
        const val CHALLENGE_PROBE =
            "(!!(window._cf_chl_opt || document.querySelector('#challenge-running, " +
                "#cf-challenge-running, #challenge-stage, #cf-chl-widget, " +
                "iframe[src*=\"challenges.cloudflare.com\"], div[class=\"ch-title-zone\"]')))"
    }
}
