package com.msaidizi.app.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import com.msaidizi.app.R
import com.msaidizi.app.ui.theme.AppTypography

/**
 * Animated voice button with pulsing effect.
 * Shows recording state with visual feedback.
 *
 * ACCESSIBILITY:
 * - Minimum touch target 48dp (WCAG 2.5.5)
 * - Content description changes with state (idle/recording)
 * - Announces state changes to screen readers
 * - High contrast colors for visibility
 */
class VoiceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.primary)
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.recording_active)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var pulseAnimator: ValueAnimator? = null
    private var pulseScale = 1.0f
    private var isRecording = false

    init {
        // ACCESSIBILITY: Minimum touch target 48dp
        val minTouch = AppTypography.minTouchTarget(context)
        minimumWidth = minTouch
        minimumHeight = minTouch

        // ACCESSIBILITY: Initial content description
        contentDescription = "Kitufe cha sauti. Gusa kurekodi sauti."

        // ACCESSIBILITY: Make focusable for screen readers
        isFocusable = true
        isClickable = true

        setOnClickListener {
            // Handled by parent
        }
    }

    /**
     * Start pulsing animation (recording state).
     * Announces state change for accessibility.
     */
    fun startPulsing() {
        isRecording = true
        contentDescription = "Inarekodi sauti. Gusa kuacha kurekodi."

        // ACCESSIBILITY: Announce state change
        announceForAccessibility("Inarekodi sauti sasa")

        pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.3f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                pulseScale = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Stop pulsing animation.
     * Announces state change for accessibility.
     */
    fun stopPulsing() {
        isRecording = false
        contentDescription = "Kitufe cha sauti. Gusa kurekodi sauti."

        // ACCESSIBILITY: Announce state change
        announceForAccessibility("Umekoma kurekodi")

        pulseAnimator?.cancel()
        pulseScale = 1.0f
        invalidate()
    }

    /**
     * Check if currently recording.
     */
    fun isCurrentlyRecording(): Boolean = isRecording

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (minOf(width, height) / 2f) * 0.8f

        // Draw pulsing ring if recording
        if (isRecording) {
            ringPaint.alpha = (255 * (1.5f - pulseScale) / 0.5f).toInt().coerceIn(0, 255)
            canvas.drawCircle(centerX, centerY, radius * pulseScale, ringPaint)
        }

        // Draw main circle
        canvas.drawCircle(centerX, centerY, radius, paint)

        // Draw mic icon (simplified)
        val iconSize = radius * 0.4f
        paint.color = context.getColor(R.color.white)
        canvas.drawRect(
            centerX - iconSize * 0.3f,
            centerY - iconSize,
            centerX + iconSize * 0.3f,
            centerY + iconSize * 0.2f,
            paint
        )
        // Mic stand
        canvas.drawRect(
            centerX - iconSize * 0.5f,
            centerY + iconSize * 0.4f,
            centerX + iconSize * 0.5f,
            centerY + iconSize * 0.6f,
            paint
        )
    }
}
