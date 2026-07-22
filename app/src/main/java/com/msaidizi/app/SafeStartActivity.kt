package com.msaidizi.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.msaidizi.app.core.util.CrashLogger
import timber.log.Timber

/**
 * SafeStartActivity — crash-safe launcher wrapper.
 *
 * This activity is the LAUNCHER entry point. It does nothing except
 * immediately forward to BootstrapActivity (onboarding) or MainActivity.
 *
 * If BootstrapActivity/MainActivity crashes on launch, the uncaught
 * exception handler redirects back here with the error details, and
 * this activity shows a diagnostic screen with:
 *   - Error message and stack trace
 *   - "Try Again" button (re-launches the target activity)
 *   - "Safe Mode" button (launches MainActivity with safe mode flag)
 *
 * This prevents the "instant crash with no UI" scenario on low-memory
 * devices like Samsung A05 (2GB RAM).
 */
class SafeStartActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CRASH_ERROR = "safe_start_crash_error"
        const val EXTRA_CRASH_STACK = "safe_start_crash_stack"
        const val EXTRA_TARGET = "safe_start_target"

        /**
         * Re-launch SafeStartActivity with crash info.
         * Called from the global UncaughtExceptionHandler.
         */
        fun createCrashIntent(context: Context, error: String, stackTrace: String): Intent {
            return Intent(context, SafeStartActivity::class.java).apply {
                putExtra(EXTRA_CRASH_ERROR, error)
                putExtra(EXTRA_CRASH_STACK, stackTrace)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashError = intent.getStringExtra(EXTRA_CRASH_ERROR)
        val crashStack = intent.getStringExtra(EXTRA_CRASH_STACK)

        if (crashError != null) {
            // We got here because the target activity crashed — show diagnostic screen
            showDiagnosticScreen(crashError, crashStack ?: "")
        } else {
            // Normal launch — forward to the real activity
            launchTarget()
        }
    }

    /**
     * Forward to BootstrapActivity or MainActivity.
     * Install a crash interceptor that redirects back here on failure.
     */
    private fun launchTarget() {
        // Install crash interceptor before launching the real activity
        installCrashInterceptor()

        val prefs = getSharedPreferences("worker_profile", MODE_PRIVATE)
        val onboardingComplete = prefs.getBoolean("onboarding_complete", false)

        val targetClass = if (onboardingComplete) {
            MainActivity::class.java
        } else {
            com.msaidizi.app.onboarding.bootstrap.BootstrapActivity::class.java
        }

        try {
            val intent = Intent(this, targetClass)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        } catch (e: Throwable) {
            Timber.e(e, "SafeStartActivity: Failed to launch ${targetClass.simpleName}")
            CrashLogger.install(this)
            showDiagnosticScreen(
                "Failed to launch ${targetClass.simpleName}: ${e.message}",
                e.stackTraceToString()
            )
        }
    }

    /**
     * Install a crash interceptor that redirects back to SafeStartActivity
     * if the target activity crashes.
     */
    private fun installCrashInterceptor() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Write crash log
                CrashLogger.install(this@SafeStartActivity)
            } catch (_: Throwable) {}

            try {
                // Redirect to SafeStartActivity with crash info
                val intent = createCrashIntent(
                    this@SafeStartActivity,
                    throwable.message ?: "Unknown error",
                    throwable.stackTraceToString()
                )
                startActivity(intent)
            } catch (_: Throwable) {
                // If we can't even redirect, fall back to default handler
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * Show a diagnostic screen with the crash error and recovery buttons.
     */
    private fun showDiagnosticScreen(error: String, stackTrace: String) {
        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                resources.displayMetrics
            ).toInt()
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // ── Error icon ──
        val iconText = TextView(this).apply {
            text = "⚠️"
            textSize = 48f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        layout.addView(iconText)

        // ── Title ──
        val title = TextView(this).apply {
            text = "Kuna Hitilafu"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(title)

        // ── Subtitle ──
        val subtitle = TextView(this).apply {
            text = "Programu imehitilafu wakati wa kuanza. Chaguo moja hapa chini."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
            setTextColor(Color.DKGRAY)
        }
        layout.addView(subtitle)

        // ── Error message ──
        val errorText = TextView(this).apply {
            text = error
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            setTextColor(Color.parseColor("#E65100"))
            setTypeface(null, Typeface.MONOSPACE)
        }
        layout.addView(errorText)

        // ── Stack trace (collapsible) ──
        if (stackTrace.isNotBlank()) {
            var stackVisible = false
            val stackToggle = TextView(this).apply {
                text = "▼ Onyesha maelezo ya hitilafu"
                textSize = 12f
                setPadding(0, dp(8), 0, dp(4))
                setTextColor(Color.parseColor("#1976D2"))
                setOnClickListener {
                    stackVisible = !stackVisible
                    stackText.visibility = if (stackVisible) android.view.View.VISIBLE else android.view.View.GONE
                    text = if (stackVisible) "▲ Ficha maelezo" else "▼ Onyesha maelezo ya hitilafu"
                }
            }
            layout.addView(stackToggle)

            val stackText = TextView(this).apply {
                text = stackTrace.take(4000) // Limit to prevent OOM on 2GB devices
                textSize = 10f
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.parseColor("#F5F5F5"))
                setTextColor(Color.DKGRAY)
                setTypeface(null, Typeface.MONOSPACE)
                visibility = android.view.View.GONE
                isVerticalScrollBarEnabled = true
            }
            layout.addView(stackText)
        }

        // ── Device info ──
        val deviceInfo = TextView(this).apply {
            text = "Kifaa: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "RAM: ${getTotalRamMB()}MB"
            textSize = 11f
            setPadding(0, dp(16), 0, dp(24))
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }
        layout.addView(deviceInfo)

        // ── Try Again button ──
        val retryButton = Button(this).apply {
            text = "🔄 Jaribu Tena"
            textSize = 18f
            setPadding(dp(32), dp(16), dp(32), dp(16))
            setOnClickListener {
                // Clear crash state and try again
                intent.removeExtra(EXTRA_CRASH_ERROR)
                intent.removeExtra(EXTRA_CRASH_STACK)
                launchTarget()
            }
        }
        layout.addView(retryButton)

        // ── Safe Mode button ──
        val safeModeButton = Button(this).apply {
            text = "🛡️ Njia Salama (Text Only)"
            textSize = 16f
            setPadding(dp(32), dp(12), dp(32), dp(12))
            setOnClickListener {
                // Launch MsaidiziApp in safe mode, then go to MainActivity
                val app = application as? MsaidiziApp
                app?.enterSafeMode("User chose safe mode from error screen")

                val mainIntent = Intent(this@SafeStartActivity, MainActivity::class.java).apply {
                    putExtra("safe_mode", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(mainIntent)
                finish()
            }
        }
        layout.addView(safeModeButton)

        // ── Crash log note ──
        val logNote = TextView(this).apply {
            text = "Taarifa ya hitilafu imehifadhiwa kwenye kifaa chako.\n" +
                    "Tafadhali tuma kwa msaidizi wa kiufundi."
            textSize = 11f
            setPadding(0, dp(24), 0, 0)
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }
        layout.addView(logNote)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun getTotalRamMB(): Long {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024 * 1024)
        } catch (_: Throwable) {
            -1
        }
    }
}
