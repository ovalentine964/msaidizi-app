package com.msaidizi.app.vision

import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.language.FederatedLearningClient
import com.msaidizi.app.core.model.CorrectionType
import com.msaidizi.app.core.model.UserCorrection
import com.msaidizi.app.core.model.WordType
import com.msaidizi.app.core.model.WorkerVocabulary
import com.msaidizi.app.core.model.WorkerVocabularyDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vision Correction Tracker — Learns from product misidentifications.
 *
 * When the classifier says "nyanya" but the worker says "pilipili":
 * 1. Records the correction locally in WorkerVocabulary
 * 2. Adjusts classifier confidence thresholds per product
 * 3. Sends anonymized correction to federated learning backend
 * 4. Feeds corrections back into model fine-tuning pipeline
 *
 * ## Learning Flow
 * ```
 * Classifier: "Hii ni nyanya" (confidence: 0.72)
 * Worker: "Hii si nyanya, ni pilipili"
 *   ↓
 * VisionCorrectionTracker.recordCorrection()
 *   ├── WorkerVocabulary: update pilipili ↔ visual features
 *   ├── ConfidenceAdjuster: lower nyanya threshold, raise pilipili
 *   └── FederatedLearningClient: send anonymized pattern
 * ```
 *
 * ## Privacy
 * - Raw images are NEVER uploaded
 * - Only anonymized correction patterns are shared
 * - Worker identity is hashed (see FederatedLearningClient)
 * - Corrections are aggregated before upload (batch of 20+)
 *
 * @param workerVocabularyDao Worker vocabulary for persisting corrections
 * @param federatedClient Federated learning client for cloud aggregation
 */
