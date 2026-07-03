package com.zerohash.sdk.automation

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen, opaque, brand-loading view that covers the automation WebView so
 * the user never sees the underlying Coinbase page while automation runs.
 *
 * Android port of iOS `LoadingOverlayView` (itself the native counterpart of the
 * browser extension's injected overlay): a white full-bleed background, a centered
 * three-dot loader in the brand palette, a cycling title + subtitle, and a
 * "Powered by <brand>" footer. Titles/subtitles cycle in parallel every
 * [OverlayOptions.cycleMs] when more than one message is supplied; only the line
 * whose text actually changed fades.
 */
internal class LoadingOverlayView(
    context: Context,
    private val options: OverlayOptions,
) {
    private val root: FrameLayout
    private val dots = mutableListOf<View>()
    private val titleLabel: TextView
    private val subtitleLabel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var animatorSet: AnimatorSet? = null
    private var cycleIndex = 0
    /** Longer of the two arrays; <= 1 means static (no cycling). */
    private val slotCount = maxOf(options.titles.size, options.subtitles.size)

    init {
        root = FrameLayout(context).apply {
            // Deliberately always-light (white bg, dark text, fixed-fill dark
            // wordmarks) regardless of system dark mode — matches the iOS
            // LoadingOverlayView and the web injected overlay, which are a fixed
            // light "Powered by" cover. Not theme-aware on purpose.
            setBackgroundColor(Color.WHITE)
            // Opaque + swallow touches so the user can't poke the page underneath.
            isClickable = true
            isFocusable = true
        }

        // Stage (dots + text) fills the space above the footer and centers content.
        val stage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val dotsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(options.brand.left, options.brand.middle, options.brand.right).forEach { c ->
            val dot = View(context).apply {
                val size = dp(context, 15)
                val margin = dp(context, 3)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, 0, margin, 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                }
            }
            dots.add(dot)
            dotsContainer.addView(dot)
        }
        stage.addView(dotsContainer)

        titleLabel = TextView(context).apply {
            text = options.titles[0]
            setTextColor(Color.parseColor("#111827"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(context, 24), dp(context, 56), dp(context, 24), 0) }
        }
        stage.addView(titleLabel)

        subtitleLabel = TextView(context).apply {
            text = options.subtitles[0]
            setTextColor(Color.parseColor("#4B5563"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(context, 24), dp(context, 6), dp(context, 24), 0) }
        }
        stage.addView(subtitleLabel)

        root.addView(stage, matchParent())

        // Footer: hairline top border + centered "Powered by" + brand mark.
        val footer = buildFooter(context)
        root.addView(
            footer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
    }

    private fun buildFooter(context: Context): View {
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val border = View(context).apply {
            setBackgroundColor(Color.parseColor("#E5E7EB"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1),
            )
        }
        footer.addView(border)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val v = dp(context, 22)
            setPadding(0, v, 0, v)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(TextView(context).apply {
            text = "Powered by"
            setTextColor(Color.parseColor("#111827"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        row.addView(ImageView(context).apply {
            setImageResource(options.brand.markRes)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 14),
            ).apply { marginStart = dp(context, 6) }
        })
        footer.addView(row)
        return footer
    }

    fun getView(): View = root

    /** Begin the dot animation and (if multiple messages) the copy cycle. */
    fun start() {
        startDotAnimation()
        startCycleIfNeeded()
    }

    fun stop() {
        animatorSet?.cancel()
        animatorSet = null
        handler.removeCallbacksAndMessages(null)
    }

    // ── Three-dot animation (staggered fade + lift, infinite) ────────────────

    private fun startDotAnimation() {
        animatorSet?.cancel()
        val animators = dots.mapIndexed { index, dot -> dotAnimation(dot, index) }
        animatorSet = AnimatorSet().apply {
            playTogether(animators)
            start()
        }
    }

    private fun dotAnimation(dot: View, index: Int): AnimatorSet {
        val delay = index * 150L
        val fadeOut = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f).apply {
            duration = 300; startDelay = delay
        }
        val liftUp = ObjectAnimator.ofFloat(dot, "translationY", 0f, -20f).apply {
            duration = 300; startDelay = delay
        }
        val fadeIn = ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1f).apply {
            duration = 300; startDelay = delay + 300
        }
        val liftDown = ObjectAnimator.ofFloat(dot, "translationY", -20f, 0f).apply {
            duration = 300; startDelay = delay + 300
        }
        return AnimatorSet().apply {
            playSequentially(
                AnimatorSet().apply { playTogether(fadeOut, liftUp) },
                AnimatorSet().apply { playTogether(fadeIn, liftDown) },
            )
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animation.start()
                }
            })
        }
    }

    // ── Message cycling (mirrors iOS startCycle/fadeSwap) ────────────────────

    private fun startCycleIfNeeded() {
        if (slotCount <= 1) return
        handler.postDelayed(object : Runnable {
            override fun run() {
                advanceCycle()
                handler.postDelayed(this, options.cycleMs)
            }
        }, options.cycleMs)
    }

    private fun advanceCycle() {
        cycleIndex = (cycleIndex + 1) % slotCount
        // Index each array independently with modulo so a single-element array
        // stays static while the other cycles.
        val nextTitle = options.titles[cycleIndex % options.titles.size]
        val nextSubtitle = options.subtitles[cycleIndex % options.subtitles.size]
        fadeSwap(titleLabel, nextTitle)
        fadeSwap(subtitleLabel, nextSubtitle)
    }

    /** Fade out → swap text → fade in, only if the text actually changed. */
    private fun fadeSwap(label: TextView, next: String) {
        if (label.text == next) return
        label.animate().alpha(0f).setDuration(250).withEndAction {
            label.text = next
            label.animate().alpha(1f).setDuration(250).start()
        }.start()
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics,
        ).toInt()
}
