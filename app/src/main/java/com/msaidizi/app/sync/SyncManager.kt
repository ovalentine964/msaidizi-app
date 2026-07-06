package com.msaidizi.app.sync

import android.content.Context
import androidx.work.*
import com.msaidizi.app.MsaidiziApp
import com.msaidizi.app.core.util.CryptoUtils
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.google.gson.Gson
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit


/**
 * Sync Manager — orchestrates cloud synchronization.
 *
 * Strategy:
 * - Store-forward: queue locally in SQLite
 * - Trigger sync on: WiFi + charging (preferred), WiFi only, 3G+ (critical only)
 * - Compress with zstd, encrypt with AES-256
 * - Retry with exponential backoff
 */
class SyncManager(
    private val syncQueue: SyncQueue,
    private val networkMonitor: NetworkMonitor,
    private val httpClient: HttpClient,
    private val json: Gson
) {
    companion object {
        private const val SYNC_WORK_NAME = "msaidizi_sync"
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val BASE_RETRY_DELAY_MS = 5000L  // 5 seconds
    }

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState

    private var syncJob: Job? = null

    /**
     * Start network monitoring.
     */
    fun initialize() {
        networkMonitor.startMonitoring()
        Timber.d("SyncManager initialized")
    }

    /**
     * Schedule background sync using WorkManager.
     * Triggers when WiFi is available (preferred: WiFi + charging).
     */
    fun scheduleBackgroundSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            repeatInterval = 6, TimeUnit.HOURS,
            flexTimeInterval = 1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )

        Timber.d("Background sync scheduled")
    }

    /**
     * Trigger an immediate sync.
     * Called when user taps sync button or when good connectivity detected.
     */
    suspend fun syncNow(scope: CoroutineScope): SyncStatus {
        if (_syncState.value == SyncState.SYNCING) {
            Timber.w("Sync already in progress")
            return SyncStatus.ALREADY_IN_PROGRESS
        }

        if (!networkMonitor.isConnected()) {
            Timber.w("No network connectivity")
            return SyncStatus.NO_NETWORK
        }

        _syncState.value = SyncState.SYNCING
        syncJob = scope.launch {
            try {
                performSync()
                _syncState.value = SyncState.SUCCESS
            } catch (e: Exception) {
                Timber.e(e, "Sync failed")
                _syncState.value = SyncState.ERROR
            }
        }

        syncJob?.join()
        return if (_syncState.value == SyncState.SUCCESS) SyncStatus.SUCCESS else SyncStatus.ERROR
    }

    /**
     * Check if network is available (for SyncWorker pre-check).
     */
    fun isNetworkAvailable(): Boolean {
        return networkMonitor.isConnected()
    }

    /**
     * Get count of unsynced transactions (for SyncWorker pre-check).
     */
    suspend fun getUnsyncedCount(): Int {
        return syncQueue.getUnsyncedCount()
    }

    /**
     * Perform background sync — called by SyncWorker.
     * Wraps performSync with state management.
     *
     * @return Number of transactions successfully synced.
     * @throws Exception if sync fails after all retries.
     */
    suspend fun performBackgroundSync(): Int {
        if (_syncState.value == SyncState.SYNCING) {
            Timber.w("Sync already in progress, skipping background sync")
            return 0
        }

        _syncState.value = SyncState.SYNCING
        return try {
            val unsyncedCount = syncQueue.getUnsyncedCount()
            performSync()
            _syncState.value = SyncState.SUCCESS
            unsyncedCount
        } catch (e: Exception) {
            _syncState.value = SyncState.ERROR
            throw e
        }
    }

    /**
     * Perform the actual sync operation (push + pull).
     *
     * Push: Upload unsynced local transactions to backend.
     * Pull: Download remote changes since last sync.
     */
    private suspend fun performSync() = withContext(Dispatchers.IO) {
        // ═══ PUSH: Upload unsynced transactions ═══
        val unsyncedCount = syncQueue.getUnsyncedCount()
        if (unsyncedCount > 0) {
            Timber.d("Pushing $unsyncedCount transactions")
            pushTransactions()
        } else {
            Timber.d("Nothing to push")
        }

        // ═══ PULL: Download remote changes ═══
        pullChanges()
    }

    /**
     * Push unsynced local transactions to the backend.
     * Uses compressed + encrypted payload with retry.
     */
    private suspend fun pushTransactions() {
        val payload = syncQueue.prepareSyncPayload()
        val payloadJson = json.toJson(payload)

        // Compress
        val compressed = compressData(payloadJson.toByteArray(Charsets.UTF_8))

        // Encrypt
        val encrypted = CryptoUtils.encrypt(compressed)

        // Upload with retry
        var attempt = 0
        var success = false

        while (attempt < MAX_RETRY_ATTEMPTS && !success) {
            try {
                val response = httpClient.post("https://api.msaidizi.app/v1/sync/push") {
                    contentType(ContentType.Application.OctetStream)
                    header("X-Device-Id", getDeviceId())
                    header("X-Payload-Size", encrypted.size.toString())
                    setBody(encrypted)
                }

                if (response.status.isSuccess()) {
                    success = true
                    // Mark as synced
                    val syncedIds = payload.transactions.map { it.id }
                    syncQueue.markAsSynced(syncedIds)
                    Timber.d("Push successful: ${syncedIds.size} transactions uploaded")
                } else {
                    Timber.w("Push failed with status: ${response.status}")
                    attempt++
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        val delay = BASE_RETRY_DELAY_MS * (1L shl attempt)
                        Timber.d("Retrying push in ${delay}ms (attempt $attempt)")
                        delay(delay)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Push attempt $attempt failed")
                attempt++
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    val delay = BASE_RETRY_DELAY_MS * (1L shl attempt)
                    delay(delay)
                }
            }
        }

        if (!success) {
            throw Exception("Push failed after $MAX_RETRY_ATTEMPTS attempts")
        }
    }

    /**
     * Pull remote changes from the backend.
     * Downloads new intelligence products and server-side updates.
     */
    private suspend fun pullChanges() {
        try {
            val response = httpClient.get("https://api.msaidizi.app/v1/sync/pull") {
                header("X-Device-Id", getDeviceId())
            }

            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                Timber.d("Pull successful: received changes")
                // TODO: Parse and apply remote changes to local DB
                // This would involve deserializing the response and upserting
                // into Room DB via the appropriate DAOs
            } else {
                Timber.w("Pull failed with status: ${response.status}")
            }
        } catch (e: Exception) {
            // Pull failure is non-critical — local data is still valid
            Timber.w(e, "Pull failed (non-critical)")
        }
    }

    /**
     * Compress data using zstd.
     */
    private fun compressData(data: ByteArray): ByteArray {
        return try {
            com.github.luben.zstd.Zstd.compress(data)
        } catch (e: Exception) {
            Timber.w(e, "zstd compression failed, using raw data")
            data
        }
    }

    /**
     * Get unique device ID.
     */
    private fun getDeviceId(): String {
        return CryptoUtils.sha256(
            "${android.os.Build.FINGERPRINT}-${android.os.Build.SERIAL}"
        )
    }

    /**
     * Stop sync operations.
     */
    fun stop() {
        syncJob?.cancel()
        networkMonitor.stopMonitoring()
        _syncState.value = SyncState.IDLE
    }

    /**
     * Get sync status info.
     */
    suspend fun getSyncInfo(): Map<String, Any> {
        return mapOf(
            "state" to _syncState.value.name,
            "unsyncedCount" to syncQueue.getUnsyncedCount(),
            "isConnected" to networkMonitor.isConnected(),
            "connectionType" to networkMonitor.connectionType.value.name
        )
    }
}

