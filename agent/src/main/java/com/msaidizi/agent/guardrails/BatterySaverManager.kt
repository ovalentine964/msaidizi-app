package com.msaidizi.agent.guardrails

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BatterySaverManager — Monitors battery level and adjusts app behavior.
 *
 * When battery is critically low (<15%), the app enters battery saver mode:
 * - Disables on-device LLM inference (uses template responses)
 * - Reduces voice pipeline to essential-only (STT, no TTS)
 * - Defers all background sync
 * - Disables proactive agent suggestions
 * - Reduces UI animations
 *
 * This ensures the app remains useful for transaction recording
 * even when the phone is about to die.
 */
@Singleton
class BatterySaverManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _batteryState = MutableStateFlow(BatteryState())
    val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()

    private var lastCheckTime: Long = 0L

    /**
     * Check current battery status and update state.
     * Call this periodically (e.g., every 5 minutes) or before heavy operations.
     */
    fun checkBattery(): BatteryState {
        val now = System.currentTimeMillis()
        // Throttle checks to once per minute
        if (now - lastCheckTime < 60_000L) return _batteryState.value
        lastCheckTime = now

        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val isCharging = intent?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } ?: false

        val previousState = _batteryState.value
        val saverMode = when {
            isCharging -> SaverMode.OFF
            percent < 0 -> SaverMode.OFF // Unknown battery, don't restrict
            percent <= BATTERY_CRITICAL -> SaverMode.FULL
            percent <= BATTERY_LOW -> SaverMode.LITE
            else -> SaverMode.OFF
        }

        val state = BatteryState(
            percent = percent,
            isCharging = isCharging,
            saverMode = saverMode
        )

        _batteryState.value = state

        if (saverMode != previousState.saverMode) {
            when (saverMode) {
                SaverMode.FULL -> Timber.w(
                    "🔋 Battery critical ($percent%) — entering FULL battery saver mode"
                )
                SaverMode.LITE -> Timber.i(
                    "🔋 Battery low ($percent%) — entering LITE battery saver mode"
                )
                SaverMode.OFF -> Timber.i(
                    "🔋 Battery OK ($percent%) — battery saver off"
                )
            }
        }

        return state
    }

    /**
     * Whether LLM inference should be skipped (use template responses instead).
     */
    fun shouldSkipLlm(): Boolean {
        return _batteryState.value.saverMode == SaverMode.FULL
    }

    /**
     * Whether TTS should be disabled (text-only output).
     */
    fun shouldSkipTts(): Boolean {
        return _batteryState.value.saverMode != SaverMode.OFF
    }

    /**
     * Whether background operations should be deferred.
     */
    fun shouldDeferBackground(): Boolean {
        return _batteryState.value.saverMode != SaverMode.OFF
    }

    /**
     * Whether proactive suggestions should be suppressed.
     */
    fun shouldSuppressProactive(): Boolean {
        return _batteryState.value.saverMode != SaverMode.OFF
    }

    /**
     * Get max tokens for LLM generation based on battery state.
     * Reduces generation length in battery saver mode.
     */
    fun getMaxTokens(default: Int): Int {
        return when (_batteryState.value.saverMode) {
            SaverMode.OFF -> default
            SaverMode.LITE -> minOf(default, 128)
            SaverMode.FULL -> 0 // Skip LLM entirely
        }
    }

    companion object {
        private const val BATTERY_LOW = 25
        private const val BATTERY_CRITICAL = 15
    }
}

/**
 * Battery saver mode levels.
 */
enum class SaverMode {
    /** Normal operation — no restrictions. */
    OFF,
    /** Low battery — disable TTS, reduce LLM tokens, defer background. */
    LITE,
    /** Critical battery — disable LLM entirely, template responses only. */
    FULL
}

/**
 * Current battery state.
 */
data class BatteryState(
    val percent: Int = -1,
    val isCharging: Boolean = false,
    val saverMode: SaverMode = SaverMode.OFF
)
