package com.msaidizi.app.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BatteryOptimizer — Battery monitoring and adaptive inference for LLM use.
 *
 * Addresses GAP: Battery profiling needed for extended LLM use.
 *
 * Monitors battery level and adjusts LLM behavior:
 * - Reduces context window on low battery
 * - Reduces max tokens on low battery
 * - Warns user on extended use with low battery
 * - Tracks battery drain per inference session
 *
 * Battery levels:
 * - >50%: Full performance (ctx=2048, max_tokens=256)
 * - 30-50%: Reduced (ctx=1024, max_tokens=128)
 * - 15-30%: Minimal (ctx=512, max_tokens=64)
 * - <15%: Emergency (ctx=256, max_tokens=32, warn user)
 */
@Singleton
class BatteryOptimizer @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "battery_optimizer"

        /** Battery level thresholds (percent). */
        private const val LEVEL_FULL = 50
        private const val LEVEL_REDUCED = 30
        private const val LEVEL_MINIMAL = 15
        private const val LEVEL_EMERGENCY = 5

        /** Drain rate warning threshold (percent per hour). */
        private const val DRAIN_RATE_WARNING = 15.0

        /** Minimum interval between battery warnings (ms). */
        private const val WARNING_COOLDOWN_MS = 5 * 60 * 1000L  // 5 minutes
    }

    /**
     * Performance profile based on battery level.
     */
    data class PerformanceProfile(
        val level: BatteryLevel,
        val contextSize: Int,
        val maxTokens: Int,
        val temperature: Float,
        val threads: Int,
        val shouldWarnUser: Boolean,
        val description: String
    )

    /**
     * Battery level categories.
     */
    enum class BatteryLevel {
        FULL,       // >50%
        REDUCED,    // 30-50%
        MINIMAL,    // 15-30%
        EMERGENCY,  // <15%
        CHARGING,   // Plugged in — unlimited
        UNKNOWN
    }

    /**
     * Battery state snapshot.
     */
    data class BatteryState(
        val level: Int,              // 0-100
        val isCharging: Boolean,
        val chargingSource: String,  // USB, AC, Wireless
        val temperature: Float,      // Celsius
        val voltage: Float,          // Volts
        val health: String,
        val estimatedMinutesRemaining: Int?,
        val drainRatePerHour: Double  // percent/hour
    )

    // ── State ────────────────────────────────────────────────

    @Volatile
    private var currentBatteryState: BatteryState = BatteryState(
        level = 100,
        isCharging = false,
        chargingSource = "Unknown",
        temperature = 25.0f,
        voltage = 4.2f,
        health = "Unknown",
        estimatedMinutesRemaining = null,
        drainRatePerHour = 0.0
    )

    private var batteryReceiver: BroadcastReceiver? = null
    private var lastWarningTime = 0L
    private var sessionStartLevel = -1
    private var sessionStartTime = 0L
    private var inferenceCount = 0

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Public API ───────────────────────────────────────────

    /**
     * Start monitoring battery state.
     */
    fun startMonitoring() {
        if (batteryReceiver != null) return

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                updateBatteryState(intent)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        context.registerReceiver(batteryReceiver, filter)
        Timber.i("Battery monitoring started")
    }

    /**
     * Stop monitoring battery state.
     */
    fun stopMonitoring() {
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Timber.w(e, "Error unregistering battery receiver")
            }
        }
        batteryReceiver = null
        Timber.i("Battery monitoring stopped")
    }

    /**
     * Get the current battery state.
     */
    fun getBatteryState(): BatteryState = currentBatteryState

    /**
     * Get the current battery level as a category.
     */
    fun getBatteryLevel(): BatteryLevel {
        if (currentBatteryState.isCharging) return BatteryLevel.CHARGING
        return when {
            currentBatteryState.level > LEVEL_FULL -> BatteryLevel.FULL
            currentBatteryState.level > LEVEL_REDUCED -> BatteryLevel.REDUCED
            currentBatteryState.level > LEVEL_MINIMAL -> BatteryLevel.MINIMAL
            currentBatteryState.level > LEVEL_EMERGENCY -> BatteryLevel.EMERGENCY
            else -> BatteryLevel.EMERGENCY
        }
    }

    /**
     * Get the recommended performance profile for the current battery state.
     */
    fun getPerformanceProfile(): PerformanceProfile {
        val level = getBatteryLevel()
        return when (level) {
            BatteryLevel.CHARGING -> PerformanceProfile(
                level = level,
                contextSize = 4096,
                maxTokens = 512,
                temperature = 0.7f,
                threads = 4,
                shouldWarnUser = false,
                description = "Charging — maximum performance"
            )
            BatteryLevel.FULL -> PerformanceProfile(
                level = level,
                contextSize = 2048,
                maxTokens = 256,
                temperature = 0.7f,
                threads = 3,
                shouldWarnUser = false,
                description = "Full performance"
            )
            BatteryLevel.REDUCED -> PerformanceProfile(
                level = level,
                contextSize = 1024,
                maxTokens = 128,
                temperature = 0.5f,
                threads = 2,
                shouldWarnUser = false,
                description = "Reduced performance to save battery"
            )
            BatteryLevel.MINIMAL -> PerformanceProfile(
                level = level,
                contextSize = 512,
                maxTokens = 64,
                temperature = 0.3f,
                threads = 2,
                shouldWarnUser = true,
                description = "Minimal performance — battery low"
            )
            BatteryLevel.EMERGENCY -> PerformanceProfile(
                level = level,
                contextSize = 256,
                maxTokens = 32,
                temperature = 0.1f,
                threads = 1,
                shouldWarnUser = true,
                description = "Emergency mode — please charge soon"
            )
            BatteryLevel.UNKNOWN -> PerformanceProfile(
                level = level,
                contextSize = 1024,
                maxTokens = 128,
                temperature = 0.5f,
                threads = 2,
                shouldWarnUser = false,
                description = "Battery status unknown — using conservative settings"
            )
        }
    }

    /**
     * Record the start of an inference session.
     */
    fun startInferenceSession() {
        if (sessionStartLevel < 0) {
            sessionStartLevel = currentBatteryState.level
            sessionStartTime = System.currentTimeMillis()
        }
        inferenceCount++
    }

    /**
     * Record the end of an inference session and return session stats.
     */
    fun endInferenceSession(): InferenceSessionStats {
        val durationMs = System.currentTimeMillis() - sessionStartTime
        val levelDrop = sessionStartLevel - currentBatteryState.level

        val stats = InferenceSessionStats(
            durationMs = durationMs,
            inferenceCount = inferenceCount,
            batteryDropPercent = levelDrop,
            startLevel = sessionStartLevel,
            endLevel = currentBatteryState.level,
            drainRatePerHour = if (durationMs > 0) {
                levelDrop * 3600000.0 / durationMs
            } else 0.0
        )

        // Reset session tracking
        sessionStartLevel = -1
        inferenceCount = 0

        // Save stats for historical tracking
        saveSessionStats(stats)

        return stats
    }

    /**
     * Check if the user should be warned about battery usage.
     */
    fun shouldWarnUser(): BatteryWarning? {
        val state = currentBatteryState
        val profile = getPerformanceProfile()

        // Low battery warning
        if (profile.shouldWarnUser) {
            val now = System.currentTimeMillis()
            if (now - lastWarningTime > WARNING_COOLDOWN_MS) {
                lastWarningTime = now
                return BatteryWarning(
                    type = WarningType.LOW_BATTERY,
                    message = "Battery at ${state.level}%. LLM performance reduced to save power.",
                    level = state.level,
                    actionRequired = state.level < LEVEL_EMERGENCY
                )
            }
        }

        // High drain rate warning
        if (state.drainRatePerHour > DRAIN_RATE_WARNING && !state.isCharging) {
            val now = System.currentTimeMillis()
            if (now - lastWarningTime > WARNING_COOLDOWN_MS) {
                lastWarningTime = now
                return BatteryWarning(
                    type = WarningType.HIGH_DRAIN,
                    message = "High battery drain (${state.drainRatePerHour.toInt()}%/hr). Consider reducing usage.",
                    level = state.level,
                    actionRequired = false
                )
            }
        }

        // Temperature warning
        if (state.temperature > 40.0f) {
            return BatteryWarning(
                type = WarningType.HIGH_TEMPERATURE,
                message = "Device temperature high (${state.temperature}°C). Inference paused to cool down.",
                level = state.level,
                actionRequired = true
            )
        }

        return null
    }

    /**
     * Check if inference should be throttled due to temperature.
     */
    fun shouldThrottleInference(): Boolean {
        return currentBatteryState.temperature > 38.0f
    }

    /**
     * Get estimated remaining inference capacity.
     */
    fun getEstimatedRemainingInferences(): Int? {
        if (currentBatteryState.isCharging) return null // Unlimited
        val drainPerInference = getEstimatedDrainPerInference() ?: return null
        return (currentBatteryState.level / drainPerInference).toInt()
    }

    /**
     * Get the estimated battery drain per inference (in percent).
     */
    private fun getEstimatedDrainPerInference(): Double? {
        val stats = prefs.getFloat("avg_drain_per_inference", -1f)
        return if (stats > 0) stats.toDouble() else null
    }

    /**
     * Get historical session stats.
     */
    fun getSessionHistory(): List<InferenceSessionStats> {
        // In production, this would read from a database
        return emptyList()
    }

    /**
     * Get diagnostics.
     */
    fun getDiagnostics(): String = buildString {
        val state = currentBatteryState
        val profile = getPerformanceProfile()
        appendLine("Battery Optimizer:")
        appendLine("  Level: ${state.level}% ${if (state.isCharging) "(charging)" else ""}")
        appendLine("  Temperature: ${state.temperature}°C")
        appendLine("  Drain rate: ${"%.1f".format(state.drainRatePerHour)}%/hr")
        appendLine("  Profile: ${profile.description}")
        appendLine("  Context: ${profile.contextSize}, Tokens: ${profile.maxTokens}")
        appendLine("  Threads: ${profile.threads}")
        appendLine("  Session inferences: $inferenceCount")
        appendLine("  Est. remaining: ${getEstimatedRemainingInferences() ?: "unknown"}")
    }

    // ── Internal ─────────────────────────────────────────────

    /**
     * Update battery state from a broadcast intent.
     */
    private fun updateBatteryState(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else level

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val chargingSource = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "None"
        }

        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0f

        val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            else -> "Unknown"
        }

        // Calculate drain rate
        val oldLevel = currentBatteryState.level
        val drainRate = if (!isCharging && oldLevel > batteryPct && oldLevel > 0) {
            // Simple estimate: assume readings are ~1 minute apart
            ((oldLevel - batteryPct) * 60.0).coerceIn(0.0, 100.0)
        } else {
            currentBatteryState.drainRatePerHour
        }

        currentBatteryState = BatteryState(
            level = batteryPct,
            isCharging = isCharging,
            chargingSource = chargingSource,
            temperature = temperature,
            voltage = voltage,
            health = health,
            estimatedMinutesRemaining = estimateRemainingMinutes(batteryPct, drainRate),
            drainRatePerHour = drainRate
        )
    }

    /**
     * Estimate remaining battery minutes.
     */
    private fun estimateRemainingMinutes(level: Int, drainRate: Double): Int? {
        if (drainRate <= 0) return null
        return (level / drainRate * 60).toInt()
    }

    /**
     * Save session stats for historical tracking.
     */
    private fun saveSessionStats(stats: InferenceSessionStats) {
        // Update average drain per inference
        val avgDrain = prefs.getFloat("avg_drain_per_inference", 0f)
        val count = prefs.getInt("session_count", 0)

        val newAvg = if (count > 0 && stats.inferenceCount > 0) {
            val drainPerInference = stats.batteryDropPercent.toFloat() / stats.inferenceCount
            (avgDrain * count + drainPerInference) / (count + 1)
        } else {
            avgDrain
        }

        prefs.edit()
            .putFloat("avg_drain_per_inference", newAvg)
            .putInt("session_count", count + 1)
            .putLong("last_session_duration", stats.durationMs)
            .apply()
    }
}

/**
 * Battery warning for the user.
 */
data class BatteryWarning(
    val type: WarningType,
    val message: String,
    val level: Int,
    val actionRequired: Boolean
)

enum class WarningType {
    LOW_BATTERY,
    HIGH_DRAIN,
    HIGH_TEMPERATURE
}

/**
 * Stats from an inference session.
 */
data class InferenceSessionStats(
    val durationMs: Long,
    val inferenceCount: Int,
    val batteryDropPercent: Int,
    val startLevel: Int,
    val endLevel: Int,
    val drainRatePerHour: Double
) {
    fun toSummaryString(): String = buildString {
        appendLine("Session Stats:")
        appendLine("  Duration: ${durationMs / 1000}s")
        appendLine("  Inferences: $inferenceCount")
        appendLine("  Battery: $startLevel% → $endLevel% (-$batteryDropPercent%)")
        appendLine("  Drain rate: ${"%.1f".format(drainRatePerHour)}%/hr")
    }
}
