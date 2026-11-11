package com.zerohash.sdk.ui

import com.zerohash.sdk.CallbackHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands a session's [CallbackHandler] from the session object to whichever
 * [WebViewActivity] instance is currently showing it.
 *
 * The handler outlives any single activity instance: a configuration change or
 * a system-initiated destroy recreates the activity, and the new instance must
 * find the same handler or the host never hears how the flow ended. So an
 * activity [claim]s the handler, [detach]es when it is destroyed mid-flow, and
 * only [release]s it when the flow is actually over. Unclaimed entries (the
 * activity never started) are evicted after [ttlMs]; claimed ones are not, since
 * a flow can legitimately stay open longer than that.
 */
internal class SessionRegistry(
    private val ttlMs: Long,
    private val now: () -> Long = System::currentTimeMillis
) {
    private class Entry(val handler: CallbackHandler, val registeredAt: Long) {
        @Volatile var claimed = false
        @Volatile var owner: Any? = null
        @Volatile var onDismiss: (() -> Unit)? = null
        @Volatile var onJwt: ((String) -> Unit)? = null
        // A token that arrived while no activity was attached (startup, rotation).
        @Volatile var pendingJwt: String? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(sessionId: String, handler: CallbackHandler) {
        val cutoff = now() - ttlMs
        entries.entries.removeAll { !it.value.claimed && it.value.registeredAt < cutoff }
        entries[sessionId] = Entry(handler, now())
    }

    fun claim(sessionId: String, owner: Any, onDismiss: () -> Unit): CallbackHandler? {
        val entry = entries[sessionId] ?: return null
        synchronized(entry) {
            entry.claimed = true
            entry.owner = owner
            entry.onDismiss = onDismiss
        }
        return entry.handler
    }

    fun onJwtUpdate(sessionId: String, owner: Any, onJwt: (String) -> Unit) {
        val entry = entries[sessionId] ?: return
        val pending = synchronized(entry) {
            if (entry.owner !== owner) return
            entry.onJwt = onJwt
            entry.pendingJwt.also { entry.pendingJwt = null }
        }
        pending?.let(onJwt)
    }

    /** False only when [sessionId] is gone. With no activity attached, the token waits for the next one. */
    fun updateJwt(sessionId: String, jwt: String): Boolean {
        val entry = entries[sessionId] ?: return false
        val onJwt = synchronized(entry) { entry.onJwt.also { if (it == null) entry.pendingJwt = jwt } }
        onJwt?.invoke(jwt)
        return true
    }

    fun detach(sessionId: String, owner: Any) {
        val entry = entries[sessionId] ?: return
        synchronized(entry) {
            if (entry.owner === owner) {
                entry.owner = null
                entry.onDismiss = null
                entry.onJwt = null
            }
        }
    }

    fun release(sessionId: String) {
        entries.remove(sessionId)
    }

    fun dismiss(sessionId: String) {
        val entry = entries.remove(sessionId) ?: return
        val onDismiss = synchronized(entry) { entry.onDismiss }
        onDismiss?.invoke()
    }

    fun contains(sessionId: String): Boolean = entries.containsKey(sessionId)
}
