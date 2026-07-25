package com.msaidizi.app.superagent.harness

import com.msaidizi.app.core.database.KnowledgeDao
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import timber.log.Timber
import com.msaidizi.app.superagent.flywheel.FlywheelEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 3-Tier Hybrid Intent Router — First stage of the superagent pipeline.
 *
 * Classification strategy (cost/accuracy trade-off):
 *
 *  - **Tier 1 — Pattern Match (~60% of inputs):** Zero-cost keyword/regex matching
 *    for known intents. Instant, deterministic, no LLM invocation.
 *
 *  - **Tier 2 — Embedding Similarity (~25% of inputs):** Lightweight TF-IDF-style
 *    cosine similarity against a bank of intent exemplars. Zero API cost (pure CPU),
 *    handles paraphrases and Swahili/English variations the keywords miss.
 *
 *  - **Tier 3 — LLM Classification (~15% of inputs):** Falls back to the on-device
 *    Qwen model's function-calling capability for truly novel or ambiguous inputs.
 *    Higher latency, but handles anything.
 *
 * The LLM only fires when Tiers 1 and 2 both fail to produce a confident result,
 * keeping average cost and latency low while maximising coverage.
 *
 * Enhanced with flywheel learned vocabulary for better matching over time.
 */
@Singleton
class IntentRouter @Inject constructor(
    private val knowledgeDao: KnowledgeDao,
    private val flywheelEngine: FlywheelEngine,
    private val llmEngine: LlmEngine,
    private val gson: Gson
) {
    // ── Tier 1: Pattern matching state ──────────────────────

    /** Intent patterns loaded from assets/knowledge/intent_patterns.json */
    private var intentPatterns: Map<IntentType, List<Regex>> = emptyMap()

    /** Learned vocabulary cache (refreshed on each route call) */
    private var learnedVocab: Set<String> = emptySet()

    // Entity extraction patterns
    private val numberPattern = Regex("""\d+\.?\d*""")
    private val currencyPattern = Regex(
        """(?:ksh|kes|shillings?)\s*(\d+\.?\d*)|(\d+\.?\d*)\s*(?:ksh|kes|shillings?)""",
        RegexOption.IGNORE_CASE
    )
    private val phonePattern = Regex("""(?:\+?254|0)[17]\d{8}""")

    // ── Tier 2: Embedding similarity state ──────────────────

    /**
     * Intent exemplar bank — maps each IntentType to a list of representative
     * phrases the user might say. These are embedded at init time into TF-IDF
     * vectors for fast cosine similarity matching.
     */
    private val intentExemplars: Map<IntentType, List<String>> = mapOf(
        IntentType.RECORD_SALE to listOf(
            "nimeuza", "nimemuuza", "nimemuuzia", "sold", "i sold", "nilipatia mteja",
            "nilimuuza", "customer ameenda na", "nimepata mauzo", "nilipokea pesa ya bidhaa",
            "nauza", "niliuza", "mteja amenunua", "amechukua bidhaa",
            "record sale", "log sale", "enter sale", "sale of"
        ),
        IntentType.RECORD_EXPENSE to listOf(
            "nimetumia", "nilitumia", "expense", "cost", "spent", "nilipia",
            "nimelipia", "gharama", "matumizi", "i spent", "i paid",
            "nimelipia bill", "nilitumia pesa", "record expense", "log expense",
            "enter cost", "nimetoa pesa", "nimepunguza pesa"
        ),
        IntentType.RECORD_PURCHASE to listOf(
            "nimenunua", "nilinunua", "bought", "purchased", "nimeweka",
            "nimetia", "nimeongeza stock", "i bought", "nilichukua",
            "record purchase", "log purchase", "nimeng'oa bidhaa", "nilinunua stock"
        ),
        IntentType.RECORD_SERVICE to listOf(
            "nimemfanyia", "nimefanyia kazi", "nimefix", "kurepair", "kutengeneza",
            "nimekata nywele", "kubraid", "nimeosha gari", "nimejenga", "nimechimba",
            "nimemkata", "nilifanya kazi", "service rendered", "nimemtengenezea",
            "record service", "log service"
        ),
        IntentType.ASK_STOCK to listOf(
            "stock", "inventory", "bidhaa", "imebaki", "imepungua", "kiasi",
            "nina", "remaining", "baki", "ikoje", "vipi stock", "how much stock",
            "check stock", "stock levels", "nina bidhaa ngapi", "bidhaa zangu",
            "what is remaining", "stock count", "inventory check"
        ),
        IntentType.ASK_SALES_TODAY to listOf(
            "nimepata ngapi", "sales today", "nimeuza ngapi", "how much today",
            "leo nimepata", "today sales", "mapato ya leo", "nimeuza leo",
            "total sales", "mauzo ya leo", "how are sales", "sales report today",
            "nimepata pesa ngapi leo", "today's sales"
        ),
        IntentType.ASK_PROFIT to listOf(
            "profit", "faida", "ni ngapi", "how much profit", "nimepata faida",
            "nilipata faida", "faida ya leo", "profit today", "how much did i earn",
            "gani ni faida", "total profit", "net profit", "nimepata wangapi baada ya gharama"
        ),
        IntentType.ASK_EXPENSES to listOf(
            "nilitumia ngapi", "expenses today", "matumizi ya leo",
            "how much did i spend", "gharama", "total expenses", "matumizi",
            "spending today", "gharama za leo", "how much spent", "expense report"
        ),
        IntentType.ASK_DEBTORS to listOf(
            "deni", "debt", "owes", "anadaiwa", "credit", "mteja anadaiwa",
            "walinidai", "wana deni", "who owes me", "outstanding debts",
            "customer debts", "deni za wateja", "unpaid balances", "receivables"
        ),
        IntentType.DAILY_REPORT to listOf(
            "report", "ripoti", "summary", "muhtasari", "daily report",
            "weekly", "monthly", "jumla", "business summary", "give me report",
            "nipe ripoti", "leo imekuwaje", "how is business", "biashara ikoje",
            "performance report", "business overview"
        ),
        IntentType.ASK_ADVICE to listOf(
            "ushauri", "advice", "suggest", "recommend", "nifanye nini",
            "what should", "how can i", "ninawezaje", "ni bora",
            "give me advice", "what do you suggest", "help me decide",
            "niongoze", "niauni", "business advice", "pricing advice"
        ),
        IntentType.GREETING to listOf(
            "habari", "hi", "hello", "hey", "niaje", "sasa", "mambo",
            "vipi", "shikamoo", "good morning", "good afternoon",
            "good evening", "hujambo", "salama", "ujambo"
        ),
        IntentType.HELP to listOf(
            "help", "msaada", "unaweza", "what can you do", "nifanye nini",
            "how does this work", "unisaidie", "nipe msaada", "options",
            "menu", "commands", "what do you do"
        ),
        IntentType.THANKS to listOf(
            "asante", "thanks", "thank you", "shukrani", "nashukuru",
            "asante sana", "thank you very much", "nawashukuru"
        ),
        IntentType.FAREWELL to listOf(
            "kwaheri", "bye", "goodbye", "see you", "tutaonana",
            "baadaye", "later", "good night", "usiku mwema"
        ),
        IntentType.CHECK_CUSTOMER_DEBT to listOf(
            "customer debt", "deni ya mteja", "how much does", "owes me",
            "anadaiwa ngapi", "check debt", "mteja ana deni"
        ),
        IntentType.RECORD_PAYMENT to listOf(
            "amepay", "amelipa", "payment received", "nimelipwa",
            "record payment", "log payment", "deni imepungua", "partial payment"
        )
    )

    /** Pre-computed TF-IDF word vectors for each intent exemplar. */
    private lateinit var intentVectors: Map<IntentType, List<Map<String, Float>>>

    /** Global IDF values computed from the exemplar corpus. */
    private lateinit var idf: Map<String, Float>

    // ── Tier thresholds ──────────────────────

    companion object {
        /** Minimum confidence for Tier 1 pattern match to short-circuit. */
        private const val TIER1_CONFIDENCE = 0.8f

        /** Minimum cosine similarity for Tier 2 embedding match. */
        private const val TIER2_SIMILARITY = 0.35f

        /** If Tier 2 best similarity is below this, escalate to Tier 3. */
        private const val TIER2_ESCALATION_THRESHOLD = 0.25f

        /** Maximum tokens for Tier 3 LLM classification call. */
        private const val TIER3_MAX_TOKENS = 128
    }

    init {
        loadPatterns()
        buildExemplarVectors()
    }

    // ════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════

    /**
     * Route user input to an intent using the 3-tier hybrid pipeline.
     *
     * 1. Tier 1: Pattern match (instant, zero cost)
     * 2. Tier 2: Embedding similarity (CPU only, handles paraphrases)
     * 3. Tier 3: LLM classification (on-device Qwen, handles novel input)
     */
    suspend fun route(input: String): UserIntent {
        val normalized = input.trim().lowercase()

        // Refresh learned vocabulary from flywheel
        try {
            learnedVocab = flywheelEngine.getVocabularyWords()
        } catch (e: Exception) {
            Timber.w(e, "Failed to load learned vocabulary")
        }

        // ── Tier 1: Pattern matching ──────────────────
        var tier1Result = matchPatterns(normalized)

        // Boost with learned vocabulary
        if (tier1Result == null || tier1Result.confidence <= TIER1_CONFIDENCE) {
            val boosted = matchWithLearnedVocab(normalized, tier1Result)
            if (boosted != null) tier1Result = boosted
        }

        if (tier1Result != null && tier1Result.confidence > TIER1_CONFIDENCE) {
            Timber.d("Tier 1 matched: %s (%.2f)", tier1Result.type, tier1Result.confidence)
            return tier1Result.copy(
                entities = extractEntities(input),
                rawText = input
            )
        }

        // ── Tier 2: Embedding similarity ──────────────
        val tier2Result = matchByEmbedding(normalized)

        if (tier2Result != null && tier2Result.second >= TIER2_SIMILARITY) {
            val intent = tier2Result.first
            val similarity = tier2Result.second
            Timber.d("Tier 2 matched: %s (sim=%.3f)", intent, similarity)

            val resolved = resolveIntentFromType(intent, input)
            return resolved.copy(
                confidence = (0.6f + similarity * 0.3f).coerceAtMost(0.95f),
                entities = extractEntities(input),
                rawText = input
            )
        }

        // ── Tier 3: LLM classification ───────────────
        // Only fire if Tier 2 wasn't confident either
        if (tier2Result == null || tier2Result.second < TIER2_ESCALATION_THRESHOLD) {
            Timber.d("Escalating to Tier 3 (LLM classification)")
            val tier3Result = classifyByLlm(input)
            if (tier3Result != null) {
                Timber.d("Tier 3 matched: %s (%.2f)", tier3Result.type, tier3Result.confidence)
                return tier3Result.copy(
                    entities = extractEntities(input),
                    rawText = input
                )
            }
        } else {
            // Tier 2 had a weak match — use it with low confidence
            val intent = tier2Result.first
            val resolved = resolveIntentFromType(intent, input)
            return resolved.copy(
                confidence = (0.5f + tier2Result.second * 0.3f).coerceAtMost(0.8f),
                entities = extractEntities(input),
                rawText = input
            )
        }

        // All tiers failed — return best Tier 1 guess or UNKNOWN
        return tier1Result?.copy(entities = extractEntities(input), rawText = input)
            ?: UserIntent(
                type = IntentType.UNKNOWN,
                confidence = 0.3f,
                entities = extractEntities(input),
                rawText = input
            )
    }

    // ════════════════════════════════════════════════════════════
    //  TIER 1: Pattern Matching (existing logic, zero cost)
    // ════════════════════════════════════════════════════════════

    /**
     * Match input against known keyword/regex patterns.
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
     */
    private fun matchWithLearnedVocab(input: String, existing: UserIntent?): UserIntent? {
        val words = input.split(Regex("\\s+")).map { it.lowercase() }
        val matchedLearned = words.filter { it in learnedVocab }

        if (matchedLearned.isEmpty()) return existing

        if (existing != null && existing.confidence > 0.5f) {
            val boost = (matchedLearned.size * 0.05f).coerceAtMost(0.2f)
            return existing.copy(confidence = (existing.confidence + boost).coerceAtMost(1.0f))
        }

        return existing
    }

    // ════════════════════════════════════════════════════════════
    //  TIER 2: Embedding Similarity (CPU-only, handles paraphrases)
    // ════════════════════════════════════════════════════════════

    /**
     * Build TF-IDF vectors for all intent exemplars at init time.
     * This is a one-time cost — vectors are kept in memory for fast lookup.
     */
    private fun buildExemplarVectors() {
        // 1. Build document frequency (DF) across all exemplars
        val df = mutableMapOf<String, Int>()
        val allDocs = mutableListOf<List<String>>()
        val totalDocs: Int

        for ((_, exemplars) in intentExemplars) {
            for (phrase in exemplars) {
                val tokens = tokenize(phrase)
                allDocs.add(tokens)
                val unique = tokens.toSet()
                for (word in unique) {
                    df[word] = (df[word] ?: 0) + 1
                }
            }
        }
        totalDocs = allDocs.size

        // 2. Compute IDF: log(N / df) with smoothing
        idf = df.mapValues { (_, count) ->
            kotlin.math.ln((totalDocs.toDouble() + 1) / (count.toDouble() + 1)) + 1.0f
        }

        // 3. Build TF-IDF vector for each exemplar
        val vectors = mutableMapOf<IntentType, List<Map<String, Float>>>()
        var docIndex = 0
        for ((intent, exemplars) in intentExemplars) {
            val intentVecs = mutableListOf<Map<String, Float>>()
            for (phrase in exemplars) {
                val tokens = allDocs[docIndex++]
                intentVecs.add(computeTfidf(tokens))
            }
            vectors[intent] = intentVecs
        }
        intentVectors = vectors

        Timber.d("Tier 2: Built %d intent exemplar vectors (%d unique terms)",
            intentExemplars.values.sumOf { it.size }, idf.size)
    }

    /**
     * Tokenize input into lowercase word tokens.
     * Strips punctuation, splits on whitespace.
     */
    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 1 }
    }

    /**
     * Compute a TF-IDF vector for a list of tokens.
     * Returns a map of term → weight.
     */
    private fun computeTfidf(tokens: List<String>): Map<String, Float> {
        val tf = mutableMapOf<String, Int>()
        for (t in tokens) {
            tf[t] = (tf[t] ?: 0) + 1
        }
        val maxTf = tf.values.maxOrNull()?.toFloat() ?: 1f
        return tf.mapValues { (term, count) ->
            val termFreq = 0.5f + 0.5f * (count.toFloat() / maxTf)  // augmented TF
            val idfWeight = idf[term] ?: 1.0f
            termFreq * idfWeight
        }
    }

    /**
     * Compute cosine similarity between two sparse vectors.
     */
    private fun cosineSimilarity(a: Map<String, Float>, b: Map<String, Float>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        // Dot product over intersection
        for ((term, weightA) in a) {
            val weightB = b[term]
            if (weightB != null) {
                dotProduct += weightA * weightB
            }
            normA += weightA * weightA
        }
        for ((_, weightB) in b) {
            normB += weightB * weightB
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0f) 0f else dotProduct / denominator
    }

    /**
     * Find the best-matching intent using embedding (TF-IDF cosine) similarity.
     *
     * For each intent, computes max similarity against all its exemplars.
     * Returns the intent with the highest score and that score.
     *
     * @return Pair of (IntentType, similarity) or null if no reasonable match.
     */
    private fun matchByEmbedding(input: String): Pair<IntentType, Float>? {
        val inputTokens = tokenize(input)
        if (inputTokens.isEmpty()) return null

        val inputVec = computeTfidf(inputTokens)
        if (inputVec.isEmpty()) return null

        var bestIntent: IntentType? = null
        var bestSimilarity = 0f

        for ((intent, exemplarVecs) in intentVectors) {
            for (exemplarVec in exemplarVecs) {
                val sim = cosineSimilarity(inputVec, exemplarVec)
                if (sim > bestSimilarity) {
                    bestSimilarity = sim
                    bestIntent = intent
                }
            }
        }

        return if (bestIntent != null && bestSimilarity > 0.1f) {
            Pair(bestIntent, bestSimilarity)
        } else {
            null
        }
    }

    // ════════════════════════════════════════════════════════════
    //  TIER 3: LLM Classification (on-device Qwen, handles novel input)
    // ════════════════════════════════════════════════════════════

    /**
     * Use the on-device LLM to classify intent when Tiers 1 and 2 fail.
     *
     * Sends a compact classification prompt to Qwen with function calling,
     * then maps the result back to an IntentType.
     */
    private suspend fun classifyByLlm(input: String): UserIntent? {
        if (llmEngine.state !is LlmEngine.State.Ready) {
            Timber.d("Tier 3 skipped: LLM not ready (state=%s)", llmEngine.state)
            return null
        }

        return try {
            val systemPrompt = buildClassificationPrompt()
            val response = llmEngine.generate(
                systemPrompt = systemPrompt,
                userMessage = input,
                context = AssembledContext(
                    businessContext = "",
                    relevantKnowledge = emptyList(),
                    recentHistory = emptyList(),
                    userPreferences = emptyMap()
                ),
                toolResults = emptyList(),
                intent = UserIntent(type = IntentType.UNKNOWN, confidence = 0f, rawText = input)
            )

            parseClassificationResponse(response, input)
        } catch (e: Exception) {
            Timber.w(e, "Tier 3 LLM classification failed")
            null
        }
    }

    /**
     * Build a compact system prompt that asks the LLM to classify the user's intent.
     * Uses function calling so the response is structured and parseable.
     */
    private fun buildClassificationPrompt(): String {
        return """You are an intent classifier for a Kenyan business assistant (Msaidizi).
Classify the user's message into exactly ONE intent. Respond with ONLY a function call.

Available intents:
- record_sale: Recording a sale transaction
- record_expense: Recording an expense
- record_purchase: Recording a stock/goods purchase
- record_service: Recording a service provided (repair, beauty, cleaning, etc.)
- check_stock: Checking inventory/stock levels
- ask_sales: Querying sales data
- ask_profit: Querying profit data
- ask_expenses: Querying expense data
- ask_debtors: Checking who owes money
- daily_report: Requesting a business report
- ask_advice: Asking for business advice
- greeting: Saying hello
- farewell: Saying goodbye
- thanks: Expressing gratitude
- help: Asking for help or capabilities
- unknown: Cannot determine intent

Respond with a single function call:
<tool_call>
{"name": "classify_intent", "arguments": {"intent": "<intent_name>", "confidence": <0.0-1.0>}}
</tool_call>"""
    }

    /**
     * Parse the LLM's classification response into a UserIntent.
     */
    private fun parseClassificationResponse(response: String, originalInput: String): UserIntent? {
        // Try to extract function call
        val toolCallPattern = Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        val match = toolCallPattern.find(response)
        val jsonStr = match?.groupValues?.get(1)
            ?: response.trim().let { raw ->
                if (raw.startsWith("{") && raw.contains("\"intent\"")) raw else null
            }
            ?: return null

        return try {
            val jsonObj = com.google.gson.JsonParser.parseString(jsonStr).asJsonObject
            val intentName = jsonObj.get("intent")?.asString ?: return null
            val confidence = jsonObj.get("confidence")?.asFloat ?: 0.6f

            val intentType = mapLlmIntentToType(intentName)
            val resolved = resolveIntentFromType(intentType, originalInput)

            resolved.copy(
                confidence = confidence.coerceIn(0.4f, 0.85f)  // Cap Tier 3 confidence
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse Tier 3 classification: %s", response)
            null
        }
    }

    /**
     * Map an LLM-generated intent string to an IntentType.
     */
    private fun mapLlmIntentToType(intentName: String): IntentType {
        return when (intentName.lowercase().replace("-", "_")) {
            "record_sale" -> IntentType.RECORD_SALE
            "record_expense" -> IntentType.RECORD_EXPENSE
            "record_purchase" -> IntentType.RECORD_PURCHASE
            "record_service" -> IntentType.RECORD_SERVICE
            "record_payment" -> IntentType.RECORD_PAYMENT
            "check_stock" -> IntentType.ASK_STOCK
            "ask_sales", "ask_sales_today" -> IntentType.ASK_SALES_TODAY
            "ask_profit" -> IntentType.ASK_PROFIT
            "ask_expenses" -> IntentType.ASK_EXPENSES
            "ask_debtors" -> IntentType.ASK_DEBTORS
            "daily_report", "report" -> IntentType.DAILY_REPORT
            "ask_advice", "advice" -> IntentType.ASK_ADVICE
            "greeting" -> IntentType.GREETING
            "farewell" -> IntentType.FAREWELL
            "thanks" -> IntentType.THANKS
            "help" -> IntentType.HELP
            else -> IntentType.UNKNOWN
        }
    }

    /**
     * Resolve an IntentType into a full UserIntent with appropriate tool assignments.
     */
    private fun resolveIntentFromType(type: IntentType, input: String): UserIntent {
        return when (type) {
            IntentType.RECORD_SALE -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("record_sale"),
                toolParams = mapOf("record_sale" to extractSaleParams(input))
            )
            IntentType.RECORD_EXPENSE -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("record_expense"),
                toolParams = mapOf("record_expense" to extractExpenseParams(input))
            )
            IntentType.RECORD_PURCHASE -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("record_purchase"),
                toolParams = mapOf("record_purchase" to extractPurchaseParams(input))
            )
            IntentType.RECORD_SERVICE -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("record_service"),
                toolParams = mapOf("record_service" to extractServiceParams(input))
            )
            IntentType.ASK_STOCK -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("check_stock")
            )
            IntentType.ASK_SALES_TODAY -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("query_sales")
            )
            IntentType.ASK_PROFIT -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("query_profit")
            )
            IntentType.ASK_EXPENSES -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("query_expenses")
            )
            IntentType.ASK_DEBTORS -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("query_debtors")
            )
            IntentType.DAILY_REPORT -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("generate_report")
            )
            IntentType.ASK_ADVICE -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("query_business_data")
            )
            IntentType.GREETING -> UserIntent(type = type, confidence = 0.8f)
            IntentType.FAREWELL -> UserIntent(type = type, confidence = 0.8f)
            IntentType.THANKS -> UserIntent(type = type, confidence = 0.8f)
            IntentType.HELP -> UserIntent(type = type, confidence = 0.8f)
            else -> UserIntent(type = IntentType.UNKNOWN, confidence = 0.3f)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Pattern Matchers (Tier 1 — unchanged from original)
    // ════════════════════════════════════════════════════════════

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
            "repair", "kurepair", "kufix", "kutengeneza", "nimefix",
            "kata nywele", "kukata nywele", "braid", "kubraid", "manicure", "pedicure",
            "nimekata", "nimemkata",
            "osha gari", "kuosha gari", "car wash", "nimeosha",
            "nimefanya", "nimemfanyia", "nimejenga", "nimechimba",
            "nimemfanyia", "nimefanyia kazi"
        )
        return serviceKeywords.any { input.contains(it) }
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

    // ════════════════════════════════════════════════════════════
    //  Entity Extraction (shared across all tiers)
    // ════════════════════════════════════════════════════════════

    private fun extractEntities(input: String): Map<String, String> {
        val entities = mutableMapOf<String, String>()

        currencyPattern.find(input)?.let {
            val amount = it.groupValues[1].ifEmpty { it.groupValues[2] }
            entities["amount"] = amount
        }

        phonePattern.find(input)?.let {
            entities["phone"] = it.value
        }

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

    private fun extractServiceParams(input: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val amount = numberPattern.find(input)?.value
        if (amount != null) params["amount"] = amount
        params["service_type"] = when {
            input.contains("repair") || input.contains("fix") || input.contains("tengeneza") -> "repair"
            input.contains("kata") || input.contains("nywele") || input.contains("braid") -> "beauty"
            input.contains("osha") || input.contains("gari") || input.contains("car wash") -> "cleaning"
            input.contains("jenga") || input.contains("chimba") || input.contains("fanya") -> "construction"
            else -> "general"
        }
        return params
    }

    private fun loadPatterns() {
        // Patterns will be loaded from assets at runtime
        // For now, use the hardcoded pattern matchers above
        Timber.d("Intent patterns initialized")
    }
}
