package com.zerohash.sdk.ui

import com.zerohash.sdk.CallbackHandler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryTest {

    private class NoopHandler : CallbackHandler {
        override fun handleClose() {}
        override fun handleError(code: String?, message: String, data: JSONObject?) {}
        override fun handleEvent(type: String, data: JSONObject?) {}
    }

    private var clock = 0L
    private val registry = SessionRegistry(ttlMs = 1_000L, now = { clock })
    private val handler = NoopHandler()
    private val firstActivity = Any()
    private val recreatedActivity = Any()

    @Test
    fun `claim returns the registered handler and keeps it registered`() {
        registry.register("s1", handler)

        assertSame(handler, registry.claim("s1", firstActivity) {})
        assertTrue(registry.contains("s1"))
    }

    @Test
    fun `claim of an unknown session returns null`() {
        assertNull(registry.claim("missing", firstActivity) {})
    }

    @Test
    fun `a recreated activity reclaims the same handler after the old one detaches`() {
        registry.register("s1", handler)
        registry.claim("s1", firstActivity) {}

        registry.detach("s1", firstActivity)

        assertSame(handler, registry.claim("s1", recreatedActivity) {})
    }

    @Test
    fun `release removes the handler`() {
        registry.register("s1", handler)
        registry.claim("s1", firstActivity) {}

        registry.release("s1")

        assertFalse(registry.contains("s1"))
        assertNull(registry.claim("s1", recreatedActivity) {})
    }

    @Test
    fun `dismiss invokes the attached activity's callback and removes the handler`() {
        var dismissed = 0
        registry.register("s1", handler)
        registry.claim("s1", firstActivity) { dismissed++ }

        registry.dismiss("s1")

        assertEquals(1, dismissed)
        assertFalse(registry.contains("s1"))
    }

    @Test
    fun `dismiss reaches the recreated activity, not the destroyed one`() {
        var firstDismissed = 0
        var recreatedDismissed = 0
        registry.register("s1", handler)
        registry.claim("s1", firstActivity) { firstDismissed++ }
        registry.detach("s1", firstActivity)
        registry.claim("s1", recreatedActivity) { recreatedDismissed++ }

        registry.dismiss("s1")

        assertEquals(0, firstDismissed)
        assertEquals(1, recreatedDismissed)
    }

    @Test
    fun `a late detach from the destroyed activity does not unhook the recreated one`() {
        var recreatedDismissed = 0
        registry.register("s1", handler)
        registry.claim("s1", firstActivity) {}
        registry.claim("s1", recreatedActivity) { recreatedDismissed++ }

        registry.detach("s1", firstActivity)
        registry.dismiss("s1")

        assertEquals(1, recreatedDismissed)
    }

    @Test
    fun `dismiss with no attached activity still removes the handler`() {
        registry.register("s1", handler)
        registry.claim("s1", firstActivity) {}
        registry.detach("s1", firstActivity)

        registry.dismiss("s1")

        assertFalse(registry.contains("s1"))
    }

    @Test
    fun `an unclaimed handler past the ttl is evicted on the next register`() {
        registry.register("stale", handler)
        clock = 1_001L

        registry.register("s2", handler)

        assertFalse(registry.contains("stale"))
        assertTrue(registry.contains("s2"))
    }

    @Test
    fun `a claimed handler past the ttl survives the next register`() {
        registry.register("live", handler)
        registry.claim("live", firstActivity) {}
        clock = 10_000L

        registry.register("s2", handler)

        assertTrue(registry.contains("live"))
    }
}
