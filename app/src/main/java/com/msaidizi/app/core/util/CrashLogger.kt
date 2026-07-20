package com.msaidizi.app.core.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashLogger — writes uncaught exceptions to a file on the device.
 * 
 * When the app crashes, the stack trace is saved to:
 *   /sdcard/Android/data/com.msaidizi.app/files/crash_logs/crash_YYYY-MM-DD_HH-mm-ss.txt
 * 
 * Also accessible via:
 *   /storage/emulated/0/Android/data/com.msaidizi.app/files/crash_logs/
 * 
 * This is critical for diagnosing crashes on devices without adb access.
 */
object CrashLogger {

    private const val CRASH_DIR = "crash_logs"
    private var isInstalled = false

    /**
     * Install the global crash logger. Call this FIRST in Application.onCreate().
     */
    fun install(context: Context) {
        if (isInstalled) return
        isInstalled = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(context, thread, throwable)
            } catch (_: Throwable) {
                // If we can't write the log, fall through to default handler
            }
            
            // Call the original handler (shows the system crash dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val crashDir = File(context.getExternalFilesDir(null), CRASH_DIR)
        if (!crashDir.exists()) crashDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val crashFile = File(crashDir, "crash_$timestamp.txt")

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        
        pw.println("=== MSAIDIZI CRASH LOG ===")
        pw.println("Timestamp: ${Date()}")
        pw.println("Thread: ${thread.name}")
        pw.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("App: com.msaidizi.app v0.1.0")
        pw.println("")
        pw.println("=== STACK TRACE ===")
        throwable.printStackTrace(pw)
        
        // Also log the cause chain
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 5) {
            pw.println("")
            pw.println("=== CAUSE (depth ${++depth}) ===")
            cause.printStackTrace(pw)
            cause = cause.cause
        }

        crashFile.writeText(sw.toString())
    }
}
