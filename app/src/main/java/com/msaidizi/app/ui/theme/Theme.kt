package com.msaidizi.app.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Window
import android.view.WindowManager

/**
 * Theme configuration for Msaidizi app.
 * Sets up Material Design theme with custom colors.
 */
object AppTheme {

    /**
     * Apply theme to activity.
     */
    fun apply(activity: Activity) {
        // Set status bar color
        val window = activity.window
        window.statusBarColor = AppColors.primaryDark

        // Set navigation bar color
        window.navigationBarColor = AppColors.surface

        // Set light status bar for dark text on light background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = 0 // Clear light status bar
        }
    }

    /**
     * Get theme resource ID.
     */
    fun getThemeResId(): Int {
        return com.msaidizi.app.R.style.Theme_Msaidizi
    }

    /**
     * Apply immersive mode for voice recording.
     */
    fun applyImmersiveMode(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars())
                controller.hide(android.view.WindowInsets.Type.navigationBars())
            }
        }
    }

    /**
     * Exit immersive mode.
     */
    fun exitImmersiveMode(window: Window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.show(android.view.WindowInsets.Type.statusBars())
                controller.show(android.view.WindowInsets.Type.navigationBars())
            }
        }
    }
}
