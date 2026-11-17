package com.zerohash.sdk.ui

import android.net.Uri
import java.lang.ref.WeakReference

/** The flow that takes the host's `appRedirectUrl` while it is alive. */
internal interface AppRedirectTarget {
    val hostTaskId: Int
    fun handleAppRedirect(data: Uri)
}

// Static because the link reaches a fresh activity, not a session; the newest flow wins.
internal object AppRedirectTargets {
    private var current: WeakReference<AppRedirectTarget>? = null

    val active: AppRedirectTarget?
        get() = current?.get()

    fun register(target: AppRedirectTarget) {
        current = WeakReference(target)
    }

    fun unregister(target: AppRedirectTarget) {
        if (current?.get() === target) current = null
    }
}
