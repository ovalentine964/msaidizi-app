package com.msaidizi.app.vision

import android.graphics.Bitmap
import com.msaidizi.app.agent.harness.InferenceHarness
import com.msaidizi.app.agent.harness.InferenceHarnessException
import com.msaidizi.app.agent.harness.HarnessConfig
import com.msaidizi.app.agent.harness.ProviderCandidate
import com.msaidizi.app.scanner.ReceiptData
import com.msaidizi.app.scanner.ReceiptItem
import com.msaidizi.app.scanner.ReceiptScanner
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
 * Vision Harness — wraps the full CV pipeline (product recognition + receipt scanning)
 * with confidence thresholds, correction tracking, quality gates, and voice fallback.
 *
 * ## Purpose
 * Every CV operation goes through this harness. It ensures:
 * 1. **Confidence thresholds**: Low confidence (< 0.7) → ask worker for confirmation
 * 2. **Correction tracking**: Learn from misidentifications and OCR corrections
 * 3. **Quality gates**: Block unreliable results from entering the system
 * 4. **Voice fallback**: If CV fails completely → fall back to voice input
 * 5. **Metrics**: Track accuracy, correction rate, processing time per pipeline
 *
 * ## Confidence Strategy (both pipelines)
 * - High confidence (≥ 0.85): Auto-accept, announce to worker
 * - Medium confidence (0.70–0.85): Announce with "Je, ni X?" (Is it X?)
 * - Low confidence (< 0.70): Don't guess — ask worker or fall back to voice
 *
 * ## Pipelines
 * ### Product Recognition
 * ```
 * Camera Frame → [MobileNetV3] → [Confidence Gate] → [Correction Tracker]
 *                    ↓ low conf       ↓ medium conf        ↓ high conf
 *              voice fallback      confirm prompt      auto-accept
 * ```
 *
 * ### Receipt Scanning
 * ```
 * Receipt Photo → [ML Kit OCR] → [Parse] → [Quality Gate] → [Confidence Gate]
 *                    ↓ fail          ↓ low quality         ↓ low conf
 *              voice fallback    ask worker to re-scan   confirm items
 * ```
 *
 * ## Metrics
 * - Recognition accuracy (corrections / total) per pipeline
 * - Per-product accuracy and correction frequency
 * - Processing time per scan/classify operation
 * - Most corrected items (confusion hotspots)
 * - Fallback rate (how often CV fails → voice)
 */
