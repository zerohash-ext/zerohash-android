package com.zerohash.sdk.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.zerohash.sdk.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Custom URL schemes from Dynamic's wallet book, so a new wallet reaches the
 * allow-list without an SDK release. Starts from the build-time snapshot, then
 * merges the cached copy and a daily refresh fetched by the native side.
 */
internal object WalletSchemeStore {
    private const val TAG = "WalletSchemeStore"
    const val WALLET_BOOK_URL = "https://dynamic-static-assets.com/wallet-book/v1/stable/wallet-book.json"
    const val PREFS_NAME = "com.zerohash.sdk.wallet_schemes"
    const val SCHEMES_KEY = "schemes"
    const val FETCHED_AT_KEY = "fetched_at"
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 15_000

    private val schemeShape = Regex("^[a-z][a-z0-9+.-]*$")
    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var remote: Set<String> = emptySet()

    val schemes: Set<String>
        get() = WalletSchemeSnapshot.SCHEMES + remote

    fun refreshIfStale(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadCached(prefs)
        val fetchedAt = prefs.getLong(FETCHED_AT_KEY, 0L)
        if (now - fetchedAt < MAX_AGE_MS || !refreshing.compareAndSet(false, true)) return

        Thread {
            try {
                val body = fetch(WALLET_BOOK_URL)
                if (body == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Wallet book fetch failed; keeping the current list")
                } else {
                    apply(body, prefs, System.currentTimeMillis())
                }
            } finally {
                refreshing.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    /** Replaces the remote set when the payload parses to a non-empty list. */
    fun apply(body: String, prefs: SharedPreferences, fetchedAt: Long) {
        val parsed = parse(body)
        if (parsed.isEmpty()) {
            Log.w(TAG, "Wallet book had no usable schemes; keeping the current list")
            return
        }
        remote = parsed
        prefs.edit()
            .putString(SCHEMES_KEY, JSONArray(parsed.sorted()).toString())
            .putLong(FETCHED_AT_KEY, fetchedAt)
            .apply()
    }

    /**
     * Extracts the scheme of every `mobile.native` deep link in a wallet book.
     * Same rules as scripts/generate-wallet-schemes.mjs.
     */
    fun parse(body: String): Set<String> {
        val wallets = try {
            JSONObject(body).optJSONObject("wallets") ?: return emptySet()
        } catch (e: Exception) {
            return emptySet()
        }
        val result = mutableSetOf<String>()
        for (key in wallets.keys()) {
            val native = wallets.optJSONObject(key)?.optJSONObject("mobile")?.optString("native").orEmpty()
            schemeOf(native)?.let(result::add)
        }
        return result
    }

    fun schemeOf(deeplink: String): String? {
        val scheme = deeplink.trim().substringBefore(':', missingDelimiterValue = "").lowercase()
        if (!schemeShape.matches(scheme)) return null
        if (scheme in ExternalSchemePolicy.RESERVED_SCHEMES) return null
        return scheme
    }

    private fun loadCached(prefs: SharedPreferences) {
        if (remote.isNotEmpty()) return
        val raw = prefs.getString(SCHEMES_KEY, null) ?: return
        remote = try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun fetch(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
