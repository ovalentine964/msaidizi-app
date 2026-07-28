package com.msaidizi.app.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeltaModelUpdater — Binary diff/patch for model updates.
 *
 * Addresses GAP: Model update mechanism needs delta/patch approach.
 *
 * Instead of downloading full model files on every update, this system:
 * 1. Tracks model versions and checksums
 * 2. Downloads only the changed blocks (binary diff)
 * 3. Applies patches to create the new model version
 * 4. Verifies integrity after patching
 *
 * Block-based approach:
 * - Models are divided into fixed-size blocks (default 1MB)
 * - Each block has a SHA-256 checksum
 * - Only blocks with changed checksums are downloaded
 * - A manifest file describes the block layout
 */
@Singleton
class DeltaModelUpdater @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "model_versions"
        private const val BLOCK_SIZE_BYTES = 1024 * 1024  // 1MB blocks
        private const val MANIFEST_SUFFIX = ".manifest.json"
    }

    /**
     * Model version manifest for delta updates.
     */
    data class ModelManifest(
        val modelName: String,
        val version: String,
        val totalSizeBytes: Long,
        val blockCount: Int,
        val blockSizeBytes: Int,
        val blocks: List<BlockInfo>,
        val fullChecksum: String
    )

    /**
     * Information about a single block in a model file.
     */
    data class BlockInfo(
        val index: Int,
        val offset: Long,
        val size: Int,
        val sha256: String
    )

    /**
     * Delta update descriptor — what blocks need updating.
     */
    data class DeltaUpdate(
        val modelName: String,
        val fromVersion: String,
        val toVersion: String,
        val blocksToDownload: List<BlockInfo>,
        val blocksToKeep: List<BlockInfo>,
        val totalDownloadBytes: Long,
        val totalModelBytes: Long,
        val savingsPercent: Double
    )

    /**
     * Result of applying a delta update.
     */
    data class DeltaResult(
        val success: Boolean,
        val modelName: String,
        val fromVersion: String,
        val toVersion: String,
        val blocksUpdated: Int,
        val bytesDownloaded: Long,
        val bytesReused: Long,
        val error: String? = null
    )

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Version tracking ─────────────────────────────────────

    /**
     * Get the current version of a model.
     */
    fun getModelVersion(modelName: String): String? {
        return prefs.getString("version_$modelName", null)
    }

    /**
     * Set the version of a model (after successful download/patch).
     */
    fun setModelVersion(modelName: String, version: String) {
        prefs.edit()
            .putString("version_$modelName", version)
            .putLong("updated_$modelName", System.currentTimeMillis())
            .apply()
        Timber.i("Model version updated: %s → %s", modelName, version)
    }

    /**
     * Get the checksum of a model file.
     */
    fun getModelChecksum(modelName: String): String? {
        return prefs.getString("checksum_$modelName", null)
    }

    /**
     * Set the checksum of a model file.
     */
    fun setModelChecksum(modelName: String, checksum: String) {
        prefs.edit().putString("checksum_$modelName", checksum).apply()
    }

    // ── Block computation ────────────────────────────────────

    /**
     * Compute block checksums for a model file.
     * This creates a manifest that can be used to identify changed blocks.
     */
    suspend fun computeManifest(modelFile: File, modelName: String, version: String): ModelManifest =
        withContext(Dispatchers.IO) {
            val totalSize = modelFile.length()
            val blockCount = ((totalSize + BLOCK_SIZE_BYTES - 1) / BLOCK_SIZE_BYTES).toInt()
            val blocks = mutableListOf<BlockInfo>()

            FileInputStream(modelFile).use { fis ->
                val buffer = ByteArray(BLOCK_SIZE_BYTES)
                for (i in 0 until blockCount) {
                    val bytesRead = fis.read(buffer)
                    if (bytesRead <= 0) break

                    val sha256 = sha256(buffer, bytesRead)
                    blocks.add(BlockInfo(
                        index = i,
                        offset = (i.toLong() * BLOCK_SIZE_BYTES),
                        size = bytesRead,
                        sha256 = sha256
                    ))
                }
            }

            val fullChecksum = sha256File(modelFile)

            ModelManifest(
                modelName = modelName,
                version = version,
                totalSizeBytes = totalSize,
                blockCount = blocks.size,
                blockSizeBytes = BLOCK_SIZE_BYTES,
                blocks = blocks,
                fullChecksum = fullChecksum
            )
        }

    /**
     * Compute the delta between two manifests.
     * Returns which blocks need to be downloaded.
     */
    fun computeDelta(
        currentManifest: ModelManifest,
        targetManifest: ModelManifest
    ): DeltaUpdate {
        val currentBlocks = currentManifest.blocks.associateBy { it.sha256 }
        val blocksToDownload = mutableListOf<BlockInfo>()
        val blocksToKeep = mutableListOf<BlockInfo>()

        for (targetBlock in targetManifest.blocks) {
            val existingBlock = currentBlocks[targetBlock.sha256]
            if (existingBlock != null && existingBlock.size == targetBlock.size) {
                blocksToKeep.add(targetBlock)
            } else {
                blocksToDownload.add(targetBlock)
            }
        }

        val downloadBytes = blocksToDownload.sumOf { it.size.toLong() }
        val savings = if (targetManifest.totalSizeBytes > 0) {
            (1.0 - downloadBytes.toDouble() / targetManifest.totalSizeBytes) * 100
        } else 0.0

        return DeltaUpdate(
            modelName = targetManifest.modelName,
            fromVersion = currentManifest.version,
            toVersion = targetManifest.version,
            blocksToDownload = blocksToDownload,
            blocksToKeep = blocksToKeep,
            totalDownloadBytes = downloadBytes,
            totalModelBytes = targetManifest.totalSizeBytes,
            savingsPercent = savings
        )
    }

    /**
     * Apply a delta update to a model file.
     *
     * @param currentFile The existing model file
     * @param targetManifest The target manifest (new version)
     * @param delta The delta update descriptor
     * @param blockDownloader Function to download a block's data given its info
     * @return Result of the patch operation
     */
    suspend fun applyDelta(
        currentFile: File,
        targetManifest: ModelManifest,
        delta: DeltaUpdate,
        blockDownloader: suspend (BlockInfo) -> ByteArray
    ): DeltaResult = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(currentFile.parentFile, "${currentFile.name}.tmp")
            val backupFile = File(currentFile.parentFile, "${currentFile.name}.bak")

            // Build the new file from kept blocks + downloaded blocks
            val allBlocks = (delta.blocksToKeep + delta.blocksToDownload)
                .sortedBy { it.index }

            // Create a map of block index → source
            val keptBlocks = delta.blocksToKeep.associateBy { it.index }
            val downloadedBlocks = mutableMapOf<Int, ByteArray>()
            var bytesDownloaded = 0L

            // Download changed blocks
            for (block in delta.blocksToDownload) {
                val data = blockDownloader(block)
                downloadedBlocks[block.index] = data
                bytesDownloaded += data.size
            }

            // Write the new file
            FileOutputStream(tempFile).use { fos ->
                RandomAccessFile(currentFile, "r").use { raf ->
                    for (block in allBlocks) {
                        if (keptBlocks.containsKey(block.index)) {
                            // Copy from existing file
                            val buffer = ByteArray(block.size)
                            raf.seek(block.offset)
                            raf.readFully(buffer)
                            fos.write(buffer)
                        } else {
                            // Write downloaded block
                            val data = downloadedBlocks[block.index] ?: ByteArray(0)
                            fos.write(data)
                        }
                    }
                }
            }

            // Verify the complete file checksum
            val newChecksum = sha256File(tempFile)
            if (newChecksum != targetManifest.fullChecksum) {
                tempFile.delete()
                return@withContext DeltaResult(
                    success = false,
                    modelName = delta.modelName,
                    fromVersion = delta.fromVersion,
                    toVersion = delta.toVersion,
                    blocksUpdated = delta.blocksToDownload.size,
                    bytesDownloaded = bytesDownloaded,
                    bytesReused = delta.blocksToKeep.sumOf { it.size.toLong() },
                    error = "Checksum mismatch after patch: expected ${targetManifest.fullChecksum}, got $newChecksum"
                )
            }

            // Backup old file and replace with new
            if (currentFile.exists()) {
                currentFile.renameTo(backupFile)
            }
            tempFile.renameTo(currentFile)
            backupFile.delete()

            // Update version tracking
            setModelVersion(delta.modelName, delta.toVersion)
            setModelChecksum(delta.modelName, newChecksum)

            Timber.i("Delta update applied: %s %s → %s (%d blocks updated, %s downloaded)",
                delta.modelName, delta.fromVersion, delta.toVersion,
                delta.blocksToDownload.size, formatBytes(bytesDownloaded))

            DeltaResult(
                success = true,
                modelName = delta.modelName,
                fromVersion = delta.fromVersion,
                toVersion = delta.toVersion,
                blocksUpdated = delta.blocksToDownload.size,
                bytesDownloaded = bytesDownloaded,
                bytesReused = delta.blocksToKeep.sumOf { it.size.toLong() }
            )
        } catch (e: Exception) {
            Timber.e(e, "Delta update failed for %s", delta.modelName)
            DeltaResult(
                success = false,
                modelName = delta.modelName,
                fromVersion = delta.fromVersion,
                toVersion = delta.toVersion,
                blocksUpdated = 0,
                bytesDownloaded = 0,
                bytesReused = 0,
                error = e.message
            )
        }
    }

    /**
     * Check if a delta update is available for a model.
     */
    fun isDeltaAvailable(modelName: String, currentVersion: String, targetVersion: String): Boolean {
        if (currentVersion == targetVersion) return false
        // In production, this would check the server for a manifest
        return getModelVersion(modelName) != targetVersion
    }

    /**
     * Get update history for a model.
     */
    fun getUpdateHistory(modelName: String): Map<String, Any> {
        return mapOf(
            "modelName" to modelName,
            "currentVersion" to (getModelVersion(modelName) ?: "none"),
            "lastUpdated" to prefs.getLong("updated_$modelName", 0),
            "currentChecksum" to (getModelChecksum(modelName) ?: "unknown")
        )
    }

    // ── Utilities ────────────────────────────────────────────

    /**
     * Compute SHA-256 of a byte array.
     */
    private fun sha256(data: ByteArray, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(data, 0, length)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute SHA-256 of a file.
     */
    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(file).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Format bytes to human-readable string.
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    /**
     * Get diagnostics.
     */
    fun getDiagnostics(): String = buildString {
        appendLine("Delta Model Updater:")
        appendLine("  Block size: ${BLOCK_SIZE_BYTES / 1024}KB")
        val versions = listOf("qwen3-0.6b", "qwen3-0.8b", "whisper-tiny", "whisper-small", "piper-sw")
        for (model in versions) {
            val ver = getModelVersion(model)
            val checksum = getModelChecksum(model)
            appendLine("  $model: ${ver ?: "not tracked"} ${if (checksum != null) "(${checksum.take(12)}...)" else ""}")
        }
    }
}
