package com.msaidizi.app.core.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks download progress with speed, ETA, and data usage estimation.
 *
 * Provides user-facing information for model downloads:
 * - Current download speed (KB/s or MB/s)
 * - Estimated time remaining
 * - Data used so far / total data
 * - Percentage complete
 *
 * ## Speed Estimation
 * Uses exponential moving average (α=0.3) to smooth speed readings.
 * This avoids jumpy estimates from network fluctuations.
 *
 * ## Data Usage Awareness
 * Critical for African users on limited data plans.
 * Shows exactly how much data each download consumes.
 */
class DownloadProgressTracker {

    companion object {
        private const val TAG = "DownloadProgressTracker"
        private const val SPEED_SMOOTHING_ALPHA = 0.3
        private const val MIN_SPEED_SAMPLE_MS = 500  // Min time between speed samples
        private const val STALE_THRESHOLD_MS = 5000  // Consider download stalled after 5s
    }

    // ── Per-model tracking state ──

    private data class ModelTracker(
        val modelId: String,
        val totalBytes: Long,
        var downloadedBytes: Long = 0,
        var startTimeMs: Long = System.currentTimeMillis(),
        var lastUpdateTimeMs: Long = System.currentTimeMillis(),
        var lastBytes: Long = 0,
        var smoothedSpeedBps: Double = 0.0,  // bytes per second (EMA)
        var peakSpeedBps: Double = 0.0,
        var samples: Int = 0
    )

    private val trackers = ConcurrentHashMap<String, ModelTracker>()

    // ── Observable state ──

    private val _progressState = MutableStateFlow<Map<String, DownloadProgressInfo>>(emptyMap())
    val progressState: StateFlow<Map<String, DownloadProgressInfo>> = _progressState

    // ── Public API ──

    /**
     * Start tracking a new download.
     *
     * @param modelId Model identifier
     * @param totalBytes Total expected download size
     */
    fun startTracking(modelId: String, totalBytes: Long) {
        trackers[modelId] = ModelTracker(
            modelId = modelId,
            totalBytes = totalBytes,
            startTimeMs = System.currentTimeMillis()
        )
        updateState(modelId)
        Timber.d(TAG, "Started tracking: %s (%s)", modelId, formatBytes(totalBytes))
    }

    /**
     * Update progress for a download.
     * Call this from the download progress callback.
     *
     * @param modelId Model identifier
     * @param downloadedBytes Total bytes downloaded so far
     */
    fun updateProgress(modelId: String, downloadedBytes: Long) {
        val tracker = trackers[modelId] ?: return

        val now = System.currentTimeMillis()
        val timeDeltaMs = now - tracker.lastUpdateTimeMs

        // Skip if too soon (avoid noise)
        if (timeDeltaMs < MIN_SPEED_SAMPLE_MS && downloadedBytes < tracker.totalBytes) {
            tracker.downloadedBytes = downloadedBytes
            return
        }

        // Calculate instantaneous speed
        val bytesDelta = downloadedBytes - tracker.lastBytes
        val instantSpeedBps = if (timeDeltaMs > 0) {
            bytesDelta.toDouble() / (timeDeltaMs / 1000.0)
        } else 0.0

        // Exponential moving average for smooth speed
        if (tracker.samples == 0) {
            tracker.smoothedSpeedBps = instantSpeedBps
        } else {
            tracker.smoothedSpeedBps = SPEED_SMOOTHING_ALPHA * instantSpeedBps +
                    (1 - SPEED_SMOOTHING_ALPHA) * tracker.smoothedSpeedBps
        }

        // Track peak speed
        if (instantSpeedBps > tracker.peakSpeedBps) {
            tracker.peakSpeedBps = instantSpeedBps
        }

        tracker.downloadedBytes = downloadedBytes
        tracker.lastUpdateTimeMs = now
        tracker.lastBytes = downloadedBytes
        tracker.samples++

        updateState(modelId)
    }

    /**
     * Mark a download as completed.
     */
    fun completeTracking(modelId: String) {
        val tracker = trackers[modelId] ?: return
        tracker.downloadedBytes = tracker.totalBytes
        updateState(modelId)
        Timber.i(TAG, "Download complete: %s (%d samples, peak %s/s)",
            modelId, tracker.samples, formatSpeed(tracker.peakSpeedBps))
    }

    /**
     * Stop tracking a download (cancelled or failed).
     */
    fun stopTracking(modelId: String) {
        trackers.remove(modelId)
        updateState(modelId)
    }

    /**
     * Get progress info for a specific model.
     */
    fun getProgress(modelId: String): DownloadProgressInfo? {
        return _progressState.value[modelId]
    }

    /**
     * Get progress for all active downloads.
     */
    fun getAllProgress(): Map<String, DownloadProgressInfo> {
        return _progressState.value
    }

    /**
     * Check if a download appears stalled.
     */
    fun isStalled(modelId: String): Boolean {
        val tracker = trackers[modelId] ?: return false
        return System.currentTimeMillis() - tracker.lastUpdateTimeMs > STALE_THRESHOLD_MS
    }

    // ── State Update ──

    private fun updateState(modelId: String) {
        val tracker = trackers[modelId]
        val info = if (tracker != null) {
            val elapsedMs = System.currentTimeMillis() - tracker.startTimeMs
            val progress = if (tracker.totalBytes > 0) {
                tracker.downloadedBytes.toFloat() / tracker.totalBytes
            } else 0f

            val etaSeconds = if (tracker.smoothedSpeedBps > 0 && tracker.downloadedBytes < tracker.totalBytes) {
                val remainingBytes = tracker.totalBytes - tracker.downloadedBytes
                remainingBytes / tracker.smoothedSpeedBps
            } else null

            DownloadProgressInfo(
                modelId = modelId,
                totalBytes = tracker.totalBytes,
                downloadedBytes = tracker.downloadedBytes,
                progress = progress.coerceIn(0f, 1f),
                progressPercent = (progress * 100).toInt().coerceIn(0, 100),
                speedBps = tracker.smoothedSpeedBps,
                speedFormatted = formatSpeed(tracker.smoothedSpeedBps),
                etaSeconds = etaSeconds?.toLong(),
                etaFormatted = etaSeconds?.let { formatEta(it.toLong()) },
                elapsedMs = elapsedMs,
                elapsedFormatted = formatElapsed(elapsedMs),
                downloadedFormatted = formatBytes(tracker.downloadedBytes),
                totalFormatted = formatBytes(tracker.totalBytes),
                isStalled = isStalled(modelId)
            )
        } else {
            null
        }

        _progressState.value = _progressState.value.toMutableMap().apply {
            if (info != null) put(modelId, info) else remove(modelId)
        }
    }

    // ── Formatting Helpers ──

    private fun formatSpeed(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond < 1024 -> "${bytesPerSecond.toInt()} B/s"
            bytesPerSecond < 1024 * 1024 -> "${"%.1f".format(bytesPerSecond / 1024)} KB/s"
            else -> "${"%.1f".format(bytesPerSecond / (1024 * 1024))} MB/s"
        }
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private fun formatElapsed(ms: Long): String {
        val seconds = ms / 1000
        return formatEta(seconds)
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }
}

/**
 * Progress information for a single model download.
 * Designed for display in the UI.
 */
data class DownloadProgressInfo(
    val modelId: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val progress: Float,
    val progressPercent: Int,
    val speedBps: Double,
    val speedFormatted: String,
    val etaSeconds: Long?,
    val etaFormatted: String?,
    val elapsedMs: Long,
    val elapsedFormatted: String,
    val downloadedFormatted: String,
    val totalFormatted: String,
    val isStalled: Boolean
)
