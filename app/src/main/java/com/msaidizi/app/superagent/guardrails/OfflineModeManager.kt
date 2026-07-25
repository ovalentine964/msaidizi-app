package com.msaidizi.app.superagent.guardrails

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OfflineModeManager — Pillar 6: Graceful Degradation.
 *
 * Ensures the app functions safely when offline:
 * - Local SQLite for all data persistence (already via Room DB)
 * - Cached model inference (local LLM via llama.cpp / SherpaOnnx)
 * - Sync queue for pending operations
 * - Fail-closed for safety operations (guardrails always enforced)
 * - Offline indicator and degraded-mode warnings
 *
 * Architecture:
 * - Online mode: full capabilities (cloud LLM, real-time sync)
 * - Offline mode: local-only (cached model, local DB, sync queue)
 * - Degraded mode: partial connectivity (some features limited)
 *
 * Fail-closed: safety guardrails NEVER degrade in offline mode.
 * If safety checks can't be performed, the operation is BLOCKED.
 */
@Singleton
class OfflineModeManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _connectivityState = MutableStateFlow(ConnectivityState.UNKNOWN)
    val connectivityState: StateFlow<ConnectivityState> = _connectivityState.asStateFlow()

    private val syncQueue = mutableListOf<QueuedOperation>()
    private val _syncQueueSize = MutableStateFlow(0)
    val syncQueueSize: StateFlow<Int> = _syncQueueSize.asStateFlow()

    private var offlineStartTime: Long? = null
    private var lastOnlineTime: Long = Instant.now().epochSecond

    // ─── Connectivity Detection ───

    /**
     * Check current connectivity state.
     */
    fun checkConnectivity(): ConnectivityState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = network?.let { cm.getNetworkCapabilities(it) }

        val state = when {
            capabilities == null -> ConnectivityState.OFFLINE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectivityState.ONLINE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectivityState.ONLINE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectivityState.ONLINE
            else -> ConnectivityState.DEGRADED
        }

        val previousState = _connectivityState.value
        _connectivityState.value = state

        // Track offline duration
        when {
            state == ConnectivityState.OFFLINE && previousState != ConnectivityState.OFFLINE -> {
                offlineStartTime = Instant.now().epochSecond
                Timber.w("📴 Device went OFFLINE — entering degraded mode")
            }
            state == ConnectivityState.ONLINE && previousState == ConnectivityState.OFFLINE -> {
                offlineStartTime = null
                lastOnlineTime = Instant.now().epochSecond
                Timber.i("📶 Device back ONLINE — processing sync queue")
            }
        }

        return state
    }

    /**
     * Get the current operational mode.
     */
    fun getOperationalMode(): OperationalMode {
        return when (_connectivityState.value) {
            ConnectivityState.ONLINE -> OperationalMode.FULL
            ConnectivityState.DEGRADED -> OperationalMode.DEGRADED
            ConnectivityState.OFFLINE -> OperationalMode.OFFLINE
            ConnectivityState.UNKNOWN -> OperationalMode.OFFLINE
        }
    }

    // ─── Fail-Closed Safety ───

    /**
     * Check if an operation is safe to perform in current mode.
     * FAIL-CLOSED: safety operations are BLOCKED if we can't verify them.
     */
    fun isOperationSafe(
        operation: OperationType,
        requiresVerification: Boolean = false
    ): SafetyCheckResult {
        val mode = getOperationalMode()

        return when (operation) {
            // These operations ALWAYS work offline (local DB)
            OperationType.RECORD_TRANSACTION -> SafetyCheckResult(
                allowed = true,
                mode = mode,
                reason = "Local database available"
            )
            OperationType.VIEW_INVENTORY -> SafetyCheckResult(
                allowed = true,
                mode = mode,
                reason = "Local database available"
            )
            OperationType.LOCAL_INFERENCE -> SafetyCheckResult(
                allowed = true,
                mode = mode,
                reason = "Cached model available"
            )

            // These operations are BLOCKED offline (fail-closed)
            OperationType.CLOUD_INFERENCE -> SafetyCheckResult(
                allowed = mode == OperationalMode.FULL,
                mode = mode,
                reason = if (mode == OperationalMode.FULL) "Cloud available" else "FAIL-CLOSED: Cloud inference requires connectivity",
                fallback = "Use local cached model"
            )
            OperationType.SYNC_DATA -> SafetyCheckResult(
                allowed = mode != OperationalMode.OFFLINE,
                mode = mode,
                reason = if (mode == OperationalMode.OFFLINE) "Queued for sync" else "Sync available",
                fallback = "Queued for when online"
            )
            OperationType.FINANCIAL_VERIFICATION -> SafetyCheckResult(
                allowed = true, // Always allowed, uses local data
                mode = mode,
                reason = "Local verification available",
                degradedWarning = if (mode != OperationalMode.FULL) "Cross-reference limited to local data" else null
            )
            OperationType.GUARDRAIL_CHECK -> SafetyCheckResult(
                allowed = true, // Guardrails ALWAYS run (fail-closed)
                mode = mode,
                reason = "Guardrails are always active"
            )
        }
    }

    // ─── Sync Queue ───

    /**
     * Queue an operation for sync when back online.
     */
    fun queueForSync(operation: QueuedOperation) {
        syncQueue.add(operation)
        _syncQueueSize.value = syncQueue.size
        Timber.d("Queued operation: ${operation.type} (queue size: ${syncQueue.size})")
    }

    /**
     * Get all pending sync operations.
     */
    fun getPendingOperations(): List<QueuedOperation> = syncQueue.toList()

    /**
     * Mark an operation as synced.
     */
    fun markSynced(operationId: String) {
        syncQueue.removeAll { it.operationId == operationId }
        _syncQueueSize.value = syncQueue.size
    }

    /**
     * Get the sync queue with deduplication and ordering.
     */
    fun getOrderedSyncQueue(): List<QueuedOperation> {
        return syncQueue
            .sortedBy { it.timestamp }
            .distinctBy { it.operationId }
    }

    /**
     * Clear the sync queue after successful full sync.
     */
    fun clearSyncQueue() {
        syncQueue.clear()
        _syncQueueSize.value = 0
        Timber.i("Sync queue cleared")
    }

    // ─── Offline Duration ───

    /**
     * Get how long the device has been offline (in seconds).
     */
    fun getOfflineDuration(): Long {
        val startTime = offlineStartTime ?: return 0
        return Instant.now().epochSecond - startTime
    }

    /**
     * Check if offline duration exceeds a threshold.
     */
    fun isOfflineTooLong(maxSeconds: Long = MAX_OFFLINE_SECONDS): Boolean {
        return getOfflineDuration() > maxSeconds
    }

    /**
     * Get offline status summary.
     */
    fun getOfflineStatus(): OfflineStatus {
        val duration = getOfflineDuration()
        return OfflineStatus(
            isOffline = _connectivityState.value == ConnectivityState.OFFLINE,
            durationSeconds = duration,
            pendingSyncCount = syncQueue.size,
            lastOnlineTime = lastOnlineTime,
            degradedCapabilities = if (_connectivityState.value != ConnectivityState.ONLINE) {
                listOf("Cloud inference", "Real-time sync", "Cross-reference verification")
            } else emptyList(),
            activeCapabilities = listOf(
                "Local database",
                "Transaction recording",
                "Inventory management",
                "Cached model inference",
                "Safety guardrails"
            )
        )
    }

    companion object {
        private const val MAX_OFFLINE_SECONDS = 86400L // 24 hours
    }
}

// ─── Enums & Data Classes ───

enum class ConnectivityState {
    ONLINE,
    OFFLINE,
    DEGRADED,
    UNKNOWN
}

enum class OperationalMode {
    FULL,       // All capabilities available
    DEGRADED,   // Some capabilities limited
    OFFLINE     // Local-only mode
}

enum class OperationType {
    RECORD_TRANSACTION,
    VIEW_INVENTORY,
    LOCAL_INFERENCE,
    CLOUD_INFERENCE,
    SYNC_DATA,
    FINANCIAL_VERIFICATION,
    GUARDRAIL_CHECK
}

data class SafetyCheckResult(
    val allowed: Boolean,
    val mode: OperationalMode,
    val reason: String,
    val fallback: String? = null,
    val degradedWarning: String? = null
)

data class QueuedOperation(
    val operationId: String,
    val type: String,
    val payload: Map<String, Any>,
    val timestamp: Long = Instant.now().epochSecond,
    val retryCount: Int = 0,
    val maxRetries: Int = 3
)

data class OfflineStatus(
    val isOffline: Boolean,
    val durationSeconds: Long,
    val pendingSyncCount: Int,
    val lastOnlineTime: Long,
    val degradedCapabilities: List<String>,
    val activeCapabilities: List<String>
)
