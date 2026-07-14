package com.msaidizi.app.vision

/**
 * Data models for the product recognition system.
 *
 * ProductClassifier → ProductRecognition → UI / Inventory / Voice
 */

/**
 * Result of a product recognition inference.
 *
 * @param productSwahili Product name in Swahili (e.g., "nyanya")
 * @param productEnglish Product name in English (e.g., "tomato")
 * @param category Product category (e.g., "produce")
 * @param confidence Classification confidence [0.0, 1.0]
 * @param suggestedPriceKSh Suggested market price in KSh
 * @param quantityEstimate Estimated count visible in frame (default 1)
 * @param processingTimeMs Total inference time in milliseconds
 * @param modelUsed Which model was used (e.g., "mobilenetv3-small")
 * @param classIndex Raw class index from the model
 */
data class ProductRecognition(
    val productSwahili: String,
    val productEnglish: String,
    val category: String,
    val confidence: Double,
    val suggestedPriceKSh: Double,
    val quantityEstimate: Int = 1,
    val processingTimeMs: Long = 0,
    val modelUsed: String = "mobilenetv3-small",
    val classIndex: Int = -1
) {
    /** Is this recognition confident enough to act on? */
    val isReliable: Boolean get() = confidence >= CONFIDENCE_THRESHOLD

    /** Voice-friendly Swahili description */
    val voiceDescription: String
        get() = if (isReliable) {
            "Hii ni $productSwahili — bei ya soko ni KSh ${suggestedPriceKSh.toInt()}"
        } else {
            "Sijui hii ni nini. Je, ni $productSwahili?"
        }

    /** Inventory prompt in Swahili */
    val inventoryPrompt: String
        get() {
            val count = if (quantityEstimate > 1) quantityEstimate else 1
            return "Nimeona $productSwahili $count — niongeze kwenye stock?"
        }

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.65
        const val HIGH_CONFIDENCE = 0.85
    }
}

/**
 * Product entry in the Kenyan produce catalog.
 *
 * Maps class indices to product metadata including
 * Swahili names, English names, and market prices.
 */
data class ProductEntry(
    val classIndex: Int,
    val swahiliName: String,
    val englishName: String,
    val category: String,
    val defaultPriceKSh: Double,
    val unit: String = "pieces",
    /** Seasonal price variation factor (1.0 = normal, >1 = expensive season) */
    val seasonalFactor: Double = 1.0,
    /** Common alternative Swahili names / dialectal variants */
    val aliases: List<String> = emptyList()
) {
    /** Price adjusted for current season */
    val currentPriceKSh: Double get() = defaultPriceKSh * seasonalFactor
}

/**
 * Correction from a worker when the classifier misidentifies.
 *
 * Flows into WorkerVocabulary and FederatedLearningClient.
 */
data class VisionCorrection(
    val predictedProductSwahili: String,
    val predictedConfidence: Double,
    val correctedProductSwahili: String,
    val correctedProductEnglish: String,
    val frameTimestampMs: Long = System.currentTimeMillis(),
    val workerId: Long = 1
)

/**
 * State of the real-time camera classification pipeline.
 */
sealed class RecognitionState {
    /** No model loaded yet */
    object Uninitialized : RecognitionState()

    /** Model is loading */
    object Loading : RecognitionState()

    /** Ready to classify frames */
    object Ready : RecognitionState()

    /** Actively classifying a frame */
    object Classifying : RecognitionState()

    /** Classification result available */
    data class Result(val recognition: ProductRecognition) : RecognitionState()

    /** Worker is confirming or correcting */
    data class AwaitingConfirmation(val recognition: ProductRecognition) : RecognitionState()

    /** Error occurred */
    data class Error(val message: String) : RecognitionState()
}

/**
 * Inventory action result after recognition.
 */
data class InventoryAction(
    val productSwahili: String,
    val quantity: Int,
    val unitPriceKSh: Double,
    val totalPriceKSh: Double,
    val action: ActionType,
    val timestampMs: Long = System.currentTimeMillis()
) {
    enum class ActionType {
        ADDED_TO_STOCK,
        QUANTITY_UPDATED,
        PRICE_UPDATED,
        REJECTED
    }

    /** Voice confirmation in Swahili */
    val confirmation: String
        get() = when (action) {
            ActionType.ADDED_TO_STOCK ->
                "Sawa! Nimeongeza $productSwahili $quantity kwenye stock. Bei ni KSh ${totalPriceKSh.toInt()}."
            ActionType.QUANTITY_UPDATED ->
                "Nimesasisha $productSwahili kuwa $quantity."
            ActionType.PRICE_UPDATED ->
                "Bei ya $productSwahili imesasishwa kuwa KSh ${unitPriceKSh.toInt()}."
            ActionType.REJECTED ->
                "Sijaongeza. Sema tena ukishuhudia."
        }
}
