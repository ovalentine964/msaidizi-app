package com.msaidizi.app.core.ai

import android.content.Context
import android.content.SharedPreferences
import com.msaidizi.app.voice.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the bundled mini-model for instant first-launch functionality
 * and coordinates background download of the full model.
 *
 * ## Strategy
 * - APK ships with a tiny bundled model (~10MB) for immediate basic AI
 * - On first launch, downloads full Qwen 0.5B (~300MB) in background
 * - App works with bundled model until full model is ready
 * - WiFi-only option respects user data costs
 *
 * ## Valentine's Mum Test
 * She downloads → installs → opens → says "Habari" → it works.
 * No "download model" screen. No waiting. Just works.
 */
@Singleton
class BundledModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRegistry: ModelRegistry,
    private val modelDownloader: ModelDownloader
) {
    companion object {
        private const val TAG = "BundledModelManager"
        private const val PREFS_NAME = "bundled_model_prefs"
        private const val KEY_FULL_MODEL_DOWNLOADED = "full_model_downloaded"
        private const val KEY_DOWNLOAD_WIFI_ONLY = "download_wifi_only"
        private const val KEY_FIRST_LAUNCH_COMPLETED = "first_launch_completed"
        private const val KEY_SELECTED_LANGUAGE = "selected_language"

        /** The bundled mini-model ID (shipped in APK assets) */
        const val BUNDLED_MODEL_ID = "qwen-0.5b-mini"

        /** The full model ID (downloaded post-install) */
        const val FULL_MODEL_ID = "qwen-0.5b-q4km"
    }

    /** Coroutine scope for background downloads */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ────────────────────── State ──────────────────────

    private val _bundledModelState = MutableStateFlow(BundledModelState.CHECKING)
    val bundledModelState: StateFlow<BundledModelState> = _bundledModelState

    private val _fullModelProgress = MutableStateFlow(0f)
    /** Progress of full model download (0.0 to 1.0) */
    val fullModelProgress: StateFlow<Float> = _fullModelProgress

    private val _downloadState = MutableStateFlow(FullModelDownloadState.NOT_STARTED)
    val downloadState: StateFlow<FullModelDownloadState> = _downloadState

    // ────────────────────── Public API ──────────────────────

    /**
     * Initialize on app start. Checks bundled model availability
     * and kicks off full model download if needed.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        Timber.i(TAG, "Initializing BundledModelManager")

        // Check if bundled model exists in assets
        val bundledReady = isBundledModelAvailable()
        _bundledModelState.value = if (bundledReady) {
            Timber.i(TAG, "Bundled mini-model available — app can work immediately")
            BundledModelState.READY
        } else {
            Timber.w(TAG, "Bundled model not found — checking full model")
            if (modelRegistry.isModelReady(FULL_MODEL_ID)) {
                BundledModelState.FULL_MODEL_READY
            } else {
                BundledModelState.UNAVAILABLE
            }
        }

        // If full model not downloaded, start background download
        if (!isFullModelDownloaded() && !modelRegistry.isModelReady(FULL_MODEL_ID)) {
            Timber.i(TAG, "Full model not downloaded — scheduling background download")
            startFullModelDownload()
        }
    }

    /**
     * Check if the app has any usable model (bundled or full).
     * This is the key method: if true, the app can do AI inference.
     */
    fun hasUsableModel(): Boolean {
        return isBundledModelAvailable() ||
               modelRegistry.isModelReady(FULL_MODEL_ID) ||
               modelRegistry.isModelReady(BUNDLED_MODEL_ID)
    }

    /**
     * Get the path to the best available model.
     * Prefers full model, falls back to bundled.
     */
    fun getBestModelPath(): File? {
        // Prefer full model
        val fullPath = modelRegistry.getModelPath(FULL_MODEL_ID)
        if (fullPath != null && fullPath.exists()) return fullPath

        // Fall back to bundled
        val bundledPath = modelRegistry.getModelPath(BUNDLED_MODEL_ID)
        if (bundledPath != null && bundledPath.exists()) return bundledPath

        // Check assets for bundled model
        val assetModel = File(context.filesDir, "models/bundled_qwen_mini.gguf")
        if (assetModel.exists()) return assetModel

        return null
    }

    /**
     * Check if the bundled mini-model is available (in assets or extracted).
     */
    fun isBundledModelAvailable(): Boolean {
        // Check if already extracted
        val extracted = File(context.filesDir, "models/bundled_qwen_mini.gguf")
        if (extracted.exists() && extracted.length() > 1_000_000) return true

        // Check if it's in assets
        return try {
            context.assets.open("models/bundled_qwen_mini.gguf").use { it.available() > 0 }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract the bundled model from assets to internal storage.
     * Called lazily on first inference attempt.
     */
    suspend fun extractBundledModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val outputDir = File(context.filesDir, "models")
            outputDir.mkdirs()
            val outputFile = File(outputDir, "bundled_qwen_mini.gguf")

            if (outputFile.exists() && outputFile.length() > 1_000_000) {
                Timber.d(TAG, "Bundled model already extracted")
                return@withContext true
            }

            Timber.i(TAG, "Extracting bundled mini-model from assets...")
            context.assets.open("models/bundled_qwen_mini.gguf").use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            Timber.i(TAG, "Bundled model extracted: ${outputFile.length() / (1024 * 1024)}MB")
            _bundledModelState.value = BundledModelState.READY
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract bundled model")
            _bundledModelState.value = BundledModelState.UNAVAILABLE
            false
        }
    }

    /**
     * Check if the full model has been downloaded.
     */
    fun isFullModelDownloaded(): Boolean {
        return prefs.getBoolean(KEY_FULL_MODEL_DOWNLOADED, false) ||
               modelRegistry.isModelReady(FULL_MODEL_ID)
    }

    /**
     * Set WiFi-only preference for background downloads.
     */
    fun setWifiOnlyDownload(wifiOnly: Boolean) {
        prefs.edit().putBoolean(KEY_DOWNLOAD_WIFI_ONLY, wifiOnly).apply()
    }

    /**
     * Get WiFi-only preference.
     */
    fun isWifiOnlyDownload(): Boolean {
        // Default to false — target users (mama mboga, boda boda) have no WiFi.
        // They use Safaricom data bundles. Blocking on WiFi = blocking the app.
        return prefs.getBoolean(KEY_DOWNLOAD_WIFI_ONLY, false)
    }

    /**
     * Save selected language.
     */
    fun setSelectedLanguage(languageCode: String) {
        prefs.edit().putString(KEY_SELECTED_LANGUAGE, languageCode).apply()
    }

    /**
     * Get selected language.
     */
    fun getSelectedLanguage(): String {
        return prefs.getString(KEY_SELECTED_LANGUAGE, "sw") ?: "sw"
    }

    /**
     * Mark first launch as completed.
     */
    fun markFirstLaunchCompleted() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_COMPLETED, true).apply()
    }

    /**
     * Check if first launch has been completed.
     */
    fun isFirstLaunchCompleted(): Boolean {
        return prefs.getBoolean(KEY_FIRST_LAUNCH_COMPLETED, false)
    }

    // ────────────────────── Private ──────────────────────

    /**
     * Start background download of the full model.
     */
    private fun startFullModelDownload() {
        _downloadState.value = FullModelDownloadState.DOWNLOADING

        scope.launch {
            try {
                // Observe download progress
                val progressJob = launch {
                    modelDownloader.downloadProgress.collect { progressMap ->
                        val p = progressMap[FULL_MODEL_ID] ?: 0f
                        _fullModelProgress.value = p
                    }
                }

                // Start the download (WiFi-only by default)
                val success = modelDownloader.downloadModel(
                    modelId = FULL_MODEL_ID,
                    forceNetwork = !isWifiOnlyDownload()
                )

                progressJob.cancel()

                if (success) {
                    prefs.edit().putBoolean(KEY_FULL_MODEL_DOWNLOADED, true).apply()
                    _downloadState.value = FullModelDownloadState.COMPLETED
                    _fullModelProgress.value = 1f
                    Timber.i(TAG, "Full model downloaded successfully")
                } else {
                    _downloadState.value = FullModelDownloadState.WAITING_FOR_WIFI
                    Timber.i(TAG, "Full model download deferred (waiting for WiFi)")
                }
            } catch (e: Exception) {
                Timber.e(e, "Full model download failed")
                _downloadState.value = FullModelDownloadState.FAILED
            }
        }
    }
}

// ────────────────────── Enums ──────────────────────

/**
 * State of the bundled mini-model.
 */
enum class BundledModelState {
    /** Checking if bundled model exists */
    CHECKING,
    /** Bundled model available — app works immediately */
    READY,
    /** Full model already downloaded — bundled not needed */
    FULL_MODEL_READY,
    /** No model available */
    UNAVAILABLE
}

/**
 * State of the full model background download.
 */
enum class FullModelDownloadState {
    NOT_STARTED,
    DOWNLOADING,
    WAITING_FOR_WIFI,
    COMPLETED,
    FAILED
}
