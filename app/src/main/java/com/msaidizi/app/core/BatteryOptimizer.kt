package com.msaidizi.app.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Battery optimization for informal workers who can't always charge.
 *
 * Batches network requests, defers non-critical work when battery is low,
 * and reduces voice processing frequency to extend battery life.
 *
 * ## Producer Theory (ECO 201 §1.2)
 * Battery is a scarce input. We optimize output (app functionality) subject
 * to the constraint: BatteryLife = f(screenOn, network, CPU, models).
 * Reducing any factor extends battery life.
 *
 * ## Statistical Quality Control (STA 346)
 * Battery drain rate monitored as a quality metric:
 *   DrainRate = ΔBattery / ΔTime (mAh/hour)
 *   Target: < 8%/hour active use, < 1%/hour background
 *
 * ## Performance Targets
 * - Active use: < 8% battery/hour
 * - Background: < 1% battery/hour
 * - Voice processing: batch and defer when < 20% battery
 * - Network: batch requests, defer sync when < 15% battery
 */
@Singleton
class BatteryOptimizer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BatteryOptimizer"

        // Battery level thresholds
        const val LEVEL_LOW = 20          // Start reducing non-essential work
        const val LEVEL_CRITICAL = 10     // Only essential work
        const val LEVEL_EMERGENCY = 5     // Minimal operation

        // Work tags for WorkManager
        const val WORK_TAG_BATCHED_NETWORK = "batched_network"
        const val WORK_TAG_DEFERRED_SYNC = "deferred_sync"
        const val WORK_TAG_DEFERRED_LEARNING = "deferred_learning"
        const val WORK_TAG_BATTERY_CHECK = "battery_check"
    }

    /**
     * Battery optimization levels.
     */
    enum class OptimizationLevel {
        /** Full functionality */
        NORMAL,
        /** Reduce non-essential work (background sync, analytics) */
        REDUCED,
        /** Only essential work (user transactions, voice) */
        ESSENTIAL_ONLY,
        /** Minimal: no background work, no model loading */
        MINIMAL
    }

    /**
     * Battery status snapshot.
     */
    data class BatteryStatus(
        val level: Int,           // 0-100
        val isCharging: Boolean,
        val temperature: Float,   // Celsius
        val voltage: Int,         // mV
        val optimizationLevel: OptimizationLevel,
        val estimatedHoursLeft: Double?
    )

    // ────────────────────── State ──────────────────────

    private val _batteryStatus = MutableStateFlow(getCurrentBatteryStatus())
    /** Observable battery status */
    val batteryStatus: StateFlow<BatteryStatus> = _batteryStatus

    private val _optimizationLevel = MutableStateFlow(OptimizationLevel.NORMAL)
    /** Current optimization level */
    val optimizationLevel: StateFlow<OptimizationLevel> = _optimizationLevel

    // Batched request queue
    private val pendingRequests = mutableListOf<PendingRequest>()

    // ────────────────────── Public API ──────────────────────

    /**
     * Update battery status. Call periodically or on battery change broadcast.
     */
    fun updateStatus() {
        val status = getCurrentBatteryStatus()
        _batteryStatus.value = status
        _optimizationLevel.value = status.optimizationLevel

        if (status.optimizationLevel != OptimizationLevel.NORMAL) {
            Timber.i(TAG, "Battery optimization: level=%s, battery=%d%%, charging=%b",
                status.optimizationLevel, status.level, status.isCharging)
        }
    }

    /**
     * Check if a non-critical operation should proceed.
     * Returns false if battery is too low for the operation.
     */
    fun shouldAllowNonCritical(): Boolean {
        val status = _batteryStatus.value
        return status.isCharging || status.optimizationLevel <= OptimizationLevel.REDUCED
    }

    /**
     * Check if model loading should proceed.
     * Model loading is CPU-intensive and drains battery.
     */
    fun shouldAllowModelLoading(): Boolean {
        val status = _batteryStatus.value
        return status.isCharging || status.optimizationLevel <= OptimizationLevel.ESSENTIAL_ONLY
    }

    /**
     * Check if voice processing should proceed at normal frequency.
     * When false, reduce VAD polling or use lighter model.
     */
    fun shouldAllowFullVoiceProcessing(): Boolean {
        val status = _batteryStatus.value
        return status.isCharging || status.level > LEVEL_LOW
    }

    /**
     * Check if background sync should proceed.
     */
    fun shouldAllowBackgroundSync(): Boolean {
        val status = _batteryStatus.value
        return status.isCharging || status.optimizationLevel <= OptimizationLevel.REDUCED
    }

    /**
     * Get recommended voice processing interval (ms).
     * Longer intervals = less CPU = less battery.
     */
    fun getRecommendedVoiceIntervalMs(): Long {
        val level = _optimizationLevel.value
        return when (level) {
            OptimizationLevel.NORMAL -> 30L        // 30ms (normal VAD)
            OptimizationLevel.REDUCED -> 60L       // 60ms (reduced polling)
            OptimizationLevel.ESSENTIAL_ONLY -> 100L // 100ms (minimal polling)
            OptimizationLevel.MINIMAL -> 200L      // 200ms (barely responsive)
        }
    }

    /**
     * Get recommended max LLM inference per hour.
     * Limits CPU-intensive operations.
     */
    fun getMaxInferencesPerHour(): Int {
        val level = _optimizationLevel.value
        return when (level) {
            OptimizationLevel.NORMAL -> 60
            OptimizationLevel.REDUCED -> 30
            OptimizationLevel.ESSENTIAL_ONLY -> 10
            OptimizationLevel.MINIMAL -> 3
        }
    }

    /**
     * Batch a network request for later execution.
     * Executes immediately if battery is fine; queues otherwise.
     */
    fun batchNetworkRequest(request: PendingRequest) {
        if (shouldAllowBackgroundSync()) {
            // Execute now
            request.execute()
        } else {
            // Queue for batch execution when charging
            synchronized(pendingRequests) {
                pendingRequests.add(request)
            }
            Timber.d(TAG, "Batched network request (%d pending)", pendingRequests.size)
            scheduleBatchedWork()
        }
    }

    /**
     * Schedule batched work using WorkManager.
     * Runs when device is charging or has sufficient battery.
     */
    fun scheduleDeferredWork(workTag: String, delayMinutes: Long = 30) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DeferredWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .addTag(workTag)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workTag,
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        Timber.d(TAG, "Scheduled deferred work: %s (delay=%dmin)", workTag, delayMinutes)
    }

    /**
     * Schedule recurring battery-aware sync.
     * Uses WorkManager constraints to run only when charging.
     */
    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val syncWork = PeriodicWorkRequestBuilder<DeferredWorker>(
            6, TimeUnit.HOURS,    // Repeat every 6 hours
            30, TimeUnit.MINUTES  // Flex interval
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG_DEFERRED_SYNC)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "periodic_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncWork
        )

        Timber.i(TAG, "Scheduled periodic sync (every 6h, when charging)")
    }

    /**
     * Flush all pending batched requests.
     * Call when device starts charging.
     */
    fun flushPendingRequests() {
        synchronized(pendingRequests) {
            Timber.i(TAG, "Flushing %d pending requests", pendingRequests.size)
            for (request in pendingRequests) {
                try {
                    request.execute()
                } catch (e: Exception) {
                    Timber.e(e, "Error executing batched request")
                }
            }
            pendingRequests.clear()
        }
    }

    /**
     * Get battery status summary for debugging.
     */
    fun getStatusSummary(): String {
        val status = _batteryStatus.value
        return buildString {
            appendLine("Battery: ${status.level}%")
            appendLine("Charging: ${status.isCharging}")
            appendLine("Temperature: ${status.temperature}°C")
            appendLine("Optimization: ${status.optimizationLevel}")
            appendLine("Voice interval: ${getRecommendedVoiceIntervalMs()}ms")
            appendLine("Max inferences/hr: ${getMaxInferencesPerHour()}")
            appendLine("Pending requests: ${pendingRequests.size}")
            if (status.estimatedHoursLeft != null) {
                appendLine("Est. hours left: ${"%.1f".format(status.estimatedHoursLeft)}")
            }
        }
    }

    // ────────────────────── Private ──────────────────────

    private fun getCurrentBatteryStatus(): BatteryStatus {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val batteryPercent = if (level >= 0 && scale > 0) (level * 100) / scale else 50

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val temperature = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val optimizationLevel = when {
            isCharging -> OptimizationLevel.NORMAL
            batteryPercent <= LEVEL_EMERGENCY -> OptimizationLevel.MINIMAL
            batteryPercent <= LEVEL_CRITICAL -> OptimizationLevel.ESSENTIAL_ONLY
            batteryPercent <= LEVEL_LOW -> OptimizationLevel.REDUCED
            else -> OptimizationLevel.NORMAL
        }

        // Rough estimate: ~3000mAh battery, ~300mA average drain
        val estimatedHours = if (!isCharging && batteryPercent > 0) {
            (batteryPercent / 100.0) * 10.0  // ~10 hours at 100%
        } else null

        return BatteryStatus(
            level = batteryPercent,
            isCharging = isCharging,
            temperature = temperature,
            voltage = voltage,
            optimizationLevel = optimizationLevel,
            estimatedHoursLeft = estimatedHours
        )
    }

    private fun scheduleBatchedWork() {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .build()

        val work = OneTimeWorkRequestBuilder<DeferredWorker>()
            .setConstraints(constraints)
            .addTag(WORK_TAG_BATCHED_NETWORK)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_TAG_BATCHED_NETWORK,
            ExistingWorkPolicy.KEEP,
            work
        )
    }
}

/**
 * Placeholder for a batched network request.
 */
data class PendingRequest(
    val id: String,
    val description: String,
    val execute: () -> Unit
)

/**
 * WorkManager worker for deferred tasks.
 */
class DeferredWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.d("DeferredWorker: executing deferred work")
        // Actual work is dispatched by the component that scheduled it
        return Result.success()
    }
}