@Singleton
class VisionCorrectionTracker @Inject constructor(
    private val workerVocabularyDao: WorkerVocabularyDao,
    private val federatedClient: FederatedLearningClient
) {
    companion object {
        private const val TAG = "VisionCorrection"
        private const val CORRECTION_BATCH_SIZE = 20
        private const val MIN_CORRECTIONS_FOR_CONFIDENCE_UPDATE = 3
    }

    // ── Correction history ──
    private val pendingCorrections = mutableListOf<VisionCorrection>()
    private val _correctionFlow = MutableSharedFlow<VisionCorrection>(replay = 0)
    val correctionFlow: SharedFlow<VisionCorrection> = _correctionFlow

    // Per-product confusion matrix: predicted → corrected count
    private val confusionMatrix = mutableMapOf<String, MutableMap<String, Int>>()

    // Confidence adjustment factors per product
    private val confidenceAdjustments = mutableMapOf<String, Double>()

    // ── Recording ──────────────────────────────────────────────────

    /**
     * Record a correction from the worker.
     *
     * "Hii si nyanya, ni pilipili" → records that the classifier confused
     * nyanya with pilipili, and updates vocabulary accordingly.
     *
     * @param correction The vision correction data
     */
    suspend fun recordCorrection(correction: VisionCorrection) = withContext(Dispatchers.IO) {
        Timber.i(
            TAG, "Correction: %s → %s (was %.0f%% confident)",
            correction.predictedProductSwahili,
            correction.correctedProductSwahili,
            correction.predictedConfidence * 100
        )

        // 1. Update confusion matrix
        val predictedMap = confusionMatrix.getOrPut(correction.predictedProductSwahili) { mutableMapOf() }
        predictedMap[correction.correctedProductSwahili] =
            (predictedMap[correction.correctedProductSwahili] ?: 0) + 1

        // 2. Store in WorkerVocabulary — add the corrected product as a confirmed entry
        val existing = workerVocabularyDao.getBySpokenForm(
            correction.workerId,
            correction.correctedProductSwahili
        )

        if (existing != null) {
            // Update existing entry — worker confirmed this mapping
            workerVocabularyDao.update(
                existing.copy(
                    workerConfirmed = true,
                    confidence = (existing.confidence * 0.7 + 0.3).coerceAtMost(1.0),
                    frequency = existing.frequency + 1,
                    lastSeenAt = System.currentTimeMillis() / 1000
                )
            )
        } else {
            // Create new vocabulary entry for the corrected product
            workerVocabularyDao.upsert(
                WorkerVocabulary(
                    workerId = correction.workerId,
                    spokenForm = correction.correctedProductSwahili,
                    canonicalForm = correction.correctedProductSwahili,
                    language = "sw",
                    wordType = WordType.PRODUCT,
                    frequency = 1,
                    confidence = 0.7,
                    categoryHint = ProductDatabase.getBySwahiliName(correction.correctedProductSwahili)?.category ?: "produce",
                    workerConfirmed = true,
                    firstSeenAt = System.currentTimeMillis() / 1000,
                    lastSeenAt = System.currentTimeMillis() / 1000
                )
            )
        }

        // 3. Update confidence adjustments
        updateConfidenceAdjustment(correction)

        // 4. Add to pending batch for federated learning
        pendingCorrections.add(correction)
        _correctionFlow.emit(correction)

        // 5. If batch is full, send to federated learning
        if (pendingCorrections.size >= CORRECTION_BATCH_SIZE) {
            flushCorrectionsToBackend()
        }
    }

    /**
     * Quick correction from voice input.
     * Parses "Hii si X, ni Y" pattern.
     *
     * @param predictedProductSwahili What the classifier said
     * @param correctedProductSwahili What the worker said
     * @param confidence The classifier's original confidence
     * @param workerId Worker profile ID
     */
    suspend fun recordVoiceCorrection(
        predictedProductSwahili: String,
        correctedProductSwahili: String,
        confidence: Double,
        workerId: Long = 1
    ) {
        val correctedEntry = ProductDatabase.getBySwahiliName(correctedProductSwahili)
            ?: ProductDatabase.findBestMatch(correctedProductSwahili)

        recordCorrection(
            VisionCorrection(
                predictedProductSwahili = predictedProductSwahili,
                predictedConfidence = confidence,
                correctedProductSwahili = correctedEntry?.swahiliName ?: correctedProductSwahili,
                correctedProductEnglish = correctedEntry?.englishName ?: correctedProductSwahili,
                workerId = workerId
            )
        )
    }

    // ── Confidence Adjustment ──────────────────────────────────────

    /**
     * Get the confidence adjustment factor for a product.
     * Returns a multiplier: <1.0 = lower confidence, >1.0 = boost confidence.
     *
     * Used by ProductClassifier to adjust raw softmax outputs.
     */
    fun getConfidenceAdjustment(productSwahili: String): Double {
        return confidenceAdjustments[productSwahili] ?: 1.0
    }

    /**
     * Get products that are commonly confused with the given product.
     */
    fun getConfusedProducts(productSwahili: String): Map<String, Int> {
        return confusionMatrix[productSwahili] ?: emptyMap()
    }

    /**
     * Check if a product pair is commonly confused.
     * Useful for disambiguation prompts.
     */
    fun isConfusedWith(productA: String, productB: String): Boolean {
        val directConfusion = confusionMatrix[productA]?.get(productB) ?: 0
        val reverseConfusion = confusionMatrix[productB]?.get(productA) ?: 0
        return (directConfusion + reverseConfusion) >= MIN_CORRECTIONS_FOR_CONFIDENCE_UPDATE
    }

    /**
     * Update confidence adjustment based on correction patterns.
     *
     * If the classifier keeps confusing nyanya with pilipili:
     * - Lower the confidence multiplier for nyanya
     * - Raise the confidence multiplier for pilipili
     */
    private fun updateConfidenceAdjustment(correction: VisionCorrection) {
        val predicted = correction.predictedProductSwahili
        val corrected = correction.correctedProductSwahili

        // Count how many times this confusion has happened
        val confusionCount = confusionMatrix[predicted]?.get(corrected) ?: 0

        if (confusionCount >= MIN_CORRECTIONS_FOR_CONFIDENCE_UPDATE) {
            // Lower confidence for the commonly-misidentified product
            val currentAdj = confidenceAdjustments[predicted] ?: 1.0
            confidenceAdjustments[predicted] = (currentAdj * 0.9).coerceAtLeast(0.5)

            // Boost confidence for the correct product
            val correctAdj = confidenceAdjustments[corrected] ?: 1.0
            confidenceAdjustments[corrected] = (correctAdj * 1.1).coerceAtMost(1.5)

            Timber.d(
                TAG, "Confidence adjusted: %s ×%.2f, %s ×%.2f",
                predicted, confidenceAdjustments[predicted],
                corrected, confidenceAdjustments[corrected]
            )
        }
    }

    // ── Federated Learning ─────────────────────────────────────────

    /**
     * Flush pending corrections to the federated learning backend.
     * Corrections are anonymized before upload (see FederatedLearningClient).
     */
    private suspend fun flushCorrectionsToBackend() {
        if (pendingCorrections.isEmpty()) return

        val corrections = pendingCorrections.toList()
        pendingCorrections.clear()

        try {
            // Convert to UserCorrection format for FederatedLearningClient
            val userCorrections = corrections.map { vc ->
                UserCorrection(
                    correctionType = CorrectionType.ITEM,
                    originalValue = vc.predictedProductSwahili,
                    correctedValue = vc.correctedProductSwahili,
                    originalInput = "vision:${vc.predictedConfidence}",
                    correctionInput = "vision:correction",
                    context = """{"source":"product_classifier","confidence":${vc.predictedConfidence}}""",
                    createdAt = vc.frameTimestampMs / 1000
                )
            }

            // Upload via federated learning client (anonymized)
            val calibrationParams = com.msaidizi.app.core.language.CalibrationParams(
                temperature = 1.5f,
                plattA = 0.8f,
                plattB = -0.3f,
                prior = 0.7f
            )
            federatedClient.uploadUpdate(
                language = "sw",
                corrections = userCorrections,
                adapterBytes = null,  // No LoRA adapter for vision yet
                calibrationParams = calibrationParams
            )

            Timber.i(TAG, "Flushed %d corrections to federated backend", corrections.size)

        } catch (e: Throwable) {
            Timber.w(e, "Failed to flush corrections — will retry")
            // Re-add for next attempt
            pendingCorrections.addAll(0, corrections)
        }
    }

    /**
     * Force-flush any pending corrections.
     * Called on app pause/stop.
     */
    suspend fun flush() {
        flushCorrectionsToBackend()
    }

    // ── Statistics ─────────────────────────────────────────────────

    /**
     * Get correction statistics for diagnostics.
     */
    fun getStats(): CorrectionStats {
        val totalCorrections = confusionMatrix.values.sumOf { it.values.sum() }
        val uniqueConfusions = confusionMatrix.count()
        val worstConfusion = confusionMatrix.entries
            .flatMap { (predicted, corrections) ->
                corrections.map { (corrected, count) -> Triple(predicted, corrected, count) }
            }
            .maxByOrNull { it.third }

        return CorrectionStats(
            totalCorrections = totalCorrections,
            uniqueConfusionPairs = uniqueConfusions,
            worstConfusionPair = worstConfusion?.let { "${it.first}→${it.second}" } ?: "none",
            worstConfusionCount = worstConfusion?.third ?: 0,
            pendingUploads = pendingCorrections.size
        )
    }

    data class CorrectionStats(
        val totalCorrections: Int,
        val uniqueConfusionPairs: Int,
        val worstConfusionPair: String,
        val worstConfusionCount: Int,
        val pendingUploads: Int
    )
}
