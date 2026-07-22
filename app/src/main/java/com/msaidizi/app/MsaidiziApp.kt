package com.msaidizi.app

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    lateinit var modelRegistryProvider: javax.inject.Provider<ModelRegistry>

    @Inject
    lateinit var networkMonitorProvider: javax.inject.Provider<NetworkMonitor>

    @Inject
    lateinit var federatedLearningClientProvider: javax.inject.Provider<com.msaidizi.app.core.language.FederatedLearningClient>

    @Inject
    lateinit var syncManagerProvider: javax.inject.Provider<com.msaidizi.app.sync.SyncManager>

    @Inject
    lateinit var briefingDeliveryProvider: javax.inject.Provider<BriefingDelivery>

    @Inject
    lateinit var audioBriefingDeliveryProvider: javax.inject.Provider<com.msaidizi.app.voice.briefing.AudioBriefingDelivery>

    @Inject
    lateinit var bundledModelManagerProvider: javax.inject.Provider<com.msaidizi.app.core.ai.BundledModelManager>

    @Inject
    lateinit var memoryManagerProvider: javax.inject.Provider<com.msaidizi.app.core.MemoryManager>

    @Inject
    lateinit var orchestratorProvider: javax.inject.Provider<com.msaidizi.app.agent.Orchestrator>

    /** Flag: app is running in safe mode (degraded functionality). */
    @Volatile
    var safeMode: Boolean = false
        private set

    /** Human-readable reason for safe mode. */
    @Volatile
    var safeModeReason: String = ""
        private set

    // Lazy accessors — deferred construction until first access
    // Each wrapped in try/catch so failure of one doesn't crash the app
    internal val modelRegistry: ModelRegistry? by lazy {
        try { modelRegistryProvider.get() } catch (e: Throwable) {
            Timber.e(e, "ModelRegistry unavailable — safe mode")
            enterSafeMode("ModelRegistry failed: ${e.message}")
            null
        }
    }
    internal val networkMonitor: NetworkMonitor? by lazy {
        try { networkMonitorProvider.get() } catch (e: Throwable) {
            Timber.e(e, "NetworkMonitor unavailable")
            null
        }
    }
    internal val federatedLearningClient: com.msaidizi.app.core.language.FederatedLearningClient? by lazy {
        try { federatedLearningClientProvider.get() } catch (e: Throwable) {
            Timber.e(e, "FederatedLearningClient unavailable")
            null
        }
    }
    internal val syncManager: com.msaidizi.app.sync.SyncManager? by lazy {
        try { syncManagerProvider.get() } catch (e: Throwable) {
            Timber.e(e, "SyncManager unavailable")
            null
        }
    }
    internal val briefingDelivery: BriefingDelivery? by lazy {
        try { briefingDeliveryProvider.get() } catch (e: Throwable) {
            Timber.e(e, "BriefingDelivery unavailable")
            null
        }
    }
    internal val audioBriefingDelivery: com.msaidizi.app.voice.briefing.AudioBriefingDelivery? by lazy {
        try { audioBriefingDeliveryProvider.get() } catch (e: Throwable) {
            Timber.e(e, "AudioBriefingDelivery unavailable")
            null
        }
    }
    internal val bundledModelManager: com.msaidizi.app.core.ai.BundledModelManager? by lazy {
        try { bundledModelManagerProvider.get() } catch (e: Throwable) {
            Timber.e(e, "BundledModelManager unavailable")
            null
        }
    }
    internal val memoryManager: com.msaidizi.app.core.MemoryManager? by lazy {
        try { memoryManagerProvider.get() } catch (e: Throwable) {
            Timber.e(e, "MemoryManager unavailable")
            null
        }
    }
    internal val orchestrator: com.msaidizi.app.agent.Orchestrator? by lazy {
        try { orchestratorProvider.get() } catch (e: Throwable) {
            Timber.e(e, "Orchestrator unavailable")
            null
        }
    }

    /**
     * Enter safe mode — text-only input, no encryption, cloud-only LLM.
     * Safe to call multiple times; only the first reason is recorded.
     */
    fun enterSafeMode(reason: String) {
        if (!safeMode) {
            safeMode = true
            safeModeReason = reason
            Timber.w("Entering SAFE MODE: $reason")
            try { io.sentry.Sentry.captureMessage("Safe mode: $reason") } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {

        // Install crash logger BEFORE super.onCreate() — catches Hilt injection crashes
        // Note: Application object is already constructed by the framework at this point
        try {
            com.msaidizi.app.core.util.CrashLogger.install(this)
        } catch (_: Throwable) {}

        // Wrap super.onCreate() — Hilt field injection happens here
        // If any Hilt provider fails during injection, enter safe mode instead of crashing
        try {
            super.onCreate()
        } catch (e: Throwable) {
            // Hilt injection failed — enter safe mode
            Timber.e(e, "Hilt injection FAILED — entering safe mode")
            try {
                com.msaidizi.app.core.util.CrashLogger.install(this)
            } catch (_: Throwable) {}
            enterSafeMode("Hilt injection failed: ${e.message}")
            // Don't re-throw — continue in safe mode
        }



        // Initialize logging first (before any other init)
        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
        } catch (_: Throwable) {}

        // Wrap entire initialization in Throwable catch so OOM, StackOverflow,
        // and other Error subclasses don't crash the app before UI renders.
        // App starts in degraded/safe mode if init fails.
        try {
            initializeApp()
        } catch (e: Throwable) {
            Timber.e(e, "initializeApp() FAILED — entering safe mode")
            enterSafeMode("Initialization failed: ${e.message}")
            try {
                io.sentry.Sentry.captureException(e)
            } catch (_: Throwable) {}
        }

        // Register memory trim callback (safe even in degraded mode)
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

    /**
     * Full application initialization. Wrapped by onCreate() so that any failure
     * (including OOM, StackOverflow — Throwable subclasses) degrades gracefully
     * instead of crashing the app.
     */
    private fun initializeApp() {
        // Step 1: Initialize Sentry crash reporting (must be before other init)
        Timber.d("Init step: Sentry crash reporting")
        try {
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
        } catch (e: Throwable) {
            // Sentry init failure must not crash the app
            Timber.e(e, "Init step FAILED: Sentry crash reporting")
        }

        // Step 2: Register Bouncy Castle provider for Post-Quantum Cryptography
        Timber.d("Init step: BouncyCastle provider")
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 1)
                Timber.i("Bouncy Castle provider registered for PQC support")
            } else {
                // Replace Android's stripped BC with the full version
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
                Timber.i("Bouncy Castle provider replaced with PQC-capable version")
            }
        } catch (e: Throwable) {
            // BouncyCastle failure must not crash the app
            Timber.e(e, "Init step FAILED: BouncyCastle provider")
        }

        // Step 3: Detect device tier
        Timber.d("Init step: DeviceTier detection")
        try {
            DeviceTier.initialize(this)
            Timber.d("MsaidiziApp: Device tier = ${DeviceTier.current}")
        } catch (e: Throwable) {
            Timber.e(e, "Init step FAILED: DeviceTier detection")
        }
        try {
            Timber.d("MsaidiziApp: Total RAM = ${getTotalRamMB()}MB")
            Timber.d("MsaidiziApp: Available cores = ${Runtime.getRuntime().availableProcessors()}")
        } catch (e: Throwable) {
            Timber.e(e, "Init step FAILED: Device info logging")
        }

        // Step 4: Start network monitoring
        Timber.d("Init step: Network monitoring")
        try {
            networkMonitor?.startMonitoring()
        } catch (e: Throwable) {
            Timber.e(e, "Init step FAILED: Network monitoring")
        }

        // Step 4b: Explicit Orchestrator initialization (deferred from constructor)
        Timber.d("Init step: Orchestrator initialization")
        try {
            orchestrator?.initialize()
        } catch (e: Throwable) {
            Timber.e(e, "Init step FAILED: Orchestrator initialization")
        }

        // ── Deferred initialization ──────────────────────────────────────
        // Schedule non-critical work after a delay so the UI can render first
        // and WorkManager (custom Configuration.Provider) is fully ready.
        Handler(Looper.getMainLooper()).postDelayed({
            initializeDeferred()
        }, DEFERRED_INIT_DELAY_MS)
    }

    override val workManagerConfiguration: Configuration
        get() = try {
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(
                    if (BuildConfig.DEBUG) android.util.Log.DEBUG
                    else android.util.Log.ERROR
                )
                .build()
        } catch (e: Throwable) {
            Timber.e(e, "WorkManager config failed — using default")
            Configuration.Builder().build()
        }

    /**
     * Handle memory pressure by releasing non-critical resources.
     * Critical for 2GB devices where Android's LMK is aggressive.
     */
    private fun handleMemoryPressure(level: Int) {
        // Delegate to MemoryManager for systematic cleanup
        memoryManager?.onTrimMemory(level)

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
        if (modelRegistry != null && !modelRegistry!!.isTierReady(ModelTier.FIRST_LAUNCH)) {
            Timber.i("MsaidiziApp: Scheduling Tier 1 download")
            WorkManager.getInstance(this).enqueueUniqueWork(
                ModelDownloadWorker.WORK_NAME_TIER1,
                ExistingWorkPolicy.KEEP,
                ModelDownloadWorker.tier1Request()
            )
        }

        // Schedule Tier 2 download (WiFi-only, will retry until WiFi available)
        if (modelRegistry != null && !modelRegistry!!.isTierReady(ModelTier.ON_DEMAND)) {
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

    /**
     * Deferred initialization — runs ~10s after onCreate.
     * These tasks are non-critical and must not block the UI.
     * Wrapped in Throwable catch for the same resilience as initializeApp().
     */
    private fun initializeDeferred() {
        Timber.d("Deferred init: starting")

        // Step 5: Initialize federated learning with hashed device ID
        Timber.d("Init step: Federated learning")
        try {
            val deviceId = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            federatedLearningClient?.initialize(deviceId)
        } catch (e: Throwable) {
            Timber.e(e, "Init step FAILED: Federated learning")
        }

        // Step 6: Schedule tiered model downloads (uses WorkManager)
        Timber.d("Init step: Model download scheduling")
        try {
            scheduleModelDownloads()
        } catch (e: Throwable) {
            Timber.e(e, "Init step FAILED: Model download scheduling")
        }

        // Step 7: Extract bundled models from APK assets (offline-first)
        Timber.d("Init step: Bundled model initialization")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                bundledModelManager?.initialize()
                Timber.i("Bundled models initialized")
            } catch (e: Throwable) {
                Timber.e(e, "Init step FAILED: Bundled model initialization")
                try {
                    io.sentry.Sentry.captureException(e)
                } catch (_: Throwable) {}
            }
        }

        // Step 8: Schedule background update checks (silent, 24h interval)
        Timber.d("Init step: Update check scheduling")
        try {
            UpdateCheckWorker.schedule(this)
        } catch (e: Throwable) {
            Timber.e(e, "Init step FAILED: Update check scheduling")
        }

        // Step 9: Schedule daily briefing notifications (7 AM morning, 7 PM evening)
        Timber.d("Init step: Briefing notification scheduling")
        try {
            scheduleBriefingNotifications()
        } catch (e: Throwable) {
            Timber.e(e, "Init step FAILED: Briefing notification scheduling")
        }

        // Step 10: Crash Recovery — check for incomplete agent tasks
        Timber.d("Init step: Crash recovery")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val recoveryTasks = orchestrator?.recoverIncompleteTasks() ?: emptyList()
                if (recoveryTasks.isNotEmpty()) {
                    Timber.i("Crash recovery: found %d incomplete tasks", recoveryTasks.size)
                    for (rt in recoveryTasks) {
                        delay(rt.delayMs)
                        Timber.i("Recovering task %s (action=%s, phase=%s)",
                            rt.checkpoint.taskId, rt.action, rt.checkpoint.lastPhase)
                        try {
                            val inputJson = rt.checkpoint.inputJson
                            val input = com.google.gson.Gson().fromJson(
                                inputJson, Map::class.java
                            ) as? Map<*, *>
                            val text = input?.get("text") as? String
                            val language = input?.get("language") as? String ?: "sw"
                            if (text != null) {
                                orchestrator?.processInput(text, language)
                                Timber.i("Recovered task %s successfully", rt.checkpoint.taskId)
                            } else {
                                Timber.w("Cannot recover task %s: input text is null", rt.checkpoint.taskId)
                            }
                        } catch (e: Throwable) {
                            Timber.e(e, "Failed to recover task %s", rt.checkpoint.taskId)
                        }
                    }
                }
                // Cleanup old recovery data
                orchestrator?.cleanupRecoveryData()
            } catch (e: Throwable) {
                Timber.e(e, "Init step FAILED: Crash recovery")
            }
        }

        Timber.d("Deferred init: complete")
    }

    private fun getTotalRamMB(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    companion object {
        /** Delay before deferred init runs (ms). */
        private const val DEFERRED_INIT_DELAY_MS = 10_000L
    }
}
