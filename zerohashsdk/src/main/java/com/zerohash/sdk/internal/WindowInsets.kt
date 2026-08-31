package com.zerohash.sdk.internal

import android.util.Log
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zerohash.sdk.BuildConfig

/**
 * Pads [this] so its content stays clear of the system bars and the keyboard.
 *
 * A host app targeting SDK 35+ gets edge-to-edge enforced, so the window is
 * neither inset for the system bars nor resized for the IME. Unhandled, that
 * puts a page's top-row controls underneath the status bar and hides its footer
 * behind the keyboard.
 *
 * The union of [WindowInsetsCompat.Type.systemBars] and
 * [WindowInsetsCompat.Type.ime] takes the larger value per edge, so the bottom
 * pad follows the keyboard while it is open and falls back to the navigation bar
 * once it closes. Shrinking the view reflows the page, which is the behaviour
 * hosts on SDK 34 and below got for free.
 *
 * Padding by the insets the view actually receives keeps this correct without
 * assuming anything about the host's theme. A host whose ActionBar has already
 * consumed the top inset dispatches 0 here, so there is no double gap.
 */
internal fun View.padForSystemBarsAndKeyboard(tag: String) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val content = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
        )
        view.setPadding(content.left, content.top, content.right, content.bottom)
        if (BuildConfig.DEBUG) {
            Log.d(tag, "Insets applied: top=${content.top} bottom=${content.bottom}")
        }
        WindowInsetsCompat.CONSUMED
    }
}
