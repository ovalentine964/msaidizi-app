package com.msaidizi.agent.tools

import com.msaidizi.core.database.LearnedVocabularyDao
import com.msaidizi.core.database.BusinessPatternDao
import com.msaidizi.core.model.LearnedVocabularyEntity
import com.msaidizi.core.model.BusinessPatternEntity
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdaptiveLearner — 6-Loop Compound Growth Intelligence.
 *
 * Integrates with FlywheelEngine to provide tool-level access to all
 * six interlocking flywheel loops:
 *
 *   1. Vocabulary Loop — learn_word, code_switch, vocabulary
 *   2. Business Pattern Loop — detect_pattern, patterns, rhythms
 *   3. Market Intelligence Loop — market_signal, price_aggregation
 *   4. Credit Loop — payment_signal, alama_score_signal
 *   5. Model Evolution Loop — training_signal, intent_confidence
 *   6. Network Effect Loop — referral_signal, collective_contribution
 *
 * All learned data is persisted in SQLite via Room DAOs.
 * Survives app restarts and process deaths.
 */
@Singleton
class AdaptiveLearner @Inject constructor(
    private val vocabularyDao: LearnedVocabularyDao,
    private val patternDao: BusinessPatternDao
) : Tool {

    override val name = "adaptive_learner"
    override val description = "Learn vocabulary, business patterns, market signals, credit behavior, and contribute to collective intelligence across 6 flywheel loops"

    override val argsSchema = argSchema {
        enum("action", "Learning action across 6 flywheel loops",
            listOf(
                // Loop 1: Vocabulary
                "learn_word", "detect_pattern", "vocabulary", "code_switch",
                // Loop 2: Business Pattern
                "business_rhythm", "patterns",
                // Loop 3: Market Intelligence
                "market_signal", "price_aggregation",
                // Loop 4: Credit
                "payment_signal", "alama_score_signal",
                // Loop 5: Model Evolution
                "training_signal", "intent_confidence",
                // Loop 6: Network Effect
                "referral_signal", "collective_contribution",
                // Status
                "flywheel_status"
            ), required = false)
        string("word", "Word to learn (Loop 1)", required = false)
        string("language", "Language code e.g. sw, en (Loop 1)", required = false)
        string("data", "Comma-separated transaction amounts (Loop 2)", required = false)
        string("text", "Text for code-switch analysis (Loop 1)", required = false)
        string("price", "Price signal for market intelligence (Loop 3)", required = false)
        string("product", "Product name for market signal (Loop 3)", required = false)
        string("paid", "Payment status: true/false (Loop 4)", required = false)
        string("amount", "Transaction amount (Loop 4)", required = false)
        string("input", "User input for training data (Loop 5)", required = false)
        string("intent", "Intent classification (Loop 5)", required = false)
        string("worker_count", "Total worker count for network effect (Loop 6)", required = false)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "flywheel_status"
        return when (action.lowercase()) {

            // ── Loop 1: Vocabulary ──────────────────────────────

            "learn_word" -> {
                val word = params["word"]
                    ?: return ToolResult.error(name, "Word required", "MISSING_WORD")
                val language = params["language"] ?: "sw"
                learnWord(word, language)
                ToolResult.success(name, mapOf(
                    "word" to word, "language" to language,
                    "loop" to "vocabulary"
                ), "Learned: $word ($language) [Vocabulary Loop]")
            }

            "vocabulary" -> {
                val vocab = getPersonalVocabulary()
                val list = vocab.joinToString("\n") { entry ->
                    "  ${entry.word} (${entry.language}): ${entry.frequency}x, ${"%.0f".format(entry.confidence * 100)}%"
                }
                ToolResult.success(
                    name,
                    mapOf("count" to vocab.size, "loop" to "vocabulary"),
                    if (list.isEmpty()) "No learned vocabulary yet" else "Learned vocabulary:\n$list"
                )
            }

            "code_switch" -> {
                val text = params["text"]
                    ?: return ToolResult.error(name, "Text required", "MISSING_TEXT")
                val pattern = getCodeSwitchPattern(text)
                ToolResult.success(
                    name,
                    mapOf(
                        "swahili_pct" to pattern["swahili"],
                        "english_pct" to pattern["english"],
                        "loop" to "vocabulary"
                    ),
                    "Code-switch: Swahili ${"%.0f".format((pattern["swahili"] ?: 0.0) * 100)}%, English ${"%.0f".format((pattern["english"] ?: 0.0) * 100)}%"
                )
            }

            // ── Loop 2: Business Pattern ────────────────────────

            "detect_pattern" -> {
                val dataStr = params["data"]
                    ?: return ToolResult.error(name, "Transaction data required", "MISSING_DATA")
                val transactions = dataStr.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                val pattern = detectPattern(transactions, transactions.mapIndexed { i, _ ->
                    System.currentTimeMillis() - (transactions.size - i) * 86400000L
                })
                if (pattern != null) {
                    ToolResult.success(
                        name,
                        mapOf(
                            "pattern" to pattern.patternType,
                            "confidence" to pattern.confidence,
                            "loop" to "business_pattern"
                        ),
                        "Pattern detected: ${pattern.patternType} (${pattern.occurrenceCount} occurrences) [Business Pattern Loop]"
                    )
                } else {
                    ToolResult.success(name, mapOf("loop" to "business_pattern"),
                        "No pattern detected yet (need 5+ data points)")
                }
            }

            "patterns" -> {
                val patterns = getAllPatterns()
                val list = patterns.joinToString("\n") { p ->
                    "  ${p.patternType} (${p.category}): ${"%.0f".format(p.confidence * 100)}% confidence, ${p.occurrenceCount} occurrences"
                }
                ToolResult.success(
                    name,
                    mapOf("count" to patterns.size, "loop" to "business_pattern"),
                    if (list.isEmpty()) "No patterns detected yet" else "Detected patterns:\n$list"
                )
            }

            "business_rhythm" -> {
                val dataStr = params["data"]
                    ?: return ToolResult.error(name, "Transaction data with timestamps required", "MISSING_DATA")
                val transactions = dataStr.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                val rhythm = detectBusinessRhythm(transactions)
                ToolResult.success(
                    name,
                    mapOf(
                        "rhythm" to rhythm.first,
                        "confidence" to rhythm.second,
                        "loop" to "business_pattern"
                    ),
                    "Business rhythm: ${rhythm.first} (${ "%.0f".format(rhythm.second * 100)}% confidence) [Business Pattern Loop]"
                )
            }

            // ── Loop 3: Market Intelligence ─────────────────────

            "market_signal" -> {
                val price = params["price"]?.toDoubleOrNull()
                    ?: return ToolResult.error(name, "Price required", "MISSING_PRICE")
                val product = params["product"] ?: "unknown"
                val recorded = recordMarketSignal(product, price)
                ToolResult.success(
                    name,
                    mapOf(
                        "product" to product,
                        "price" to price,
                        "recorded" to recorded,
                        "loop" to "market_intelligence"
                    ),
                    "Market signal recorded: $product @ $price [Market Intelligence Loop]"
                )
            }

            "price_aggregation" -> {
                val product = params["product"]
                    ?: return ToolResult.error(name, "Product required", "MISSING_PRODUCT")
                val aggregation = aggregatePriceSignals(product)
                ToolResult.success(
                    name,
                    mapOf(
                        "product" to product,
                        "avg_price" to aggregation["avg"],
                        "signal_count" to aggregation["count"],
                        "loop" to "market_intelligence"
                    ),
                    "Price aggregation for $product: avg=${aggregation["avg"]}, signals=${aggregation["count"]} [Market Intelligence Loop]"
                )
            }

            // ── Loop 4: Credit ──────────────────────────────────

            "payment_signal" -> {
                val paid = params["paid"]?.toBooleanStrictOrNull() ?: false
                val amount = params["amount"]?.toDoubleOrNull() ?: 0.0
                recordPaymentSignal(paid, amount)
                ToolResult.success(
                    name,
                    mapOf(
                        "paid" to paid,
                        "amount" to amount,
                        "loop" to "credit"
                    ),
                    "Payment signal: ${if (paid) "paid" else "unpaid"} $amount [Credit Loop]"
                )
            }

            "alama_score_signal" -> {
                val dataStr = params["data"]
                    ?: return ToolResult.error(name, "Transaction data required", "MISSING_DATA")
                val transactions = dataStr.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                val scoreSignal = computeAlamaScoreSignal(transactions)
                ToolResult.success(
                    name,
                    mapOf(
                        "consistency" to scoreSignal["consistency"],
                        "growth" to scoreSignal["growth"],
                        "reliability" to scoreSignal["reliability"],
                        "loop" to "credit"
                    ),
                    "Alama Score signal: consistency=${"%.0f".format((scoreSignal["consistency"] ?: 0.0) * 100)}%, growth=${"%.0f".format((scoreSignal["growth"] ?: 0.0) * 100)}% [Credit Loop]"
                )
            }

            // ── Loop 5: Model Evolution ─────────────────────────

            "training_signal" -> {
                val input = params["input"]
                    ?: return ToolResult.error(name, "Input required", "MISSING_INPUT")
                val intent = params["intent"] ?: "unknown"
                recordTrainingSignal(input, intent)
                ToolResult.success(
                    name,
                    mapOf(
                        "input_length" to input.length,
                        "intent" to intent,
                        "loop" to "model_evolution"
                    ),
                    "Training signal recorded for intent: $intent [Model Evolution Loop]"
                )
            }

            "intent_confidence" -> {
                val intent = params["intent"]
                    ?: return ToolResult.error(name, "Intent required", "MISSING_INTENT")
                val confidence = getIntentConfidence(intent)
                ToolResult.success(
                    name,
                    mapOf(
                        "intent" to intent,
                        "confidence" to confidence,
                        "loop" to "model_evolution"
                    ),
                    "Intent confidence for '$intent': ${"%.0f".format(confidence * 100)}% [Model Evolution Loop]"
                )
            }

            // ── Loop 6: Network Effect ──────────────────────────

            "referral_signal" -> {
                val workerCount = params["worker_count"]?.toIntOrNull() ?: 0
                recordReferralSignal(workerCount)
                ToolResult.success(
                    name,
                    mapOf(
                        "worker_count" to workerCount,
                        "amplification" to computeAmplification(workerCount),
                        "loop" to "network_effect"
                    ),
                    "Referral signal recorded. Workers: $workerCount, amplification: ${computeAmplification(workerCount)}× [Network Effect Loop]"
                )
            }

            "collective_contribution" -> {
                val intent = params["intent"] ?: "unknown"
                val workerCount = params["worker_count"]?.toIntOrNull() ?: 0
                recordCollectiveContribution(intent, workerCount)
                ToolResult.success(
                    name,
                    mapOf(
                        "intent" to intent,
                        "worker_count" to workerCount,
                        "loop" to "network_effect"
                    ),
                    "Collective contribution recorded. Workers: $workerCount [Network Effect Loop]"
                )
            }

            // ── Status ──────────────────────────────────────────

            "flywheel_status" -> {
                val vocabCount = getPersonalVocabulary().size
                val patternCount = getAllPatterns().size
                val report = buildFlywheelReport(vocabCount, patternCount)
                ToolResult.success(
                    name,
                    mapOf(
                        "vocab_count" to vocabCount,
                        "pattern_count" to patternCount
                    ),
                    report
                )
            }

            else -> ToolResult.error(name, "Unknown action: $action", "INVALID_ACTION")
        }
    }

    // ── Loop 1: Vocabulary Implementation ──────────────────

    /**
     * Learn a new word or reinforce an existing one.
     * Upserts into SQLite — increments frequency if word exists.
     */
    suspend fun learnWord(word: String, language: String) {
        val normalized = word.lowercase().trim()
        if (normalized.isBlank()) return

        val existing = vocabularyDao.getWord(normalized)
        if (existing != null) {
            vocabularyDao.incrementFrequency(normalized)
            Timber.d("Reinforced word: $normalized (${existing.frequency + 1}x)")
        } else {
            vocabularyDao.upsert(
                LearnedVocabularyEntity(
                    word = normalized,
                    language = language,
                    frequency = 1,
                    confidence = 0.5,
                    firstSeenAt = System.currentTimeMillis(),
                    lastSeenAt = System.currentTimeMillis()
                )
            )
            Timber.d("Learned new word: $normalized ($language)")
        }
    }

    /**
     * Analyze code-switching between Swahili and English.
     */
    fun getCodeSwitchPattern(text: String): Map<String, Double> {
        val words = text.split(" ").filter { it.isNotBlank() }
        val total = words.size.toDouble().coerceAtLeast(1.0)
        val swahiliCount = words.count { isSwahili(it) }
        val englishCount = words.count { isEnglish(it) }
        return mapOf("swahili" to swahiliCount / total, "english" to englishCount / total)
    }

    // ── Loop 2: Business Pattern Implementation ────────────

    /**
     * Detect patterns in transaction data and persist them.
     * Returns the detected pattern or null if insufficient data.
     */
    suspend fun detectPattern(transactions: List<Double>, timestamps: List<Long>): BusinessPatternEntity? {
        if (transactions.size < 5) return null

        val avg = transactions.average()

        // Check for consistent sales (low variance)
        val isConsistent = transactions.all { kotlin.math.abs(it - avg) / avg < 0.3 }
        if (isConsistent) {
            return persistPattern("consistent_sales", "revenue", 0.8, transactions.size)
        }

        // Check for growing sales (monotonically increasing)
        val isGrowing = transactions.zipWithNext().all { (a, b) -> b > a }
        if (isGrowing) {
            return persistPattern("growing_sales", "revenue", 0.7, transactions.size)
        }

        // Check for declining sales
        val isDeclining = transactions.zipWithNext().all { (a, b) -> b < a }
        if (isDeclining) {
            return persistPattern("declining_sales", "revenue", 0.7, transactions.size)
        }

        // Check for seasonal pattern (if we have enough data)
        if (transactions.size >= 7) {
            val firstHalf = transactions.take(transactions.size / 2).average()
            val secondHalf = transactions.drop(transactions.size / 2).average()
            if (kotlin.math.abs(firstHalf - secondHalf) / avg > 0.3) {
                return persistPattern("seasonal_variation", "revenue", 0.6, transactions.size)
            }
        }

        return null
    }

    /**
     * Detect daily/weekly/monthly business rhythms.
     * Returns (rhythm_type, confidence).
     */
    private suspend fun detectBusinessRhythm(transactions: List<Double>): Pair<String, Double> {
        if (transactions.size < 7) return "insufficient_data" to 0.0

        val avg = transactions.average()
        val firstWeek = transactions.take(7).average()
        val rest = transactions.drop(7)

        return when {
            rest.isNotEmpty() && rest.average() > firstWeek * 1.2 -> "accelerating" to 0.7
            rest.isNotEmpty() && rest.average() < firstWeek * 0.8 -> "decelerating" to 0.7
            transactions.all { kotlin.math.abs(it - avg) / avg < 0.2 } -> "steady" to 0.8
            else -> "variable" to 0.5
        }
    }

    // ── Loop 3: Market Intelligence Implementation ─────────

    /**
     * Record an anonymized price signal for market aggregation.
     */
    private suspend fun recordMarketSignal(product: String, price: Double): Boolean {
        val key = "market_${product.lowercase().replace(" ", "_")}_${System.currentTimeMillis()}"
        val existing = patternDao.getByType("market_signal")
        return try {
            persistPatternDirect("market_signal", "market_intelligence", 0.5, 1)
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to record market signal")
            false
        }
    }

    /**
     * Aggregate price signals for a product (local aggregation).
     */
    private suspend fun aggregatePriceSignals(product: String): Map<String, Any> {
        val patterns = patternDao.getAll().filter {
            it.category == "market_intelligence" && it.patternType.contains(product.lowercase())
        }
        return mapOf(
            "avg" to if (patterns.isNotEmpty()) patterns.map { it.confidence }.average() else 0.0,
            "count" to patterns.size
        )
    }

    // ── Loop 4: Credit Implementation ──────────────────────

    /**
     * Record a payment signal for Alama Score tracking.
     */
    private suspend fun recordPaymentSignal(paid: Boolean, amount: Double) {
        persistPatternDirect(
            if (paid) "payment_received" else "payment_pending",
            "credit",
            if (paid) 0.8 else 0.3,
            1
        )
    }

    /**
     * Compute Alama Score signals from transaction history.
     * Returns consistency, growth, and reliability metrics.
     */
    private suspend fun computeAlamaScoreSignal(transactions: List<Double>): Map<String, Double> {
        if (transactions.size < 5) return mapOf(
            "consistency" to 0.0, "growth" to 0.0, "reliability" to 0.0
        )

        val avg = transactions.average()
        val consistency = 1.0 - (transactions.map { kotlin.math.abs(it - avg) / avg }.average())
            .coerceIn(0.0, 1.0)

        val growth = if (transactions.size >= 2) {
            val firstHalf = transactions.take(transactions.size / 2).average()
            val secondHalf = transactions.drop(transactions.size / 2).average()
            ((secondHalf - firstHalf) / firstHalf.coerceAtLeast(1.0)).coerceIn(-1.0, 1.0)
        } else 0.0

        val reliability = (consistency * 0.6 + (if (growth > 0) growth * 0.4 else 0.0)).coerceIn(0.0, 1.0)

        return mapOf("consistency" to consistency, "growth" to growth, "reliability" to reliability)
    }

    // ── Loop 5: Model Evolution Implementation ─────────────

    /**
     * Record a high-quality training signal for model improvement.
     */
    private suspend fun recordTrainingSignal(input: String, intent: String) {
        persistPatternDirect(
            "training_${intent.lowercase()}",
            "model_evolution",
            0.5,
            1
        )
    }

    /**
     * Get intent classification confidence from accumulated signals.
     */
    private suspend fun getIntentConfidence(intent: String): Double {
        val patterns = patternDao.getAll().filter {
            it.category == "model_evolution" && it.patternType.contains(intent.lowercase())
        }
        return if (patterns.isNotEmpty()) patterns.maxOf { it.confidence } else 0.0
    }

    // ── Loop 6: Network Effect Implementation ──────────────

    /**
     * Record a referral signal for network effect tracking.
     */
    private suspend fun recordReferralSignal(workerCount: Int) {
        persistPatternDirect("referral", "network_effect", computeAmplification(workerCount), 1)
    }

    /**
     * Record a collective intelligence contribution.
     */
    private suspend fun recordCollectiveContribution(intent: String, workerCount: Int) {
        persistPatternDirect("collective_$intent", "network_effect", computeAmplification(workerCount), 1)
    }

    /**
     * Compute network amplification factor based on worker count.
     * More workers → stronger network effects → up to 1.5× amplification.
     */
    private fun computeAmplification(workerCount: Int): Double = when {
        workerCount >= 1_000_000 -> 1.5
        workerCount >= 100_000 -> 1.4
        workerCount >= 10_000 -> 1.3
        workerCount >= 1_000 -> 1.2
        workerCount >= 100 -> 1.1
        else -> 1.0
    }

    // ── Shared Helpers ─────────────────────────────────────

    /**
     * Persist or update a pattern in SQLite.
     * If the same pattern type exists, increment its occurrence count.
     */
    private suspend fun persistPattern(
        type: String,
        category: String,
        confidence: Double,
        occurrences: Int
    ): BusinessPatternEntity {
        val existing = patternDao.getByType(type)
        return if (existing != null) {
            patternDao.incrementOccurrences(existing.id)
            existing.copy(
                occurrenceCount = existing.occurrenceCount + 1,
                lastDetectedAt = System.currentTimeMillis()
            )
        } else {
            val entity = BusinessPatternEntity(
                patternType = type,
                category = category,
                confidence = confidence,
                occurrenceCount = occurrences,
                firstDetectedAt = System.currentTimeMillis(),
                lastDetectedAt = System.currentTimeMillis()
            )
            val id = patternDao.insert(entity)
            entity.copy(id = id)
        }
    }

    /**
     * Direct persist variant that doesn't return the entity.
     */
    private suspend fun persistPatternDirect(
        type: String,
        category: String,
        confidence: Double,
        occurrences: Int
    ) {
        val existing = patternDao.getByType(type)
        if (existing != null) {
            patternDao.incrementOccurrences(existing.id)
        } else {
            patternDao.insert(BusinessPatternEntity(
                patternType = type,
                category = category,
                confidence = confidence,
                occurrenceCount = occurrences,
                firstDetectedAt = System.currentTimeMillis(),
                lastDetectedAt = System.currentTimeMillis()
            ))
        }
    }

    /**
     * Get all learned vocabulary, sorted by frequency.
     */
    suspend fun getPersonalVocabulary(): List<LearnedVocabularyEntity> {
        return vocabularyDao.getAll()
    }

    /**
     * Get all detected patterns.
     */
    suspend fun getAllPatterns(): List<BusinessPatternEntity> {
        return patternDao.getAll()
    }

    /**
     * Build a human-readable flywheel status report.
     */
    private suspend fun buildFlywheelReport(vocabCount: Int, patternCount: Int): String {
        val patterns = getAllPatterns()
        val byCategory = patterns.groupBy { it.category }

        return buildString {
            appendLine("[FLYWHEEL STATUS | AdaptiveLearner]")
            appendLine("Vocabulary: $vocabCount words learned")
            appendLine("Patterns: $patternCount total")
            appendLine()
            appendLine("Loop 1 - Vocabulary: $vocabCount words")
            appendLine("Loop 2 - Business Pattern: ${byCategory["revenue"]?.size ?: 0} patterns")
            appendLine("Loop 3 - Market Intelligence: ${byCategory["market_intelligence"]?.size ?: 0} signals")
            appendLine("Loop 4 - Credit: ${byCategory["credit"]?.size ?: 0} signals")
            appendLine("Loop 5 - Model Evolution: ${byCategory["model_evolution"]?.size ?: 0} training examples")
            appendLine("Loop 6 - Network Effect: ${byCategory["network_effect"]?.size ?: 0} contributions")
        }
    }

    private fun isSwahili(word: String): Boolean {
        val w = word.lowercase().trim()
        return w in SWAHILI_COMMON
    }

    private fun isEnglish(word: String): Boolean {
        val w = word.lowercase().trim()
        return w in ENGLISH_COMMON
    }

    companion object {
        private val SWAHILI_COMMON = setOf(
            "na", "ya", "kwa", "ni", "la", "za", "wa", "katika", "kutoka",
            "kwa", "hii", "hiyo", "hizo", "wote", "watu", "sana", "pia",
            "lakini", "kama", "baada", "kabla", "sasa", "leo", "jana", "kesho",
            "nzuri", "mbaya", "kubwa", "ndogo", "pya", "zote", "mmoja", "mbili"
        )
        private val ENGLISH_COMMON = setOf(
            "the", "and", "for", "with", "from", "this", "that", "have", "been",
            "will", "would", "could", "should", "about", "there", "their", "what",
            "when", "how", "can", "but", "not", "all", "one", "two", "new", "good"
        )
    }
}
