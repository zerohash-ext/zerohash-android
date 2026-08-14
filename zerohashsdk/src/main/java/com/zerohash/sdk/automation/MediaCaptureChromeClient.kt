package com.zerohash.sdk.automation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebChromeClient for the automation WebView. Grants the page's `getUserMedia`
 * capture request for the identity/liveness step — but only to a trusted https
 * origin, and only for tracks backed by a runtime permission.
 *
 * Each requested track maps to a runtime permission we must hold first (video →
 * `CAMERA`, audio → `RECORD_AUDIO`); iOS grants at capture time, Android has to
 * ask up front ([ensureRuntimePermissions]). Audio is requested only when the
 * page asks for it — a video-only ID/selfie check never prompts for the mic.
 * iOS parity: `AutomationSessionViewController.requestMediaCapturePermissionFor`.
 */
internal class MediaCaptureChromeClient(private val activity: Activity) : WebChromeClient() {

    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = request.origin
        // The ID/liveness capture runs in the provider's cross-origin iframe
        // (sdk.onfido.com), so accept the exchange host AND the liveness provider —
        // not just the exchange. The main frame is locked to Coinbase elsewhere.
        val trusted = origin != null && origin.scheme == "https" &&
            isTrustedMediaCaptureHost(origin.host)
        val wantsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        // Deny only when it wants neither track — a later audio-only request (mic
        // after camera) is still legit, so don't require video every time.
        if (!trusted || (!wantsVideo && !wantsAudio)) {
            Log.d(TAG, "media capture DENIED origin=${origin?.host} resources=${request.resources.joinToString()}")
            request.deny()
            return
        }

        // Ask for the runtime permission behind each requested track.
        val needed = mutableSetOf<String>()
        if (wantsVideo) needed.add(Manifest.permission.CAMERA)
        if (wantsAudio) needed.add(Manifest.permission.RECORD_AUDIO)

        ensureRuntimePermissions(needed) { granted ->
            // Grant each track only if its runtime permission came through.
            val grant = mutableListOf<String>()
            if (wantsVideo && Manifest.permission.CAMERA in granted) {
                grant.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            }
            if (wantsAudio && Manifest.permission.RECORD_AUDIO in granted) {
                grant.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            }
            if (grant.isEmpty()) {
                Log.d(TAG, "media capture DENIED: runtime permission refused")
                request.deny()
                return@ensureRuntimePermissions
            }
            Log.d(TAG, "media capture GRANTED origin=${origin?.host} resources=${grant.joinToString()}")
            request.grant(grant.toTypedArray())
        }
    }

    /**
     * Ensure [permissions], calling [onResult] with the subset that ended up granted
     * (already-held ones included). Prompts once for any not held via the activity's
     * [ComponentActivity.getActivityResultRegistry] (registered directly since the
     * request happens mid-session, unregistered once the result arrives). Empty when
     * the host activity isn't a [ComponentActivity].
     */
    private fun ensureRuntimePermissions(permissions: Set<String>, onResult: (Set<String>) -> Unit) {
        val held = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }.toSet()
        val toRequest = permissions - held
        if (toRequest.isEmpty()) {
            onResult(held)
            return
        }
        val registry = (activity as? ComponentActivity)?.activityResultRegistry
        if (registry == null) {
            Log.w(TAG, "cannot request $toRequest: host is not a ComponentActivity")
            onResult(held)
            return
        }
        var launcher: ActivityResultLauncher<Array<String>>? = null
        launcher = registry.register(
            "zh-media-capture-${NEXT_KEY.getAndIncrement()}",
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            onResult(held + result.filterValues { it }.keys)
            launcher?.unregister()
        }
        launcher.launch(toRequest.toTypedArray())
    }

    companion object {
        private const val TAG = "ZHAutomation"

        /** Globally unique, monotonic registry-key suffix across all instances. */
        private val NEXT_KEY = AtomicInteger(0)
    }
}
