package com.msaidizi.agent.harness

import com.msaidizi.core.database.KnowledgeDao
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import timber.log.Timber
import com.msaidizi.agent.flywheel.FlywheelEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 3-Tier Hybrid Intent Router — First stage of the superagent pipeline.
 *
 * Classification strategy (cost / accuracy trade-off):
 *
 *  - **Tier 1 — Pattern Match (~60% of inputs):** Zero-cost keyword/regex matching
 *    for known intents. Instant, deterministic, no LLM invocation.
 *
 *  - **Tier 2 — Embedding Similarity (~25% of inputs):** Lightweight hash-trick
 *    embeddings (fixed-dimension float arrays) with cosine similarity.
 *    Zero API cost (pure CPU), handles paraphrases and Swahili/English
 *    variations the keywords miss.
 *
 *  - **Tier 3 — LLM Function Calling (~15% of inputs):** Falls back to the
 *    on-device Qwen 0.8B model via Hermes-style function calling for truly
 *    novel or ambiguous inputs. Higher latency, but handles anything.
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
     * phrases the user might say. Embedded into fixed-dimension float arrays
     * at init time for fast cosine similarity matching.
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
        ),
        IntentType.SCAN_RECEIPT to listOf(
            "scan receipt", "skan risiti", "picha ya risiti", "ona risiti",
            "tazama risiti", "scan picha", "receipt image", "ocr receipt",
            "scan receipt image", "chukua risiti", "piga risiti picha"
        ),
        IntentType.VIEW_DASHBOARD to listOf(
            "dashboard", "ripoti ya biashara", "muhtasari", "muhtasari wa biashara",
            "business health", "afya ya biashara", "jinsi biashara inavyoenda",
            "show dashboard", "dashboard status", "biashara ikoje",
            "nione dashboard", "onekana dashboard", "overall status"
        ),
        IntentType.QUICK_SALE to listOf(
            "quick sale", "uza haraka", "haraka", "sale ya haraka",
            "fast sale", "quick record", "haraka nimeuza",
            "one tap sale", "fupi sale", "uza upesi"
        ),
        IntentType.CHAMA_MANAGE to listOf(
            "chama", "mchango", "contribute", "group savings",
            "chama changu", "mchango wangu", "chama contributions",
            "table banking", "harambee", "contribute to chama",
            "chama balance", "chama members"
        ),
        IntentType.CREDIT_CHECK to listOf(
            "credit score", "mkopo", "loan ready", "credit readiness",
            "nikopeshwe", "naweza pata mkopo", "niko tayari mkopo",
            "check credit", "credit report", "信用score",
            "oweza mkopo", "readiness ya mkopo"
        ),
        IntentType.LOAN_COMPARE to listOf(
            "compare loans", "linganisha mikopo", "which loan", "mkopo gani",
            "loan comparison", "interest rate", "kiwango cha riba",
            "best loan", "mkopo bora", "compare lenders",
            "loan options", "chaguo za mkopo"
        ),
        IntentType.INSURANCE_MATCH to listOf(
            "insurance", "bima", "insurance match", "bima gani",
            "find insurance", "nipe bima", "insurance quote",
            "bima ya biashara", "business insurance", "cover",
            "insurance options", "chaguo za bima"
        ),
        IntentType.RIDE_SHARE to listOf(
            "ride", "pikipiki", "nduthi", "boda boda",
            "ride share", "gari", "transport", "nipe ride",
            "panda pikipiki", "delivery", "fanya delivery",
            "send package", "tuma mzigo"
        ),
        IntentType.MARKET_PRICE to listOf(
            "market price", "bei ya soko", "soko", "bei ya soko leo",
            "current price", "bei ya sasa", "market rates",
            "bei za soko", "bei ya bidhaa", "price check",
            "bei gani leo", "bei za leo"
        ),
        IntentType.PROOF_OF_INCOME to listOf(
            "proof of income", "uthibitisho wa mapato", "income statement",
            "mapato yangu", "show income", "income report",
            "proof ya mapato", "generate income proof",
            "income certificate", "financial statement"
        ),
        IntentType.GOAL_TRACK to listOf(
            "goal", "target", "lengo", "malengo",
            "track goal", "set target", "weka lengo",
            "my goals", "target yangu", "progress",
            "nimefikia", "goal tracker", "achievement"
        ),
        IntentType.WHATSAPP_REPORT to listOf(
            "whatsapp", "tuma whatsapp", "report whatsapp",
            "whatsapp report", "send report whatsapp",
            "tuma ripoti whatsapp", "whatsapp muhtasari",
            "share on whatsapp", "shiriki whatsapp"
        )
    )

    /** Pre-computed embedding vectors (fixed-dimension float arrays) for each intent. */
    private lateinit var intentEmbeddings: Map<IntentType, List<FloatArray>>

    // ── Tier thresholds ──────────────────────

    companion object {
        /** Minimum confidence for Tier 1 pattern match to short-circuit. */
        private const val TIER1_CONFIDENCE = 0.8f

        /**
         * Minimum cosine similarity for Tier 2 embedding match.
         * Hash-trick embeddings in 256 dimensions — 0.75 works well for
         * short Swahili/English business phrases.
         */
        private const val TIER2_SIMILARITY = 0.75f

        /** If Tier 2 best similarity is below this, escalate to Tier 3. */
        private const val TIER2_ESCALATION_THRESHOLD = 0.50f

        /** Dimensionality of the hash-trick embedding vectors. */
        private const val EMBEDDING_DIM = 256

        /** Maximum tokens for Tier 3 LLM classification call. */
        private const val TIER3_MAX_TOKENS = 128
    }

    init {
        loadPatterns()
        buildExemplarEmbeddings()
    }

    // ════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════

    /**
     * Route user input to an intent using the 3-tier hybrid pipeline.
     *
     * 1. Tier 1: Pattern match (instant, zero cost)
     * 2. Tier 2: Embedding similarity (CPU only, handles paraphrases)
     * 3. Tier 3: LLM function calling (on-device Qwen, handles novel input)
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

        // ── Tier 3: LLM function calling ─────────────
        // Only fire if Tier 2 wasn't confident either
        if (tier2Result == null || tier2Result.second < TIER2_ESCALATION_THRESHOLD) {
            Timber.d("Escalating to Tier 3 (LLM function calling)")
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
    //  TIER 1: Pattern Matching (zero cost, instant)
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

        // Purchase/stock addition
        if (matchesPurchasePattern(input)) {
            return UserIntent(
                type = IntentType.RECORD_PURCHASE,
                confidence = 0.85f,
                requiredTools = listOf("record_purchase"),
                toolParams = mapOf("record_purchase" to extractPurchaseParams(input))
            )
        }

        // Service transaction
        if (matchesServicePattern(input)) {
            return UserIntent(
                type = IntentType.RECORD_SERVICE,
                confidence = 0.85f,
                requiredTools = listOf("record_service"),
                toolParams = mapOf("record_service" to extractServiceParams(input))
            )
        }

        // Payment recording
        if (matchesPaymentPattern(input)) {
            return UserIntent(
                type = IntentType.RECORD_PAYMENT,
                confidence = 0.85f,
                requiredTools = listOf("record_payment"),
                toolParams = mapOf("record_payment" to extractPaymentParams(input))
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

        // Customer debt check
        if (matchesCustomerDebtPattern(input)) {
            return UserIntent(
                type = IntentType.CHECK_CUSTOMER_DEBT,
                confidence = 0.85f,
                requiredTools = listOf("query_debtors")
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

        // Advice
        if (matchesAdvicePattern(input)) {
            return UserIntent(
                type = IntentType.ASK_ADVICE,
                confidence = 0.8f,
                requiredTools = listOf("query_business_data")
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

        // Farewell
        if (matchesFarewellPattern(input)) {
            return UserIntent(type = IntentType.FAREWELL, confidence = 0.9f)
        }

        // Scan receipt
        if (matchesScanReceiptPattern(input)) {
            return UserIntent(
                type = IntentType.SCAN_RECEIPT,
                confidence = 0.9f,
                requiredTools = listOf("scan_receipt")
            )
        }

        // Dashboard
        if (matchesDashboardPattern(input)) {
            return UserIntent(
                type = IntentType.VIEW_DASHBOARD,
                confidence = 0.85f,
                requiredTools = listOf("business_health_dashboard")
            )
        }

        // Quick sale
        if (matchesQuickSalePattern(input)) {
            return UserIntent(
                type = IntentType.QUICK_SALE,
                confidence = 0.85f,
                requiredTools = listOf("quick_sale"),
                toolParams = mapOf("quick_sale" to extractSaleParams(input))
            )
        }

        // Chama
        if (matchesChamaPattern(input)) {
            return UserIntent(
                type = IntentType.CHAMA_MANAGE,
                confidence = 0.85f,
                requiredTools = listOf("chama_manager")
            )
        }

        // Credit check
        if (matchesCreditCheckPattern(input)) {
            return UserIntent(
                type = IntentType.CREDIT_CHECK,
                confidence = 0.85f,
                requiredTools = listOf("credit_readiness")
            )
        }

        // Loan compare
        if (matchesLoanComparePattern(input)) {
            return UserIntent(
                type = IntentType.LOAN_COMPARE,
                confidence = 0.85f,
                requiredTools = listOf("loan_comparison")
            )
        }

        // Insurance
        if (matchesInsurancePattern(input)) {
            return UserIntent(
                type = IntentType.INSURANCE_MATCH,
                confidence = 0.85f,
                requiredTools = listOf("insurance_matcher")
            )
        }

        // Ride share
        if (matchesRideSharePattern(input)) {
            return UserIntent(
                type = IntentType.RIDE_SHARE,
                confidence = 0.85f,
                requiredTools = listOf("ride_share")
            )
        }

        // Market price
        if (matchesMarketPricePattern(input)) {
            return UserIntent(
                type = IntentType.MARKET_PRICE,
                confidence = 0.85f,
                requiredTools = listOf("market_price_broadcaster")
            )
        }

        // Proof of income
        if (matchesProofOfIncomePattern(input)) {
            return UserIntent(
                type = IntentType.PROOF_OF_INCOME,
                confidence = 0.85f,
                requiredTools = listOf("proof_of_income")
            )
        }

        // Goal tracking
        if (matchesGoalTrackPattern(input)) {
            return UserIntent(
                type = IntentType.GOAL_TRACK,
                confidence = 0.85f,
                requiredTools = listOf("goal_tracker")
            )
        }

        // WhatsApp report
        if (matchesWhatsAppReportPattern(input)) {
            return UserIntent(
                type = IntentType.WHATSAPP_REPORT,
                confidence = 0.85f,
                requiredTools = listOf("whatsapp_reporter")
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
    //  TIER 2: Embedding Similarity (hash-trick float arrays)
    // ════════════════════════════════════════════════════════════

    /**
     * Compute a fixed-dimension embedding for a piece of text using the
     * **hashing trick** (feature hashing / "hashing vectorizer").
     *
     * Each token is hashed into one of [EMBEDDING_DIM] buckets; the sign
     * of the hash determines whether to add or subtract, reducing collision
     * bias.  Bigrams are also hashed for richer representation.
     *
     * This is deterministic, runs in O(n) time, and needs no vocabulary table.
     */
    private fun embedText(text: String): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return vec

        // Unigrams
        for (token in tokens) {
            val h = token.hashCode()
            val idx = (h and 0x7FFFFFFF) % EMBEDDING_DIM
            val sign = if (h < 0) -1f else 1f
            vec[idx] += sign
        }

        // Bigrams for richer context
        for (i in 0 until tokens.size - 1) {
            val bigram = "${tokens[i]}_${tokens[i + 1]}"
            val h = bigram.hashCode()
            val idx = (h and 0x7FFFFFFF) % EMBEDDING_DIM
            val sign = if (h < 0) -1f else 1f
            vec[idx] += sign * 0.5f  // bigrams weighted lower
        }

        // L2-normalise so cosine similarity is just a dot product
        val norm = sqrt(vec.fold(0f) { acc, v -> acc + v * v })
        if (norm > 0f) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    /**
     * Pre-compute embedding vectors for every intent exemplar at init time.
     * Stored as [intentEmbeddings]: Map<IntentType, List<FloatArray>>.
     */
    private fun buildExemplarEmbeddings() {
        val embeddings = mutableMapOf<IntentType, List<FloatArray>>()
        for ((intent, exemplars) in intentExemplars) {
            embeddings[intent] = exemplars.map { embedText(it) }
        }
        intentEmbeddings = embeddings

        val totalExemplars = intentExemplars.values.sumOf { it.size }
        Timber.d(
            "Tier 2: Built %d intent exemplar embeddings (dim=%d)",
            totalExemplars, EMBEDDING_DIM
        )
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
     * Compute cosine similarity between two L2-normalised vectors.
     * Since both are unit vectors, this is just the dot product.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot  // already L2-normalised → cosine
    }

    /**
     * Find the best-matching intent using embedding cosine similarity.
     *
     * For each intent, computes max similarity against all its exemplars.
     * Returns the intent with the highest score and that score.
     *
     * @return Pair of (IntentType, similarity) or null if no reasonable match.
     */
    private fun matchByEmbedding(input: String): Pair<IntentType, Float>? {
        val inputEmbedding = embedText(input)
        val inputNorm = sqrt(inputEmbedding.fold(0f) { acc, v -> acc + v * v })
        if (inputNorm == 0f) return null

        var bestIntent: IntentType? = null
        var bestSimilarity = 0f

        for ((intent, exemplarVecs) in intentEmbeddings) {
            for (exemplarVec in exemplarVecs) {
                val sim = cosineSimilarity(inputEmbedding, exemplarVec)
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
    //  TIER 3: LLM Function Calling (Qwen 0.8B, Hermes format)
    // ════════════════════════════════════════════════════════════

    /**
     * Use the on-device LLM to classify intent when Tiers 1 and 2 fail.
     *
     * Builds a Hermes-style function calling prompt with all tool schemas
     * from [ToolSchemas], sends it to Qwen 0.8B, and parses the structured
     * `<tool_call>` response.
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
                context = AssembledContext(),
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
     * Build a Hermes-style system prompt that asks the LLM to classify the
     * user's intent using function calling.
     *
     * Includes full tool schemas from [ToolSchemas.ALL] so the LLM can
     * produce a structured `<tool_call>` response.
     */
    private fun buildClassificationPrompt(): String {
        return buildString {
            appendLine("You are an intent classifier for a Kenyan business assistant (Msaidizi).")
            appendLine("Classify the user's message into exactly ONE intent by calling the appropriate function.")
            appendLine("Respond with ONLY a function call in the format shown below.")
            appendLine()
            appendLine("# Available Functions")
            appendLine()
            for ((name, schema) in ToolSchemas.ALL) {
                appendLine("## $name")
                appendLine("Description: ${schema.description}")
                if (schema.parameters.isNotEmpty()) {
                    appendLine("Parameters:")
                    val required = mutableListOf<String>()
                    val optional = mutableListOf<String>()
                    for ((paramName, paramSchema) in schema.parameters) {
                        val desc = buildString {
                            append("- $paramName (${paramSchema.type})")
                            paramSchema.enum?.let { append(" [${it.joinToString(", ")}]") }
                            append(": ${paramSchema.description}")
                        }
                        if (paramSchema.required) required.add(desc) else optional.add(desc)
                    }
                    if (required.isNotEmpty()) {
                        appendLine("  Required:")
                        required.forEach { appendLine("    $it") }
                    }
                    if (optional.isNotEmpty()) {
                        appendLine("  Optional:")
                        optional.forEach { appendLine("    $it") }
                    }
                }
                appendLine()
            }
            appendLine("# Response Format")
            appendLine()
            appendLine("Respond with ONLY a JSON block in this exact format:")
            appendLine()
            appendLine("<tool_call>")
            appendLine("{\"name\": \"function_name\", \"arguments\": {\"param1\": \"value1\"}}")
            appendLine("</tool_call>")
            appendLine()
            appendLine("Rules:")
            appendLine("- Call ONLY one function per response")
            appendLine("- Use the exact function names listed above")
            appendLine("- For conversational messages (greetings, thanks, help, farewell), respond naturally WITHOUT a function call")
            appendLine("- If the message is ambiguous or unclear, call the function that best matches")
        }.also { prompt ->
            Timber.d("Tier 3 prompt built: %d chars, %d tools", prompt.length, ToolSchemas.ALL.size)
        }
    }

    /**
     * Parse the LLM's function calling response into a UserIntent.
     *
     * Handles two formats:
     * 1. Hermes `<tool_call>{"name": "...", "arguments": {...}}</tool_call>` blocks
     * 2. Raw JSON fallback when tags are missing
     */
    private fun parseClassificationResponse(response: String, originalInput: String): UserIntent? {
        // Try to extract function call via HermesPromptBuilder first
        val functionCall = HermesPromptBuilder.parseFunctionCall(response)
        if (functionCall != null) {
            val intentType = HermesPromptBuilder.refineIntent(functionCall)
            if (intentType != IntentType.UNKNOWN) {
                val resolved = resolveIntentFromType(intentType, originalInput)
                return resolved.copy(
                    confidence = (resolved.confidence * 0.85f).coerceIn(0.4f, 0.85f)
                )
            }
        }

        // Fallback: try to parse raw JSON with "intent" field (legacy format)
        val toolCallPattern = Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        val match = toolCallPattern.find(response)
        val jsonStr = match?.groupValues?.get(1)
            ?: response.trim().let { raw ->
                if (raw.startsWith("{") && raw.contains("\"intent\"")) raw else null
            }
            ?: return null

        return try {
            val jsonObj = JsonParser.parseString(jsonStr).asJsonObject
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
            "scan_receipt" -> IntentType.SCAN_RECEIPT
            "business_health_dashboard" -> IntentType.VIEW_DASHBOARD
            "quick_sale" -> IntentType.QUICK_SALE
            "chama_manager" -> IntentType.CHAMA_MANAGE
            "credit_readiness" -> IntentType.CREDIT_CHECK
            "loan_comparison" -> IntentType.LOAN_COMPARE
            "insurance_matcher" -> IntentType.INSURANCE_MATCH
            "ride_share" -> IntentType.RIDE_SHARE
            "market_price_broadcaster" -> IntentType.MARKET_PRICE
            "proof_of_income" -> IntentType.PROOF_OF_INCOME
            "goal_tracker" -> IntentType.GOAL_TRACK
            "whatsapp_reporter" -> IntentType.WHATSAPP_REPORT
            "receipt_scanner" -> IntentType.SCAN_RECEIPT
            "cfo_engine" -> IntentType.DAILY_REPORT
            "pricing_advisor" -> IntentType.ASK_ADVICE
            "debt_tracker" -> IntentType.ASK_DEBTORS
            "inventory_tracker" -> IntentType.ASK_STOCK
            "profit_by_product" -> IntentType.ASK_PROFIT
            "mpesa_auto_logger" -> IntentType.RECORD_SALE
            "record_transaction" -> IntentType.RECORD_SALE
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
            IntentType.RECORD_PAYMENT -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("record_payment"),
                toolParams = mapOf("record_payment" to extractPaymentParams(input))
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
            IntentType.CHECK_CUSTOMER_DEBT -> UserIntent(
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
            IntentType.SCAN_RECEIPT -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("scan_receipt")
            )
            IntentType.VIEW_DASHBOARD -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("business_health_dashboard")
            )
            IntentType.QUICK_SALE -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("quick_sale"),
                toolParams = mapOf("quick_sale" to extractSaleParams(input))
            )
            IntentType.CHAMA_MANAGE -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("chama_manager")
            )
            IntentType.CREDIT_CHECK -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("credit_readiness")
            )
            IntentType.LOAN_COMPARE -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("loan_comparison")
            )
            IntentType.INSURANCE_MATCH -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("insurance_matcher")
            )
            IntentType.RIDE_SHARE -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("ride_share")
            )
            IntentType.MARKET_PRICE -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("market_price_broadcaster")
            )
            IntentType.PROOF_OF_INCOME -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("proof_of_income")
            )
            IntentType.GOAL_TRACK -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("goal_tracker")
            )
            IntentType.WHATSAPP_REPORT -> UserIntent(
                type = type, confidence = 0.7f,
                requiredTools = listOf("whatsapp_reporter")
            )
            else -> UserIntent(type = IntentType.UNKNOWN, confidence = 0.3f)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Pattern Matchers (Tier 1)
    // ════════════════════════════════════════════════════════════

    private fun matchesSalePattern(input: String): Boolean {
        val saleKeywords = listOf(
            "nimeuza", "niliuza", "uza", "sold", "sale", "nimemuuza",
            "nimepata", "customer", "mteja ame", "nilipatia",
            "i sold", "nimemuuzia", "nimemuuza", "record sale",
            "log sale", "enter sale"
        )
        return saleKeywords.any { input.contains(it) }
    }

    private fun matchesExpensePattern(input: String): Boolean {
        val expenseKeywords = listOf(
            "nimetumia", "nilitumia", "expense", "cost", "spent",
            "nilipia", "nimelipia", "gharama", "matumizi",
            "i spent", "i paid", "record expense", "log expense",
            "enter cost", "nimetoa pesa"
        )
        return expenseKeywords.any { input.contains(it) }
    }

    private fun matchesPurchasePattern(input: String): Boolean {
        val keywords = listOf(
            "nimenunua", "nilinunua", "bought", "purchased",
            "nimeweka", "nimetia", "nimeongeza stock",
            "i bought", "nilichukua", "record purchase", "log purchase"
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
            "nimemfanyia", "nimefanyia kazi", "record service", "log service"
        )
        return serviceKeywords.any { input.contains(it) }
    }

    private fun matchesPaymentPattern(input: String): Boolean {
        val keywords = listOf(
            "amepay", "amelipa", "payment received", "nimelipwa",
            "record payment", "log payment", "deni imepungua",
            "partial payment", "kulipa", "amefanya payment"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesStockCheckPattern(input: String): Boolean {
        val stockKeywords = listOf(
            "stock", "inventory", "bidhaa", "imebaki", "imepungua",
            "how much", "kiasi", "nina", "remaining", "baki",
            "ikoje", "vipi stock", "check stock", "stock levels"
        )
        return stockKeywords.any { input.contains(it) }
    }

    private fun matchesSalesQueryPattern(input: String): Boolean {
        val keywords = listOf(
            "nimepata ngapi", "sales today", "nimeuza ngapi",
            "how much today", "leo nimepata", "today sales",
            "mapato ya leo", "nimeuza leo", "total sales", "today's sales"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesProfitQueryPattern(input: String): Boolean {
        val keywords = listOf(
            "profit", "faida", "ni ngapi", "how much profit",
            "nimepata faida", "nilipata faida", "faida ya leo", "total profit"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesExpenseQueryPattern(input: String): Boolean {
        val keywords = listOf(
            "nilitumia ngapi", "expenses today", "matumizi ya leo",
            "how much did i spend", "gharama", "total expenses", "expense report"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesDebtorsPattern(input: String): Boolean {
        val keywords = listOf(
            "deni", "debt", "owes", "anadaiwa", "credit",
            "mteja anadaiwa", "walinidai", "wana deni",
            "who owes me", "outstanding debts", "receivables"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesCustomerDebtPattern(input: String): Boolean {
        val keywords = listOf(
            "customer debt", "deni ya mteja", "how much does", "owes me",
            "anadaiwa ngapi", "check debt", "mteja ana deni"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesReportPattern(input: String): Boolean {
        val keywords = listOf(
            "report", "ripoti", "summary", "muhtasari",
            "daily report", "weekly", "monthly", "jumla",
            "business summary", "how is business", "biashara ikoje"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesAdvicePattern(input: String): Boolean {
        val keywords = listOf(
            "ushauri", "advice", "suggest", "recommend",
            "nifanye nini", "what should", "how can i",
            "ninawezaje", "ni bora", "business advice", "pricing advice"
        )
        return keywords.any { input.contains(it) }
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

    private fun matchesFarewellPattern(input: String): Boolean {
        val keywords = listOf(
            "kwaheri", "bye", "goodbye", "see you", "tutaonana",
            "baadaye", "later", "good night", "usiku mwema"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesScanReceiptPattern(input: String): Boolean {
        val keywords = listOf(
            "scan receipt", "skan risiti", "picha ya risiti", "ona risiti",
            "tazama risiti", "scan picha", "receipt image", "ocr receipt",
            "scan receipt image", "chukua risiti", "piga risiti picha",
            "scan", "skan", "risiti"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesDashboardPattern(input: String): Boolean {
        val keywords = listOf(
            "dashboard", "muhtasari wa biashara", "afya ya biashara",
            "business health", "show dashboard", "nione dashboard",
            "overall status", "jinsi biashara inavyoenda",
            "onekana dashboard", "dashboard status"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesQuickSalePattern(input: String): Boolean {
        val keywords = listOf(
            "quick sale", "uza haraka", "haraka", "sale ya haraka",
            "fast sale", "quick record", "one tap sale", "uza upesi"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesChamaPattern(input: String): Boolean {
        val keywords = listOf(
            "chama", "mchango", "contribute", "group savings",
            "table banking", "harambee", "chama changu",
            "chama balance", "chama members"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesCreditCheckPattern(input: String): Boolean {
        val keywords = listOf(
            "credit score", "mkopo", "loan ready", "credit readiness",
            "nikopeshwe", "naweza pata mkopo", "niko tayari mkopo",
            "check credit", "credit report", "oweza mkopo"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesLoanComparePattern(input: String): Boolean {
        val keywords = listOf(
            "compare loans", "linganisha mikopo", "which loan", "mkopo gani",
            "loan comparison", "interest rate", "kiwango cha riba",
            "best loan", "mkopo bora", "compare lenders", "loan options"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesInsurancePattern(input: String): Boolean {
        val keywords = listOf(
            "insurance", "bima", "insurance match", "bima gani",
            "find insurance", "nipe bima", "insurance quote",
            "bima ya biashara", "business insurance", "cover"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesRideSharePattern(input: String): Boolean {
        val keywords = listOf(
            "ride", "pikipiki", "nduthi", "boda boda",
            "ride share", "panda pikipiki", "delivery",
            "send package", "tuma mzigo", "gari"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesMarketPricePattern(input: String): Boolean {
        val keywords = listOf(
            "market price", "bei ya soko", "soko", "bei ya soko leo",
            "current price", "bei ya sasa", "market rates",
            "bei za soko", "bei ya bidhaa", "bei gani leo", "bei za leo"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesProofOfIncomePattern(input: String): Boolean {
        val keywords = listOf(
            "proof of income", "uthibitisho wa mapato", "income statement",
            "mapato yangu", "show income", "income report",
            "proof ya mapato", "income certificate", "financial statement"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesGoalTrackPattern(input: String): Boolean {
        val keywords = listOf(
            "goal", "target", "lengo", "malengo",
            "track goal", "set target", "weka lengo",
            "my goals", "target yangu", "goal tracker"
        )
        return keywords.any { input.contains(it) }
    }

    private fun matchesWhatsAppReportPattern(input: String): Boolean {
        val keywords = listOf(
            "whatsapp", "tuma whatsapp", "report whatsapp",
            "whatsapp report", "send report whatsapp",
            "tuma ripoti whatsapp", "share on whatsapp"
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
            !listOf(
                "nimeuza", "niliuza", "nilipatia", "mteja", "ame", "kwa", "za", "nilimuuza",
                "nimemuuza", "nimemuuzia", "customer", "sold", "i", "for", "record", "sale", "log"
            ).contains(w.lowercase())
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

    private fun extractPaymentParams(input: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        currencyPattern.find(input)?.let {
            params["amount"] = it.groupValues[1].ifEmpty { it.groupValues[2] }
        }
        // Try to extract customer name (simple heuristic)
        val customerPatterns = listOf(
            Regex("""(?:mteja|customer)\s+(\w+)""", RegexOption.IGNORE_CASE),
            Regex("""(\w+)\s+(?:amelipa|amepay|has paid)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in customerPatterns) {
            pattern.find(input)?.let {
                params["customer"] = it.groupValues[1]
                return@let
            }
            if (params.containsKey("customer")) break
        }
        return params
    }

    private fun loadPatterns() {
        // Patterns will be loaded from assets at runtime
        // For now, use the hardcoded pattern matchers above
        Timber.d("Intent patterns initialized")
    }
}