@Singleton
class VisionHarness @Inject constructor(
    private val inferenceHarness: InferenceHarness,
    private val correctionTracker: VisionCorrectionTracker,
    private val receiptScanner: ReceiptScanner
) {
    companion object {
        private const val TAG = "VisionHarness"

        // ── Confidence thresholds ──
        /** ≥ 0.85: Very confident, auto-accept */
        const val HIGH_CONFIDENCE = 0.85
        /** 0.70–0.85: Moderately confident, ask for confirmation */
        const val MEDIUM_CONFIDENCE = 0.70
        /** < 0.70: Low confidence — ask worker or fallback to voice */
        const val LOW_CONFIDENCE = 0.70

        // ── Receipt quality thresholds ──
        /** Minimum items for a valid receipt parse */
        const val MIN_RECEIPT_ITEMS = 1
        /** Minimum total for a valid receipt */
        const val MIN_RECEIPT_TOTAL = 1.0
        /** Confidence assigned when receipt has no items but has a total */
        const val RECEIPT_TOTAL_ONLY_CONFIDENCE = 0.55
        /** Confidence assigned when receipt has items and total matches */
        const val RECEIPT_FULL_MATCH_CONFIDENCE = 0.90
        /** Confidence assigned when receipt has items but total doesn't match */
        const val RECEIPT_MISMATCH_CONFIDENCE = 0.60
        /** Base confidence for a parsed receipt with items */
        const val RECEIPT_BASE_CONFIDENCE = 0.75

        // ── Timeouts ──
        const val CV_INFERENCE_TIMEOUT_MS = 8_000L
        const val CLOUD_CV_TIMEOUT_MS = 15_000L
        const val RECEIPT_SCAN_TIMEOUT_MS = 10_000L
    }

    // ═══════════════════════════════════════════════════════════════
    // METRICS — Atomic counters for thread-safe tracking
    // ═══════════════════════════════════════════════════════════════

    // Product recognition metrics
    private val productRecognitions = AtomicLong(0)
    private val productHighConf = AtomicLong(0)
    private val productMediumConf = AtomicLong(0)
    private val productLowConf = AtomicLong(0)
    private val productCorrections = AtomicLong(0)
    private val productAutoAccepted = AtomicLong(0)
    private val productVoiceFallbacks = AtomicLong(0)

    // Receipt scanning metrics
    private val receiptScans = AtomicLong(0)
    private val receiptHighConf = AtomicLong(0)
    private val receiptMediumConf = AtomicLong(0)
    private val receiptLowConf = AtomicLong(0)
    private val receiptCorrections = AtomicLong(0)
    private val receiptVoiceFallbacks = AtomicLong(0)
    private val receiptProcessingTimeTotalMs = AtomicLong(0)

    // Per-product accuracy tracking
    private val productStats = ConcurrentHashMap<String, ProductVisionStats>()

    // Per-receipt-item correction tracking
    private val receiptItemCorrections = ConcurrentHashMap<String, ItemCorrectionStats>()

    // ── Events ────────────────────────────────────────────────────
    private val _events = MutableSharedFlow<VisionHarnessEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<VisionHarnessEvent> = _events

    // ═══════════════════════════════════════════════════════════════
    // PRODUCT RECOGNITION — Wrapped CV inference with confidence gates
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classify a camera frame with full harness protection.
     *
     * Returns a [VisionResult] that includes the confidence level and
     * recommended action (auto-accept, confirm, or ask worker).
     *
     * When confidence < 0.70, the recommended action is [RecommendedAction.ASK_WORKER],
     * which signals the caller to prompt for voice input as fallback.
     *
     * @param bitmap Camera frame
     * @param classifyFn The actual classifier function (ProductClassifier::classify)
     * @param cloudClassifyFn Optional cloud fallback classifier
     * @param workerId Worker profile ID for correction tracking
     * @return Vision result with confidence-based action recommendation
     */
    suspend fun recognizeProduct(
        bitmap: Bitmap,
        classifyFn: suspend (Bitmap) -> ProductRecognition?,
        cloudClassifyFn: (suspend (Bitmap) -> ProductRecognition?)? = null,
        workerId: Long = 1
    ): VisionResult {
        val recognitionId = UUID.randomUUID().toString().take(12)
        val startTime = System.currentTimeMillis()
        productRecognitions.incrementAndGet()

        // 1. Try on-device classification with harness fallback chain
        val recognition: ProductRecognition?
        var providerUsed = "on-device"

        try {
            val result = inferenceHarness.execute(
                config = HarnessConfig(timeoutMs = CV_INFERENCE_TIMEOUT_MS, maxRetries = 1),
                providers = buildProductProviderChain(bitmap, classifyFn, cloudClassifyFn),
                taskType = "cv:product-recognition"
            )
            recognition = result.value
            providerUsed = result.providerId
        } catch (e: InferenceHarnessException) {
            Timber.e(TAG, "[%s] All CV providers failed: %s", recognitionId, e.message)
            productVoiceFallbacks.incrementAndGet()
            _events.emit(VisionHarnessEvent.RecognitionFailed(
                recognitionId = recognitionId,
                pipeline = "product",
                error = e.message ?: "unknown",
                fallbackToVoice = true
            ))
            return VisionResult(
                recognitionId = recognitionId,
                recognition = null,
                confidenceLevel = ConfidenceLevel.NONE,
                recommendedAction = RecommendedAction.VOICE_FALLBACK,
                providerUsed = "none",
                latencyMs = System.currentTimeMillis() - startTime,
                pipeline = "product"
            )
        }

        if (recognition == null) {
            productVoiceFallbacks.incrementAndGet()
            return VisionResult(
                recognitionId = recognitionId,
                recognition = null,
                confidenceLevel = ConfidenceLevel.NONE,
                recommendedAction = RecommendedAction.VOICE_FALLBACK,
                providerUsed = providerUsed,
                latencyMs = System.currentTimeMillis() - startTime,
                pipeline = "product"
            )
        }

        // 2. Apply correction-based confidence adjustments
        val adjustedConfidence = applyCorrectionAdjustments(recognition)

        // 3. Determine confidence level and recommended action
        val confidenceLevel = classifyConfidence(adjustedConfidence)
        val recommendedAction = determineProductAction(confidenceLevel)

        // 4. Update per-product stats
        updateProductStats(recognition.productSwahili, adjustedConfidence, confidenceLevel)

        // 5. Track confidence distribution
        when (confidenceLevel) {
            ConfidenceLevel.HIGH -> productHighConf.incrementAndGet()
            ConfidenceLevel.MEDIUM -> productMediumConf.incrementAndGet()
            ConfidenceLevel.LOW -> productLowConf.incrementAndGet()
            ConfidenceLevel.NONE -> {}
        }

        val latencyMs = System.currentTimeMillis() - startTime
        val adjustedRecognition = recognition.copy(confidence = adjustedConfidence)

        _events.emit(VisionHarnessEvent.ProductRecognized(
            recognitionId = recognitionId,
            product = adjustedRecognition.productSwahili,
            confidence = adjustedConfidence,
            confidenceLevel = confidenceLevel,
            recommendedAction = recommendedAction,
            latencyMs = latencyMs
        ))

        Timber.d(TAG, "[%s] Product: %s (%.0f%%, %s → %s) in %dms",
            recognitionId, adjustedRecognition.productSwahili,
            adjustedConfidence * 100, confidenceLevel.name,
            recommendedAction.name, latencyMs)

        return VisionResult(
            recognitionId = recognitionId,
            recognition = adjustedRecognition,
            confidenceLevel = confidenceLevel,
            recommendedAction = recommendedAction,
            providerUsed = providerUsed,
            latencyMs = latencyMs,
            pipeline = "product"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // RECEIPT SCANNING — OCR + parse with quality gates
    // ═══════════════════════════════════════════════════════════════

    /**
     * Scan a receipt with full harness protection.
     *
     * Pipeline: Camera → OCR → Parse → Quality Gate → Confidence Gate → Result
     *
     * Quality gates check:
     * - OCR returned text
     * - Items were parsed
     * - Total is reasonable
     * - Items total matches declared total
     *
     * When quality/confidence is low, returns [RecommendedAction.ASK_WORKER]
     * to prompt the worker for manual item entry or voice input.
     *
     * @param bitmap Receipt photo
     * @param workerId Worker profile ID
     * @return Receipt scan result with quality/confidence assessment
     */
    suspend fun scanReceipt(
        bitmap: Bitmap,
        workerId: Long = 1
    ): ReceiptScanResult {
        val scanId = UUID.randomUUID().toString().take(12)
        val startTime = System.currentTimeMillis()
        receiptScans.incrementAndGet()

        try {
            // 1. Run OCR + parsing with timeout
            val receiptData = withTimeout(RECEIPT_SCAN_TIMEOUT_MS) {
                receiptScanner.scanReceipt(bitmap)
            }

            if (receiptData == null) {
                receiptVoiceFallbacks.incrementAndGet()
                _events.emit(VisionHarnessEvent.ReceiptScanFailed(
                    scanId = scanId,
                    error = "OCR returned no text",
                    fallbackToVoice = true
                ))
                return ReceiptScanResult(
                    scanId = scanId,
                    receiptData = null,
                    confidence = 0.0,
                    confidenceLevel = ConfidenceLevel.NONE,
                    recommendedAction = RecommendedAction.VOICE_FALLBACK,
                    qualityIssues = listOf("OCR haikupata maandishi yoyote"),
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }

            // 2. Assess receipt quality
            val quality = assessReceiptQuality(receiptData)

            // 3. Determine confidence and action
            val confidenceLevel = classifyConfidence(quality.confidence)
            val recommendedAction = determineReceiptAction(confidenceLevel, quality)

            // 4. Track metrics
            receiptProcessingTimeTotalMs.addAndGet(
                System.currentTimeMillis() - startTime
            )
            when (confidenceLevel) {
                ConfidenceLevel.HIGH -> receiptHighConf.incrementAndGet()
                ConfidenceLevel.MEDIUM -> receiptMediumConf.incrementAndGet()
                ConfidenceLevel.LOW -> receiptLowConf.incrementAndGet()
                ConfidenceLevel.NONE -> {}
            }

            val latencyMs = System.currentTimeMillis() - startTime

            _events.emit(VisionHarnessEvent.ReceiptScanned(
                scanId = scanId,
                itemCount = receiptData.items.size,
                total = receiptData.total,
                confidence = quality.confidence,
                confidenceLevel = confidenceLevel,
                recommendedAction = recommendedAction,
                latencyMs = latencyMs
            ))

            Timber.d(TAG, "[%s] Receipt: %d items, KSh %.0f (%.0f%%, %s → %s) in %dms",
                scanId, receiptData.items.size, receiptData.total,
                quality.confidence * 100, confidenceLevel.name,
                recommendedAction.name, latencyMs)

            return ReceiptScanResult(
                scanId = scanId,
                receiptData = receiptData,
                confidence = quality.confidence,
                confidenceLevel = confidenceLevel,
                recommendedAction = recommendedAction,
                qualityIssues = quality.issues,
                latencyMs = latencyMs
            )

        } catch (e: TimeoutCancellationException) {
            Timber.w(TAG, "[%s] Receipt scan timed out", scanId)
            receiptVoiceFallbacks.incrementAndGet()
            _events.emit(VisionHarnessEvent.ReceiptScanFailed(
                scanId = scanId,
                error = "Scan timed out",
                fallbackToVoice = true
            ))
            return ReceiptScanResult(
                scanId = scanId,
                receiptData = null,
                confidence = 0.0,
                confidenceLevel = ConfidenceLevel.NONE,
                recommendedAction = RecommendedAction.VOICE_FALLBACK,
                qualityIssues = listOf("Muda wa kusoma risiti umekwisha"),
                latencyMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Throwable) {
            Timber.e(e, "[%s] Receipt scan failed", scanId)
            receiptVoiceFallbacks.incrementAndGet()
            return ReceiptScanResult(
                scanId = scanId,
                receiptData = null,
                confidence = 0.0,
                confidenceLevel = ConfidenceLevel.NONE,
                recommendedAction = RecommendedAction.VOICE_FALLBACK,
                qualityIssues = listOf("Hitilafu: ${e.message ?: "haijulikani"}"),
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Assess receipt parse quality and assign a confidence score.
     *
     * Heuristics:
     * - Has items + total matches sum → high confidence (0.90)
     * - Has items + total present but mismatch → medium (0.60)
     * - Has items but no total → medium (0.75)
     * - Has total only, no items → low (0.55)
     * - Empty → none (0.0)
     */
    private fun assessReceiptQuality(receipt: ReceiptData): ReceiptQuality {
        val issues = mutableListOf<String>()
        var confidence = 0.0

        val hasItems = receipt.items.size >= MIN_RECEIPT_ITEMS
        val hasTotal = receipt.total >= MIN_RECEIPT_TOTAL
        val hasMerchant = receipt.merchantName.isNotBlank()

        when {
            // Best case: items + total + they agree
            hasItems && hasTotal -> {
                val itemsSum = receipt.items.sumOf { it.totalPrice }
                val diff = kotlin.math.abs(itemsSum - receipt.total)
                val diffRatio = if (receipt.total > 0) diff / receipt.total else 0.0

                confidence = if (diffRatio < 0.15) {
                    // Total matches items within 15%
                    RECEIPT_FULL_MATCH_CONFIDENCE
                } else {
                    // Total doesn't match items — possible OCR error
                    issues.add("Jumla (KSh ${"%.0f".format(receipt.total)}) haifanani na bidhaa (KSh ${"%.0f".format(itemsSum)})")
                    RECEIPT_MISMATCH_CONFIDENCE
                }
            }

            // Has items but no total extracted
            hasItems && !hasTotal -> {
                confidence = RECEIPT_BASE_CONFIDENCE
                issues.add("Jumla haikupatikana — jumla ya bidhaa ni KSh ${"%.0f".format(receipt.items.sumOf { it.totalPrice })}")
            }

            // Total only, no items — OCR probably missed line items
            !hasItems && hasTotal -> {
                confidence = RECEIPT_TOTAL_ONLY_CONFIDENCE
                issues.add("Bidhaa hazikupatikana — jumla tu ndiyo imepatikana")
            }

            // Nothing useful
            else -> {
                confidence = 0.0
                issues.add("Hakuna data yoyote iliyo patikana kutoka risiti")
            }
        }

        // Merchant name is a quality signal
        if (!hasMerchant && hasItems) {
            issues.add("Jina la duka halikupatikana")
            confidence *= 0.95 // Slight penalty
        }

        // Check for suspiciously high item count (OCR noise)
        if (receipt.items.size > 30) {
            issues.add("Bidhaa nyingi sana (${receipt.items.size}) — huenda OCR imeharibu")
            confidence *= 0.85
        }

        return ReceiptQuality(
            confidence = confidence.coerceIn(0.0, 1.0),
            issues = issues,
            hasItems = hasItems,
            hasTotal = hasTotal,
            hasMerchant = hasMerchant
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // CONFIRMATION & CORRECTION — Human-in-the-loop
    // ═══════════════════════════════════════════════════════════════

    /**
     * Worker confirms a product recognition was correct.
     * Strengthens the confidence for this product.
     */
    suspend fun confirmProductRecognition(
        recognition: ProductRecognition,
        workerId: Long = 1
    ) {
        productAutoAccepted.incrementAndGet()

        val stats = productStats.getOrPut(recognition.productSwahili) {
            ProductVisionStats(recognition.productSwahili)
        }
        stats.recordConfirmation()

        _events.emit(VisionHarnessEvent.ProductConfirmed(
            product = recognition.productSwahili,
            confidence = recognition.confidence,
            workerId = workerId
        ))

        Timber.d(TAG, "Product confirmed: %s (total confirms: %d)",
            recognition.productSwahili, stats.confirmations)
    }

    /**
     * Worker corrects a product misidentification.
     * "Hii si nyanya, ni pilipili" → records correction and learns.
     *
     * @param predicted What the classifier said
     * @param correctedSwahili What the worker says it is
     * @param workerId Worker profile ID
     */
    suspend fun recordProductCorrection(
        predicted: ProductRecognition,
        correctedSwahili: String,
        workerId: Long = 1
    ) {
        productCorrections.incrementAndGet()

        // Record in correction tracker (feeds into federated learning)
        correctionTracker.recordVoiceCorrection(
            predictedProductSwahili = predicted.productSwahili,
            correctedProductSwahili = correctedSwahili,
            confidence = predicted.confidence,
            workerId = workerId
        )

        // Update per-product stats for both predicted and corrected
        val predictedStats = productStats.getOrPut(predicted.productSwahili) {
            ProductVisionStats(predicted.productSwahili)
        }
        predictedStats.recordMisidentification()

        val correctedStats = productStats.getOrPut(correctedSwahili) {
            ProductVisionStats(correctedSwahili)
        }
        correctedStats.recordConfirmation()

        _events.emit(VisionHarnessEvent.ProductCorrectionRecorded(
            predicted = predicted.productSwahili,
            corrected = correctedSwahili,
            confidence = predicted.confidence,
            workerId = workerId
        ))

        Timber.i(TAG, "Product correction: %s → %s (was %.0f%% confident)",
            predicted.productSwahili, correctedSwahili, predicted.confidence * 100)
    }

    /**
     * Worker corrects a receipt scan item.
     * Records the correction to improve future receipt parsing.
     *
     * @param originalItem The incorrectly parsed item
     * @param correctedName What the item actually is
     * @param correctedPrice The correct price (0 = don't change)
     * @param workerId Worker profile ID
     */
    suspend fun recordReceiptCorrection(
        originalItem: ReceiptItem,
        correctedName: String,
        correctedPrice: Double = 0.0,
        workerId: Long = 1
    ) {
        receiptCorrections.incrementAndGet()

        // Feed correction into ReceiptScanner's learning
        receiptScanner.learnCorrection(originalItem.itemName, correctedName)

        // Track per-item correction frequency
        val key = originalItem.itemName.lowercase().trim()
        val stats = receiptItemCorrections.getOrPut(key) { ItemCorrectionStats(key) }
        stats.recordCorrection(correctedName)

        _events.emit(VisionHarnessEvent.ReceiptCorrectionRecorded(
            originalItem = originalItem.itemName,
            correctedItem = correctedName,
            originalPrice = originalItem.totalPrice,
            correctedPrice = correctedPrice,
            workerId = workerId
        ))

        Timber.i(TAG, "Receipt correction: '%s' → '%s' (KSh %.0f → %.0f)",
            originalItem.itemName, correctedName,
            originalItem.totalPrice, if (correctedPrice > 0) correctedPrice else originalItem.totalPrice)
    }

    /**
     * Worker confirms a receipt scan was fully correct.
     */
    suspend fun confirmReceiptScan(
        receipt: ReceiptData,
        workerId: Long = 1
    ) {
        receipt.items.forEach { item ->
            // Positive feedback: no correction needed
            val stats = receiptItemCorrections.getOrPut(item.itemName.lowercase()) {
                ItemCorrectionStats(item.itemName.lowercase())
            }
            stats.recordCorrectScan()
        }

        _events.emit(VisionHarnessEvent.ReceiptConfirmed(
            itemCount = receipt.items.size,
            total = receipt.total,
            workerId = workerId
        ))

        Timber.d(TAG, "Receipt confirmed: %d items, KSh %.0f", receipt.items.size, receipt.total)
    }

    // ═══════════════════════════════════════════════════════════════
    // CONFIDENCE LOGIC
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classify confidence into levels.
     * Threshold: < 0.70 → LOW (ask worker for confirmation / voice fallback)
     */
    private fun classifyConfidence(confidence: Double): ConfidenceLevel {
        return when {
            confidence >= HIGH_CONFIDENCE -> ConfidenceLevel.HIGH
            confidence >= MEDIUM_CONFIDENCE -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW   // < 0.70 → ask worker
        }
    }

    /**
     * Determine action for product recognition based on confidence.
     */
    private fun determineProductAction(level: ConfidenceLevel): RecommendedAction {
        return when (level) {
            ConfidenceLevel.HIGH -> RecommendedAction.AUTO_ACCEPT
            ConfidenceLevel.MEDIUM -> RecommendedAction.CONFIRM_WITH_WORKER
            ConfidenceLevel.LOW -> RecommendedAction.ASK_WORKER
            ConfidenceLevel.NONE -> RecommendedAction.VOICE_FALLBACK
        }
    }

    /**
     * Determine action for receipt scanning based on confidence and quality.
     */
    private fun determineReceiptAction(
        level: ConfidenceLevel,
        quality: ReceiptQuality
    ): RecommendedAction {
        return when {
            // No useful data at all → voice fallback
            level == ConfidenceLevel.NONE -> RecommendedAction.VOICE_FALLBACK

            // Low confidence → ask worker to verify or enter manually
            level == ConfidenceLevel.LOW -> {
                if (quality.hasItems) {
                    // We have items but low confidence → ask worker to confirm each
                    RecommendedAction.ASK_WORKER
                } else {
                    // No items parsed → voice fallback
                    RecommendedAction.VOICE_FALLBACK
                }
            }

            // Medium → confirm with worker
            level == ConfidenceLevel.MEDIUM -> RecommendedAction.CONFIRM_WITH_WORKER

            // High → auto-accept
            else -> RecommendedAction.AUTO_ACCEPT
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

    private fun buildProductProviderChain(
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

        // Text-only fallback: return null to signal "ask the worker via voice"
        providers.add(
            ProviderCandidate(
                providerId = "cv-voice-fallback",
                modelId = "voice-input",
                provider = { null }
            )
        )

        return providers
    }

    // ═══════════════════════════════════════════════════════════════
    // METRICS & MONITORING
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get comprehensive vision harness stats for both pipelines.
     */
    fun getStats(): VisionHarnessStats {
        val totalProduct = productRecognitions.get()
        val totalReceipt = receiptScans.get()
        val productCorrs = productCorrections.get()
        val receiptCorrs = receiptCorrections.get()

        return VisionHarnessStats(
            // Product recognition
            productRecognitions = totalProduct,
            productHighConfidence = productHighConf.get(),
            productMediumConfidence = productMediumConf.get(),
            productLowConfidence = productLowConf.get(),
            productCorrections = productCorrs,
            productAutoAccepted = productAutoAccepted.get(),
            productVoiceFallbacks = productVoiceFallbacks.get(),
            productAccuracy = if (totalProduct > 0) 1.0 - (productCorrs.toDouble() / totalProduct) else 1.0,
            productCorrectionRate = if (totalProduct > 0) productCorrs.toDouble() / totalProduct else 0.0,

            // Receipt scanning
            receiptScans = totalReceipt,
            receiptHighConfidence = receiptHighConf.get(),
            receiptMediumConfidence = receiptMediumConf.get(),
            receiptLowConfidence = receiptLowConf.get(),
            receiptCorrections = receiptCorrs,
            receiptVoiceFallbacks = receiptVoiceFallbacks.get(),
            receiptAvgProcessingTimeMs = if (totalReceipt > 0)
                receiptProcessingTimeTotalMs.get() / totalReceipt else 0,
            receiptAccuracy = if (totalReceipt > 0) 1.0 - (receiptCorrs.toDouble() / totalReceipt) else 1.0,
            receiptCorrectionRate = if (totalReceipt > 0) receiptCorrs.toDouble() / totalReceipt else 0.0,

            // Per-product breakdown
            productStats = productStats.mapValues { (_, s) -> s.snapshot() },

            // Most corrected receipt items
            mostCorrectedItems = getMostCorrectedReceiptItems(5)
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

    /**
     * Get the most commonly corrected receipt items.
     * Returns items sorted by correction frequency (highest first).
     */
    fun getMostCorrectedReceiptItems(limit: Int = 5): List<ItemCorrectionStatsSnapshot> {
        return receiptItemCorrections.values
            .map { it.snapshot() }
            .filter { it.totalScans >= 2 }
            .sortedByDescending { it.correctionRate }
            .take(limit)
    }

    /**
     * Get accuracy breakdown per product for diagnostics.
     */
    fun getAccuracyPerProduct(): Map<String, Double> {
        return productStats.mapValues { (_, stats) ->
            val snap = stats.snapshot()
            snap.accuracy
        }
    }

    /**
     * Get processing time stats.
     */
    fun getProcessingTimeStats(): ProcessingTimeStats {
        val totalReceipt = receiptScans.get()
        return ProcessingTimeStats(
            receiptAvgMs = if (totalReceipt > 0)
                receiptProcessingTimeTotalMs.get() / totalReceipt else 0,
            receiptTotalScans = totalReceipt
        )
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
     * Result of a harness-wrapped product recognition.
     */
    data class VisionResult(
        val recognitionId: String,
        val recognition: ProductRecognition?,
        val confidenceLevel: ConfidenceLevel,
        val recommendedAction: RecommendedAction,
        val providerUsed: String,
        val latencyMs: Long,
        val pipeline: String = "product"
    ) {
        /** Whether the recognition is reliable enough to auto-accept */
        val isAutoAcceptable: Boolean get() = recommendedAction == RecommendedAction.AUTO_ACCEPT

        /** Whether we need worker confirmation */
        val needsConfirmation: Boolean get() = recommendedAction == RecommendedAction.CONFIRM_WITH_WORKER

        /** Whether we should ask the worker to identify the product */
        val shouldAskWorker: Boolean get() = recommendedAction == RecommendedAction.ASK_WORKER

        /** Whether CV failed and we should fall back to voice input */
        val shouldFallbackToVoice: Boolean get() = recommendedAction == RecommendedAction.VOICE_FALLBACK

        /** Voice prompt based on confidence */
        fun getVoicePrompt(): String {
            return when (recommendedAction) {
                RecommendedAction.AUTO_ACCEPT ->
                    "Hii ni ${recognition?.productSwahili} — bei ya soko ni KSh ${recognition?.suggestedPriceKSh?.toInt()}"
                RecommendedAction.CONFIRM_WITH_WORKER ->
                    "Je, ni ${recognition?.productSwahili}? (Nimeona kwa ${"%.0f".format((recognition?.confidence ?: 0.0) * 100)}%)"
                RecommendedAction.ASK_WORKER ->
                    "Sijui hii ni nini. Sema jina la bidhaa."
                RecommendedAction.VOICE_FALLBACK ->
                    "Kamera haikuweza kutambua. Sema jina la bidhaa na bei."
            }
        }
    }

    /**
     * Result of a harness-wrapped receipt scan.
     */
    data class ReceiptScanResult(
        val scanId: String,
        val receiptData: ReceiptData?,
        val confidence: Double,
        val confidenceLevel: ConfidenceLevel,
        val recommendedAction: RecommendedAction,
        val qualityIssues: List<String> = emptyList(),
        val latencyMs: Long
    ) {
        /** Whether the scan is reliable enough to auto-accept */
        val isAutoAcceptable: Boolean get() = recommendedAction == RecommendedAction.AUTO_ACCEPT

        /** Whether we need the worker to confirm items */
        val needsConfirmation: Boolean get() = recommendedAction == RecommendedAction.CONFIRM_WITH_WORKER

        /** Whether we should ask the worker to enter items manually */
        val shouldAskWorker: Boolean get() = recommendedAction == RecommendedAction.ASK_WORKER

        /** Whether CV failed and we should fall back to voice input */
        val shouldFallbackToVoice: Boolean get() = recommendedAction == RecommendedAction.VOICE_FALLBACK

        /** Voice prompt based on scan result */
        fun getVoicePrompt(): String {
            return when (recommendedAction) {
                RecommendedAction.AUTO_ACCEPT -> {
                    val items = receiptData?.items?.joinToString(", ") {
                        "${it.itemName} KSh ${"%.0f".format(it.totalPrice)}"
                    } ?: ""
                    "Risiti imesomwa: $items. Jumla ni KSh ${"%.0f".format(receiptData?.total ?: 0.0)}"
                }
                RecommendedAction.CONFIRM_WITH_WORKER -> {
                    val items = receiptData?.items?.joinToString(", ") {
                        "${it.itemName} KSh ${"%.0f".format(it.totalPrice)}"
                    } ?: ""
                    "Nimesoma risiti: $items. Je, ni sahihi?"
                }
                RecommendedAction.ASK_WORKER ->
                    "Sijaweza kusoma risiti vizuri. Sema bidhaa na bei moja moja."
                RecommendedAction.VOICE_FALLBACK ->
                    "Kamera haikuweza kusoma risiti. Sema bidhaa na bei kwa sauti."
            }
        }
    }

    // ── Confidence levels ──────────────────────────────────────────

    enum class ConfidenceLevel {
        /** ≥ 0.85: Very confident, auto-accept */
        HIGH,
        /** 0.70–0.85: Moderately confident, ask for confirmation */
        MEDIUM,
        /** < 0.70: Low confidence — ask worker or fallback to voice */
        LOW,
        /** No usable prediction at all */
        NONE
    }

    // ── Recommended actions ────────────────────────────────────────

    enum class RecommendedAction {
        /** Automatically accept and announce to worker */
        AUTO_ACCEPT,
        /** Announce with "Je, ni X?" and wait for confirmation */
        CONFIRM_WITH_WORKER,
        /** Don't guess — ask the worker to identify */
        ASK_WORKER,
        /** CV failed completely — fall back to voice input */
        VOICE_FALLBACK
    }

    // ── Per-product vision accuracy stats ──────────────────────────

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

    // ── Per-receipt-item correction stats ──────────────────────────

    class ItemCorrectionStats(private val itemName: String) {
        var totalScans = 0L; private set
        var corrections = 0L; private set
        val correctionTargets = mutableMapOf<String, Int>()

        fun recordCorrection(correctedTo: String) {
            totalScans++
            corrections++
            correctionTargets[correctedTo] = (correctionTargets[correctedTo] ?: 0) + 1
        }

        fun recordCorrectScan() {
            totalScans++
        }

        fun snapshot() = ItemCorrectionStatsSnapshot(
            itemName = itemName,
            totalScans = totalScans,
            corrections = corrections,
            correctionRate = if (totalScans > 0) corrections.toDouble() / totalScans else 0.0,
            mostCommonCorrection = correctionTargets.maxByOrNull { it.value }?.key
        )
    }

    data class ItemCorrectionStatsSnapshot(
        val itemName: String,
        val totalScans: Long,
        val corrections: Long,
        val correctionRate: Double,
        val mostCommonCorrection: String?
    )

    // ── Receipt quality assessment ─────────────────────────────────

    private data class ReceiptQuality(
        val confidence: Double,
        val issues: List<String>,
        val hasItems: Boolean,
        val hasTotal: Boolean,
        val hasMerchant: Boolean
    )

    // ── Processing time stats ──────────────────────────────────────

    data class ProcessingTimeStats(
        val receiptAvgMs: Long,
        val receiptTotalScans: Long
    )

    // ── Aggregate stats ────────────────────────────────────────────

    data class VisionHarnessStats(
        // Product recognition
        val productRecognitions: Long,
        val productHighConfidence: Long,
        val productMediumConfidence: Long,
        val productLowConfidence: Long,
        val productCorrections: Long,
        val productAutoAccepted: Long,
        val productVoiceFallbacks: Long,
        val productAccuracy: Double,
        val productCorrectionRate: Double,

        // Receipt scanning
        val receiptScans: Long,
        val receiptHighConfidence: Long,
        val receiptMediumConfidence: Long,
        val receiptLowConfidence: Long,
        val receiptCorrections: Long,
        val receiptVoiceFallbacks: Long,
        val receiptAvgProcessingTimeMs: Long,
        val receiptAccuracy: Double,
        val receiptCorrectionRate: Double,

        // Per-product breakdown
        val productStats: Map<String, ProductVisionStatsSnapshot>,

        // Most corrected receipt items
        val mostCorrectedItems: List<ItemCorrectionStatsSnapshot>
    )
}

// ═══════════════════════════════════════════════════════════════
// EVENTS
// ═══════════════════════════════════════════════════════════════

sealed class VisionHarnessEvent {

    // ── Product recognition events ──

    data class ProductRecognized(
        val recognitionId: String,
        val product: String,
        val confidence: Double,
        val confidenceLevel: VisionHarness.ConfidenceLevel,
        val recommendedAction: VisionHarness.RecommendedAction,
        val latencyMs: Long
    ) : VisionHarnessEvent()

    data class ProductConfirmed(
        val product: String,
        val confidence: Double,
        val workerId: Long
    ) : VisionHarnessEvent()

    data class ProductCorrectionRecorded(
        val predicted: String,
        val corrected: String,
        val confidence: Double,
        val workerId: Long
    ) : VisionHarnessEvent()

    data class RecognitionFailed(
        val recognitionId: String,
        val pipeline: String,
        val error: String,
        val fallbackToVoice: Boolean
    ) : VisionHarnessEvent()

    // ── Receipt scanning events ──

    data class ReceiptScanned(
        val scanId: String,
        val itemCount: Int,
        val total: Double,
        val confidence: Double,
        val confidenceLevel: VisionHarness.ConfidenceLevel,
        val recommendedAction: VisionHarness.RecommendedAction,
        val latencyMs: Long
    ) : VisionHarnessEvent()

    data class ReceiptConfirmed(
        val itemCount: Int,
        val total: Double,
        val workerId: Long
    ) : VisionHarnessEvent()

    data class ReceiptCorrectionRecorded(
        val originalItem: String,
        val correctedItem: String,
        val originalPrice: Double,
        val correctedPrice: Double,
        val workerId: Long
    ) : VisionHarnessEvent()

    data class ReceiptScanFailed(
        val scanId: String,
        val error: String,
        val fallbackToVoice: Boolean
    ) : VisionHarnessEvent()
}
