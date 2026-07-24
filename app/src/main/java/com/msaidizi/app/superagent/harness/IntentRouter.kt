package com.msaidizi.app.superagent.harness

import com.msaidizi.app.core.database.KnowledgeDao
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import timber.log.Timber
import com.msaidizi.app.superagent.flywheel.FlywheelEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intent Router — First stage of the superagent pipeline.
 *
 * Uses pattern matching + LLM fallback to classify user intent.
 * Optimized for low-resource devices: pattern matching first (zero cost),
 * LLM only for ambiguous inputs.
 *
 * Enhanced with flywheel learned vocabulary for better classification.
 */
@Singleton
class IntentRouter @Inject constructor(
    private val knowledgeDao: KnowledgeDao,
    private val flywheelEngine: FlywheelEngine,
    private val gson: Gson
) {
    // Intent patterns loaded from assets/knowledge/intent_patterns.json
    private var intentPatterns: Map<IntentType, List<Regex>> = emptyMap()

    // Learned vocabulary cache (refreshed on each route call)
    private var learnedVocab: Set<String> = emptySet()

    // Entity extraction patterns
    private val numberPattern = Regex("""\d+\.?\d*""")
    private val currencyPattern = Regex("""(?:ksh|kes|shillings?)\s*(\d+\.?\d*)|(\d+\.?\d*)\s*(?:ksh|kes|shillings?)""", RegexOption.IGNORE_CASE)
    private val phonePattern = Regex("""(?:\+?254|0)[17]\d{8}""")

    init {
        loadPatterns()
    }

    /**
     * Route user input to an intent.
     * Tries pattern matching first (fast), falls back to LLM classification.
     * Uses flywheel learned vocabulary to improve matching over time.
     */
    suspend fun route(input: String): UserIntent {
        val normalized = input.trim().lowercase()

        // Refresh learned vocabulary from flywheel
        try {
            learnedVocab = flywheelEngine.getVocabularyWords()
        } catch (e: Exception) {
            Timber.w(e, "Failed to load learned vocabulary")
        }

        // 1. Try pattern matching (zero cost, instant)
        var patternResult = matchPatterns(normalized)

        // 2. If no strong match, try matching against learned vocabulary
        if (patternResult == null || patternResult.confidence <= 0.8f) {
            val vocabBoosted = matchWithLearnedVocab(normalized, patternResult)
            if (vocabBoosted != null) {
                patternResult = vocabBoosted
            }
        }

        if (patternResult != null && patternResult.confidence > 0.8f) {
            return patternResult.copy(
                entities = extractEntities(input),
                rawText = input
            )
        }

        // 3. Use LLM for classification (only when patterns are ambiguous)
        // For now, return best pattern match or UNKNOWN
        return patternResult?.copy(entities = extractEntities(input), rawText = input)
            ?: UserIntent(
                type = IntentType.UNKNOWN,
                confidence = 0.5f,
                entities = extractEntities(input),
                rawText = input
            )
    }

    /**
     * Match input against known patterns.
     */
    private fun matchPatterns(input: String): UserIntent? {
        // Sale recording
        if (matchesSalePattern(input)) {
            return UserIntent(
                type = IntentType.RECORD_SALE,
                confidence = 0.9f,
                requiredTools = listOf("record_sale"),
                toolParams = mapOf("record_sale" to extractSaleParams(input))
            )
        }

        // Expense recording
        if (matchesExpensePattern(input)) {
            return UserIntent(
                type = IntentType.RECORD_EXPENSE,
                confidence = 0.9f,
                requiredTools = listOf("record_expense"),
                toolParams = mapOf("record_expense" to extractExpenseParams(input))
            )
        }

        // Stock check
        if (matchesStockCheckPattern(input)) {
            return UserIntent(
                type = IntentType.ASK_STOCK,
                confidence = 0.85f,
                requiredTools = listOf("check_stock")
            )
        }

        // Sales query
        if (matchesSalesQueryPattern(input)) {
            return UserIntent(
                type = IntentType.ASK_SALES_TODAY,
                confidence = 0.85f,
                requiredTools = listOf("query_sales")
            )
        }

        // Profit query
        if (matchesProfitQueryPattern(input)) {
            return UserIntent(
                type = IntentType.ASK_PROFIT,
                confidence = 0.85f,
                requiredTools = listOf("query_profit")
            )
        }

        // Expense query
        if (matchesExpenseQueryPattern(input)) {
            return UserIntent(
                type = IntentType.ASK_EXPENSES,
                confidence = 0.85f,
                requiredTools = listOf("query_expenses")
            )
        }

        // Debtors
        if (matchesDebtorsPattern(input)) {
            return UserIntent(
                type = IntentType.ASK_DEBTORS,
                confidence = 0.85f,
                requiredTools = listOf("query_debtors")
            )
        }

        // Greeting
        if (matchesGreetingPattern(input)) {
            return UserIntent(type = IntentType.GREETING, confidence = 0.95f)
        }

        // Help
        if (matchesHelpPattern(input)) {
            return UserIntent(type = IntentType.HELP, confidence = 0.9f)
        }

        // Thanks
        if (matchesThanksPattern(input)) {
            return UserIntent(type = IntentType.THANKS, confidence = 0.9f)
        }

        // Advice
        if (matchesAdvicePattern(input)) {
            return UserIntent(
                type = IntentType.ASK_ADVICE,
                confidence = 0.8f,
                requiredTools = listOf("query_business_data")
            )
        }

        // Purchase/stock addition
        if (matchesPurchasePattern(input)) {
            return UserIntent(
                type = IntentType.RECORD_PURCHASE,
                confidence = 0.85f,
                requiredTools = listOf("record_purchase"),
                toolParams = mapOf("record_purchase" to extractPurchaseParams(input))
            )
        }

        // Daily report
        if (matchesReportPattern(input)) {
            return UserIntent(
                type = IntentType.DAILY_REPORT,
                confidence = 0.85f,
                requiredTools = listOf("generate_report")
            )
        }

        // Service transaction (fundi, salon, barber, etc.)
        if (matchesServicePattern(input)) {
            return UserIntent(
                type = IntentType.RECORD_SERVICE,
                confidence = 0.85f,
                requiredTools = listOf("record_service"),
                toolParams = mapOf("record_service" to extractServiceParams(input))
            )
        }

        return null
    }

    /**
     * Boost classification using learned vocabulary from the flywheel.
     * If the user uses words the system has learned from past interactions,
     * we can infer the intent with higher confidence.
     */
    private fun matchWithLearnedVocab(input: String, existing: UserIntent?): UserIntent? {
        val words = input.split(Regex("\\s+")).map { it.lowercase() }
        val matchedLearned = words.filter { it in learnedVocab }

        if (matchedLearned.isEmpty()) return existing

        // If we already have a partial match, boost its confidence
        if (existing != null && existing.confidence > 0.5f) {
            val boost = (matchedLearned.size * 0.05f).coerceAtMost(0.2f)
            return existing.copy(confidence = (existing.confidence + boost).coerceAtMost(1.0f))
        }

        // Learned vocab alone isn't enough to classify — return existing
        return existing
    }

    // ── Pattern matchers ──────────────────────

    private fun matchesSalePattern(input: String): Boolean {
        val saleKeywords = listOf(
            "nimeuza", "niliuza", "uza", "sold", "sale", "nimemuuza",
            "nimepata", "customer", "mteja ame", "nilipatia",
            "sold", "i sold", "nimemuuzia", "nimemuuza"
        )
        return saleKeywords.any { input.contains(it) }
    }

    private fun matchesExpensePattern(input: String): Boolean {
        val expenseKeywords = listOf(
            "nimetumia", "nilitumia", "expense", "cost", "spent",
            "nilipia", "nimelipia", "gharama", "matumizi",
            "i spent", "i paid", "nimetumia"
        )
        return expenseKeywords.any { input.contains(it) }
    }

    private fun matchesStockCheckPattern(input: String): Boolean {
        val stockKeywords = listOf(
            "stock", "inventory", "bidhaa", "imebaki", "imepungua",
            "how much", "kiasi", "nina", "remaining", "baki",
            "ikoje", "vipi stock"
        )
        return stockKeywords.any { input.contains(it) }
    }

    private fun matchesSalesQueryPattern(input: String): Boolean {
        val keywords = listOf(
            "nimepata ngapi", "sales today", "nimeuza ngapi",
            "how much today", "leo nimepata", "today sales",
            "mapato ya leo", "nimeuza leo"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesProfitQueryPattern(input: String): Boolean {
        val keywords = listOf(
            "profit", "faida", "ni ngapi", "how much profit",
            "nimepata faida", "nilipata faida"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesExpenseQueryPattern(input: String): Boolean {
        val keywords = listOf(
            "nilitumia ngapi", "expenses today", "matumizi ya leo",
            "how much did i spend", "gharama"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesDebtorsPattern(input: String): Boolean {
        val keywords = listOf(
            "deni", "debt", "owes", "anadaiwa", "credit",
            "mteja anadaiwa", "walinidai", "wana deni"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesServicePattern(input: String): Boolean {
        val serviceKeywords = listOf(
            // Repair
            "repair", "kurepair", "kufix", "kutengeneza", "nimefix",
            // Beauty
            "kata nywele", "kukata nywele", "braid", "kubraid", "manicure", "pedicure",
            "nimekata", "nimemkata",
            // Cleaning
            "osha gari", "kuosha gari", "car wash", "nimeosha",
            // Construction
            "nimefanya", "nimemfanyia", "nimejenga", "nimechimba",
            // General service
            "nimemfanyia", "nimefanyia kazi"
        )
        return serviceKeywords.any { input.contains(it) }
    }

    private fun extractServiceParams(input: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val amount = numberPattern.find(input)?.value
        if (amount != null) params["amount"] = amount
        // Detect service type
        params["service_type"] = when {
            input.contains("repair") || input.contains("fix") || input.contains("tengeneza") -> "repair"
            input.contains("kata") || input.contains("nywele") || input.contains("braid") -> "beauty"
            input.contains("osha") || input.contains("gari") || input.contains("car wash") -> "cleaning"
            input.contains("jenga") || input.contains("chimba") || input.contains("fanya") -> "construction"
            else -> "general"
        }
        return params
    }

    private fun matchesGreetingPattern(input: String): Boolean {
        val greetings = listOf(
            "habari", "hi", "hello", "hey", "niaje", "sasa",
            "mambo", "vipi", "shikamoo", "good morning",
            "good afternoon", "good evening", "hujambo"
        )
        return greetings.any { input.startsWith(it) || input.contains(it) }
    }

    private fun matchesHelpPattern(input: String): Boolean {
        val keywords = listOf("help", "msaada", "unaweza", "what can you do", "nifanye nini")
        return keywords.any { input.contains(it) }
    }

    private fun matchesThanksPattern(input: String): Boolean {
        val keywords = listOf("asante", "thanks", "thank you", "shukrani", "nashukuru")
        return keywords.any { input.contains(it) }
    }

    private fun matchesAdvicePattern(input: String): Boolean {
        val keywords = listOf(
            "ushauri", "advice", "suggest", "recommend",
            "nifanye nini", "what should", "how can i",
            "ninawezaje", "ni bora"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesPurchasePattern(input: String): Boolean {
        val keywords = listOf(
            "nimenunua", "nilinunua", "bought", "purchased",
            "nimeweka", "nimetia", "nimeongeza stock",
            "i bought", "nilichukua"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesReportPattern(input: String): Boolean {
        val keywords = listOf(
            "report", "ripoti", "summary", "muhtasari",
            "daily report", "weekly", "monthly", "jumla"
        )
        return keywords.any { input.contains(it) }
    }

    // ── Entity extraction ──────────────────────

    private fun extractEntities(input: String): Map<String, String> {
        val entities = mutableMapOf<String, String>()

        // Extract currency amounts
        currencyPattern.find(input)?.let {
            val amount = it.groupValues[1].ifEmpty { it.groupValues[2] }
            entities["amount"] = amount
        }

        // Extract phone numbers
        phonePattern.find(input)?.let {
            entities["phone"] = it.value
        }

        // Extract numbers (quantities)
        numberPattern.findAll(input).forEach { match ->
            if (!entities.containsKey("amount")) {
                entities["quantity"] = match.value
            }
        }

        return entities
    }

    private fun extractSaleParams(input: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        currencyPattern.find(input)?.let {
            params["amount"] = it.groupValues[1].ifEmpty { it.groupValues[2] }
        }
        // Extract product name (rough heuristic)
        val words = input.split(" ")
        val productWords = words.filter { w ->
            !listOf("nimeuza", "niliuza", "nilipatia", "mteja", "ame", "kwa", "za", "nilimuuza",
                "nimemuuza", "nimemuuzia", "customer", "sold", "i", "for").contains(w.lowercase())
                    && !w.matches(Regex("""\d+\.?\d*"""))
        }
        if (productWords.isNotEmpty()) {
            params["product"] = productWords.joinToString(" ")
        }
        return params
    }

    private fun extractExpenseParams(input: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        currencyPattern.find(input)?.let {
            params["amount"] = it.groupValues[1].ifEmpty { it.groupValues[2] }
        }
        // Categorize expense
        val categories = mapOf(
            "transport" to listOf("transport", "usafiri", "fare", "matatu", "boda"),
            "rent" to listOf("rent", "kodi", "house"),
            "food" to listOf("food", "chakula", "lunch", "breakfast", "meal"),
            "utilities" to listOf("electricity", "umeme", "water", "maji", "airtime"),
            "stock" to listOf("stock", "bidhaa", "goods", "merchandise")
        )
        for ((cat, keywords) in categories) {
            if (keywords.any { input.contains(it) }) {
                params["category"] = cat
                break
            }
        }
        params.putIfAbsent("category", "misc")
        params["description"] = input
        return params
    }

    private fun extractPurchaseParams(input: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        currencyPattern.find(input)?.let {
            params["cost"] = it.groupValues[1].ifEmpty { it.groupValues[2] }
        }
        return params
    }

    private fun loadPatterns() {
        // Patterns will be loaded from assets at runtime
        // For now, use the hardcoded pattern matchers above
        Timber.d("Intent patterns initialized")
    }
}
