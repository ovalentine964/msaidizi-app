package com.msaidizi.app

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.msaidizi.app.core.model.ModelTier
import com.msaidizi.app.sync.NetworkMonitor
import com.msaidizi.app.update.UpdateCheckWorker
import com.msaidizi.app.voice.ModelRegistry
import com.msaidizi.app.voice.work.ModelDownloadWorker
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.msaidizi.app.core.util.DeviceTier
import com.msaidizi.app.cfo.BriefingDelivery
import com.msaidizi.app.loops.BriefingNotificationWorker
import dagger.hilt.android.HiltAndroidApp
import io.sentry.android.core.SentryAndroid
import org.bouncycastle.jce.provider.BouncyCastleProvider
import timber.log.Timber
import java.security.Security
import javax.inject.Inject

/**
 * Msaidizi Application class.
 *
 * Initializes the multi-agent CFO system optimized for 2GB Android devices.
 * Key responsibilities:
 * - Initialize Timber logging
 * - Configure WorkManager for background sync
 * - Detect device tier for progressive enhancement
 * - Set up memory monitoring
 */
@HiltAndroidApp
class MsaidiziApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var modelRegistry: ModelRegistry

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var federatedLearningClient: com.msaidizi.app.core.language.FederatedLearningClient

    @Inject
    lateinit var syncManager: com.msaidizi.app.sync.SyncManager

    @Inject
    lateinit var briefingDelivery: BriefingDelivery

    @Inject
    lateinit var memoryManager: com.msaidizi.app.core.MemoryManager

    @Inject
    lateinit var orchestrator: com.msaidizi.app.agent.Orchestrator

    override fun onCreate() {
        super.onCreate()

        // Initialize Sentry crash reporting (must be before other init)
        val sentryDsn = BuildConfig.SENTRY_DSN
        if (sentryDsn.isNotBlank()) {
            SentryAndroid.init(this) { options ->
                options.dsn = sentryDsn
                options.environment = if (BuildConfig.DEBUG) "development" else "production"
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"
                options.tracesSampleRate = if (BuildConfig.DEBUG) 1.0 else 0.2
                options.isEnableAutoSessionTracking = true
                options.sessionTrackingIntervalMillis = 30_000L
                options.isAttachStacktrace = true
                options.isSendDefaultPii = false
                // Performance monitoring
                options.isEnableUserInteractionTracing = true
                options.isEnableActivityLifecycleBreadcrumbs = true
                options.isEnableAppLifecycleBreadcrumbs = true
                options.isEnableSystemEventBreadcrumbs = true
            }
            Timber.i("Sentry crash reporting initialized")
        } else {
            Timber.d("Sentry DSN not configured, skipping crash reporting")
        }

        // Initialize logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Register Bouncy Castle provider for Post-Quantum Cryptography
        // ML-KEM (FIPS 203) and ML-DSA (FIPS 204) require Bouncy Castle 1.79+
        // Android's built-in BC is stripped and lacks PQC support
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            Timber.i("Bouncy Castle provider registered for PQC support")
        } else {
            // Replace Android's stripped BC with the full version
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            Timber.i("Bouncy Castle provider replaced with PQC-capable version")
        }

        // Detect device tier
        DeviceTier.initialize(this)

        Timber.d("MsaidiziApp: Device tier = ${DeviceTier.current}")
        Timber.d("MsaidiziApp: Total RAM = ${getTotalRamMB()}MB")
        Timber.d("MsaidiziApp: Available cores = ${Runtime.getRuntime().availableProcessors()}")

        // Start network monitoring
        networkMonitor.startMonitoring()

        // Initialize federated learning with hashed device ID
        val deviceId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        federatedLearningClient.initialize(deviceId)

        // Schedule tiered model downloads
        scheduleModelDownloads()

        // Schedule background update checks (silent, 24h interval)
        UpdateCheckWorker.schedule(this)

        // Schedule daily briefing notifications (7 AM morning, 7 PM evening)
        scheduleBriefingNotifications()

        // ── Crash Recovery: check for incomplete agent tasks ──
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val recoveryTasks = orchestrator.recoverIncompleteTasks()
                if (recoveryTasks.isNotEmpty()) {
                    Timber.i("Crash recovery: found %d incomplete tasks", recoveryTasks.size)
                    for (rt in recoveryTasks) {
                        kotlinx.coroutines.delay(rt.delayMs)
                        Timber.i("Recovering task %s (action=%s, phase=%s)",
                            rt.checkpoint.taskId, rt.action, rt.checkpoint.lastPhase)
                        // Re-process the input from the checkpoint
                        // This re-runs the full OODA cycle from scratch,
                        // which is safe because transaction handlers are idempotent
                        try {
                            val inputJson = rt.checkpoint.inputJson
                            val input = com.google.gson.Gson().fromJson(
                                inputJson, Map::class.java
                            ) as? Map<*, *>
                            val text = input?.get("text") as? String
                            val language = input?.get("language") as? String ?: "sw"
                            if (text != null) {
                                orchestrator.processInput(text, language)
                                Timber.i("Recovered task %s successfully", rt.checkpoint.taskId)
                            } else {
                                Timber.w("Cannot recover task %s: input text is null", rt.checkpoint.taskId)
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to recover task %s", rt.checkpoint.taskId)
                        }
                    }
                }
                // Cleanup old recovery data
                orchestrator.cleanupRecoveryData()
            } catch (e: Exception) {
                Timber.e(e, "Crash recovery check failed")
            }
        }

        // Register memory trim callback
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                handleMemoryPressure(level)
            }

            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            override fun onLowMemory() {
                handleMemoryPressure(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }
        })
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG
                else android.util.Log.ERROR
            )
            .build()

    /**
     * Handle memory pressure by releasing non-critical resources.
     * Critical for 2GB devices where Android's LMK is aggressive.
     */
    private fun handleMemoryPressure(level: Int) {
        // Delegate to MemoryManager for systematic cleanup
        memoryManager.onTrimMemory(level)

        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Timber.d("Memory pressure: UI hidden — releasing UI caches")
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Timber.w("Memory pressure: moderate — releasing non-essential caches")
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Timber.w("Memory pressure: low — releasing ASR/TTS models if idle")
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Timber.e("Memory pressure: CRITICAL — emergency cleanup")
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Timber.e("Memory pressure: COMPLETE — app may be killed soon")
            }
        }
    }

    /**
     * Schedule tiered model downloads using WorkManager.
     * Tier 1: Downloads immediately on any network.
     * Tier 2: Downloads on WiFi only, battery not low.
     */
    private fun scheduleModelDownloads() {
        // Check if Tier 1 models need downloading
        if (!modelRegistry.isTierReady(ModelTier.FIRST_LAUNCH)) {
            Timber.i("MsaidiziApp: Scheduling Tier 1 download")
            WorkManager.getInstance(this).enqueueUniqueWork(
                ModelDownloadWorker.WORK_NAME_TIER1,
                ExistingWorkPolicy.KEEP,
                ModelDownloadWorker.tier1Request()
            )
        }

        // Schedule Tier 2 download (WiFi-only, will retry until WiFi available)
        if (!modelRegistry.isTierReady(ModelTier.ON_DEMAND)) {
            Timber.i("MsaidiziApp: Scheduling Tier 2 download (WiFi-only)")
            WorkManager.getInstance(this).enqueueUniqueWork(
                ModelDownloadWorker.WORK_NAME_TIER2,
                ExistingWorkPolicy.KEEP,
                ModelDownloadWorker.tier2Request()
            )
        }
    }

    /**
     * Schedule morning/evening/weekly briefing notifications.
     * Only schedules if onboarding is complete.
     */
    private fun scheduleBriefingNotifications() {
        val prefs = getSharedPreferences("worker_profile", MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_complete", false)) {
            BriefingNotificationWorker.scheduleAllBriefings(this)
        }
    }

    private fun getTotalRamMB(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }
}
