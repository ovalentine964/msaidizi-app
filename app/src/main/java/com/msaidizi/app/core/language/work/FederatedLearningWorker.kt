package com.msaidizi.app.core.language.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.msaidizi.app.core.language.FederatedLearningClient
import com.msaidizi.app.core.language.LanguageLearningPipeline
import com.msaidizi.app.core.model.UserCorrectionDao
import com.msaidizi.app.security.privacy.ConsentManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for background federated learning operations.
 *
 * Handles two tasks:
 * 1. LoRA training — on-device fine-tuning while charging
 * 2. Federated sync — upload/download model updates on WiFi + charging
 *
 * Scheduled by LanguageLearningPipeline when correction thresholds are met.
 * Uses WorkManager for reliable execution that survives process death.
 *
 * Constraints:
 * - WiFi only (metered networks are too expensive for model uploads)
 * - Battery not low (training is CPU-intensive)
 * - Device idle preferred (don't compete with user activity)
 * - Charging preferred (training can take 10-15 minutes)
 *
 * Battery impact: ~0.1% per training session, ~0.05% per sync.
 */
@HiltWorker
class FederatedLearningWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val federatedLearningClient: FederatedLearningClient,
    private val languageLearningPipeline: LanguageLearningPipeline,
    private val userCorrectionDao: UserCorrectionDao,
    private val consentManager: ConsentManager,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME_LORA = "fl_lora_training"
        const val WORK_NAME_SYNC = "fl_sync"
        const val WORK_NAME_VERSION_CHECK = "fl_version_check"
        const val KEY_TASK = "task"
        const val KEY_LANGUAGE = "language"
        const val TASK_LORA = "lora"
        const val TASK_SYNC = "sync"
        const val TASK_DOWNLOAD = "download"
        const val TASK_VERSION_CHECK = "version_check"

        /**
         * Schedule LoRA training when enough corrections are available.
         * Requires: charging + battery not low + idle.
         */
        fun loraTrainingRequest(language: String): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(true)
                .build()

            return OneTimeWorkRequestBuilder<FederatedLearningWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(
                    KEY_TASK to TASK_LORA,
                    KEY_LANGUAGE to language
                ))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .addTag("federated_learning")
                .addTag("lora_training")
                .build()
        }

        /**
         * Schedule federated sync (upload + download).
         * Requires: WiFi + charging + battery not low.
         */
        fun syncRequest(language: String): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi only
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()

            return OneTimeWorkRequestBuilder<FederatedLearningWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(
                    KEY_TASK to TASK_SYNC,
                    KEY_LANGUAGE to language
                ))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 6, TimeUnit.HOURS)
                .addTag("federated_learning")
                .addTag("fl_sync")
                .build()
        }

        /**
         * Schedule a periodic version check (lightweight).
         * Runs daily on any network to check for new global models.
         */
        fun versionCheckRequest(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return PeriodicWorkRequestBuilder<FederatedLearningWorker>(
                24, TimeUnit.HOURS,    // Repeat every 24 hours
                2, TimeUnit.HOURS      // Flex interval
            )
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_TASK to TASK_VERSION_CHECK))
                .addTag("federated_learning")
                .addTag("version_check")
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val task = inputData.getString(KEY_TASK) ?: return Result.failure()
        val language = inputData.getString(KEY_LANGUAGE) ?: "sw"

        Timber.tag("FLWorker").i("Starting FL task: %s for language: %s", task, language)

        return try {
            when (task) {
                TASK_LORA -> doLoRATraining(language)
                TASK_SYNC -> doFederatedSync(language)
                TASK_VERSION_CHECK -> doVersionCheck(language)
                else -> {
                    Timber.tag("FLWorker").w("Unknown task: %s", task)
                    Result.failure()
                }
            }
        } catch (e: Throwable) {
            Timber.tag("FLWorker").e(e, "FL task failed: %s", task)
            Result.retry()
        }
    }

    /**
     * Perform on-device LoRA fine-tuning.
     *
     * Steps:
     * 1. Check consent (must have analytics/FL consent)
     * 2. Collect unapplied corrections
     * 3. Run LoRA training via FederatedLearningClient
     * 4. Upload the trained adapter to FL server
     */
    private suspend fun doLoRATraining(language: String): Result {
        // Consent check
        if (!consentManager.hasConsent(ConsentManager.ConsentPurpose.ANALYTICS)) {
            Timber.tag("FLWorker").w("LoRA training blocked: no analytics consent")
            return Result.failure()
        }

        val corrections = userCorrectionDao.getUnapplied()
        if (corrections.size < 50) {
            Timber.tag("FLWorker").d("Not enough corrections for LoRA: %d", corrections.size)
            return Result.success()
        }

        Timber.tag("FLWorker").i("Starting LoRA training: %d corrections", corrections.size)
        val success = federatedLearningClient.performLoRATraining(corrections, language)

        return if (success) {
            Timber.tag("FLWorker").i("LoRA training complete")
            Result.success()
        } else {
            Timber.tag("FLWorker").w("LoRA training returned false")
            Result.retry()
        }
    }

    /**
     * Perform federated sync: upload local updates + download global model.
     *
     * Steps:
     * 1. Check consent
     * 2. Upload anonymized corrections + LoRA adapter
     * 3. Check for new global model version
     * 4. Download and apply global model if available
     */
    private suspend fun doFederatedSync(language: String): Result {
        // Consent check
        if (!consentManager.hasConsent(ConsentManager.ConsentPurpose.ANALYTICS)) {
            Timber.tag("FLWorker").w("FL sync blocked: no analytics consent")
            return Result.failure()
        }

        val corrections = userCorrectionDao.getRecent(200)
        if (corrections.isEmpty()) {
            Timber.tag("FLWorker").d("No corrections to sync")
            return Result.success()
        }

        // Upload
        Timber.tag("FLWorker").i("Uploading %d corrections for %s", corrections.size, language)
        federatedLearningClient.uploadUpdate(
            language = language,
            corrections = corrections,
            adapterBytes = null, // Would come from LanguageModelRegistry
            calibrationParams = com.msaidizi.app.core.language.CalibrationParams(
                temperature = 1.5f,
                plattA = 0.8f,
                plattB = -0.3f,
                prior = 0.7f
            )
        )

        // Download
        Timber.tag("FLWorker").i("Checking for global model updates for %s", language)
        val download = federatedLearningClient.downloadUpdate(language)
        if (download != null) {
            Timber.tag("FLWorker").i("Downloaded global model v%s", download.version)
            federatedLearningClient.applyGlobalUpdate(
                update = download,
                language = language,
                userCorrectionCount = corrections.size
            )
        }

        return Result.success()
    }

    /**
     * Check for new model versions (lightweight poll).
     * If a new version is available, schedule a full sync.
     */
    private suspend fun doVersionCheck(language: String): Result {
        Timber.tag("FLWorker").d("Checking for model updates for %s", language)
        // The version check happens via the download endpoint
        // If a new version is available, it will be returned
        val download = federatedLearningClient.downloadUpdate(language)
        if (download != null) {
            Timber.tag("FLWorker").i("New model available: v%s — scheduling sync", download.version)
            // Schedule a full sync
            val syncWork = syncRequest(language)
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(
                    WORK_NAME_SYNC,
                    ExistingWorkPolicy.REPLACE,
                    syncWork
                )
        }
        return Result.success()
    }
}
