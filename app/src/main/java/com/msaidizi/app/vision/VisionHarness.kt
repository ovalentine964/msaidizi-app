package com.msaidizi.app.vision

import android.graphics.Bitmap
import com.msaidizi.app.agent.harness.InferenceHarness
import com.msaidizi.app.agent.harness.InferenceHarnessException
import com.msaidizi.app.agent.harness.HarnessConfig
import com.msaidizi.app.agent.harness.ProviderCandidate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vision Harness — wraps the CV pipeline with confidence thresholds,
 * correction tracking, and human-in-the-loop confirmation.
 *
 * ## Purpose
 * Every vision model call goes through this harness. It ensures:
 * 1. **Confidence thresholds**: Low confidence → ask worker for confirmation
 * 2. **Correction tracking**: Learn from misidentifications
 * 3. **Fallback chain**: on-device CV → cloud CV → text-only (ask user)
 * 4. **Quality metrics**: Track recognition accuracy and correction rate
 *
 * ## Confidence Strategy
 * - High confidence (≥0.85): Auto-accept, announce to worker
 * - Medium confidence (0.65–0.85): Announce with "Je, ni X?" (Is it X?)
 * - Low confidence (<0.65): Don't guess, ask worker to identify
 *
 * ## Pipeline
 * ```
 * Camera Frame → [CV Model] → [Confidence Gate] → [Correction Tracker]
 *                    ↓ low conf       ↓ medium conf        ↓ high conf
 *              ask worker          confirm prompt      auto-accept
 *                    ↓
 *              correction → learn → improve thresholds
 * ```
 *
 * ## Metrics
 * - Recognition accuracy (corrections / total)
 * - Per-product accuracy
 * - Average confidence
 * - Correction rate trend
 * - Worker confirmation rate
 */
