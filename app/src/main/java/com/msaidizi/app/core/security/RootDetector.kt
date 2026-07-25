package com.msaidizi.app.core.security

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root detection utility for Msaidizi app.
 *
 * Checks for common indicators of rooted devices:
 * - su binary in PATH or common locations
 * - Dangerous system properties (ro.debuggable, ro.secure)
 * - Test-keys build (custom ROMs)
 * - Magisk-specific indicators
 * - BusyBox presence
 * - Frida/Xposed framework detection
 *
 * Use checkRootStatus() for a comprehensive assessment.
 * Use isRooted() for a simple boolean check.
 */
@Singleton
class RootDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Comprehensive root check. Returns detailed result.
     */
    fun checkRootStatus(): RootCheckResult {
        val indicators = mutableListOf<String>()
        var riskScore = 0

        // 1. Check for su binary
        if (checkSuBinary()) {
            indicators.add("su binary found in PATH")
            riskScore += 40
        }

        // 2. Check dangerous system properties
        val dangerousProps = checkDangerousProperties()
        if (dangerousProps.isNotEmpty()) {
            indicators.addAll(dangerousProps)
            riskScore += dangerousProps.size * 15
        }

        // 3. Check for test-keys build
        if (checkTestKeys()) {
            indicators.add("Test-keys build detected (custom ROM)")
            riskScore += 20
        }

        // 4. Check for Magisk
        if (checkMagisk()) {
            indicators.add("Magisk indicators detected")
            riskScore += 30
        }

        // 5. Check for BusyBox
        if (checkBusyBox()) {
            indicators.add("BusyBox detected")
            riskScore += 10
        }

        // 6. Check for hooking frameworks
        if (checkFrida()) {
            indicators.add("Frida framework detected")
            riskScore += 35
        }
        if (checkXposed()) {
            indicators.add("Xposed framework detected")
            riskScore += 35
        }

        // 7. Check if app is debuggable in release
        if (checkDebuggableInRelease()) {
            indicators.add("App is debuggable in release build")
            riskScore += 50
        }

        val isRooted = riskScore >= 30
        val riskLevel = when {
            riskScore >= 60 -> RiskLevel.HIGH
            riskScore >= 30 -> RiskLevel.MEDIUM
            riskScore > 0 -> RiskLevel.LOW
            else -> RiskLevel.NONE
        }

        if (isRooted) {
            Timber.w("Root detection: RISK=$riskLevel, score=$riskScore, indicators=${indicators.joinToString()}")
        }

        return RootCheckResult(
            isRooted = isRooted,
            riskLevel = riskLevel,
            riskScore = riskScore,
            indicators = indicators
        )
    }

    /**
     * Simple boolean check.
     */
    fun isRooted(): Boolean = checkRootStatus().isRooted

    // ── Individual Checks ──

    private fun checkSuBinary(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/su", "/su/bin/su", "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkDangerousProperties(): List<String> {
        val issues = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.debuggable"))
            val output = process.inputStream.bufferedReader().readText().trim()
            if (output == "1") issues.add("ro.debuggable=1")
        } catch (_: Exception) { /* getprop not available */ }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "ro.secure"))
            val output = process.inputStream.bufferedReader().readText().trim()
            if (output == "0") issues.add("ro.secure=0")
        } catch (_: Exception) { /* getprop not available */ }

        return issues
    }

    private fun checkTestKeys(): Boolean {
        return Build.TAGS?.contains("test-keys") == true
    }

    private fun checkMagisk(): Boolean {
        val magiskPaths = listOf(
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/magisk.db",
            "/data/adb/modules", "/data/adb/magisk.img"
        )
        if (magiskPaths.any { File(it).exists() }) return true

        // Check for MagiskHide mounted filesystems
        try {
            val mounts = File("/proc/mounts").readText()
            if (mounts.contains("magisk") || mounts.contains("tmpfs /sbin")) return true
        } catch (_: Exception) { /* /proc/mounts not readable */ }

        return false
    }

    private fun checkBusyBox(): Boolean {
        val paths = listOf("/system/bin/busybox", "/system/xbin/busybox", "/sbin/busybox")
        return paths.any { File(it).exists() }
    }

    private fun checkFrida(): Boolean {
        // Check for Frida server port
        try {
            val process = Runtime.getRuntime().exec(arrayOf("ls", "/proc/self/fd"))
            val output = process.inputStream.bufferedReader().readText()
            if (output.contains("frida")) return true
        } catch (_: Exception) { /* not available */ }

        // Check for frida-agent in maps
        try {
            val maps = File("/proc/self/maps").readText()
            if (maps.contains("frida") || maps.contains("gadget")) return true
        } catch (_: Exception) { /* /proc/self/maps not readable */ }

        return false
    }

    private fun checkXposed(): Boolean {
        val xposedPaths = listOf(
            "/system/framework/XposedBridge.jar",
            "/system/lib/libxposed_art.so",
            "/system/lib64/libxposed_art.so",
            "/data/data/de.robv.android.xposed.installer"
        )
        return xposedPaths.any { File(it).exists() }
    }

    private fun checkDebuggableInRelease(): Boolean {
        return try {
            val appFlags = context.applicationInfo.flags
            (appFlags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) {
            false
        }
    }

    // ── Data Classes ──

    data class RootCheckResult(
        val isRooted: Boolean,
        val riskLevel: RiskLevel,
        val riskScore: Int,
        val indicators: List<String>
    )

    enum class RiskLevel { NONE, LOW, MEDIUM, HIGH }
}
