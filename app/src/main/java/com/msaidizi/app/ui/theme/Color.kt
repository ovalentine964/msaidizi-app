package com.msaidizi.app.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color

/**
 * Color definitions for Msaidizi app.
 * Optimized for readability on budget phone screens.
 */
object AppColors {

    // Primary colors — Deep Navy brand palette (matches colors.xml)
    val primary = Color.parseColor("#1B2A4A")        // Deep Navy — professional, trustworthy
    val primaryDark = Color.parseColor("#0D1929")    // Midnight
    val primaryLight = Color.parseColor("#2A4070")   // Light Navy

    // Accent — African Orange for CTAs and highlights (matches colors.xml)
    val accent = Color.parseColor("#E8853D")          // African Orange — energy, warmth
    val accentDark = Color.parseColor("#D06B2A")      // Dark Orange

    // Backgrounds
    val background = Color.parseColor("#FAFAFA")
    val surface = Color.parseColor("#FFFFFF")
    val surfaceVariant = Color.parseColor("#F5F5F5")

    // Text
    val textPrimary = Color.parseColor("#212121")
    val textSecondary = Color.parseColor("#757575")
    val textOnPrimary = Color.parseColor("#FFFFFF")

    // Semantic colors
    val profitPositive = Color.parseColor("#4CAF50")  // Green — positive values
    val profitNegative = Color.parseColor("#F44336")  // Red — negative values
    val warning = Color.parseColor("#FF9800")         // Orange — warnings
    val info = Color.parseColor("#2196F3")            // Blue — info

    // Recording states
    val recordingActive = Color.parseColor("#F44336") // Red — recording
    val recordingIdle = Color.parseColor("#1B2A4A")   // Navy — ready (matches primary)

    // Card shadows and borders
    val cardBorder = Color.parseColor("#E0E0E0")

    /**
     * Get color state list for button backgrounds.
     */
    fun primaryButtonColors(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf()
            ),
            intArrayOf(
                primaryDark,
                primary,
                Color.parseColor("#9E9E9E")
            )
        )
    }

    /**
     * Get color state list for text buttons.
     */
    fun textButtonColors(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf()
            ),
            intArrayOf(
                accentDark,
                accent,
                Color.parseColor("#9E9E9E")
            )
        )
    }
}
