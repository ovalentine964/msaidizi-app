package com.msaidizi.app.core.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.msaidizi.app.voice.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates model downloads with resume capability, WiFi-only option,
 * progress notifications, and background execution via WorkManager.
 *
 * ## Download Strategy
 *
 * Models are downloaded in priority order:
 * 1. **CRITICAL** (Silero VAD, ~2.5MB) — any network, immediate
 * 2. **HIGH** (Whisper, Piper, ~65MB) — any network, first launch
 * 3. **LOW** (Qwen LLM, ~300MB) — WiFi only, on-demand
 *
 * ## Resume Capability
 *
 * Downloads use HTTP Range headers for resume support. If a download is
 * interrupted (network loss, app kill), it resumes from the last byte
 * when retried. Resume state is persisted to disk.
 *
 * ## WiFi-Only Mode
 *
 * Large models (>50MB) default to WiFi-only. Users can override this
 * in settings. The WiFi-only check uses [ConnectivityManager] to detect
 * unmetered connections.
 *
 * ## Background Downloads
 *
 * Uses Android WorkManager for reliable background execution:
 * - Survives process death
 * - Respects battery/network constraints
 * - Automatic retry with exponential backoff
 *
 * ## Notifications
 *
 * Download progress is shown as a persistent notification with:
 * - Model name and size
 * - Progress bar
 * - Cancel action
 *
 * @see ModelRegistry for model file operations
 * @see ModelManager for model loading and lifecycle
 */
