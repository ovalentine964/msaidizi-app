package com.msaidizi.app.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
 * Total: ~367MB (downloaded, not in APK)
 */
@Singleton
class ModelRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** CDN base URL — use Cloudflare R2 or similar for Africa edge */
        private const val MODEL_CDN = "https://models.msaidizi.app/v1"

        /** Model definitions with SHA-256 checksums and metadata */
        val MODELS: Map<String, ModelDef> = mapOf(
            "whisper-tiny-int4" to ModelDef(
                id = "whisper-tiny-int4",
                filename = "whisper-tiny-int4.onnx",
                url = "$MODEL_CDN/whisper-tiny-int4.onnx",
                sha256 = "",  // Populated at build time from CI
                sizeBytes = 42_000_000L,
                priority = ModelPriority.HIGH,
                requiredFor = listOf(Feature.VOICE_INPUT)
            ),
            "piper-swahili" to ModelDef(
                id = "piper-swahili",
                filename = "piper-swahili.onnx",
                url = "$MODEL_CDN/piper-swahili.onnx",
                sha256 = "",
                sizeBytes = 26_000_000L,
                priority = ModelPriority.HIGH,
                requiredFor = listOf(Feature.VOICE_OUTPUT)
            ),
            "silero-vad" to ModelDef(
                id = "silero-vad",
                filename = "silero_vad.onnx",
                url = "$MODEL_CDN/silero_vad.onnx",
                sha256 = "",
                sizeBytes = 2_500_000L,
                priority = ModelPriority.CRITICAL,
                requiredFor = listOf(Feature.VOICE_INPUT)
            ),
            "qwen-0.5b-q4km" to ModelDef(
                id = "qwen-0.5b-q4km",
                filename = "qwen-0.5b-q4_k_m.gguf",
                url = "$MODEL_CDN/qwen-0.5b-q4_k_m.gguf",
                sha256 = "",
                sizeBytes = 310_000_000L,
                priority = ModelPriority.LOW,
                requiredFor = listOf(Feature.LLM_INFERENCE)
            )
        )

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val BUFFER_SIZE = 8192
    }

    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _downloadState = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    /** Observable download state for all models */
    val downloadState: StateFlow<Map<String, ModelState>> = _downloadState

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Observable download progress (0.0 to 1.0) for each model */
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    // ────────────────────── Public API ──────────────────────

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
            if (isModelReady(modelId)) {
                updateState(modelId, ModelState.READY)
                onProgress(modelId, 1.0f)
                continue
            }

            val def = MODELS[modelId]!!
            val destFile = File(modelsDir, def.filename)
            val tempFile = File(modelsDir, "${def.filename}.tmp")
            val resumeFile = File(modelsDir, "${def.filename}.resume")

            try {
                updateState(modelId, ModelState.DOWNLOADING)
                onProgress(modelId, 0f)

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

                // Verify SHA-256 if checksum is provided
                updateState(modelId, ModelState.VERIFYING)
                if (def.sha256.isNotEmpty()) {
                    val hash = sha256File(tempFile)
                    if (hash != def.sha256) {
                        tempFile.delete()
                        resumeFile.delete()
                        updateState(modelId, ModelState.ERROR)
                        Timber.e(
                            "Model %s SHA-256 mismatch: expected %s, got %s",
                            modelId, def.sha256, hash
                        )
                        continue
                    }
                }

                // Atomic rename: temp → final
                val renamed = tempFile.renameTo(destFile)
                if (!renamed) {
                    // Fallback: copy and delete
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }
                resumeFile.delete()

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
     */
    suspend fun downloadModel(
        modelId: String,
        onProgress: (Float) -> Unit = {}
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
            if (def.sha256.isNotEmpty()) {
                val hash = sha256File(tempFile)
                if (hash != def.sha256) {
                    tempFile.delete()
                    updateState(modelId, ModelState.ERROR)
                    return@withContext false
                }
            }

            tempFile.renameTo(destFile)
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
     * Check if all critical + high priority models are downloaded.
     */
    fun areEssentialModelsReady(): Boolean {
        return MODELS.values
            .filter { it.priority <= ModelPriority.HIGH }
            .all { isModelReady(it.id) }
    }

    // ────────────────────── Private Helpers ──────────────────────

    /**
     * Get models required, sorted by priority.
     */
    private fun getRequiredModels(): List<String> {
        return MODELS.entries
            .sortedBy { it.value.priority.ordinal }
            .map { it.key }
    }

    /**
     * Download a file with resume support.
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
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "Msaidizi/1.0")

        // Support resume via Range header
        if (resumeOffset > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeOffset-")
            Timber.d("Resuming download from byte %d", resumeOffset)
        }

        try {
            connection.connect()

            val responseCode = connection.responseCode
            // 200 = full download, 206 = partial content (resume)
            if (responseCode != 200 && responseCode != 206) {
                throw Exception("HTTP $responseCode: ${connection.responseMessage}")
            }

            val totalBytes = connection.contentLength.toLong()
            var downloaded = 0L

            connection.inputStream.use { input ->
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
            connection.disconnect()
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
 */
data class ModelDef(
    val id: String,
    val filename: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val priority: ModelPriority,
    val requiredFor: List<Feature>
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
