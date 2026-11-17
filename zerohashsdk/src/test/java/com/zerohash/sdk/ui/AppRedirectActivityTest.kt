package com.zerohash.sdk.ui

import android.content.Intent
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppRedirectActivityTest {

    private class FakeTarget(override val hostTaskId: Int) : AppRedirectTarget {
        val received = mutableListOf<Uri>()
        override fun handleAppRedirect(data: Uri) {
            received += data
        }
    }

    private val returnUrl = Uri.parse(
        "acmeapp://redirect?zh_wallet_return=sent&zh_tx=0x9b1f6a4f0a9d7c4b2e8d1f3a5c7e9b0d2f4a6c8e0b1d3f5a7c9e1b3d5f7a9c1e"
    )

    @After
    fun tearDown() {
        AppRedirectTargets.active?.let(AppRedirectTargets::unregister)
    }

    private fun launch(intent: Intent) =
        Robolectric.buildActivity(AppRedirectActivity::class.java, intent).create().get()

    @Test
    fun `hands the url to the live flow and finishes`() {
        val target = FakeTarget(hostTaskId = 7)
        AppRedirectTargets.register(target)

        val activity = launch(Intent(Intent.ACTION_VIEW, returnUrl))

        assertEquals(listOf(returnUrl), target.received)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `finishes without a flow to hand the url to`() {
        val activity = launch(Intent(Intent.ACTION_VIEW, returnUrl))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `ignores an intent without data`() {
        val target = FakeTarget(hostTaskId = 7)
        AppRedirectTargets.register(target)

        val activity = launch(Intent(Intent.ACTION_VIEW))

        assertTrue(target.received.isEmpty())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a flow that was destroyed is no longer a target`() {
        val target = FakeTarget(hostTaskId = 7)
        AppRedirectTargets.register(target)
        AppRedirectTargets.unregister(target)

        launch(Intent(Intent.ACTION_VIEW, returnUrl))

        assertTrue(target.received.isEmpty())
    }
}