@Singleton
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry,
    private val dataSaverManager: DataSaverManager
) {
    companion object {
        private const val TAG = "ModelDownloader"

        // Notification channel
        private const val NOTIFICATION_CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID_BASE = 2000

        // WiFi-only size threshold (bytes) — models larger than this require WiFi
        private const val WIFI_ONLY_SIZE_THRESHOLD = 50_000_000L // 50MB

        // WorkManager tags
        private const val WORK_TAG_ESSENTIAL = "model_download_essential"
        private const val WORK_TAG_ON_DEMAND = "model_download_ondemand"
        private const val WORK_TAG_PREFIX = "model_download_"

        // Max concurrent downloads
        private const val MAX_CONCURRENT_DOWNLOADS = 2
    }

    // ────────────────────── State ──────────────────────

    /** Per-model download progress (0.0 to 1.0) */
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    /** Per-model download state */
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates

    /** Overall downloader state */
    private val _downloaderState = MutableStateFlow(DownloaderState.IDLE)
    val downloaderState: StateFlow<DownloaderState> = _downloaderState

    /** Active download jobs for cancellation */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Whether WiFi-only mode is enforced for large models */
    var wifiOnlyMode: Boolean = true

    /** Coroutine scope for background downloads */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Download progress tracker with speed/ETA/data usage */
    private val progressTracker = DownloadProgressTracker()

    /** Observable progress info with speed, ETA, data usage */
    val detailedProgress: StateFlow<Map<String, DownloadProgressInfo>>
        get() = progressTracker.progressState

    // ────────────────────── Public API ──────────────────────

    /**
     * Ensure all essential models are downloaded.
     * Called during app initialization.
     *
     * Essential models = Silero VAD + Whisper + Piper.
     * Downloads over any network (they're small enough).
     */
    suspend fun ensureEssentialModels() = withContext(Dispatchers.IO) {
        val essential = listOf("silero-vad", "whisper-tiny-int4", "piper-swahili")
        val missing = essential.filter { !modelRegistry.isModelReady(it) }

        if (missing.isEmpty()) {
            Timber.d(TAG, "All essential models already available")
            return@withContext
        }

        Timber.i(TAG, "Downloading %d essential models: %s", missing.size, missing)
        _downloaderState.value = DownloaderState.DOWNLOADING

        for (modelId in missing) {
            downloadModelInternal(modelId, wifiOnlyOverride = false)
        }

        _downloaderState.value = DownloaderState.IDLE
    }

    /**
     * Download a specific model by ID.
     *
     * @param modelId Model identifier from [ModelRegistry.MODELS]
     * @param forceNetwork If true, download even on mobile data (ignoring wifiOnlyMode)
     * @return true if download succeeded or model was already available
     */
    suspend fun downloadModel(
        modelId: String,
        forceNetwork: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (modelRegistry.isModelReady(modelId)) {
            Timber.d(TAG, "Model %s already available", modelId)
            return@withContext true
        }

        val def = ModelRegistry.MODELS[modelId]
        if (def == null) {
            Timber.w(TAG, "Unknown model ID: %s", modelId)
            return@withContext false
        }

        // Data-saver check: get recommendation based on network conditions
        val recommendation = dataSaverManager.getDownloadRecommendation(def.sizeBytes)
        when (recommendation.action) {
            DownloadAction.BLOCKED -> {
                Timber.w(TAG, "Download blocked: %s — %s", modelId, recommendation.messageEn)
                updateDownloadState(modelId, DownloadState.WAITING_FOR_WIFI)
                return@withContext false
            }
            DownloadAction.WAIT_FOR_NETWORK -> {
                Timber.i(TAG, "No network — queuing %s", modelId)
                queueBackgroundDownload(modelId)
                updateDownloadState(modelId, DownloadState.QUEUED)
                return@withContext false
            }
            DownloadAction.CONFIRM_REQUIRED -> {
                if (!forceNetwork) {
                    Timber.i(TAG, "Data saver: %s needs confirmation (%s)", modelId, recommendation.messageEn)
                    updateDownloadState(modelId, DownloadState.WAITING_FOR_WIFI)
                    queueBackgroundDownload(modelId)
                    return@withContext false
                }
            }
            else -> { /* proceed */ }
        }

        // Check WiFi-only constraint (legacy check)
        val requiresWifi = wifiOnlyMode && def.sizeBytes > WIFI_ONLY_SIZE_THRESHOLD
        if (requiresWifi && !forceNetwork && !isOnWifi()) {
            Timber.i(TAG, "Model %s requires WiFi (%d MB) — queuing for background", modelId, def.sizeBytes / (1024 * 1024))
            queueBackgroundDownload(modelId)
            updateDownloadState(modelId, DownloadState.WAITING_FOR_WIFI)
            return@withContext false
        }

        return@withContext downloadModelInternal(modelId, forceNetwork)
    }

    /**
     * Download the on-demand LLM model with progressive loading.
     * Updated: Starts with smallest variant (Q2_K) if data-saver is on,
     * then upgrades to Q4_K_M when WiFi becomes available.
     *
     * @param preferLite If true, download the smallest available variant first
     * @return true if download succeeded
     */
    suspend fun downloadLlmModel(preferLite: Boolean = false): Boolean {
        val useLite = preferLite || dataSaverManager.isDataSaverEnabled()

        if (useLite && !dataSaverManager.isOnUnmeteredConnection()) {
            // Data-saver: try Q2_K first (~300MB for Qwen, ~650MB for Gemma)
            val liteGemma = "gemma-4-e2b-q2k"
            val liteQwen = "qwen-3.5-0.8b-q2k"

            if (!modelRegistry.isModelReady(liteGemma)) {
                Timber.i(TAG, "Data-saver mode: downloading lite Gemma Q2_K (%d MB)", 650)
                val downloaded = downloadModel(liteGemma)
                if (downloaded) {
                    // Queue full model for WiFi upgrade later
                    queueUpgradeDownload("gemma-4-e2b-q4km")
                    return true
                }
            }

            if (!modelRegistry.isModelReady(liteQwen)) {
                Timber.i(TAG, "Data-saver mode: downloading lite Qwen Q2_K (%d MB)", 300)
                val downloaded = downloadModel(liteQwen)
                if (downloaded) {
                    queueUpgradeDownload("qwen-3.5-0.8b-q4km")
                    return true
                }
            }
        }

        // Standard path: try Gemma 4 E2B first (primary model)
        val gemmaModelId = "gemma-4-e2b-q4km"
        if (modelRegistry.isModelReady(gemmaModelId)) return true

        Timber.i(TAG, "Starting primary LLM model download: Gemma 4 E2B (%d MB)", 1500)
        val gemmaDownloaded = downloadModel(gemmaModelId)
        if (gemmaDownloaded) return true

        // Fallback: Qwen 3.5 0.8B
        val qwenModelId = "qwen-3.5-0.8b-q4km"
        if (modelRegistry.isModelReady(qwenModelId)) return true

        Timber.i(TAG, "Gemma download failed, starting fallback: Qwen 3.5 0.8B (%d MB)", 580)
        return downloadModel(qwenModelId)
    }

    /**
     * Queue a model upgrade download for when WiFi becomes available.
     * Used for progressive loading: download Q2_K now, upgrade to Q4_K_M on WiFi.
     */
    fun queueUpgradeDownload(targetModelId: String) {
        Timber.i(TAG, "Queuing upgrade download: %s (will run on WiFi)", targetModelId)
        queueBackgroundDownload(targetModelId, wifiOnly = true)
    }

    /**
     * Get the download recommendation for a model (for UI display).
     * Shows data cost, warnings, and alternative model suggestions.
     */
    fun getDownloadRecommendation(modelId: String): DownloadRecommendation? {
        val def = ModelRegistry.MODELS[modelId] ?: return null
        return dataSaverManager.getDownloadRecommendation(def.sizeBytes)
    }

    /**
     * Queue a model download for background execution via WorkManager.
     * The download will run when constraints are met (WiFi, battery, etc.).
     *
     * @param modelId Model to download
     * @param wifiOnly Whether to require WiFi (default: true for large models)
     */
    fun queueBackgroundDownload(modelId: String, wifiOnly: Boolean = true) {
        val def = ModelRegistry.MODELS[modelId] ?: return

        val constraints = Constraints.Builder().apply {
            if (wifiOnly || def.sizeBytes > WIFI_ONLY_SIZE_THRESHOLD) {
                setRequiredNetworkType(NetworkType.UNMETERED)
            } else {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
            setRequiresBatteryNotLow(true)
        }.build()

        val inputData = workDataOf(
            "model_id" to modelId,
            "wifi_only" to wifiOnly
        )

        val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorkerImpl>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .addTag(WORK_TAG_PREFIX + modelId)
            .addTag(if (wifiOnly) WORK_TAG_ON_DEMAND else WORK_TAG_ESSENTIAL)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "model_download_$modelId",
                ExistingWorkPolicy.KEEP,
                workRequest
            )

        Timber.i(TAG, "Queued background download for %s (wifiOnly=%b)", modelId, wifiOnly)
        updateDownloadState(modelId, DownloadState.QUEUED)
    }

    /**
     * Cancel a queued or in-progress download.
     *
     * @param modelId Model to cancel
     */
    fun cancelDownload(modelId: String) {
        // Cancel WorkManager job
        WorkManager.getInstance(context)
            .cancelUniqueWork("model_download_$modelId")

        // Cancel coroutine job if active
        activeJobs[modelId]?.cancel()
        activeJobs.remove(modelId)

        updateDownloadState(modelId, DownloadState.CANCELLED)
        updateProgress(modelId, 0f)
        Timber.i(TAG, "Download cancelled for %s", modelId)
    }

    /**
     * Cancel all pending and active downloads.
     */
    fun cancelAllDownloads() {
        WorkManager.getInstance(context)
            .cancelAllWorkByTag(WORK_TAG_ESSENTIAL)
        WorkManager.getInstance(context)
            .cancelAllWorkByTag(WORK_TAG_ON_DEMAND)

        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()

        _downloadStates.value = emptyMap()
        _downloadProgress.value = emptyMap()
        Timber.i(TAG, "All downloads cancelled")
    }

    /**
     * Retry a failed download.
     *
     * @param modelId Model to retry
     */
    suspend fun retryDownload(modelId: String): Boolean {
        updateDownloadState(modelId, DownloadState.PENDING)
        return downloadModel(modelId)
    }

    /**
     * Get download status for a specific model.
     */
    fun getDownloadState(modelId: String): DownloadState {
        return _downloadStates.value[modelId] ?: DownloadState.NOT_STARTED
    }

    /**
     * Check if any downloads are currently active.
     */
    fun isDownloading(): Boolean {
        return _downloadStates.value.any { it.value == DownloadState.DOWNLOADING }
    }

    /**
     * Check if we're currently on a WiFi (unmetered) connection.
     */
    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Register a network callback to auto-resume downloads when WiFi becomes available.
     * Call this from Application.onCreate or a lifecycle observer.
     */
    fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.d(TAG, "WiFi available — checking for queued downloads")
                scope.launch {
                    resumeQueuedDownloads()
                }
            }
        })
    }

    // ────────────────────── Internal Download Logic ──────────────────────

    /**
     * Internal download implementation with full progress tracking and notification.
     * Now integrates DownloadProgressTracker for speed/ETA/data usage and
     * records data usage with DataSaverManager.
     */
    private suspend fun downloadModelInternal(
        modelId: String,
        wifiOnlyOverride: Boolean = false
    ): Boolean {
        val def = ModelRegistry.MODELS[modelId] ?: return false

        // Prevent duplicate downloads
        if (getDownloadState(modelId) == DownloadState.DOWNLOADING) {
            Timber.d(TAG, "Model %s is already being downloaded", modelId)
            return false
        }

        updateDownloadState(modelId, DownloadState.DOWNLOADING)
        updateProgress(modelId, 0f)
        progressTracker.startTracking(modelId, def.sizeBytes)
        showDownloadNotification(modelId, def, 0)

        val job = scope.launch {
            try {
                val success = modelRegistry.downloadModel(modelId) { progress ->
                    updateProgress(modelId, progress)
                    val downloadedBytes = (def.sizeBytes * progress).toLong()
                    progressTracker.updateProgress(modelId, downloadedBytes)
                    showDownloadNotification(modelId, def, (progress * 100).toInt())
                }

                if (success) {
                    updateDownloadState(modelId, DownloadState.COMPLETED)
                    updateProgress(modelId, 1f)
                    progressTracker.completeTracking(modelId)
                    dataSaverManager.recordDataUsage(def.sizeBytes)
                    showCompletedNotification(modelId, def)
                    Timber.i(TAG, "Model %s downloaded successfully (%d MB)",
                        modelId, def.sizeBytes / (1024 * 1024))
                } else {
                    updateDownloadState(modelId, DownloadState.FAILED)
                    progressTracker.stopTracking(modelId)
                    showErrorNotification(modelId, def, "Download failed")
                    Timber.e(TAG, "Model %s download failed", modelId)
                }
            } catch (e: CancellationException) {
                updateDownloadState(modelId, DownloadState.CANCELLED)
                progressTracker.stopTracking(modelId)
                Timber.i(TAG, "Model %s download cancelled", modelId)
            } catch (e: Exception) {
                updateDownloadState(modelId, DownloadState.FAILED)
                progressTracker.stopTracking(modelId)
                showErrorNotification(modelId, def, e.message ?: "Unknown error")
                Timber.e(e, "Model %s download error", modelId)
            } finally {
                activeJobs.remove(modelId)
                cancelNotification(modelId)
            }
        }

        activeJobs[modelId] = job
        job.join()
        return getDownloadState(modelId) == DownloadState.COMPLETED
    }

    /**
     * Resume downloads that were queued waiting for WiFi.
     */
    private suspend fun resumeQueuedDownloads() {
        val queued = _downloadStates.value.filter { it.value == DownloadState.QUEUED || it.value == DownloadState.WAITING_FOR_WIFI }
        if (queued.isEmpty()) return

        Timber.i(TAG, "Resuming %d queued downloads on WiFi", queued.size)
        for ((modelId, _) in queued) {
            if (modelRegistry.isModelReady(modelId)) {
                updateDownloadState(modelId, DownloadState.COMPLETED)
                continue
            }
            downloadModel(modelId)
        }
    }

    // ────────────────────── Notifications ──────────────────────

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of AI model downloads"
                setShowBadge(false)
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun showDownloadNotification(modelId: String, def: com.msaidizi.app.voice.ModelDef, percent: Int) {
        ensureNotificationChannel()
        try {
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading ${friendlyModelName(modelId)}")
                .setContentText("${def.sizeBytes / (1024 * 1024)} MB")
                .setProgress(100, percent, percent == 0)
                .setOngoing(true)
                .setSilent(true)
                .build()

            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID_BASE + modelId.hashCode() and 0xFFFF,
                notification
            )
        } catch (e: SecurityException) {
            Timber.w(TAG, "Notification permission not granted")
        }
    }

    private fun showCompletedNotification(modelId: String, def: com.msaidizi.app.voice.ModelDef) {
        ensureNotificationChannel()
        try {
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("${friendlyModelName(modelId)} downloaded")
                .setContentText("Ready to use")
                .setAutoCancel(true)
                .setSilent(true)
                .build()

            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID_BASE + modelId.hashCode() and 0xFFFF,
                notification
            )
        } catch (e: SecurityException) {
            Timber.w(TAG, "Notification permission not granted")
        }
    }

    private fun showErrorNotification(modelId: String, def: com.msaidizi.app.voice.ModelDef, error: String) {
        ensureNotificationChannel()
        try {
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("${friendlyModelName(modelId)} download failed")
                .setContentText(error)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID_BASE + modelId.hashCode() and 0xFFFF,
                notification
            )
        } catch (e: SecurityException) {
            Timber.w(TAG, "Notification permission not granted")
        }
    }

    private fun cancelNotification(modelId: String) {
        try {
            NotificationManagerCompat.from(context).cancel(
                NOTIFICATION_ID_BASE + modelId.hashCode() and 0xFFFF
            )
        } catch (_: Exception) {}
    }

    private fun friendlyModelName(modelId: String): String {
        return when (modelId) {
            "silero-vad" -> "Voice Detector"
            "whisper-tiny-int4" -> "Speech Recognition"
            "piper-swahili" -> "Swahili Voice"
            "gemma-4-e2b-q4km" -> "AI Assistant (Gemma 4)"
            "gemma-4-e2b-q3km" -> "AI Assistant (Gemma 4 Lite)"
            "gemma-4-e2b-q2k" -> "AI Assistant (Gemma 4 Mini)"
            "qwen-3.5-0.8b-q4km" -> "AI Fallback (Qwen)"
            "qwen-3.5-0.8b-q2k" -> "AI Starter (Qwen Mini)"
            else -> modelId
        }
    }

    // ────────────────────── State Helpers ──────────────────────

    private fun updateDownloadState(modelId: String, state: DownloadState) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(modelId, state)
        }
    }

    private fun updateProgress(modelId: String, progress: Float) {
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            put(modelId, progress.coerceIn(0f, 1f))
        }
    }
}

