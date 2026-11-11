package com.zerohash.sdk.internal

import android.util.Log
import com.zerohash.sdk.ZerohashError
import com.zerohash.sdk.ZerohashSession
import com.zerohash.sdk.ui.WebViewActivity

/**
 * Shared body of every session's `updateJwt`. False if [session] is not active or [newJwt]
 * is invalid (reported through [onError]); true means the token was accepted.
 */
internal fun updateSessionJwt(
    session: ZerohashSession?,
    newJwt: String,
    tag: String,
    onError: (ZerohashError) -> Unit
): Boolean {
    val active = session?.takeIf { it.isActive() } ?: return false

    val validationResult = JwtValidator.validate(newJwt)
    if (validationResult.isFailure) {
        val msg = validationResult.exceptionOrNull()?.message ?: "JWT validation failed"
        Log.e(tag, "JWT validation failed: $msg")
        onError(ZerohashError.ConfigurationError(msg))
        return false
    }

    return WebViewActivity.updateJwt(active.id, newJwt)
}
