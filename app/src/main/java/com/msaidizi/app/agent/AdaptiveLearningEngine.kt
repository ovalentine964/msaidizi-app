package com.msaidizi.app.agent

import com.msaidizi.app.core.database.PatternDao
import com.msaidizi.app.core.database.TransactionDao
import com.msaidizi.app.core.model.*
import com.msaidizi.app.core.util.SwahiliParser
import com.msaidizi.app.voice.LlmEngine
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptive Learning Engine — the brain of on-device personalization.
 *
 * Implements the 3-level adaptive learning architecture:
 * - Level 1: Rules (code-based, via SwahiliParser + IntentRouter) — already exists
 * - Level 2: Context injection (learn user's items, prices, patterns) — THIS CLASS
 * - Level 3: LoRA fine-tuning (advanced, future) — data collection only
 *
 * Core responsibilities:
 * 1. Track user corrections ("no, that was X not Y")
 * 2. Learn user's product vocabulary (what they sell, at what prices)
 * 3. Learn user's business patterns (busy days, seasonal trends)
 * 4. Inject learned context into LLM prompts for personalized advice
 * 5. Apply learned corrections to improve intent parsing
 * 6. Collect training data for future LoRA fine-tuning (Level 3)
 *
 * Privacy: ALL learning happens on-device. No data leaves the phone.
 *
 * Performance on 2GB device:
 * - Correction recording: <5ms (Room insert)
 * - Vocabulary lookup: <2ms (indexed query)
 * - Context generation: <50ms (aggregation queries)
 * - Memory: Stateless — all data in Room database
 */
@Singleton
class AdaptiveLearningEngine @Inject constructor(
    private val userVocabularyDao: UserVocabularyDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val transactionDao: TransactionDao,
    private val patternDao: PatternDao,
    private val patternTracker: BusinessPatternTracker,
    private val learningAgent: LearningAgent
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ═══════════════ CORRECTION TRACKING ═══════════════

    /**
     * Record a user correction.
     * Called when the user says "no, that was X not Y" or corrects a transaction.
     *
     * This is the primary learning signal. Each correction:
     * 1. Updates the vocabulary mapping (spoken → canonical)
     * 2. Updates price/quantity tracking
     * 3. Is stored for pattern analysis
     * 4. Is queued for future LoRA training data (Level 3)
     */
    suspend fun recordCorrection(
        correctionType: CorrectionType,
        originalValue: String,
        correctedValue: String,
        originalInput: String = "",
        correctionInput: String = "",
        transactionId: Long = 0
    ) = withContext(Dispatchers.IO) {
        Timber.i("Correction recorded: %s '%s' → '%s'",
            correctionType, originalValue, correctedValue)

        // 1. Store the correction
        val correction = UserCorrection(
            originalTransactionId = transactionId,
            correctionType = correctionType,
            originalValue = originalValue,
            correctedValue = correctedValue,
            originalInput = originalInput,
            correctionInput = correctionInput,
            applied = false
        )
        userCorrectionDao.insert(correction)

        // 2. Apply correction to vocabulary
        when (correctionType) {
            CorrectionType.ITEM -> {
                // User corrected an item name: learn the mapping
                learnVocabularyMapping(originalValue, correctedValue)
            }
            CorrectionType.PRICE -> {
                // User corrected a price: update price tracking
                val price = correctedValue.toDoubleOrNull()
                if (price != null) {
                    // Try to find the item from the original input
                    val item = SwahiliParser.extractItemName(originalInput)
                    if (item != null) {
                        patternTracker.trackPriceChange(item, price)
                        // Also update vocabulary price range
                        userVocabularyDao.updatePrice(item, price)
                    }
                }
            }
            CorrectionType.QUANTITY -> {
                val qty = correctedValue.toDoubleOrNull()
                if (qty != null) {
                    val item = SwahiliParser.extractItemName(originalInput)
                    if (item != null) {
                        userVocabularyDao.updateAvgQuantity(item, qty)
                    }
                }
            }
            CorrectionType.INTENT -> {
                // Intent correction: store for future pattern learning
                learningAgent.recordPattern(
                    PatternType.VOCABULARY,
                    mapOf(
                        "type" to "intent_correction",
                        "original" to originalValue,
                        "corrected" to correctedValue,
                        "input" to originalInput
                    )
                )
            }
            CorrectionType.CATEGORY -> {
                // Category correction: update vocabulary category
                val item = SwahiliParser.extractItemName(originalInput)
                if (item != null) {
                    val existing = userVocabularyDao.getBySpokenForm(item)
                    if (existing != null) {
                        userVocabularyDao.update(existing.copy(category = correctedValue))
                    }
                }
            }
            CorrectionType.OTHER -> {
                // Generic correction: just store it
                Timber.d("Generic correction stored: %s → %s", originalValue, correctedValue)
            }
        }

        // 3. Mark correction as applied
        val allUnapplied = userCorrectionDao.getUnapplied()
        val thisCorrection = allUnapplied.lastOrNull { it.originalValue == originalValue }
        if (thisCorrection != null) {
            userCorrectionDao.markApplied(listOf(thisCorrection.id))
        }
    }

    /**
     * Parse and record a correction from natural language input.
     * E.g., "hapana, niliuza mandazi siyo maandazi" → ITEM correction
     */
    suspend fun parseAndRecordCorrection(
        text: String,
        lastTransaction: Transaction?,
        language: String = "sw"
    ): Boolean {
        val lower = text.lowercase()

        // Detect correction patterns
        val isCorrection = CORRECTION_PATTERNS.any { it.containsMatchIn(lower) }
        if (!isCorrection && lastTransaction == null) return false

        // Parse the correction
        val correction = parseCorrectionText(text, lastTransaction) ?: return false

        recordCorrection(
            correctionType = correction.first,
            originalValue = correction.second,
            correctedValue = correction.third,
            originalInput = text,
            transactionId = lastTransaction?.id ?: 0
        )

        return true
    }

    /**
     * Parse correction text to extract type, original, and corrected values.
     */
    private fun parseCorrectionText(
        text: String,
        lastTransaction: Transaction?
    ): Triple<CorrectionType, String, String>? {
        val lower = text.lowercase()

        // Price correction: "hapana, bei ni 500 siyo 300" or "I meant 500 not 300"
        val priceCorrection = PRICE_CORRECTION_PATTERN.find(lower)
        if (priceCorrection != null) {
            val correctPrice = priceCorrection.groupValues[1]
            val wrongPrice = priceCorrection.groupValues.getOrNull(2) ?: lastTransaction?.totalAmount?.toInt()?.toString() ?: ""
            return Triple(CorrectionType.PRICE, wrongPrice, correctPrice)
        }

        // Item correction: "hapana, ni mandazi siyo maandazi" or "no, it's sugar not flour"
        val itemCorrection = ITEM_CORRECTION_PATTERN.find(lower)
        if (itemCorrection != null) {
            val correctItem = itemCorrection.groupValues[1].trim()
            val wrongItem = itemCorrection.groupValues.getOrNull(2)?.trim()
                ?: lastTransaction?.item ?: ""
            return Triple(CorrectionType.ITEM, wrongItem, correctItem)
        }

        // Quantity correction: "niliuza kumi siyo tano" or "I sold 10 not 5"
        val qtyCorrection = QTY_CORRECTION_PATTERN.find(lower)
        if (qtyCorrection != null) {
            val correctQty = qtyCorrection.groupValues[1]
            val wrongQty = qtyCorrection.groupValues.getOrNull(2) ?: ""
            return Triple(CorrectionType.QUANTITY, wrongQty, correctQty)
        }

        // Simple number correction: just a number after correction trigger
        if (CORRECTION_PATTERNS.any { it.containsMatchIn(lower) }) {
            val numbers = Regex("""(\d+(?:\.\d+)?)""").findAll(text)
                .map { it.value }
                .toList()
            if (numbers.isNotEmpty()) {
                val correctedValue = numbers.first()
                val originalValue = if (numbers.size > 1) numbers[1]
                    else lastTransaction?.totalAmount?.toInt()?.toString() ?: ""
                return Triple(CorrectionType.PRICE, originalValue, correctedValue)
            }
        }

        return null
    }

    // ═══════════════ VOCABULARY LEARNING ═══════════════

    /**
     * Learn a vocabulary mapping: spoken form → canonical form.
     * Creates or updates the UserVocabulary entry with confidence scoring.
     */
    suspend fun learnVocabularyMapping(spoken: String, canonical: String) =
        withContext(Dispatchers.IO) {
            val spokenLower = spoken.lowercase().trim()
            val canonicalLower = canonical.lowercase().trim()

            if (spokenLower.isBlank() || canonicalLower.isBlank()) return@withContext

            val existing = userVocabularyDao.getBySpokenForm(spokenLower)

            if (existing != null) {
                // Update existing entry
                if (existing.canonicalForm == canonicalLower) {
                    // Same mapping → increase confidence
                    userVocabularyDao.incrementFrequency(spokenLower)
                    userVocabularyDao.updateConfidence(spokenLower, 0.9)
                } else {
                    // Different mapping → decrease confidence, update
                    userVocabularyDao.update(existing.copy(
                        canonicalForm = canonicalLower,
                        frequency = existing.frequency + 1,
                        confidence = (existing.confidence * 0.5).coerceIn(0.1, 1.0),
                        isUserDefined = true,
                        lastUsedAt = System.currentTimeMillis() / 1000
                    ))
                }
            } else {
                // New vocabulary entry
                val category = guessCategory(canonicalLower)
                userVocabularyDao.upsert(UserVocabulary(
                    spokenForm = spokenLower,
                    canonicalForm = canonicalLower,
                    category = category,
                    isUserDefined = true,
                    confidence = 0.3 // Initial confidence for new terms
                ))
                Timber.d("New vocabulary: '%s' → '%s' (category=%s)", spokenLower, canonicalLower, category)
            }
        }

    /**
     * Record a transaction and learn from it.
     * This is called for every successful transaction to build vocabulary.
     */
    suspend fun learnFromTransaction(transaction: Transaction) = withContext(Dispatchers.IO) {
        val item = transaction.item.lowercase().trim()
        if (item.isBlank()) return@withContext

        // Update vocabulary
        val existing = userVocabularyDao.getBySpokenForm(item)
        if (existing != null) {
            userVocabularyDao.incrementFrequency(item)
            if (transaction.type == TransactionType.SALE && transaction.totalAmount > 0) {
                val unitPrice = if (transaction.quantity > 0) {
                    transaction.totalAmount / transaction.quantity
                } else {
                    transaction.totalAmount
                }
                userVocabularyDao.updatePrice(item, unitPrice)
                userVocabularyDao.updateAvgQuantity(item, transaction.quantity)
            }
        } else {
            // New product the user sells
            userVocabularyDao.upsert(UserVocabulary(
                spokenForm = item,
                canonicalForm = item,
                frequency = 1,
                confidence = 0.2,
                avgPrice = if (transaction.quantity > 0) transaction.totalAmount / transaction.quantity else transaction.totalAmount,
                avgQuantity = transaction.quantity,
                category = transaction.category,
                isUserDefined = false
            ))
            Timber.d("Learned new product: %s (category=%s)", item, transaction.category)
        }

        // Track price for pattern analysis
        if (transaction.type == TransactionType.SALE && transaction.totalAmount > 0) {
            val unitPrice = if (transaction.quantity > 0) {
                transaction.totalAmount / transaction.quantity
            } else {
                transaction.totalAmount
            }
            patternTracker.trackPriceChange(item, unitPrice, transaction.quantity)
        }

        // Also record in legacy LearningAgent
        learningAgent.recordUserTerm(item, item, transaction.language)
    }

    /**
     * Look up the canonical form for a spoken term.
     * Checks user vocabulary first, then falls back to default dictionary.
     */
    suspend fun resolveTerm(spoken: String): String {
        val lower = spoken.lowercase().trim()

        // Check user vocabulary (highest priority)
        val userEntry = userVocabularyDao.getBySpokenForm(lower)
        if (userEntry != null && userEntry.confidence >= 0.3) {
            // Also update legacy LearningAgent
            learningAgent.recordUserTerm(lower, userEntry.canonicalForm)
            return userEntry.canonicalForm
        }

        // Check legacy LearningAgent vocabulary
        val legacyCanonical = learningAgent.getCanonicalForm(lower)
        if (legacyCanonical != null) return legacyCanonical

        // Fall back to SwahiliParser dictionary
        return SwahiliParser.extractItemName(lower) ?: lower
    }

    /**
     * Get price suggestion for an item based on learned data.
     * Returns null if no price data exists.
     */
    suspend fun suggestPrice(item: String): Double? {
        val lower = item.lowercase().trim()

        // Check user vocabulary
        val vocab = userVocabularyDao.getBySpokenForm(lower)
        if (vocab != null && vocab.priceObservations > 0) {
            return vocab.avgPrice
        }

        // Check pattern tracker
        val priceInsight = patternTracker.getPriceInsight(lower)
        if (priceInsight != null && priceInsight.confidence > 0.3) {
            return priceInsight.averagePrice
        }

        return null
    }

    /**
     * Get quantity suggestion for an item based on learned data.
     */
    suspend fun suggestQuantity(item: String): Double? {
        val vocab = userVocabularyDao.getBySpokenForm(item.lowercase())
        return if (vocab != null && vocab.avgQuantity > 0) vocab.avgQuantity else null
    }

    // ═══════════════ CONTEXT INJECTION ═══════════════

    /**
     * Generate personalized context for LLM prompts.
     * This is the core of Level 2 adaptive learning.
     *
     * Assembles:
     * 1. User's product vocabulary and price ranges
     * 2. Business patterns (peak hours, best days, trends)
     * 3. Recent correction history (what the user cares about)
     * 4. Business health score
     *
     * The LLM uses this context to give personalized, relevant advice.
     */
    suspend fun generatePersonalizedContext(
        maxTokens: Int = 300,
        language: String = "sw"
    ): String = withContext(Dispatchers.IO) {
        val context = StringBuilder()
        val maxChars = maxTokens * 3

        // 1. Product vocabulary with prices
        val topProducts = userVocabularyDao.getTopByFrequency(8)
        if (topProducts.isNotEmpty()) {
            val productList = topProducts.joinToString(", ") { v ->
                val priceStr = if (v.priceObservations > 0) {
                    " (${v.avgPrice.toInt()}-${v.maxPrice.toInt()} KSh)"
                } else ""
                "${v.canonicalForm}$priceStr"
            }
            context.append(if (language == "sw") {
                "Bidhaa za mteja: $productList. "
            } else {
                "User's products: $productList. "
            })
        }

        // 2. Business patterns from tracker
        val patternContext = patternTracker.generateContextForLLM(maxTokens / 2)
        if (patternContext.isNotBlank()) {
            context.append(patternContext)
        }

        // 3. Recent corrections (what user cares about)
        val recentCorrections = userCorrectionDao.getRecent(5)
        if (recentCorrections.isNotEmpty()) {
            val correctionSummary = recentCorrections.joinToString("; ") { c ->
                "${c.correctionType.name.lowercase()}: ${c.originalValue}→${c.correctedValue}"
            }
            context.append(if (language == "sw") {
                "Marekebisho ya hivi karibuni: $correctionSummary. "
            } else {
                "Recent corrections: $correctionSummary. "
            })
        }

        // 4. Business health
        try {
            val health = patternTracker.calculateBusinessHealthScore()
            context.append(if (language == "sw") {
                "Afya ya biashara: ${health.totalScore.toInt()}/100. "
            } else {
                "Business health: ${health.totalScore.toInt()}/100. "
            })
        } catch (e: Exception) {
            Timber.d(e, "Could not calculate health score for context")
        }

        // Truncate if needed
        val result = context.toString()
        if (result.length > maxChars) result.take(maxChars) else result
    }

    /**
     * Enhance an intent with learned corrections.
     * If we've seen corrections for this type of input before,
     * apply the learned mapping.
     */
    suspend fun enhanceIntentWithLearning(
        intent: IntentResult,
        rawText: String
    ): IntentResult = withContext(Dispatchers.IO) {
        val enhancedData = intent.extractedData.toMutableMap()

        // Try to resolve item names through vocabulary
        val item = enhancedData["item"]
        if (item != null) {
            val resolved = resolveTerm(item)
            if (resolved != item) {
                enhancedData["item"] = resolved
                Timber.d("Vocabulary resolution: '%s' → '%s'", item, resolved)
            }
        }

        // Try to suggest price if missing
        if (enhancedData["amount"] == null && enhancedData["item"] != null) {
            val suggestedPrice = suggestPrice(enhancedData["item"]!!)
            if (suggestedPrice != null) {
                enhancedData["suggestedPrice"] = suggestedPrice.toInt().toString()
                Timber.d("Price suggestion for %s: KSh %d", enhancedData["item"], suggestedPrice.toInt())
            }
        }

        // Check for known correction patterns in the input
        val corrections = userCorrectionDao.getRecent(50)
        for (correction in corrections) {
            if (correction.correctionType == CorrectionType.ITEM &&
                rawText.lowercase().contains(correction.originalValue.lowercase())
            ) {
                // This input contains a term that was previously corrected
                enhancedData["item"] = correction.correctedValue
                Timber.d("Correction applied: '%s' → '%s'",
                    correction.originalValue, correction.correctedValue)
                break
            }
        }

        if (enhancedData != intent.extractedData) {
            intent.copy(extractedData = enhancedData)
        } else {
            intent
        }
    }

    // ═══════════════ LEARNING STATISTICS ═══════════════

    /**
     * Get learning progress statistics.
     */
    suspend fun getLearningStats(): LearningStats = withContext(Dispatchers.IO) {
        val vocabCount = userVocabularyDao.getCount()
        val highConfidenceCount = userVocabularyDao.getHighConfidenceCount()
        val avgConfidence = userVocabularyDao.getAverageConfidence() ?: 0.0
        val correctionCount = userCorrectionDao.getCount()
        val categories = userVocabularyDao.getCategories()

        LearningStats(
            vocabularySize = vocabCount,
            highConfidenceVocabulary = highConfidenceCount,
            averageConfidence = avgConfidence,
            totalCorrections = correctionCount,
            categories = categories,
            isReadyForPersonalization = vocabCount >= 5 && correctionCount >= 1,
            personalizationLevel = when {
                vocabCount >= 50 && correctionCount >= 10 -> PersonalizationLevel.ADVANCED
                vocabCount >= 10 && correctionCount >= 3 -> PersonalizationLevel.MODERATE
                vocabCount >= 3 -> PersonalizationLevel.BASIC
                else -> PersonalizationLevel.NONE
            }
        )
    }

    /**
     * Export training data for future LoRA fine-tuning (Level 3).
     * Collects correction pairs as input→output training examples.
     */
    suspend fun exportTrainingData(): List<TrainingExample> = withContext(Dispatchers.IO) {
        val corrections = userCorrectionDao.getRecent(1000)
        corrections.map { c ->
            TrainingExample(
                input = c.originalInput,
                expectedOutput = c.correctedValue,
                correctionType = c.correctionType.name,
                timestamp = c.createdAt
            )
        }
    }

    // ═══════════════ BACKGROUND LEARNING ═══════════════

    /**
     * Run background learning tasks.
     * Called periodically (e.g., during heartbeat or when charging).
     *
     * Tasks:
     * 1. Analyze day-of-week patterns
     * 2. Analyze peak hours
     * 3. Analyze product performance
     * 4. Cleanup old unused vocabulary
     */
    suspend fun runBackgroundLearning() = withContext(Dispatchers.IO) {
        Timber.d("Starting background learning...")

        try {
            // Analyze patterns (these are idempotent — safe to repeat)
            patternTracker.analyzeDayOfWeekPatterns()
            patternTracker.analyzePeakHours()
            patternTracker.analyzeProductPerformance()

            // Cleanup unused vocabulary entries
            userVocabularyDao.cleanupUnused()

            // Cleanup old corrections (keep last 90 days)
            val cutoff = System.currentTimeMillis() / 1000 - 90 * 24 * 3600
            userCorrectionDao.deleteOlderThan(cutoff)

            Timber.d("Background learning complete")
        } catch (e: Exception) {
            Timber.e(e, "Background learning failed")
        }
    }

    /**
     * Launch background learning in a non-blocking coroutine.
     * Fire-and-forget — doesn't block the caller.
     */
    fun launchBackgroundLearning() {
        engineScope.launch {
            runBackgroundLearning()
        }
    }

    // ═══════════════ HELPERS ═══════════════

    private fun guessCategory(item: String): String {
        val lower = item.lowercase()
        return when {
            lower.contains("nyanya") || lower.contains("viazi") ||
            lower.contains("vitunguu") || lower.contains("sukuma") -> "produce"
            lower.contains("unga") || lower.contains("mchele") ||
            lower.contains("mahindi") || lower.contains("maharagwe") -> "grains"
            lower.contains("nyama") || lower.contains("kuku") ||
            lower.contains("samaki") || lower.contains("mayai") -> "protein"
            lower.contains("mafuta") || lower.contains("sukari") ||
            lower.contains("chumvi") || lower.contains("chai") -> "cooking"
            lower.contains("mandazi") || lower.contains("chapati") ||
            lower.contains("mkate") -> "prepared_food"
            lower.contains("sabuni") || lower.contains("dawa") -> "household"
            else -> "other"
        }
    }

    companion object {
        /** Patterns that indicate a correction */
        private val CORRECTION_PATTERNS = listOf(
            Regex("""(?i)(hapana|siyo|si\s+sahihi|wrong|incorrect|badilisha|rekebisha|change|correct)"""),
            Regex("""(?i)(i\s+meant|alisema|kusema|sio|si\s+hiyo)"""),
            Regex("""(?i)(sawa|no|nope|actually)"""),
            Regex("""(?i)(futa|delete|ondoa|remove)""")
        )

        /** Price correction pattern: "bei ni 500" or "I meant 500" */
        private val PRICE_CORRECTION_PATTERN = Regex(
            """(?i)(?:bei\s+ni|bei\s+ya|price\s+(?:is|was)|i\s+meant|meant)\s+(\d+(?:\.\d+)?)\s*(?:siyo|sio|not|rather\s+than)?\s*(\d+(?:\.\d+)?)?"""
        )

        /** Item correction pattern: "ni mandazi siyo maandazi" */
        private val ITEM_CORRECTION_PATTERN = Regex(
            """(?i)(?:ni|bidhaa\s+ni|item\s+(?:is|was)|i\s+meant)\s+(\w+)\s*(?:siyo|sio|not|rather\s+than)\s*(\w+)?"""
        )

        /** Quantity correction pattern: "kumi siyo tano" */
        private val QTY_CORRECTION_PATTERN = Regex(
            """(?i)(?:ni|quantity\s+(?:is|was)|i\s+sold)\s+(\d+)\s*(?:siyo|sio|not|rather\s+than)\s*(\d+)?"""
        )
    }
}

// ═══════════════ DATA CLASSES ═══════════════

/** Learning statistics */
data class LearningStats(
    val vocabularySize: Int,
    val highConfidenceVocabulary: Int,
    val averageConfidence: Double,
    val totalCorrections: Int,
    val categories: List<String>,
    val isReadyForPersonalization: Boolean,
    val personalizationLevel: PersonalizationLevel
)

/** Personalization maturity levels */
enum class PersonalizationLevel {
    /** No personalization yet (< 3 vocabulary items) */
    NONE,

    /** Basic personalization (3+ items, some corrections) */
    BASIC,

    /** Moderate personalization (10+ items, 3+ corrections, patterns emerging) */
    MODERATE,

    /** Advanced personalization (50+ items, 10+ corrections, reliable patterns) */
    ADVANCED
}

/** Training example for future LoRA fine-tuning (Level 3) */
data class TrainingExample(
    val input: String,
    val expectedOutput: String,
    val correctionType: String,
    val timestamp: Long
)
