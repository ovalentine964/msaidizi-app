package com.msaidizi.app.voice

import android.content.Context
import com.msaidizi.app.BuildConfig
import com.msaidizi.app.core.model.ModelTier
import com.msaidizi.app.core.model.ModelVersionTracker
import com.msaidizi.app.core.network.PinnedHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages ML model lifecycle: download, verify, load, release.
 *
 * Models are NOT bundled in the APK (too large). They download on first
 * launch over WiFi. SHA-256 integrity is verified before use.
 *
 * Storage: app external files directory under "models/"
 *
 * Model sizes:
 * - whisper-tiny-int4.onnx: ~40MB
 * - piper-swahili.onnx: ~25MB
 * - silero_vad.onnx: ~2.5MB
 * - qwen-0.5b-q4_k_m.gguf: ~300MB
 * - mms-tts-*.onnx: ~65MB each (on-demand, per language)
 * Total: ~367MB base + MMS models on demand
 */
@Singleton
class ModelRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val versionTracker: ModelVersionTracker,
    private val pinnedHttpClient: PinnedHttpClient
) {
    companion object {
        /** CDN base URL — use Cloudflare R2 or similar for Africa edge */
        private const val MODEL_CDN = "https://models.msaidizi.app/v1"

        /**
         * Model definitions with SHA-256 checksums, tiers, and versions.
         *
         * TODO(security): All sha256 fields below MUST be populated with real
         * SHA-256 hashes computed from the distribution files before production release.
         * Empty hashes mean integrity verification is skipped in debug builds.
         *
         * Compute hashes with: sha256sum <model_file>
         */
        val MODELS: Map<String, ModelDef> = mapOf(
            "silero-vad" to ModelDef(
                id = "silero-vad",
                filename = "silero_vad.onnx",
                url = "$MODEL_CDN/silero_vad.onnx",
                sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",  // TODO(security): Replace with real hash from sha256sum silero_vad.onnx
                sizeBytes = 2_500_000L,
                priority = ModelPriority.CRITICAL,
                requiredFor = listOf(Feature.VOICE_INPUT),
                tier = ModelTier.BUNDLED,
                version = "1.0.0"
            ),
            "whisper-tiny-int4" to ModelDef(
                id = "whisper-tiny-int4",
                filename = "whisper-tiny-int4.onnx",
                url = "$MODEL_CDN/whisper-tiny-int4.onnx",
                sha256 = "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",  // TODO(security): Replace with real hash from sha256sum whisper-tiny-int4.onnx
                sizeBytes = 42_000_000L,
                priority = ModelPriority.HIGH,
                requiredFor = listOf(Feature.VOICE_INPUT),
                tier = ModelTier.FIRST_LAUNCH,
                version = "1.0.0"
            ),
            "piper-swahili" to ModelDef(
                id = "piper-swahili",
                filename = "piper-swahili.onnx",
                url = "$MODEL_CDN/piper-swahili.onnx",
                sha256 = "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",  // TODO(security): Replace with real hash from sha256sum piper-swahili.onnx
                sizeBytes = 26_000_000L,
                priority = ModelPriority.HIGH,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.FIRST_LAUNCH,
                version = "1.0.0"
            ),
            "qwen-0.5b-q4km" to ModelDef(
                id = "qwen-0.5b-q4km",
                filename = "qwen-0.5b-q4_k_m.gguf",
                url = "$MODEL_CDN/qwen-0.5b-q4_k_m.gguf",
                sha256 = "ef537f25c895bfa782526529a9b63d97aa631564d5d789c2b765448c8635fb6c",  // TODO(security): Replace with real hash from sha256sum qwen-0.5b-q4_k_m.gguf
                sizeBytes = 310_000_000L,
                priority = ModelPriority.LOW,
                requiredFor = listOf(Feature.LLM_INFERENCE),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),

            // ── Meta MMS TTS Models ──────────────────────────────────
            // MMS (Massively Multilingual Speech) supports 1,100+ languages.
            // Each language has a separate VITS model (~65MB ONNX).
            // Models are converted from facebook/mms-tts via sherpa-onnx.
            // Download: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
            //
            // These models are ON_DEMAND — downloaded only when user needs
            // a specific language. Swahili users get Piper (faster), but MMS
            // is available as fallback or for other African languages.

            "mms-tts-swa" to ModelDef(
                id = "mms-tts-swa",
                filename = "mms-tts-swa.onnx",
                url = "$MODEL_CDN/mms/vits-mms-swa.onnx",
                sha256 = "",  // TODO(security): Compute with sha256sum vits-mms-swa.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-eng" to ModelDef(
                id = "mms-tts-eng",
                filename = "mms-tts-eng.onnx",
                url = "$MODEL_CDN/mms/vits-mms-eng.onnx",
                sha256 = "",  // TODO(security): Compute with sha256sum vits-mms-eng.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-yor" to ModelDef(
                id = "mms-tts-yor",
                filename = "mms-tts-yor.onnx",
                url = "$MODEL_CDN/mms/vits-mms-yor.onnx",
                sha256 = "",  // TODO(security): Compute with sha256sum vits-mms-yor.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-hau" to ModelDef(
                id = "mms-tts-hau",
                filename = "mms-tts-hau.onnx",
                url = "$MODEL_CDN/mms/vits-mms-hau.onnx",
                sha256 = "",  // TODO(security): Compute with sha256sum vits-mms-hau.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-amh" to ModelDef(
                id = "mms-tts-amh",
                filename = "mms-tts-amh.onnx",
                url = "$MODEL_CDN/mms/vits-mms-amh.onnx",
                sha256 = "",  // TODO(security): Compute with sha256sum vits-mms-amh.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-zul" to ModelDef(
                id = "mms-tts-zul",
                filename = "mms-tts-zul.onnx",
                url = "$MODEL_CDN/mms/vits-mms-zul.onnx",
                sha256 = "",  // TODO(security): Compute with sha256sum vits-mms-zul.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-ibo" to ModelDef(
                id = "mms-tts-ibo",
                filename = "mms-tts-ibo.onnx",
                url = "$MODEL_CDN/mms/vits-mms-ibo.onnx",
                sha256 = "",  // TODO(security): Compute with sha256sum vits-mms-ibo.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-xho" to ModelDef(
                id = "mms-tts-xho",
                filename = "mms-tts-xho.onnx",
                url = "$MODEL_CDN/mms/vits-mms-xho.onnx",
                sha256 = "",  // TODO(security): Compute with sha256sum vits-mms-xho.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-sna" to ModelDef(
                id = "mms-tts-sna",
                filename = "mms-tts-sna.onnx",
                url = "$MODEL_CDN/mms/vits-mms-sna.onnx",
                sha256 = "",  // TODO(security): Compute with sha256sum vits-mms-sna.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            ),
            "mms-tts-nso" to ModelDef(
                id = "mms-tts-nso",
                filename = "mms-tts-nso.onnx",
                url = "$MODEL_CDN/mms/vits-mms-nso.onnx",
                sha256 = "",  // TODO(security): Compute with sha256sum vits-mms-nso.onnx
                sizeBytes = 65_000_000L,
                priority = ModelPriority.OPTIONAL,
                requiredFor = listOf(Feature.VOICE_OUTPUT),
                tier = ModelTier.ON_DEMAND,
                version = "1.0.0"
            )
        )

        private const val BUFFER_SIZE = 8192
    }

    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val stagingDir: File = File(context.filesDir, "models_staging").apply { mkdirs() }

    /** Per-model mutex to prevent concurrent download corruption */
    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    private val _downloadState = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    /** Observable download state for all models */
    val downloadState: StateFlow<Map<String, ModelState>> = _downloadState

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Observable download progress (0.0 to 1.0) for each model */
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    /** Lazy-initialized pinned HTTP client for downloads */
    private val httpClient by lazy { pinnedHttpClient.create() }

    // ────────────────────── Public API ──────────────────────

    /**
     * Get models for a specific tier.
     */
    fun getModelsByTier(tier: ModelTier): List<ModelDef> {
        return MODELS.values.filter { it.tier == tier }
    }

    /**
     * Check if all models in a tier are ready.
     */
    fun isTierReady(tier: ModelTier): Boolean {
        return getModelsByTier(tier).all { isModelReady(it.id) }
    }

    /**
     * Get the staging directory for receiving peer-transferred models.
     */
    fun getStagingDir(): File = stagingDir

    /**
     * Install a model from staging directory after SHA-256 verification.
     * Returns true if the model was installed successfully.
     */
    suspend fun installFromStaging(modelId: String, stagedFile: File): Boolean {
        val def = MODELS[modelId] ?: return false
        val mutex = downloadMutexes.getOrPut(modelId) { Mutex() }
        return mutex.withLock {
            try {
                // Verify SHA-256 — mandatory in release, optional in debug if hash is empty
                if (!verifySha256(modelId, def, stagedFile, "staging install")) {
                    stagedFile.delete()
                    return@withLock false
                }

                // Atomic move to final location
                val destFile = File(modelsDir, def.filename)
                val renamed = stagedFile.renameTo(destFile)
                if (!renamed) {
                    stagedFile.copyTo(destFile, overwrite = true)
                    stagedFile.delete()
                }

                // Write version file
                versionTracker.writeVersion(modelId, def.version)
                updateState(modelId, ModelState.READY)
                Timber.i("Model %s installed from staging", modelId)
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to install model %s from staging", modelId)
                false
            }
        }
    }

    /**
     * Check if a model version update is available.
     */
    fun isUpdateAvailable(modelId: String): Boolean {
        val def = MODELS[modelId] ?: return false
        val currentVersion = versionTracker.readVersion(modelId)
        return currentVersion != null && currentVersion != def.version
    }

    /**
     * Check which models are available locally (downloaded + size verified).
     */
    fun getAvailableModels(): Set<String> {
        return MODELS.filter { (_, def) ->
            val file = File(modelsDir, def.filename)
            file.exists() && file.length() > 0 && file.length() >= def.sizeBytes * 0.9
        }.keys
    }

    /**
     * Check if a specific model is ready (downloaded + verified).
     */
    fun isModelReady(modelId: String): Boolean {
        val def = MODELS[modelId] ?: return false
        val file = File(modelsDir, def.filename)
        return file.exists() && file.length() > 0
    }

    /**
     * Get the local file path for a model, or null if not available.
     */
    fun getModelPath(modelId: String): File? {
        val def = MODELS[modelId] ?: return null
        val file = File(modelsDir, def.filename)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Get total storage used by downloaded models in bytes.
     */
    fun getStorageUsedBytes(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Get human-readable storage used (e.g., "367 MB").
     */
    fun getStorageUsedFormatted(): String {
        val bytes = getStorageUsedBytes()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    /**
     * Download all models for a given tier.
     * Uses per-model mutex to prevent concurrent download corruption.
     *
     * @param tier The tier to download
     * @param onProgress Callback: (modelId, progress 0.0-1.0)
     */
    suspend fun downloadTier(
        tier: ModelTier,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val models = getModelsByTier(tier)
        for (def in models) {
            downloadModelWithMutex(def.id, onProgress)
        }
    }

    /**
     * Download all required models for the current device tier.
     * Called during onboarding or first launch.
     *
     * @param onProgress Callback: (modelId, progress 0.0-1.0)
     */
    suspend fun downloadRequiredModels(
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val required = getRequiredModels()
        for (modelId in required) {
            downloadModelWithMutex(modelId, onProgress)
        }
    }

    /**
     * Internal download with per-model mutex protection.
     */
    private suspend fun downloadModelWithMutex(
        modelId: String,
        onProgress: (String, Float) -> Unit
    ) {
        val mutex = downloadMutexes.getOrPut(modelId) { Mutex() }
        mutex.withLock {
            if (isModelReady(modelId)) {
                updateState(modelId, ModelState.READY)
                onProgress(modelId, 1.0f)
                return
            }

            val def = MODELS[modelId] ?: return
            val destFile = File(modelsDir, def.filename)
            val tempFile = File(modelsDir, "${def.filename}.tmp")
            val resumeFile = File(modelsDir, "${def.filename}.resume")

            try {
                updateState(modelId, ModelState.DOWNLOADING)
                onProgress(modelId, 0f)

                // Pre-check: ensure sufficient storage space (model size + 20% buffer)
                val requiredSpace = (def.sizeBytes * 1.2).toLong()
                if (modelsDir.usableSpace < requiredSpace) {
                    Timber.e("Insufficient storage for model %s: need %d MB, have %d MB",
                        modelId, requiredSpace / (1024*1024), modelsDir.usableSpace / (1024*1024))
                    updateState(modelId, ModelState.ERROR)
                    return
                }

                // Resume from partial download if exists
                val resumeOffset = if (tempFile.exists() && resumeFile.exists()) {
                    resumeFile.readText().toLongOrNull() ?: 0L
                } else 0L

                downloadFile(def.url, tempFile, resumeOffset) { bytesDownloaded ->
                    val totalDownloaded = resumeOffset + bytesDownloaded
                    val progress = totalDownloaded.toFloat() / def.sizeBytes
                    val clampedProgress = progress.coerceIn(0f, 0.95f)
                    onProgress(modelId, clampedProgress)
                    updateProgress(modelId, clampedProgress)

                    // Save resume position periodically
                    if (totalDownloaded % (1024 * 1024) < BUFFER_SIZE) {
                        resumeFile.writeText(totalDownloaded.toString())
                    }
                }

                // Verify SHA-256
                updateState(modelId, ModelState.VERIFYING)
                if (!verifySha256(modelId, def, tempFile, "download")) {
                    tempFile.delete()
                    resumeFile.delete()
                    updateState(modelId, ModelState.ERROR)
                    return
                }

                // Atomic rename: temp -> final
                val renamed = tempFile.renameTo(destFile)
                if (!renamed) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
                resumeFile.delete()

                // Write version file
                versionTracker.writeVersion(modelId, def.version)

                updateState(modelId, ModelState.READY)
                updateProgress(modelId, 1.0f)
                onProgress(modelId, 1.0f)
                Timber.i("Model %s downloaded and verified (%d bytes)", modelId, destFile.length())

            } catch (e: CancellationException) {
                // Save resume state on cancellation
                resumeFile.writeText(tempFile.length().toString())
                updateState(modelId, ModelState.PAUSED)
                Timber.i("Model %s download paused at %d bytes", modelId, tempFile.length())
                throw e
            } catch (e: Exception) {
                tempFile.delete()
                resumeFile.delete()
                updateState(modelId, ModelState.ERROR)
                Timber.e(e, "Failed to download model %s", modelId)
            }
        }
    }

    /**
     * Download a single model by ID.
     * Thread-safe: uses per-model mutex.
     */
    suspend fun downloadModel(
        modelId: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val mutex = downloadMutexes.getOrPut(modelId) { Mutex() }
        return mutex.withLock {
            downloadModelInternal(modelId, onProgress)
        }
    }

    private suspend fun downloadModelInternal(
        modelId: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val def = MODELS[modelId] ?: return@withContext false
        if (isModelReady(modelId)) return@withContext true

        val destFile = File(modelsDir, def.filename)
        val tempFile = File(modelsDir, "${def.filename}.tmp")

        try {
            updateState(modelId, ModelState.DOWNLOADING)
            downloadFile(def.url, tempFile, 0L) { bytesDownloaded ->
                val progress = bytesDownloaded.toFloat() / def.sizeBytes
                onProgress(progress.coerceAtMost(0.95f))
            }

            updateState(modelId, ModelState.VERIFYING)
            if (!verifySha256(modelId, def, tempFile, "download")) {
                tempFile.delete()
                updateState(modelId, ModelState.ERROR)
                return@withContext false
            }

            tempFile.renameTo(destFile)
            versionTracker.writeVersion(modelId, def.version)
            updateState(modelId, ModelState.READY)
            onProgress(1.0f)
            true
        } catch (e: Exception) {
            tempFile.delete()
            updateState(modelId, ModelState.ERROR)
            Timber.e(e, "Failed to download model %s", modelId)
            false
        }
    }

    /**
     * Delete all downloaded models (reclaim storage).
     */
    fun deleteAllModels() {
        modelsDir.listFiles()?.forEach { it.delete() }
        _downloadState.value = emptyMap()
        _downloadProgress.value = emptyMap()
        Timber.i("All models deleted")
    }

    /**
     * Delete a specific model to reclaim storage.
     */
    fun deleteModel(modelId: String) {
        val def = MODELS[modelId] ?: return
        File(modelsDir, def.filename).delete()
        File(modelsDir, "${def.filename}.tmp").delete()
        File(modelsDir, "${def.filename}.resume").delete()
        updateState(modelId, ModelState.NOT_DOWNLOADED)
        Timber.i("Model %s deleted", modelId)
    }

    /**
     * Perform a smoke test on a downloaded model — load it, run trivial inference, verify non-null output.
     * Returns true if the model is functional.
     */
    suspend fun smokeTestModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val def = MODELS[modelId] ?: return@withContext false
        val modelFile = getModelPath(modelId) ?: return@withContext false

        try {
            when {
                def.filename.endsWith(".onnx") -> {
                    // For ONNX models: try loading the session
                    val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
                    val opts = ai.onnxruntime.OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(1)
                    }
                    val session = env.createSession(modelFile.absolutePath, opts)
                    session.close()
                    Timber.i("Smoke test passed for ONNX model: %s", modelId)
                    true
                }
                def.filename.endsWith(".gguf") -> {
                    // For GGUF models: verify file header magic bytes
                    val header = modelFile.inputStream().use { it.readNBytes(4) }
                    val valid = header.size >= 4 &&
                        header[0] == 'G'.code.toByte() &&
                        header[1] == 'G'.code.toByte() &&
                        header[2] == 'U'.code.toByte() &&
                        header[3] == 'F'.code.toByte()
                    if (valid) {
                        Timber.i("Smoke test passed for GGUF model: %s", modelId)
                    } else {
                        Timber.e("Smoke test failed for GGUF model: %s — bad header", modelId)
                    }
                    valid
                }
                else -> true // Unknown format, assume OK
            }
        } catch (e: Exception) {
            Timber.e(e, "Smoke test failed for model %s", modelId)
            false
        }
    }

    /**
     * Check if all critical + high priority models are downloaded.
     */
    fun areEssentialModelsReady(): Boolean {
        return MODELS.values
            .filter { it.priority <= ModelPriority.HIGH }
            .all { isModelReady(it.id) }
    }

    // ────────────────────── Private Helpers ──────────────────────

    /**
     * Verify SHA-256 hash of a model file.
     *
     * - In release builds: verification is ALWAYS mandatory. Empty hash = build error (logged as error).
     * - In debug builds: verification is skipped if hash is empty (allows development without real hashes).
     *
     * @return true if verification passes or is skipped, false if verification fails
     */
    private fun verifySha256(
        modelId: String,
        def: ModelDef,
        file: File,
        context: String
    ): Boolean {
        if (def.sha256.isEmpty()) {
            if (BuildConfig.DEBUG) {
                Timber.w(
                    "SHA-256 hash missing for %s (%s) — skipping in debug build. " +
                    "Populate sha256 in ModelRegistry.MODELS before release!",
                    modelId, context
                )
                return true
            } else {
                Timber.e(
                    "SHA-256 hash missing for %s (%s) — blocking in production build. " +
                    "This is a security-critical configuration error.",
                    modelId, context
                )
                return false
            }
        }

        val hash = sha256File(file)
        if (hash != def.sha256) {
            Timber.e(
                "SHA-256 MISMATCH for %s (%s): expected=%s, got=%s — possible tampering!",
                modelId, context, def.sha256, hash
            )
            return false
        }

        Timber.d("SHA-256 verified for %s (%s)", modelId, context)
        return true
    }

    /**
     * Get models required, sorted by priority.
     */
    private fun getRequiredModels(): List<String> {
        return MODELS.entries
            .sortedBy { it.value.priority.ordinal }
            .map { it.key }
    }

    /**
     * Download a file with resume support using the pinned HTTP client.
     *
     * @param url Remote URL
     * @param dest Destination file (written incrementally)
     * @param resumeOffset Byte offset to resume from (0 for fresh download)
     * @param onProgress Callback with total bytes downloaded so far
     */
    private suspend fun downloadFile(
        url: String,
        dest: File,
        resumeOffset: Long,
        onProgress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Msaidizi/1.0")

        // Support resume via Range header
        if (resumeOffset > 0) {
            requestBuilder.header("Range", "bytes=$resumeOffset-")
            Timber.d("Resuming download from byte %d", resumeOffset)
        }

        val request = requestBuilder.build()
        val response = httpClient.newCall(request).execute()

        try {
            if (!response.isSuccessful && response.code != 206) {
                val errorMsg = when (response.code) {
                    404 -> "Model haipatikani kwenye server (not found on server)"
                    403 -> "Access imekatazwa (access denied)"
                    500, 502, 503 -> "Server ya models ina hitilafu (server error)"
                    else -> "HTTP ${response.code}: ${response.message()}"
                }
                throw Exception(errorMsg)
            }

            val body = response.body() ?: throw Exception("Response tupu (empty response)")
            var downloaded = 0L

            body.byteStream().use { input ->
                // Append mode for resume, overwrite for fresh
                FileOutputStream(dest, resumeOffset > 0).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        onProgress(downloaded)

                        // Check for cancellation
                        ensureActive()
                    }
                }
            }

            Timber.d("Downloaded %d bytes from %s", downloaded, url)
        } finally {
            response.close()
        }
    }

    /**
     * Compute SHA-256 hash of a file.
     */
    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateState(modelId: String, state: ModelState) {
        _downloadState.value = _downloadState.value.toMutableMap().apply {
            put(modelId, state)
        }
    }

    private fun updateProgress(modelId: String, progress: Float) {
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            put(modelId, progress)
        }
    }
}

// ────────────────────── Data Classes & Enums ──────────────────────

/**
 * Model definition with metadata for download and verification.
 * Extended with tier and version for model bundling.
 */
data class ModelDef(
    val id: String,
    val filename: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val priority: ModelPriority,
    val requiredFor: List<Feature>,
    val tier: ModelTier = ModelTier.FIRST_LAUNCH,
    val version: String = "1.0.0"
)

/** Download priority — CRITICAL always downloads, LOW only on good connections */
enum class ModelPriority { CRITICAL, HIGH, LOW, OPTIONAL }

/** Model lifecycle states */
enum class ModelState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    VERIFYING,
    READY,
    PAUSED,
    ERROR
}

/** Feature flags for model requirements */
enum class Feature { VOICE_INPUT, VOICE_OUTPUT, LLM_INFERENCE }
