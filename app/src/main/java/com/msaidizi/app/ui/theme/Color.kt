package com.msaidizi.app.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color

/**
 * Color definitions for Msaidizi app.
 * Optimized for readability on budget phone screens.
 */
object AppColors {

    // Primary colors — warm, inviting (not corporate)
    val primary = Color.parseColor("#2E7D32")        // Green — growth, money
    val primaryDark = Color.parseColor("#1B5E20")
    val primaryLight = Color.parseColor("#4CAF50")

    // Accent — for CTAs and highlights
    val accent = Color.parseColor("#FF6F00")          // Amber — energy, action
    val accentDark = Color.parseColor("#E65100")

    // Backgrounds
    val background = Color.parseColor("#FAFAFA")
    val surface = Color.parseColor("#FFFFFF")
    val surfaceVariant = Color.parseColor("#F5F5F5")

    // Text
    val textPrimary = Color.parseColor("#212121")
    val textSecondary = Color.parseColor("#757575")
    val textOnPrimary = Color.parseColor("#FFFFFF")

    // Semantic colors
    val profitPositive = Color.parseColor("#2E7D32")  // Green
    val profitNegative = Color.parseColor("#C62828")  // Red
    val warning = Color.parseColor("#F9A825")         // Yellow
    val info = Color.parseColor("#1565C0")            // Blue

    // Recording states
    val recordingActive = Color.parseColor("#D32F2F") // Red — recording
    val recordingIdle = Color.parseColor("#2E7D32")   // Green — ready

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
