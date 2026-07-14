package com.msaidizi.app.vision

import android.graphics.Bitmap
import com.msaidizi.app.core.database.InventoryDao
import com.msaidizi.app.core.model.InventoryItem
import com.msaidizi.app.core.model.WorkerVocabularyDao
import com.msaidizi.app.voice.TextToSpeech
import com.msaidizi.app.vision.VisionHarness
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Product Recognition Handler — Orchestrates the full recognition pipeline.
 *
 * Ties together:
 * - ProductClassifier (ONNX inference)
 * - ProductDatabase (metadata lookup)
 * - InventoryDao (stock tracking)
 * - TextToSpeech (voice prompts)
 * - VisionCorrectionTracker (learning)
 *
 * ## Flow
 * ```
 * Camera frame → classify() → voice prompt → confirm → inventory update
 *                                     ↓ (if wrong)
 *                              correction → learn
 *
 * ## Memory Budget
 * Total additional memory on top of existing app:
 * - ProductClassifier: ~17MB (ONNX model + tensors)
 * - This handler: ~1MB (caches + state)
 * - CameraCaptureFragment: ~5MB (preview + capture buffers)
 * - Grand total: ~23MB — fits in 2GB phones
 */
@Singleton
class ProductRecognitionHandler @Inject constructor(
    private val classifier: ProductClassifier,
    private val inventoryDao: InventoryDao,
    private val workerVocabularyDao: WorkerVocabularyDao,
    private val correctionTracker: VisionCorrectionTracker,
    private val tts: TextToSpeech,
    private val visionHarness: VisionHarness
) {
    companion object {
        private const val TAG = "ProductRecognition"
        private const val DEBOUNCE_MS = 1500L  // Don't re-classify same frame within 1.5s
        private const val LOW_STOCK_THRESHOLD = 5.0
    }

    // ── State ──
    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Uninitialized)
    val state: StateFlow<RecognitionState> = _state

    private val _lastRecognition = MutableStateFlow<ProductRecognition?>(null)
    val lastRecognition: StateFlow<ProductRecognition?> = _lastRecognition

    private val _inventoryActions = MutableStateFlow<List<InventoryAction>>(emptyList())
    val inventoryActions: StateFlow<List<InventoryAction>> = _inventoryActions

    private var lastClassifyTime = 0L
    private var currentWorkerId = 1L

    // ── Initialization ─────────────────────────────────────────────

    /**
     * Initialize the recognition pipeline.
     * Loads the ONNX model and prepares the classifier.
     *
     * @param workerId Worker profile ID for vocabulary tracking
     */
    suspend fun initialize(workerId: Long = 1) = withContext(Dispatchers.IO) {
        currentWorkerId = workerId
        _state.value = RecognitionState.Loading

        val loaded = classifier.loadModel()
        _state.value = if (loaded) {
            Timber.i(TAG, "Product recognition ready")
            RecognitionState.Ready
        } else {
            Timber.w(TAG, "Failed to load product classifier")
            RecognitionState.Error("Model haikupatikana. Jaribu tena baadaye.")
        }
    }

    /**
     * Cleanup resources.
     */
    fun cleanup() {
        classifier.unloadModel()
        _state.value = RecognitionState.Uninitialized
    }

    // ── Classification ─────────────────────────────────────────────

    /**
     * Classify a camera frame.
     *
     * Debounces to avoid classifying every preview frame.
     * Updates state with the result.
     *
     * @param bitmap Camera frame (any size)
     * @return Product recognition result, or null
     */
    suspend fun classifyFrame(bitmap: Bitmap): ProductRecognition? =
        withContext(Dispatchers.Default) {
            // Debounce: skip if classified recently
            val now = System.currentTimeMillis()
            if (now - lastClassifyTime < DEBOUNCE_MS) {
                return@withContext _lastRecognition.value
            }

            if (!classifier.isModelReady()) {
                _state.value = RecognitionState.Error("Model haijapakiwa")
                return@withContext null
            }

            _state.value = RecognitionState.Classifying
            lastClassifyTime = now

            // Route through VisionHarness for confidence gates, fallback, and monitoring
            val visionResult = visionHarness.recognize(
                bitmap = bitmap,
                classifyFn = { bmp -> classifier.classify(bmp) },
                workerId = currentWorkerId
            )

            val recognition = visionResult.recognition
            if (recognition != null) {
                _lastRecognition.value = recognition

                // Act on harness recommendation
                when (visionResult.recommendedAction) {
                    VisionHarness.RecommendedAction.AUTO_ACCEPT -> {
                        _state.value = RecognitionState.Result(recognition)
                    }
                    VisionHarness.RecommendedAction.CONFIRM_WITH_WORKER -> {
                        _state.value = RecognitionState.AwaitingConfirmation(recognition)
                    }
                    VisionHarness.RecommendedAction.ASK_WORKER -> {
                        _state.value = RecognitionState.Result(recognition)
                    }
                }

                Timber.d(
                    TAG, "Recognized via harness: %s (%.0f%%, KSh %.0f, action=%s)",
                    recognition.productSwahili,
                    recognition.confidence * 100,
                    recognition.suggestedPriceKSh,
                    visionResult.recommendedAction.name
                )

                recognition
            } else {
                _state.value = RecognitionState.Error("Sikuweza kutambua bidhaa")
                null
            }
        }

    /**
     * Speak the recognition result.
     * "Hii ni nyanya — bei ya soko ni KSh 50"
     */
    suspend fun announceRecognition(recognition: ProductRecognition) {
        if (recognition.isReliable) {
            tts.speak(recognition.voiceDescription, "sw")
        } else {
            tts.speak("Sijui hii ni nini. Sema jina la bidhaa.", "sw")
        }
    }

    // ── Confirmation & Correction ──────────────────────────────────

    /**
     * Confirm the recognition and offer to add to inventory.
     * Called when worker says "Ndio" or taps "Confirm".
     *
     * @param recognition The recognition to confirm
     * @return Inventory action result
     */
    suspend fun confirmAndAddToInventory(recognition: ProductRecognition): InventoryAction =
        withContext(Dispatchers.IO) {
            _state.value = RecognitionState.AwaitingConfirmation(recognition)

            // Check current stock
            val existingItem = inventoryDao.getItem(recognition.productSwahili)
            val currentStock = existingItem?.currentStock ?: 0.0

            val action = if (existingItem != null) {
                // Update existing inventory
                val newStock = currentStock + 1
                inventoryDao.incrementStock(
                    item = recognition.productSwahili,
                    quantity = 1.0,
                    newAvgCost = recognition.suggestedPriceKSh
                )
                InventoryAction(
                    productSwahili = recognition.productSwahili,
                    quantity = newStock.toInt(),
                    unitPriceKSh = recognition.suggestedPriceKSh,
                    totalPriceKSh = recognition.suggestedPriceKSh,
                    action = InventoryAction.ActionType.QUANTITY_UPDATED
                )
            } else {
                // Create new inventory entry
                inventoryDao.upsert(
                    InventoryItem(
                        item = recognition.productSwahili,
                        category = recognition.category,
                        currentStock = 1.0,
                        unit = ProductDatabase.getBySwahiliName(recognition.productSwahili)?.unit ?: "pieces",
                        avgCost = recognition.suggestedPriceKSh,
                        restockThreshold = LOW_STOCK_THRESHOLD,
                        lastRestockedAt = System.currentTimeMillis() / 1000
                    )
                )
                InventoryAction(
                    productSwahili = recognition.productSwahili,
                    quantity = 1,
                    unitPriceKSh = recognition.suggestedPriceKSh,
                    totalPriceKSh = recognition.suggestedPriceKSh,
                    action = InventoryAction.ActionType.ADDED_TO_STOCK
                )
            }

            // Update actions history
            _inventoryActions.value = _inventoryActions.value + action

            // Voice confirmation
            tts.speak(action.confirmation, "sw")

            _state.value = RecognitionState.Ready
            action
        }

    /**
     * Add a specific quantity to inventory.
     * Called when worker says "Ongeza 12" or enters a number.
     *
     * @param recognition The recognition to add stock for
     * @param quantity How many to add
     */
    suspend fun addQuantityToInventory(
        recognition: ProductRecognition,
        quantity: Int
    ): InventoryAction = withContext(Dispatchers.IO) {
        val totalCost = recognition.suggestedPriceKSh * quantity
        val existingItem = inventoryDao.getItem(recognition.productSwahili)

        if (existingItem != null) {
            inventoryDao.incrementStock(
                item = recognition.productSwahili,
                quantity = quantity.toDouble(),
                newAvgCost = recognition.suggestedPriceKSh
            )
        } else {
            inventoryDao.upsert(
                InventoryItem(
                    item = recognition.productSwahili,
                    category = recognition.category,
                    currentStock = quantity.toDouble(),
                    unit = ProductDatabase.getBySwahiliName(recognition.productSwahili)?.unit ?: "pieces",
                    avgCost = recognition.suggestedPriceKSh,
                    restockThreshold = LOW_STOCK_THRESHOLD,
                    lastRestockedAt = System.currentTimeMillis() / 1000
                )
            )
        }

        val action = InventoryAction(
            productSwahili = recognition.productSwahili,
            quantity = quantity,
            unitPriceKSh = recognition.suggestedPriceKSh,
            totalPriceKSh = totalCost,
            action = InventoryAction.ActionType.ADDED_TO_STOCK
        )

        _inventoryActions.value = _inventoryActions.value + action
        tts.speak(action.confirmation, "sw")
        action
    }

    /**
     * Reject the recognition — worker says "Hapana" or the classifier was wrong.
     * Prompts for correction.
     */
    suspend fun rejectRecognition(recognition: ProductRecognition) {
        _state.value = RecognitionState.AwaitingConfirmation(recognition)
        tts.speak("Sema jina sahihi la bidhaa.", "sw")
    }

    /**
     * Apply a correction from the worker.
     * "Hii si nyanya, ni pilipili"
     *
     * @param predicted What the classifier said
     * @param correctedSwahili What the worker says it actually is
     */
    suspend fun applyCorrection(
        predicted: ProductRecognition,
        correctedSwahili: String
    ) {
        // Route correction through VisionHarness for monitoring and learning
        visionHarness.recordCorrection(
            predicted = predicted,
            correctedSwahili = correctedSwahili,
            workerId = currentWorkerId
        )

        // Speak the corrected recognition
        val correctedProduct = ProductDatabase.getBySwahiliName(correctedSwahili)
        if (correctedProduct != null) {
            tts.speak(
                "Sawa! Hii ni ${correctedProduct.swahiliName}. Bei ya soko ni KSh ${correctedProduct.currentPriceKSh.toInt()}.",
                "sw"
            )
        }

        _state.value = RecognitionState.Ready
    }

    // ── Inventory Queries ──────────────────────────────────────────

    /**
     * Get current stock for a product.
     */
    suspend fun getStock(productSwahili: String): Double {
        return inventoryDao.getStock(productSwahili)
    }

    /**
     * Check if a product is low on stock.
     */
    suspend fun isLowStock(productSwahili: String): Boolean {
        val item = inventoryDao.getItem(productSwahili) ?: return false
        return item.currentStock <= item.restockThreshold
    }

    /**
     * Get voice-friendly stock status.
     * "Nyanya zako ni 12. Stock ni ya kutosha."
     */
    suspend fun getStockVoiceStatus(productSwahili: String): String {
        val item = inventoryDao.getItem(productSwahili)
        return if (item == null) {
            "$productSwahili haijawa kwenye stock."
        } else {
            val stock = item.currentStock.toInt()
            when {
                stock == 0 -> "$productSwahili zimeisha! Ongeza stock sasa hivi!"
                stock <= item.restockThreshold.toInt() ->
                    "$productSwahili ni $stock — stock ni chuka. Ongeza zaidi."
                else -> "$productSwahili ni $stock. Stock ni ya kutosha."
            }
        }
    }

    /**
     * Get items needing restock (voice summary).
     */
    suspend fun getLowStockSummary(): String {
        val items = inventoryDao.getItemsNeedingRestock()
        return if (items.isEmpty()) {
            "Stock yako ni nzima!"
        } else {
            val names = items.joinToString(", ") { "${it.item} (${it.currentStock.toInt()})" }
            "Vitu vinavyohitaji restock: $names"
        }
    }

    // ── Diagnostics ────────────────────────────────────────────────

    /**
     * Get system diagnostics for the recognition pipeline.
     */
    fun getDiagnostics(): Map<String, Any> {
        val correctionStats = correctionTracker.getStats()
        return mapOf(
            "model_loaded" to classifier.isModelReady(),
            "avg_inference_ms" to classifier.getAverageInferenceTimeMs(),
            "total_corrections" to correctionStats.totalCorrections,
            "confusion_pairs" to correctionStats.uniqueConfusionPairs,
            "worst_confusion" to correctionStats.worstConfusionPair,
            "pending_uploads" to correctionStats.pendingUploads,
            "state" to _state.value::class.simpleName.orEmpty()
        )
    }
}