/**
 * Sync states.
 */
enum class SyncState {
    IDLE,
    SYNCING,
    SUCCESS,
    ERROR
}

/**
 * Sync result.
 */
enum class SyncStatus {
    SUCCESS,
    NO_NETWORK,
    ALREADY_IN_PROGRESS,
    ERROR
}

/**
 * WorkManager worker for background sync.
 *
 * Retrieves SyncManager from the Application's Hilt component
 * and delegates to SyncManager.performSync() for actual sync logic.
 * Uses exponential backoff on transient failures.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        /** Input key: force sync even if nothing unsynced (for pull-intelligence). */
        const val KEY_FORCE_SYNC = "force_sync"
        /** Output key: number of transactions synced. */
        const val KEY_SYNCED_COUNT = "synced_count"
        /** Output key: error message on failure. */
        const val KEY_ERROR_MESSAGE = "error_message"
    }

    override suspend fun doWork(): Result {
        Timber.d("SyncWorker: Starting background sync (attempt %d)", runAttemptCount)

        val app = applicationContext as? MsaidiziApp
            ?: return Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to "Application is not MsaidiziApp")
            )

        val syncManager = app.syncManager
            ?: return Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to "SyncManager not available")
            )

        return try {
            // Check network connectivity before attempting sync
            if (!syncManager.isNetworkAvailable()) {
                Timber.d("SyncWorker: No network, will retry")
                return Result.retry()
            }

            // Check if there's anything to sync (unless forced)
            val forceSync = inputData.getBoolean(KEY_FORCE_SYNC, false)
            if (!forceSync && syncManager.getUnsyncedCount() == 0) {
                Timber.d("SyncWorker: Nothing to sync")
                return Result.success(workDataOf(KEY_SYNCED_COUNT to 0))
            }

            // Perform the actual sync via SyncManager
            val syncedCount = syncManager.performBackgroundSync()

            Timber.d("SyncWorker: Background sync completed — %d transactions synced", syncedCount)
            Result.success(
                workDataOf(
                    KEY_SYNCED_COUNT to syncedCount
                )
            )
        } catch (e: java.io.IOException) {
            // Network error — retry with backoff
            Timber.w(e, "SyncWorker: Network error, will retry")
            if (runAttemptCount < 5) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Network error"))
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "SyncWorker: Background sync failed")
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Unknown error"))
                )
            }
        }
    }
}

/**
 * Boot receiver to reschedule sync after device restart.
 *
 * Ensures background sync continues after device reboot by
 * re-enqueuing the periodic sync work via SyncManager.
 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Boot completed, rescheduling sync")
            try {
                val app = context.applicationContext as? MsaidiziApp
                if (app != null) {
                    app.syncManager?.scheduleBackgroundSync(context)
                    Timber.d("Sync rescheduled after boot")
                } else {
                    Timber.w("Could not reschedule sync: MsaidiziApp not available")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to reschedule sync after boot")
            }
        }
    }
}
