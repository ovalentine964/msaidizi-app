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
 * - On first launch, downloads full Qwen 3.5 0.8B (~500MB) in background
 * - App works with bundled model until full model is ready
 * - WiFi-only option respects user data costs
 * - Optional: Gemma 4 E2B download for MID/HIGH tier devices
 *
 * ## Decision Council (updated 2026-07-16 — Gemma 4 E2B promoted)
 * - Bundled mini-model: unchanged (ships in APK)
 * - Full model download: Gemma 4 E2B Q4_K_M (primary text LLM)
 * - Fallback download: Qwen 3.5 0.8B Q4_K_M (for memory-constrained devices)
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
        private const val KEY_ALT_MODEL_DOWNLOADED = "alt_model_downloaded"
        private const val KEY_DOWNLOAD_WIFI_ONLY = "download_wifi_only"
        private const val KEY_FIRST_LAUNCH_COMPLETED = "first_launch_completed"
        private const val KEY_SELECTED_LANGUAGE = "selected_language"

        /** The bundled mini-model ID (shipped in APK assets) */
        const val BUNDLED_MODEL_ID = "qwen-3.5-0.8b-mini"

        /** The full model ID (downloaded post-install) — Gemma 4 E2B primary (2026-07-16) */
        const val FULL_MODEL_ID = "gemma-4-e2b-q4km"

        /** The fallback model ID — Qwen 3.5 0.8B for memory-constrained devices */
        const val ALT_MODEL_ID = "qwen-3.5-0.8b-q4km"

        /**
         * All bundled model assets to extract on first launch.
         *
         * IMPORTANT: These filenames MUST match what scripts/download-models.sh
         * downloads into app/src/main/assets/models/. If you change a filename
         * here, update the download script too.
         *
         * Model sources:
         * - Whisper ONNX: Xenova/whisper-tiny on HuggingFace
         * - Silero VAD: k2-fsa/sherpa-onnx GitHub releases
         * - Piper TTS: k2-fsa/sherpa-onnx GitHub releases (tar.bz2 extraction)
         * - Qwen LLM: bartowski/Qwen_Qwen3.5-0.8B-GGUF on HuggingFace
         */
        private val BUNDLED_ASSETS = listOf(
            "whisper-encoder-int8.onnx",
            "whisper-decoder-int8.onnx",
            "whisper-tokens.json",
            "silero_vad.onnx",
            "piper-swahili.onnx",
            "tokens.txt",
            "Qwen3.5-0.8B-Q4_K_M.gguf"
        )

        /** espeak-ng-data directory name in assets */
        private const val ESPEAK_ASSET_DIR = "espeak-ng-data"
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

    private val _altModelProgress = MutableStateFlow(0f)
    /** Progress of alternative model download (0.0 to 1.0) */
    val altModelProgress: StateFlow<Float> = _altModelProgress

    private val _downloadState = MutableStateFlow(FullModelDownloadState.NOT_STARTED)
    val downloadState: StateFlow<FullModelDownloadState> = _downloadState

    // ────────────────────── Public API ──────────────────────

    /**
     * Initialize on app start. Checks bundled model availability
     * and kicks off full model download if needed.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        Timber.i(TAG, "Initializing BundledModelManager")

        // Check if all bundled models are already extracted
        val modelsDir = File(context.filesDir, "models")
        val allExtracted = BUNDLED_ASSETS.all { asset ->
            val file = File(modelsDir, asset)
            file.exists() && file.length() > 1000
        }

        if (allExtracted) {
            Timber.i(TAG, "All bundled models already extracted")
            _bundledModelState.value = BundledModelState.READY
        } else if (isBundledModelAvailable()) {
            // Extract bundled models from APK assets
            Timber.i(TAG, "Extracting bundled models from APK assets...")
            val extracted = extractAllBundledModels()
            _bundledModelState.value = if (extracted) {
                Timber.i(TAG, "All bundled models extracted successfully")
                BundledModelState.READY
            } else {
                BundledModelState.UNAVAILABLE
            }
        } else {
            Timber.w(TAG, "Bundled models not found in assets")
            _bundledModelState.value = BundledModelState.UNAVAILABLE
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
        // Prefer full model (Qwen 3.5 0.8B)
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
     * Check if bundled models exist in APK assets.
     */
    fun isBundledModelAvailable(): Boolean {
        // Check if first model file exists in assets
        return try {
            context.assets.open("models/${BUNDLED_ASSETS.first()}").use { it.available() > 0 }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract all bundled models from APK assets to internal storage.
     * This runs once on first launch. Total extraction: ~730MB.
     *
     * @return true if all models extracted successfully
     */
    suspend fun extractAllBundledModels(): Boolean = withContext(Dispatchers.IO) {
        try {
            val outputDir = File(context.filesDir, "models")
            outputDir.mkdirs()

            var totalExtracted = 0L

            for (assetName in BUNDLED_ASSETS) {
                val outputFile = File(outputDir, assetName)
                if (outputFile.exists() && outputFile.length() > 1000) {
                    Timber.d(TAG, "Already extracted: %s", assetName)
                    totalExtracted += outputFile.length()
                    continue
                }

                Timber.i(TAG, "Extracting %s from assets...", assetName)
                context.assets.open("models/$assetName").use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 65536)  // 64KB buffer for large files
                    }
                }
                totalExtracted += outputFile.length()
                Timber.i(TAG, "Extracted %s (%d MB)", assetName, outputFile.length() / (1024 * 1024))
            }

            // Extract espeak-ng-data directory (for Piper TTS phoneme mapping)
            // Extracted from sherpa-onnx Piper tar.bz2 by download-models.sh
            val espeakDir = File(outputDir, ESPEAK_ASSET_DIR)
            if (!espeakDir.exists() || espeakDir.listFiles()?.isEmpty() != false) {
                Timber.i(TAG, "Extracting espeak-ng-data from assets...")
                extractAssetDirectory(ESPEAK_ASSET_DIR, outputDir)
            } else {
                Timber.d(TAG, "espeak-ng-data already extracted")
            }

            Timber.i(TAG, "All bundled models extracted: %d MB total", totalExtracted / (1024 * 1024))
            _bundledModelState.value = BundledModelState.READY
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract bundled models")
            _bundledModelState.value = BundledModelState.UNAVAILABLE
            false
        }
    }

    /**
     * Extract a directory of assets recursively.
     */
    private fun extractAssetDirectory(assetPath: String, outputDir: File) {
        val files = context.assets.list(assetPath) ?: return
        val targetDir = File(outputDir, assetPath)
        targetDir.mkdirs()

        for (file in files) {
            val subPath = "$assetPath/$file"
            val subFiles = context.assets.list(subPath)
            if (subFiles != null && subFiles.isNotEmpty()) {
                // It's a directory
                extractAssetDirectory(subPath, outputDir)
            } else {
                // It's a file
                val outputFile = File(targetDir, file)
                if (!outputFile.exists()) {
                    context.assets.open(subPath).use { input ->
                        outputFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    }
                }
            }
        }
    }

    /**
     * Extract the bundled model from assets to internal storage.
     * Called lazily on first inference attempt.
     */
    suspend fun extractBundledModel(): Boolean = withContext(Dispatchers.IO) {
        extractAllBundledModels()
    }

    /**
     * Check if the full model (Qwen 3.5 0.8B) has been downloaded.
     */
    fun isFullModelDownloaded(): Boolean {
        return prefs.getBoolean(KEY_FULL_MODEL_DOWNLOADED, false) ||
               modelRegistry.isModelReady(FULL_MODEL_ID)
    }

    /**
     * Check if the alternative model (Gemma 4 E2B) has been downloaded.
     */
    fun isAltModelDownloaded(): Boolean {
        return prefs.getBoolean(KEY_ALT_MODEL_DOWNLOADED, false) ||
               modelRegistry.isModelReady(ALT_MODEL_ID)
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
     * Start background download of the full model (Qwen 3.5 0.8B Q4_K_M).
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
                    Timber.i(TAG, "Full model (Qwen 3.5 0.8B) downloaded successfully")
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

    /**
     * Start background download of the alternative model (Gemma 4 E2B Q4_K_M).
     * Only available for MID/HIGH tier devices.
     *
     * @param deviceTier The current device tier (from ModelManager)
     */
    fun startAltModelDownload(deviceTier: ModelManager.DeviceTier) {
        if (deviceTier == ModelManager.DeviceTier.LOW) {
            Timber.w(TAG, "Alt model (Gemma 4 E2B) not available for LOW-tier devices")
            return
        }

        scope.launch {
            try {
                val progressJob = launch {
                    modelDownloader.downloadProgress.collect { progressMap ->
                        val p = progressMap[ALT_MODEL_ID] ?: 0f
                        _altModelProgress.value = p
                    }
                }

                val success = modelDownloader.downloadModel(
                    modelId = ALT_MODEL_ID,
                    forceNetwork = !isWifiOnlyDownload()
                )

                progressJob.cancel()

                if (success) {
                    prefs.edit().putBoolean(KEY_ALT_MODEL_DOWNLOADED, true).apply()
                    _altModelProgress.value = 1f
                    Timber.i(TAG, "Alt model (Gemma 4 E2B) downloaded successfully")
                } else {
                    Timber.i(TAG, "Alt model download deferred (waiting for WiFi)")
                }
            } catch (e: Exception) {
                Timber.e(e, "Alt model download failed")
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
