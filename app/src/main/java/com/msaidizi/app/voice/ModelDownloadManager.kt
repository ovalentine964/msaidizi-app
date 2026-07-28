package com.msaidizi.app.voice

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ModelDownloadManager — Coordinates on-first-launch model downloads.
 *
 * This manager determines whether models should be downloaded from the network
 * (production builds with small APK) or extracted from bundled assets
 * (development builds with large APK).
 *
 * Strategy selection:
 * 1. If models are bundled in assets/ → extract (ModelManager)
 * 2. If models are NOT bundled → download via WorkManager (ModelDownloadWorker)
 * 3. If neither available → stub mode (UI-only, no AI)
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager
) {
    companion object {
        private const val PREFS_NAME = "model_download_manager"
        private const val KEY_STRATEGY = "model_strategy"  // "bundled" or "download"
        private const val KEY_DOWNLOAD_COMPLETE = "download_complete"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val workManager by lazy {
        WorkManager.getInstance(context)
    }

    /**
     * Check if models need to be acquired (either extracted or downloaded).
     * Returns true if models are already ready.
     */
    suspend fun ensureModelsReady(): Boolean {
        // First check if models are already present
        val status = modelManager.getModelStatus()
        if (status.allRequiredPresent) {
            Timber.d("Models already present")
            return true
        }

        // Determine strategy: bundled assets or network download
        val hasBundledModels = checkForBundledAssets()

        return if (hasBundledModels) {
            Timber.i("Using bundled asset extraction strategy")
            prefs.edit().putString(KEY_STRATEGY, "bundled").apply()
            modelManager.ensureModelsAvailable()
        } else {
            Timber.i("Using network download strategy")
            prefs.edit().putString(KEY_STRATEGY, "download").apply()
            scheduleDownload()
            false  // Models not immediately available
        }
    }

    /**
     * Schedule model download via WorkManager.
     * Download runs in background with WiFi-only constraint.
     */
    fun scheduleDownload() {
        // Check if download is already scheduled or complete
        if (prefs.getBoolean(KEY_DOWNLOAD_COMPLETE, false)) {
            Timber.d("Download already complete")
            return
        }

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)  // WiFi only
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .addTag("model_download")
            .build()

        workManager.enqueueUniqueWork(
            ModelDownloadWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )

        Timber.i("Model download scheduled")
    }

    /**
     * Observe download progress.
     */
    fun observeDownloadProgress(): LiveData<List<WorkInfo>> {
        return workManager.getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.WORK_NAME)
    }

    /**
     * Check if download is in progress.
     */
    fun isDownloading(): Boolean {
        val workInfos = workManager.getWorkInfosForUniqueWork(ModelDownloadWorker.WORK_NAME).get()
        return workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }

    /**
     * Check if download is complete.
     */
    fun isDownloadComplete(): Boolean {
        return prefs.getBoolean(KEY_DOWNLOAD_COMPLETE, false) ||
               modelManager.getModelStatus().allRequiredPresent
    }

    /**
     * Cancel any pending downloads.
     */
    fun cancelDownload() {
        workManager.cancelUniqueWork(ModelDownloadWorker.WORK_NAME)
        Timber.w("Model download cancelled")
    }

    /**
     * Check if the APK has bundled model assets.
     */
    private fun checkForBundledAssets(): Boolean {
        return try {
            val assets = context.assets.list("models") ?: emptyArray()
            assets.isNotEmpty() && assets.any { it != ".gitkeep" }
        } catch (e: Exception) {
            false
        }
    }
}
