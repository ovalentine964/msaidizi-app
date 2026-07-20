package com.msaidizi.app.voice.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.msaidizi.app.core.model.ModelTier
import com.msaidizi.app.sync.NetworkMonitor
import com.msaidizi.app.voice.ModelRegistry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for background model downloads.
 *
 * Tier 1 models (Whisper + Piper) download over mobile data on first launch.
 * Tier 2 models (Qwen) download only on WiFi, with battery not low.
 *
 * Uses WorkManager for reliable background execution that survives process death.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val modelRegistry: ModelRegistry,
    private val networkMonitor: NetworkMonitor
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME_TIER1 = "model_download_tier1"
        const val WORK_NAME_TIER2 = "model_download_tier2"
        const val KEY_TIER = "tier"
        const val KEY_RESULT_MESSAGE = "result_message"

        /**
         * Create a OneTimeWorkRequest for Tier 1 (mobile data OK).
         */
        fun tier1Request(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED) // Any network
                .build()

            return OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_TIER to ModelTier.FIRST_LAUNCH.name))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("model_download")
                .build()
        }

        /**
         * Create a OneTimeWorkRequest for Tier 2 (WiFi only).
         */
        fun tier2Request(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi only
                .setRequiresBatteryNotLow(true)
                .build()

            return OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_TIER to ModelTier.ON_DEMAND.name))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag("model_download")
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val tierName = inputData.getString(KEY_TIER)
            ?: return Result.failure(workDataOf(KEY_RESULT_MESSAGE to "No tier specified"))

        val tier = try {
            ModelTier.valueOf(tierName)
        } catch (e: IllegalArgumentException) {
            return Result.failure(workDataOf(KEY_RESULT_MESSAGE to "Invalid tier: $tierName"))
        }

        Timber.i("ModelDownloadWorker: Starting download for tier %s", tier)

        // For Tier 2, verify WiFi constraint
        if (tier == ModelTier.ON_DEMAND) {
            networkMonitor.startMonitoring()
            if (!networkMonitor.isWifi()) {
                Timber.d("ModelDownloadWorker: Not on WiFi, deferring Tier 2 download")
                return Result.retry()
            }
        }

        return try {
            var completedCount = 0
            var failedCount = 0

            modelRegistry.downloadTier(tier) { modelId, progress ->
                if (progress >= 1.0f) {
                    completedCount++
                    Timber.i("ModelDownloadWorker: %s downloaded", modelId)
                }
            }

            // Smoke test downloaded models
            val models = modelRegistry.getModelsByTier(tier)
            for (def in models) {
                if (modelRegistry.isModelReady(def.id)) {
                    val passed = modelRegistry.smokeTestModel(def.id)
                    if (!passed) {
                        failedCount++
                        Timber.e("ModelDownloadWorker: Smoke test failed for %s", def.id)
                    }
                } else {
                    failedCount++
                }
            }

            val message = "Tier $tier: $completedCount downloaded, $failedCount failed"
            Timber.i("ModelDownloadWorker: %s", message)

            if (failedCount > 0 && completedCount == 0) {
                Result.retry()
            } else {
                Result.success(workDataOf(KEY_RESULT_MESSAGE to message))
            }
        } catch (e: Throwable) {
            Timber.e(e, "ModelDownloadWorker: Error downloading tier %s", tier)
            Result.retry()
        }
    }
}
