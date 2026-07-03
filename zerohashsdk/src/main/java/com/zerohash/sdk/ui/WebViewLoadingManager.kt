package com.zerohash.sdk.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.zerohash.sdk.internal.Constants

/**
 * Loading UI with animated dot sequence.
 *
 * Features:
 * - Three-dot animation with fade and translation effects
 * - Yellow gradient dots (#FCFC99, #F2F07D, #F0D53E)
 * - Loading label and close button
 * - Smooth transition to WebView (300ms with 100ms delay)
 * - Error state with retry button
 * - Theme-aware colors and animations
 */
class WebViewLoadingManager(
    private val context: Context,
    private val isDarkMode: Boolean,
    /** Initial label under the dots. */
    private val loadingText: String = "Loading...",
    /** Whether to show the Close button (hidden when used as a passive cover). */
    private val showCloseButton: Boolean = true
) {
    companion object {
        private const val TAG = "WebViewLoadingManager"
    }

    interface Delegate {
        fun onLoadingClose()
        fun onRetry()
    }

    var delegate: Delegate? = null

    private lateinit var loadingView: LinearLayout
    private lateinit var dotsContainer: LinearLayout
    private lateinit var loadingLabel: TextView
    private lateinit var closeButton: Button
    private lateinit var retryButton: Button
    private val dots = mutableListOf<View>()
    private var animatorSet: AnimatorSet? = null

    init {
        loadingView = createLoadingView()
    }

    /**
     * Get the loading view.
     */
    fun getView(): View = loadingView

    /**
     * Start loading animation.
     */
    fun startAnimation() {
        stopAnimation()

        val animators = dots.mapIndexed { index, dot ->
            createDotAnimation(dot, index)
        }

        animatorSet = AnimatorSet().apply {
            playTogether(animators)
            start()
        }
    }

    /**
     * Stop loading animation.
     */
    fun stopAnimation() {
        animatorSet?.cancel()
        animatorSet = null
    }

    /**
     * Hide loading view with smooth transition.
     */
    fun hide() {
        stopAnimation()

        loadingView.animate()
            .alpha(0f)
            .setDuration(Constants.TRANSITION_DURATION_MS)
            .setStartDelay(Constants.TRANSITION_DELAY_MS)
            .withEndAction {
                loadingView.visibility = View.GONE
            }
            .start()
    }

    /**
     * Show error state with retry button.
     */
    fun showError(message: String) {
        stopAnimation()

        loadingLabel.text = message
        dotsContainer.visibility = View.GONE
        closeButton.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
    }

    /**
     * Create loading view layout.
     */
    private fun createLoadingView(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(if (isDarkMode) Constants.COLOR_DARK_BACKGROUND else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Dots container
        dotsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Create three dots
        val dotColors = listOf(
            Constants.COLOR_DOT_1,
            Constants.COLOR_DOT_2,
            Constants.COLOR_DOT_3
        )

        dotColors.forEach { color ->
            val dot = createDot(color)
            dots.add(dot)
            dotsContainer.addView(dot)
        }

        container.addView(dotsContainer)

        // Loading label
        loadingLabel = TextView(context).apply {
            text = loadingText
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
            textSize = 16f
            gravity = Gravity.CENTER
            val topMargin = dpToPx(Constants.LABEL_SPACING_DP)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, topMargin, 0, 0)
            }
        }

        container.addView(loadingLabel)

        // Close button
        closeButton = Button(context).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
            visibility = if (showCloseButton) View.VISIBLE else View.GONE
            val topMargin = dpToPx(Constants.LABEL_SPACING_DP)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, topMargin, 0, 0)
            }
            setOnClickListener {
                delegate?.onLoadingClose()
            }
        }

        container.addView(closeButton)

        // Retry button (initially hidden)
        retryButton = Button(context).apply {
            text = "Retry"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
            visibility = View.GONE
            val topMargin = dpToPx(Constants.RETRY_BUTTON_SPACING_DP)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, topMargin, 0, 0)
            }
            setOnClickListener {
                delegate?.onRetry()
            }
        }

        container.addView(retryButton)

        return container
    }

    /**
     * Create a single animated dot.
     */
    private fun createDot(color: Int): View {
        return View(context).apply {
            val size = dpToPx(Constants.DOT_SIZE_DP)
            val margin = dpToPx(Constants.DOT_SPACING_DP / 2)

            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, 0, margin, 0)
            }

            // Make it circular
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    /**
     * Create animation for a single dot.
     */
    private fun createDotAnimation(dot: View, index: Int): AnimatorSet {
        val delay = index * Constants.ANIMATION_DELAY_MS

        // Fade animation
        val fadeOut = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f).apply {
            duration = Constants.ANIMATION_DURATION_MS
            startDelay = delay
        }

        val fadeIn = ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1f).apply {
            duration = Constants.ANIMATION_DURATION_MS
            startDelay = delay + Constants.ANIMATION_DURATION_MS
        }

        // Translation animation
        val translateUp = ObjectAnimator.ofFloat(dot, "translationY", 0f, -20f).apply {
            duration = Constants.ANIMATION_DURATION_MS
            startDelay = delay
        }

        val translateDown = ObjectAnimator.ofFloat(dot, "translationY", -20f, 0f).apply {
            duration = Constants.ANIMATION_DURATION_MS
            startDelay = delay + Constants.ANIMATION_DURATION_MS
        }

        return AnimatorSet().apply {
            playSequentially(
                AnimatorSet().apply { playTogether(fadeOut, translateUp) },
                AnimatorSet().apply { playTogether(fadeIn, translateDown) }
            )
            interpolator = AccelerateDecelerateInterpolator()
            // Repeat infinitely
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animation.start()
                }
            })
        }
    }

    /**
     * Convert dp to pixels.
     */
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
