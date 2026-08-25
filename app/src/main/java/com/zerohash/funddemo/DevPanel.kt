package com.zerohash.funddemo

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Floating in-app event log for manual SDK verification.
 *
 * The SDK runs its flow in its own Activity, which covers this app entirely, so a
 * log inside [MainActivity] is only readable once the flow closes. This panel is a
 * `TYPE_APPLICATION_OVERLAY` window instead, so it stays on top of the SDK's
 * Activity while a flow is running — the Android counterpart of the iOS mock app's
 * DevPanel.
 *
 * Requires the "display over other apps" permission. [ensure] sends the user to
 * the system settings screen the first time; call it again (e.g. from `onResume`)
 * and the panel attaches once the permission is granted. Everything degrades to a
 * no-op without it — [log] still reaches Logcat via [MainActivity.addLog].
 *
 * Collapsed it is a small pill on the right edge showing the event count; tap it to
 * expand the log.
 */
object DevPanel {

    private const val MAX_ENTRIES = 500

    private val entries = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var pill: TextView? = null
    private var panel: LinearLayout? = null
    private var bodyText: TextView? = null
    private var titleText: TextView? = null
    private var isExpanded = false
    private var hasRequestedPermission = false

    /**
     * Attaches the panel. Safe to call repeatedly.
     *
     * [requestPermission] sends the user to the overlay-permission screen when
     * the permission is missing — pass it only from the dev-mode toggle. Asking
     * from `onResume` would loop: leaving for the settings screen and coming
     * back re-enters `onResume`, which would ask again immediately. It is also
     * asked at most once per process, so declining leaves the app usable.
     */
    fun ensure(activity: Activity, requestPermission: Boolean = false) {
        if (!canDrawOverlays(activity)) {
            if (requestPermission && !hasRequestedPermission) {
                hasRequestedPermission = true
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            return
        }
        if (root != null) return
        attach(activity.applicationContext)
    }

    /** Attaches or tears down to match the host app's dev-mode setting. */
    fun setEnabled(activity: Activity, enabled: Boolean, requestPermission: Boolean = false) {
        if (enabled) ensure(activity, requestPermission) else detach()
    }

    fun log(message: String) {
        synchronized(entries) {
            entries.addFirst("[${stamp.format(Date())}] $message")
            while (entries.size > MAX_ENTRIES) entries.removeLast()
        }
        root?.post { render() }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
        root?.post { render() }
    }

    /** Detach so the overlay does not outlive the app. */
    fun detach() {
        root?.let { windowManager?.removeViewImmediate(it) }
        root = null
        pill = null
        panel = null
        bodyText = null
        titleText = null
        windowManager = null
    }

    private fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    // MARK: - View construction

    private fun attach(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val container = FrameLayout(context)

        container.addView(buildPill(context))
        container.addView(buildPanel(context))

        wm.addView(container, layoutParams(context))
        windowManager = wm
        root = container
        render()
    }

    private fun buildPill(context: Context): View {
        val view = TextView(context).apply {
            text = "0"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 10).toFloat()
                setColor(Color.argb(180, 0, 0, 0))
            }
            setOnClickListener { setExpanded(true) }
        }
        pill = view
        return view
    }

    private fun buildPanel(context: Context): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        }

        titleText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleText)
        header.addView(headerButton(context, "Copy") { copyTranscript(context) })
        header.addView(headerButton(context, "Clear") { clear() })
        header.addView(headerButton(context, "Close") { setExpanded(false) })

        bodyText = TextView(context).apply {
            setTextColor(Color.argb(230, 255, 255, 255))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(context, 12), 0, dp(context, 12), dp(context, 12))
        }

        val scroll = ScrollView(context).apply {
            addView(bodyText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(240, 24, 24, 24))
            addView(header)
            addView(scroll)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(context, 320)
            ).apply { gravity = Gravity.BOTTOM }
        }
        panel = view
        return view
    }

    private fun headerButton(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#4DA3FF"))
            textSize = 13f
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
            setOnClickListener { onClick() }
        }

    // MARK: - State

    private fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        val container = root ?: return
        // The window is resized to just the visible affordance so everything
        // outside it keeps reaching the SDK's Activity untouched.
        windowManager?.updateViewLayout(container, layoutParams(container.context))
        render()
    }

    private fun render() {
        val count = synchronized(entries) { entries.size }
        pill?.apply {
            text = count.toString()
            visibility = if (isExpanded) View.GONE else View.VISIBLE
        }
        panel?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        titleText?.text = "SDK events  $count"
        if (isExpanded) {
            bodyText?.text = synchronized(entries) {
                if (entries.isEmpty()) "No events yet — start a flow." else entries.joinToString("\n")
            }
            bodyText?.movementMethod = ScrollingMovementMethod()
        }
    }

    private fun copyTranscript(context: Context) {
        val transcript = synchronized(entries) { entries.reversed().joinToString("\n") }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SDK events", transcript))
    }

    private fun layoutParams(context: Context): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams().apply {
            this.type = type
            format = android.graphics.PixelFormat.TRANSLUCENT
            // NOT_FOCUSABLE keeps the flow's keyboard and input intact — the panel is
            // tappable but never takes focus from the SDK's WebView.
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (isExpanded) {
                gravity = Gravity.BOTTOM
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = dp(context, 320)
            } else {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