@Singleton
class VisionHarness @Inject constructor(
    private val inferenceHarness: InferenceHarness,
    private val correctionTracker: VisionCorrectionTracker
) {
    companion object {
        private const val TAG = "VisionHarness"

        // Confidence thresholds
        const val HIGH_CONFIDENCE = 0.85
        const val MEDIUM_CONFIDENCE = 0.65
        const val LOW_CONFIDENCE = 0.45

        // Timeouts
        const val CV_INFERENCE_TIMEOUT_MS = 8_000L
        const val CLOUD_CV_TIMEOUT_MS = 15_000L
    }

    // ── Metrics ───────────────────────────────────────────────────
    private val totalRecognitions = AtomicLong(0)
    private val highConfidenceCount = AtomicLong(0)
    private val mediumConfidenceCount = AtomicLong(0)
    private val lowConfidenceCount = AtomicLong(0)
    private val correctionCount = AtomicLong(0)
    private val autoAcceptedCount = AtomicLong(0)

    // Per-product accuracy tracking
    private val productStats = ConcurrentHashMap<String, ProductVisionStats>()

    // ── Events ────────────────────────────────────────────────────
    private val _events = MutableSharedFlow<VisionHarnessEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<VisionHarnessEvent> = _events

    // ═══════════════════════════════════════════════════════════════
    // CORE RECOGNITION — Wrapped CV inference with confidence gates
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classify a camera frame with full harness protection.
     *
     * Returns a [VisionResult] that includes the confidence level and
     * recommended action (auto-accept, confirm, or ask worker).
     *
     * @param bitmap Camera frame
     * @param classifyFn The actual classifier function
     * @param cloudClassifyFn Optional cloud fallback classifier
     * @param workerId Worker profile ID for correction tracking
     * @return Vision result with confidence-based action recommendation
     */
    suspend fun recognize(
        bitmap: Bitmap,
        classifyFn: suspend (Bitmap) -> ProductRecognition?,
        cloudClassifyFn: (suspend (Bitmap) -> ProductRecognition?)? = null,
        workerId: Long = 1
    ): VisionResult {
        val recognitionId = UUID.randomUUID().toString().take(12)
        val startTime = System.currentTimeMillis()
        totalRecognitions.incrementAndGet()

        // 1. Try on-device classification with harness
        val recognition: ProductRecognition?
        var providerUsed = "on-device"

        try {
            val result = inferenceHarness.execute(
                config = HarnessConfig(timeoutMs = CV_INFERENCE_TIMEOUT_MS, maxRetries = 1),
                providers = buildProviderChain(bitmap, classifyFn, cloudClassifyFn),
                taskType = "cv:product-recognition"
            )
            recognition = result.value
            providerUsed = result.providerId
        } catch (e: InferenceHarnessException) {
            Timber.e(TAG, "[%s] All CV providers failed: %s", recognitionId, e.message)
            _events.emit(VisionHarnessEvent.RecognitionFailed(
                recognitionId = recognitionId,
                error = e.message ?: "unknown"
            ))
            return VisionResult(
                recognitionId = recognitionId,
                recognition = null,
                confidenceLevel = ConfidenceLevel.NONE,
                recommendedAction = RecommendedAction.ASK_WORKER,
                providerUsed = "none",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }

        if (recognition == null) {
            return VisionResult(
                recognitionId = recognitionId,
                recognition = null,
                confidenceLevel = ConfidenceLevel.NONE,
                recommendedAction = RecommendedAction.ASK_WORKER,
                providerUsed = providerUsed,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }

        // 2. Apply correction-based confidence adjustments
        val adjustedConfidence = applyCorrectionAdjustments(recognition)

        // 3. Determine confidence level and recommended action
        val confidenceLevel = classifyConfidence(adjustedConfidence)
        val recommendedAction = determineAction(confidenceLevel, recognition)

        // 4. Update per-product stats
        updateProductStats(recognition.productSwahili, adjustedConfidence, confidenceLevel)

        // 5. Track confidence level
        when (confidenceLevel) {
            ConfidenceLevel.HIGH -> highConfidenceCount.incrementAndGet()
            ConfidenceLevel.MEDIUM -> mediumConfidenceCount.incrementAndGet()
            ConfidenceLevel.LOW -> lowConfidenceCount.incrementAndGet()
            ConfidenceLevel.NONE -> {}
        }

        val latencyMs = System.currentTimeMillis() - startTime

        val adjustedRecognition = recognition.copy(confidence = adjustedConfidence)

        _events.emit(VisionHarnessEvent.RecognitionCompleted(
            recognitionId = recognitionId,
            product = adjustedRecognition.productSwahili,
            confidence = adjustedConfidence,
            confidenceLevel = confidenceLevel,
            recommendedAction = recommendedAction,
            latencyMs = latencyMs
        ))

        Timber.d(TAG, "[%s] %s: %.0f%% (%s) → %s in %dms",
            recognitionId, adjustedRecognition.productSwahili,
            adjustedConfidence * 100, confidenceLevel.name,
            recommendedAction.name, latencyMs)

        return VisionResult(
            recognitionId = recognitionId,
            recognition = adjustedRecognition,
            confidenceLevel = confidenceLevel,
            recommendedAction = recommendedAction,
            providerUsed = providerUsed,
            latencyMs = latencyMs
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // CONFIRMATION & CORRECTION — Human-in-the-loop
    // ═══════════════════════════════════════════════════════════════

    /**
     * Worker confirms the recognition was correct.
     * Strengthens the confidence for this product.
     */
    suspend fun confirmRecognition(
        recognition: ProductRecognition,
        workerId: Long = 1
    ) {
        autoAcceptedCount.incrementAndGet()

        // Positive feedback: boost confidence for this product
        val stats = productStats.getOrPut(recognition.productSwahili) {
            ProductVisionStats(recognition.productSwahili)
        }
        stats.recordConfirmation()

        Timber.d(TAG, "Confirmed: %s (total confirms: %d)",
            recognition.productSwahili, stats.confirmations)
    }

    /**
     * Worker corrects a misidentification.
     * "Hii si nyanya, ni pilipili" → records correction and learns.
     *
     * @param predicted What the classifier said
     * @param correctedSwahili What the worker says it is
     * @param workerId Worker profile ID
     */
    suspend fun recordCorrection(
        predicted: ProductRecognition,
        correctedSwahili: String,
        workerId: Long = 1
    ) {
        correctionCount.incrementAndGet()

        // Record in correction tracker
        correctionTracker.recordVoiceCorrection(
            predictedProductSwahili = predicted.productSwahili,
            correctedProductSwahili = correctedSwahili,
            confidence = predicted.confidence,
            workerId = workerId
        )

        // Update per-product stats
        val predictedStats = productStats.getOrPut(predicted.productSwahili) {
            ProductVisionStats(predicted.productSwahili)
        }
        predictedStats.recordMisidentification()

        val correctedStats = productStats.getOrPut(correctedSwahili) {
            ProductVisionStats(correctedSwahili)
        }
        correctedStats.recordConfirmation()

        _events.emit(VisionHarnessEvent.CorrectionRecorded(
            predicted = predicted.productSwahili,
            corrected = correctedSwahili,
            confidence = predicted.confidence,
            workerId = workerId
        ))

        Timber.i(TAG, "Correction: %s → %s (was %.0f%% confident)",
            predicted.productSwahili, correctedSwahili, predicted.confidence * 100)
    }

    // ═══════════════════════════════════════════════════════════════
    // CONFIDENCE LOGIC
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classify confidence into levels.
     */
    private fun classifyConfidence(confidence: Double): ConfidenceLevel {
        return when {
            confidence >= HIGH_CONFIDENCE -> ConfidenceLevel.HIGH
            confidence >= MEDIUM_CONFIDENCE -> ConfidenceLevel.MEDIUM
            confidence >= LOW_CONFIDENCE -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.NONE
        }
    }

    /**
     * Determine the recommended action based on confidence level.
     */
    private fun determineAction(
        level: ConfidenceLevel,
        recognition: ProductRecognition
    ): RecommendedAction {
        return when (level) {
            ConfidenceLevel.HIGH -> RecommendedAction.AUTO_ACCEPT
            ConfidenceLevel.MEDIUM -> RecommendedAction.CONFIRM_WITH_WORKER
            ConfidenceLevel.LOW -> RecommendedAction.ASK_WORKER
            ConfidenceLevel.NONE -> RecommendedAction.ASK_WORKER
        }
    }

    /**
     * Apply correction-based confidence adjustments from [VisionCorrectionTracker].
     * If the classifier keeps confusing nyanya with pilipili, we lower the
     * confidence for nyanya automatically.
     */
    private fun applyCorrectionAdjustments(recognition: ProductRecognition): Double {
        val adjustment = correctionTracker.getConfidenceAdjustment(recognition.productSwahili)
        return (recognition.confidence * adjustment).coerceIn(0.0, 1.0)
    }

    // ═══════════════════════════════════════════════════════════════
    // PROVIDER CHAIN — Build fallback chain for CV inference
    // ═══════════════════════════════════════════════════════════════

    private fun buildProviderChain(
        bitmap: Bitmap,
        classifyFn: suspend (Bitmap) -> ProductRecognition?,
        cloudClassifyFn: (suspend (Bitmap) -> ProductRecognition?)?
    ): List<ProviderCandidate<ProductRecognition?>> {
        val providers = mutableListOf(
            ProviderCandidate(
                providerId = "cv-on-device-mobilenetv3",
                modelId = "mobilenetv3-small",
                provider = { classifyFn(bitmap) }
            )
        )

        if (cloudClassifyFn != null) {
            providers.add(
                ProviderCandidate(
                    providerId = "cv-cloud-vision",
                    modelId = "cloud-vision-api",
                    provider = { cloudClassifyFn(bitmap) }
                )
            )
        }

        // Text-only fallback: return null to signal "ask the worker"
        providers.add(
            ProviderCandidate(
                providerId = "cv-text-fallback",
                modelId = "text-input",
                provider = { null }
            )
        )

        return providers
    }

    // ═══════════════════════════════════════════════════════════════
    // METRICS & MONITORING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get comprehensive vision harness stats.
     */
    fun getStats(): VisionHarnessStats {
        val total = totalRecognitions.get()
        val corrections = correctionCount.get()
        val accuracy = if (total > 0) 1.0 - (corrections.toDouble() / total) else 1.0

        return VisionHarnessStats(
            totalRecognitions = total,
            highConfidenceCount = highConfidenceCount.get(),
            mediumConfidenceCount = mediumConfidenceCount.get(),
            lowConfidenceCount = lowConfidenceCount.get(),
            correctionCount = corrections,
            autoAcceptedCount = autoAcceptedCount.get(),
            overallAccuracy = accuracy,
            correctionRate = if (total > 0) corrections.toDouble() / total else 0.0,
            productStats = productStats.mapValues { (_, s) -> s.snapshot() }
        )
    }

    /**
     * Get stats for a specific product.
     */
    fun getProductStats(productSwahili: String): ProductVisionStatsSnapshot? {
        return productStats[productSwahili]?.snapshot()
    }

    /**
     * Get the most commonly misidentified products.
     */
    fun getWorstProducts(limit: Int = 5): List<ProductVisionStatsSnapshot> {
        return productStats.values
            .map { it.snapshot() }
            .filter { it.totalRecognitions >= 3 }
            .sortedBy { it.accuracy }
            .take(limit)
    }

    private fun updateProductStats(
        productSwahili: String,
        confidence: Double,
        level: ConfidenceLevel
    ) {
        val stats = productStats.getOrPut(productSwahili) { ProductVisionStats(productSwahili) }
        stats.recordRecognition(confidence, level)
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA TYPES
    // ═══════════════════════════════════════════════════════════════

    /**
     * Result of a harness-wrapped vision recognition.
     */
    data class VisionResult(
        val recognitionId: String,
        val recognition: ProductRecognition?,
        val confidenceLevel: ConfidenceLevel,
        val recommendedAction: RecommendedAction,
        val providerUsed: String,
        val latencyMs: Long
    ) {
        /** Whether the recognition is reliable enough to auto-accept */
        val isAutoAcceptable: Boolean get() = recommendedAction == RecommendedAction.AUTO_ACCEPT

        /** Whether we need worker confirmation */
        val needsConfirmation: Boolean get() = recommendedAction == RecommendedAction.CONFIRM_WITH_WORKER

        /** Whether we should ask the worker to identify the product */
        val shouldAskWorker: Boolean get() = recommendedAction == RecommendedAction.ASK_WORKER

        /** Voice prompt based on confidence */
        fun getVoicePrompt(): String {
            return when (recommendedAction) {
                RecommendedAction.AUTO_ACCEPT ->
                    "Hii ni ${recognition?.productSwahili} — bei ya soko ni KSh ${recognition?.suggestedPriceKSh?.toInt()}"
                RecommendedAction.CONFIRM_WITH_WORKER ->
                    "Je, ni ${recognition?.productSwahili}? (Nimeona kwa ${"%.0f".format((recognition?.confidence ?: 0.0) * 100)}%)"
                RecommendedAction.ASK_WORKER ->
                    "Sijui hii ni nini. Sema jina la bidhaa."
            }
        }
    }

    enum class ConfidenceLevel {
        /** ≥0.85: Very confident, auto-accept */
        HIGH,
        /** 0.65–0.85: Moderately confident, ask for confirmation */
        MEDIUM,
        /** 0.45–0.65: Low confidence, ask worker to identify */
        LOW,
        /** <0.45: No usable prediction */
        NONE
    }

    enum class RecommendedAction {
        /** Automatically accept and announce */
        AUTO_ACCEPT,
        /** Announce with "Je, ni X?" and wait for confirmation */
        CONFIRM_WITH_WORKER,
        /** Don't guess — ask the worker to identify */
        ASK_WORKER
    }

    /**
     * Per-product vision accuracy stats.
     */
    class ProductVisionStats(private val productSwahili: String) {
        var totalRecognitions = 0L; private set
        var confirmations = 0L; private set
        var misidentifications = 0L; private set
        var totalConfidence = 0.0; private set

        fun recordRecognition(confidence: Double, level: ConfidenceLevel) {
            totalRecognitions++
            totalConfidence += confidence
        }

        fun recordConfirmation() {
            confirmations++
        }

        fun recordMisidentification() {
            misidentifications++
        }

        fun snapshot() = ProductVisionStatsSnapshot(
            productSwahili = productSwahili,
            totalRecognitions = totalRecognitions,
            confirmations = confirmations,
            misidentifications = misidentifications,
            accuracy = if (totalRecognitions > 0)
                1.0 - (misidentifications.toDouble() / totalRecognitions) else 1.0,
            avgConfidence = if (totalRecognitions > 0)
                totalConfidence / totalRecognitions else 0.0
        )
    }

    data class ProductVisionStatsSnapshot(
        val productSwahili: String,
        val totalRecognitions: Long,
        val confirmations: Long,
        val misidentifications: Long,
        val accuracy: Double,
        val avgConfidence: Double
    )

    data class VisionHarnessStats(
        val totalRecognitions: Long,
        val highConfidenceCount: Long,
        val mediumConfidenceCount: Long,
        val lowConfidenceCount: Long,
        val correctionCount: Long,
        val autoAcceptedCount: Long,
        val overallAccuracy: Double,
        val correctionRate: Double,
        val productStats: Map<String, ProductVisionStatsSnapshot>
    )
}

// ═══════════════════════════════════════════════════════════════
// EVENTS
// ═══════════════════════════════════════════════════════════════

sealed class VisionHarnessEvent {
    data class RecognitionCompleted(
        val recognitionId: String,
        val product: String,
        val confidence: Double,
        val confidenceLevel: VisionHarness.ConfidenceLevel,
        val recommendedAction: VisionHarness.RecommendedAction,
        val latencyMs: Long
    ) : VisionHarnessEvent()

    data class RecognitionFailed(
        val recognitionId: String,
        val error: String
    ) : VisionHarnessEvent()

    data class CorrectionRecorded(
        val predicted: String,
        val corrected: String,
        val confidence: Double,
        val workerId: Long
    ) : VisionHarnessEvent()
}