// ────────────────────── Enums ──────────────────────

/**
 * Per-model download state.
 */
enum class DownloadState {
    /** Download not started */
    NOT_STARTED,
    /** Queued in WorkManager */
    QUEUED,
    /** Waiting for WiFi connection */
    WAITING_FOR_WIFI,
    /** Pending — about to start */
    PENDING,
    /** Currently downloading */
    DOWNLOADING,
    /** Download completed, verifying */
    VERIFYING,
    /** Download and verification complete */
    COMPLETED,
    /** Download failed */
    FAILED,
    /** Download was cancelled */
    CANCELLED,
    /** Download paused (resumable) */
    PAUSED
}

/**
 * Overall downloader state.
 */
enum class DownloaderState {
    IDLE,
    DOWNLOADING,
    PAUSED,
    ERROR
}

// ────────────────────── WorkManager Worker ──────────────────────

/**
 * WorkManager worker for background model downloads.
 *
 * This is the actual [CoroutineWorker] that WorkManager executes.
 * It delegates to [ModelRegistry] for the actual download logic
 * and reports progress via the notification system.
 *
 * Separate from the existing [com.msaidizi.app.voice.work.ModelDownloadWorker]
 * which handles tier-based downloads. This worker handles individual model
 * downloads with more granular control.
 */
class ModelDownloadWorkerImpl(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString("model_id")
            ?: return Result.failure()

        Timber.i("ModelDownloadWorkerImpl: Starting download for %s", modelId)

        // Get ModelRegistry via Hilt entry point
        val registry = EntryPointAccessors.fromApplication(
            appContext,
            ModelDownloaderEntryPoint::class.java
        ).modelRegistry()

        if (registry.isModelReady(modelId)) {
            Timber.d("Model %s already downloaded", modelId)
            return Result.success()
        }

        return try {
            val success = registry.downloadModel(modelId) { progress ->
                setProgressAsync(workDataOf("progress" to progress))
            }

            if (success) {
                Timber.i("Model %s downloaded successfully via WorkManager", modelId)
                Result.success()
            } else {
                Timber.w("Model %s download failed — retrying", modelId)
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "Model %s download error", modelId)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/**
 * Hilt entry point for accessing [ModelRegistry] from WorkManager worker.
 */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ModelDownloaderEntryPoint {
    fun modelRegistry(): ModelRegistry
}
