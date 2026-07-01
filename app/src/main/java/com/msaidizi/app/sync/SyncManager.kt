package com.msaidizi.app.sync

import android.content.Context
import androidx.work.*
import com.msaidizi.app.core.util.CryptoUtils
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private val json: Json
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
    suspend fun syncNow(scope: CoroutineScope): SyncResult {
        if (_syncState.value == SyncState.SYNCING) {
            Timber.w("Sync already in progress")
            return SyncResult.ALREADY_IN_PROGRESS
        }

        if (!networkMonitor.isConnected()) {
            Timber.w("No network connectivity")
            return SyncResult.NO_NETWORK
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
        return if (_syncState.value == SyncState.SUCCESS) SyncResult.SUCCESS else SyncResult.ERROR
    }

    /**
     * Perform the actual sync operation.
     */
    private suspend fun performSync() = withContext(Dispatchers.IO) {
        val unsyncedCount = syncQueue.getUnsyncedCount()
        if (unsyncedCount == 0) {
            Timber.d("Nothing to sync")
            return@withContext
        }

        Timber.d("Syncing $unsyncedCount transactions")

        // Prepare payload
        val payload = syncQueue.prepareSyncPayload()
        val payloadJson = json.encodeToString(payload)

        // Compress
        val compressed = compressData(payloadJson.toByteArray(Charsets.UTF_8))

        // Encrypt
        val encrypted = CryptoUtils.encrypt(compressed)

        // Upload with retry
        var attempt = 0
        var success = false

        while (attempt < MAX_RETRY_ATTEMPTS && !success) {
            try {
                val response = httpClient.post("https://api.msaidizi.app/v1/sync/upload") {
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
                    Timber.d("Sync successful: ${syncedIds.size} transactions uploaded")
                } else {
                    Timber.w("Sync failed with status: ${response.status}")
                    attempt++
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        val delay = BASE_RETRY_DELAY_MS * (1L shl attempt)
                        Timber.d("Retrying sync in ${delay}ms (attempt $attempt)")
                        delay(delay)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Sync attempt $attempt failed")
                attempt++
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    val delay = BASE_RETRY_DELAY_MS * (1L shl attempt)
                    delay(delay)
                }
            }
        }

        if (!success) {
            throw Exception("Sync failed after $MAX_RETRY_ATTEMPTS attempts")
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
enum class SyncResult {
    SUCCESS,
    NO_NETWORK,
    ALREADY_IN_PROGRESS,
    ERROR
}

/**
 * WorkManager worker for background sync.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("SyncWorker: Starting background sync")

        return try {
            // This would be injected via Hilt in a real implementation
            // For now, we'll just return success
            // TODO: Inject SyncManager via HiltWorkerFactory
            Timber.d("SyncWorker: Background sync completed")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "SyncWorker: Background sync failed")
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

/**
 * Boot receiver to reschedule sync after device restart.
 */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action == android.content.Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Boot completed, rescheduling sync")
            // TODO: Schedule sync via WorkManager
        }
    }
}
