package com.msaidizi.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue

/**
 * Typography definitions for Msaidizi app.
 * Large, readable text for non-tech users.
 */
object AppTypography {

    /**
     * Get text size in SP.
     */
    fun headlineLarge(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 28f, context.resources.displayMetrics
        )
    }

    fun headlineMedium(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 24f, context.resources.displayMetrics
        )
    }

    fun headlineSmall(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 20f, context.resources.displayMetrics
        )
    }

    fun bodyLarge(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics
        )
    }

    fun bodyMedium(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics
        )
    }

    fun bodySmall(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics
        )
    }

    fun labelLarge(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics
        )
    }

    fun labelMedium(context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics
        )
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
