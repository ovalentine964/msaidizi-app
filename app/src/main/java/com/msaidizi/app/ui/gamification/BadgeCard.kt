package com.msaidizi.app.ui.gamification

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.msaidizi.app.R
import com.msaidizi.app.databinding.ViewBadgeCardBinding
import com.msaidizi.app.gamification.BadgeStatus
import timber.log.Timber

/**
 * Reusable badge card component for the gamification gallery.
 *
 * Displays a badge with:
 * - Emoji icon
 * - Name (Swahili)
 * - Description
 * - Earned/locked state with visual distinction
 * - Earned date (if earned)
 * - Category color coding
 *
 * Anti-shame design: locked badges show as "not yet earned" (never grayed out harshly).
 * Badges can never be lost — once earned, always earned.
 */
class BadgeCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: ViewBadgeCardBinding =
        ViewBadgeCardBinding.inflate(LayoutInflater.from(context), this, true)

    private var badgeStatus: BadgeStatus? = null
    private var isAnimating = false

    init {
        // Card click shows detail (if needed)
        setOnClickListener { badgeStatus?.let { showEarnedAnimation() } }
    }

    /**
     * Bind badge data to the card.
     *
     * @param status The badge status (earned/locked)
     * @param categoryColor Category-specific accent color resource ID
     */
    fun bind(status: BadgeStatus, categoryColor: Int) {
        badgeStatus = status
        val badge = status.badge

        // Emoji icon
        binding.badgeEmoji.text = badge.emoji

        // Name (Swahili — primary language)
        binding.badgeName.text = badge.nameSw

        // Description
        binding.badgeDescription.text = badge.descriptionSw

        // Earned vs locked state
        if (status.earned) {
            applyEarnedState(categoryColor)
        } else {
            applyLockedState()
        }
    }

    /**
     * Apply earned visual state — full color, visible details.
     */
    private fun applyEarnedState(categoryColor: Int) {
        binding.badgeCard.strokeColor = categoryColor
        binding.badgeCard.setCardBackgroundColor(
            blendColors(categoryColor, Color.WHITE, 0.12f)
        )
        binding.badgeEmoji.alpha = 1.0f
        binding.badgeName.alpha = 1.0f
        binding.badgeDescription.alpha = 0.85f
        binding.badgeLockIcon.visibility = View.GONE
        binding.badgeEarnedIndicator.visibility = View.VISIBLE
        binding.badgeEarnedIndicator.setColorFilter(categoryColor)
    }

    /**
     * Apply locked visual state — muted but not harsh.
     * Anti-shame: gentle dimming, encouraging text.
     */
    private fun applyLockedState() {
        binding.badgeCard.strokeColor = ContextCompat.getColor(context, R.color.badge_locked_stroke)
        binding.badgeCard.setCardBackgroundColor(
            ContextCompat.getColor(context, R.color.badge_locked_bg)
        )
        binding.badgeEmoji.alpha = 0.4f
        binding.badgeName.alpha = 0.5f
        binding.badgeDescription.alpha = 0.4f
        binding.badgeLockIcon.visibility = View.VISIBLE
        binding.badgeEarnedIndicator.visibility = View.GONE
    }

    /**
     * Play celebration animation when a badge is earned.
     * Bounce + scale effect using Lottie-compatible property animations.
     */
    fun playEarnAnimation() {
        if (isAnimating) return
        isAnimating = true

        val scaleX = ObjectAnimator.ofFloat(this, View.SCALE_X, 0.8f, 1.15f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(this, View.SCALE_Y, 0.8f, 1.15f, 1.0f)
        val alpha = ObjectAnimator.ofFloat(this, View.ALPHA, 0.5f, 1.0f)

        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 600
            interpolator = OvershootInterpolator(1.5f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isAnimating = false
                }
            })
            start()
        }
    }

    /**
     * Small pulse animation when tapped (earned badges only).
     */
    private fun showEarnedAnimation() {
        if (badgeStatus?.earned != true) return

        val pulse = ObjectAnimator.ofFloat(binding.badgeEmoji, View.SCALE_X, 1.0f, 1.3f, 1.0f)
        val pulseY = ObjectAnimator.ofFloat(binding.badgeEmoji, View.SCALE_Y, 1.0f, 1.3f, 1.0f)

        AnimatorSet().apply {
            playTogether(pulse, pulseY)
            duration = 300
            interpolator = OvershootInterpolator(2.0f)
            start()
        }
    }

    /**
     * Blend two colors by a ratio (0.0 = color1, 1.0 = color2).
     */
    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val r = (Color.red(color1) * inverseRatio + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * inverseRatio + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }
}
