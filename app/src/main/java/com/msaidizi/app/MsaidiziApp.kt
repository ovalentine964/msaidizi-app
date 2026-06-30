package com.msaidizi.app

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.msaidizi.app.core.util.DeviceTier
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
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

    override fun onCreate() {
        super.onCreate()

        // Initialize logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Detect device tier
        DeviceTier.initialize(this)

        Timber.d("MsaidiziApp: Device tier = ${DeviceTier.current}")
        Timber.d("MsaidiziApp: Total RAM = ${getTotalRamMB()}MB")
        Timber.d("MsaidiziApp: Available cores = ${Runtime.getRuntime().availableProcessors()}")

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
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Timber.d("Memory pressure: UI hidden — releasing UI caches")
                // Release UI caches, image caches
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Timber.w("Memory pressure: moderate — releasing non-essential caches")
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Timber.w("Memory pressure: low — releasing ASR/TTS models if idle")
                // VoicePipeline will handle model release
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Timber.e("Memory pressure: CRITICAL — emergency cleanup")
                // Release all models, keep only DB and foreground state
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Timber.e("Memory pressure: COMPLETE — app may be killed soon")
                // Save all state, prepare for process death
            }
        }
    }

    private fun getTotalRamMB(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }
}
