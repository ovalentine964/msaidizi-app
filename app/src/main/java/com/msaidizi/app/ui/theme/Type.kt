package com.msaidizi.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue

/**
 * Typography definitions for Msaidizi app.
 * Large, readable text for non-tech users.
 *
 * ACCESSIBILITY: All minimum sizes increased for elderly/visually impaired users.
 * - Minimum body text: 16sp (was 14sp)
 * - Minimum label: 16sp (was 14sp)
 * - Headlines: 28-34sp for clear hierarchy
 * - Touch targets: 48dp minimum enforced via theme
 */
object AppTypography {

    /** Minimum text size for any interactive or readable element */
    const val MIN_TEXT_SIZE_SP = 16f

    /** Minimum touch target size in dp (WCAG / Material Design guideline) */
    const val MIN_TOUCH_TARGET_DP = 48

    /**
     * Get text size in SP.
     * ACCESSIBILITY: Increased all sizes for elderly users.
     */
    fun headlineLarge(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 34f, context.resources.displayMetrics
        )
    }

    fun headlineMedium(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 28f, context.resources.displayMetrics
        )
    }

    fun headlineSmall(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 22f, context.resources.displayMetrics
        )
    }

    fun bodyLarge(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 20f, context.resources.displayMetrics
        )
    }

    fun bodyMedium(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics
        )
    }

    /**
     * Body small — ACCESSIBILITY: minimum 16sp for elderly readability.
     * Previous 14sp was too small for users over 50.
     */
    fun bodySmall(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics
        )
    }

    fun labelLarge(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics
        )
    }

    /**
     * Label medium — ACCESSIBILITY: minimum 16sp.
     */
    fun labelMedium(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics
        )
    }

    /**
     * Get minimum touch target size in pixels.
     * Returns 48dp as per WCAG 2.5.5 and Material Design guidelines.
     */
    fun minTouchTarget(context: Context): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, MIN_TOUCH_TARGET_DP.toFloat(), context.resources.displayMetrics
        ).toInt()
    }

    /**
     * Get typeface for different weights.
     */
    fun getTypeface(context: Context, weight: Int = Typeface.NORMAL): Typeface {
        return when (weight) {
            Typeface.BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }
}
