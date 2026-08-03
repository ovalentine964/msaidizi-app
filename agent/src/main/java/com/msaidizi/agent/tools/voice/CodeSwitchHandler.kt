package com.msaidizi.agent.tools.voice

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * CodeSwitchHandler — Handles Swahili-English code-switching in voice input.
 *
 * In Kenya, speakers commonly mix Swahili and English within a single sentence:
 *   "Nimeuza nyanya kilo 5, elfu moja for the tomatoes"
 *   "Customer amenilipia pesa ya stock niliyompatia"
 *
 * This handler:
 * 1. Segments mixed text into language-homogeneous chunks
 * 2. Normalizes code-switched text for downstream processing
 * 3. Extracts semantic meaning regardless of language mixing
 * 4. Maps business terms across languages (Swahili ↔ English)
 *
 * Fully on-device — no network calls.
 */
@Singleton
class CodeSwitchHandler @Inject constructor(
    private val languageDetector: LanguageDetector
) : Tool {

    override val name = "code_switch_handler"
    override val description = "Handle Swahili-English code-switching: segment, normalize, and translate mixed text"

    override val argsSchema = argSchema {
        enum("action", "Action to perform",
            listOf("segment", "normalize", "translate"), required = false)
        string("text", "Text to process")
        string("target_language", "Target language for translation (sw or en)", required = false)
    }

    // ── Bilingual business term mappings ─────────────────────

    /**
     * Common business/financial term translations for Kenya's informal economy.
     * Maps Swahili terms to English equivalents and vice versa.
     */
    private val businessTermMap = mapOf(
        // Swahili → English
        "faida" to "profit",
        "hasara" to "loss",
        "deni" to "debt",
        "mteja" to "customer",
        "bidhaa" to "product",
        "gharama" to "cost",
        "bei" to "price",
        "punguzo" to "discount",
        "malipo" to "payment",
        "pesa" to "money",
        "shilingi" to "shillings",
        "mkopo" to "loan",
        "riba" to "interest",
        "biashara" to "business",
        "duka" to "shop",
        "soko" to "market",
        "stock" to "stock",
        "hisa" to "shares",
        "akiba" to "savings",
        "bili" to "bill",
        "risiti" to "receipt",
        "kodi" to "rent",
        "mshahara" to "salary",
        "mapato" to "income",
        "matumizi" to "expenses",
        "mkurugenzi" to "director",
        "meneja" to "manager",
        "mfanyakazi" to "employee",
        "mwenyewe" to "owner",
        "usalama" to "security",
        "bima" to "insurance",
        // M-Pesa ecosystem terms
        "salio" to "balance",
        "muamala" to "transaction",
        "kutuma" to "send",
        "kupokea" to "receive",
        "kulipa" to "pay",
        "kutoa" to "withdraw",
        "kuweka" to "deposit",
        "kurejesha" to "reversal",
        "msimbo" to "confirmation_code",
        "nambari" to "number",
        "uthibitisho" to "confirmation",
        "fuliza" to "overdraft",
        "mshwari" to "savings_loan",
        "jumla" to "wholesale",
        "rejareja" to "retail",
        "msambazaji" to "supplier",
        "dhamana" to "collateral",
        "mtaji" to "working_capital",
        "mtiririko" to "cash_flow",
        "mauzo" to "sales",
        "manunuzi" to "purchases",
        "lengo" to "goal",
        "ripoti" to "report",
        // Numbers (Swahili)
        "moja" to "1",
        "mbili" to "2",
        "tatu" to "3",
        "nne" to "4",
        "tano" to "5",
        "sita" to "6",
        "saba" to "7",
        "nane" to "8",
        "tisa" to "9",
        "kumi" to "10",
        "mia" to "hundred",
        "elfu" to "thousand",
        "laki" to "hundred_thousand",
        "milioni" to "million"
    )

    /**
     * Sheng money terms — critical for Nairobi informal economy.
     * Maps Sheng slang → standard Swahili equivalent and numeric value.
     */
    private val shengMoneyTerms = mapOf(
        "thao" to ShengMoney("elfu moja", 1000),
        "kibaki" to ShengMoney("elfu moja", 1000),
        "ngiri" to ShengMoney("elfu moja", 1000),
        "soo" to ShengMoney("mia moja", 100),
        "jaboya" to ShengMoney("mia tano", 500),
        "finje" to ShengMoney("mia tano", 500),
        "ndovu" to ShengMoney("elfu kumi", 10000),
        "gunia" to ShengMoney("laki moja", 100000),
        "robo" to ShengMoney("mia mbili na hamsini", 250),
        "ka-quarter" to ShengMoney("mia tano na ishirini", 525),
        "kichele" to ShengMoney("pesa", -1), // general money, no fixed amount
        "doe" to ShengMoney("pesa", -1),
        "chingwa" to ShengMoney("pesa", -1),
        "munde" to ShengMoney("pesa", -1),
        "ngwizas" to ShengMoney("pesa nyingi", -1)
    )

    /** Sheng general vocabulary for classification */
    private val shengVocabulary = setOf(
        "sasa", "niaje", "mambo", "vipi", "poa", "sijui", "ata", "juu",
        "ndio", "sio", "manze", "bro", "dude", "fam",
        "mzing", "msee", "dem", "chali", "mresh",
        "mbogi", "genje", "kuhepa", "kublack",
        "maze", "aki", "eh", "eeh", "aai", "woiye",
        "ati", "bas", "sasa hivi",
        // Sheng verb constructions (ku- + English verb)
        "kuchill", "kuvibe", "kupiga", "kudinya", "kush",
        "kuoga", "kumess", "kucatch", "kudrop", "kupick",
        "kuorder", "kuload", "kupost",
        // Money terms
        "thao", "kibaki", "ngiri", "soo", "jaboya", "finje",
        "ndovu", "gunia", "robo", "kichele", "doe", "chingwa", "munde", "ngwizas"
    )

    /** Reverse mapping: English → Swahili */
    private val reverseTermMap = businessTermMap.entries
        .filter { it.value != "stock" } // Skip "stock" which maps to itself
        .associate { (k, v) -> v to k }

    /** Terms that are commonly used in both languages (code-switch anchors) */
    val sharedTerms = setOf(
        "stock", "customer", "business", "market", "payment", "receipt",
        "profit", "loss", "cost", "price", "loan", "savings",
        "bank", "mpesa", "mobile", "money", "cash",
        // M-Pesa terms used across languages
        "fuliza", "mshwari", "stk", "push", "till", "paybill",
        "float", "reversal", "balance"
    )

    /**
     * Intent-preserving business phrase patterns (Tier 3).
     * Maps common code-switched phrases to structured intents.
     */
    private val intentPatterns = listOf(
        IntentPattern(
            pattern = Regex("(?:nime|nimelipia|nimetuma|nimepata).*(?:stk|push|malipo).*([0-9]+)", RegexOption.IGNORE_CASE),
            intent = "payment_received",
            extractAmount = true
        ),
        IntentPattern(
            pattern = Regex("(?:customer|mteja).*(?:amenilipia|amelipa|ametuma).*([0-9]+)", RegexOption.IGNORE_CASE),
            intent = "payment_received",
            extractAmount = true
        ),
        IntentPattern(
            pattern = Regex("(?:nimeuza|niliuza|nimepiga).*(?:stock|bidhaa|genje)", RegexOption.IGNORE_CASE),
            intent = "sale",
            extractAmount = false
        ),
        IntentPattern(
            pattern = Regex("(?:nimenunua|nimeweka).*(?:stock|bidhaa|genje)", RegexOption.IGNORE_CASE),
            intent = "purchase",
            extractAmount = false
        ),
        IntentPattern(
            pattern = Regex("(?:stock|bidhaa).*(?:imepungua|zinaisha|imeisha)", RegexOption.IGNORE_CASE),
            intent = "restock_needed",
            extractAmount = false
        ),
        IntentPattern(
            pattern = Regex("(?:deni|chung|ana deni|anaowe).*([0-9]+)", RegexOption.IGNORE_CASE),
            intent = "debt_outstanding",
            extractAmount = true
        ),
        IntentPattern(
            pattern = Regex("(?:faida|profit|owinjo).*(?:ngapi|gani|leo)", RegexOption.IGNORE_CASE),
            intent = "query_profit",
            extractAmount = false
        ),
        IntentPattern(
            pattern = Regex("(?:ripoti|report).*(?:siku|wiki|mwezi|leo|daily|weekly)", RegexOption.IGNORE_CASE),
            intent = "request_report",
            extractAmount = false
        )
    )

    // ── Segmentation ─────────────────────────────────────────

    /**
     * Tier 1: Segment code-switched text into language-homogeneous chunks.
     *
     * Example:
     *   Input:  "Nimeuza nyanya kilo 5, elfu moja for the tomatoes"
     *   Output: [
     *     Segment("Nimeuza nyanya kilo 5, elfu moja", "sw", 0.85),
     *     Segment("for the tomatoes", "en", 0.9)
     *   ]
     */
    fun segment(text: String): List<LanguageSegment> {
        if (text.isBlank()) return emptyList()

        val words = text.split(Regex("(?<=[\\s,;.!?])")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val segments = mutableListOf<LanguageSegment>()
        val currentSegment = mutableListOf<String>()
        var currentLang: String? = null

        for (word in words) {
            val cleanWord = word.trim().lowercase().replace(Regex("[^a-z0-9-]"), "")
            if (cleanWord.isBlank()) {
                currentSegment.add(word)
                continue
            }

            val wordLang = classifyWord(cleanWord)

            if (currentLang == null) {
                currentLang = wordLang
                currentSegment.add(word)
            } else if (wordLang == currentLang || wordLang == "shared" || wordLang == "number") {
                currentSegment.add(word)
            } else {
                val segmentText = currentSegment.joinToString("").trim()
                if (segmentText.isNotBlank()) {
                    segments.add(LanguageSegment(
                        text = segmentText,
                        language = currentLang,
                        confidence = 0.8
                    ))
                }
                currentSegment.clear()
                currentSegment.add(word)
                currentLang = wordLang
            }
        }

        val segmentText = currentSegment.joinToString("").trim()
        if (segmentText.isNotBlank() && currentLang != null) {
            segments.add(LanguageSegment(
                text = segmentText,
                language = currentLang,
                confidence = 0.8
            ))
        }

        return mergeSmallSegments(segments)
    }

    /**
     * Tier 2: Phrase-level segmentation with intent grouping.
     * Groups consecutive same-language words into phrases and detects
     * language boundaries at the phrase level.
     */
    fun segmentPhrases(text: String): List<PhraseSegment> {
        val wordSegments = segment(text)
        val phrases = mutableListOf<PhraseSegment>()

        for (seg in wordSegments) {
            // Expand Sheng money terms inline
            val expandedText = expandShengMoney(seg.text)
            phrases.add(PhraseSegment(
                text = seg.text,
                expandedText = expandedText,
                language = seg.language,
                confidence = seg.confidence,
                containsShengMoney = hasShengMoney(seg.text)
            ))
        }

        return phrases
    }

    /**
     * Tier 3: Intent-preserving normalization.
     * Extracts business intent regardless of language mixing.
     * "Customer amenilipia ya stock" → {intent: payment_received, item: stock}
     */
    fun extractIntent(text: String): IntentExtraction {
        val normalized = normalizeText(text)
        val lower = normalized.lowercase()

        // Try intent patterns
        for (pattern in intentPatterns) {
            val match = pattern.pattern.find(lower)
            if (match != null) {
                val amount = if (pattern.extractAmount && match.groupValues.size > 1) {
                    parseCompoundNumber(match.groupValues[1]) ?: match.groupValues[1].toLongOrNull()
                } else null

                return IntentExtraction(
                    intent = pattern.intent,
                    amount = amount,
                    rawText = text,
                    normalizedText = normalized,
                    confidence = 0.85
                )
            }
        }

        // Fallback: keyword-based intent
        val intent = when {
            lower.containsAny("nimeuza", "niliuza", "nimepiga mauzo") -> "sale"
            lower.containsAny("nimenunua", "nimelipia stock") -> "purchase"
            lower.containsAny("deni", "chung", "ana deni") -> "debt"
            lower.containsAny("faida", "profit", "owinjo") -> "query_profit"
            lower.containsAny("hasara", "loss") -> "query_loss"
            lower.containsAny("ripoti", "report") -> "request_report"
            lower.containsAny("bei", "price", "ngapi") -> "query_price"
            lower.containsAny("salio", "balance") -> "query_balance"
            lower.containsAny("fuliza", "mshwari", "mkopo") -> "query_credit"
            else -> "general"
        }

        return IntentExtraction(
            intent = intent,
            amount = null,
            rawText = text,
            normalizedText = normalized,
            confidence = 0.6
        )
    }

    /**
     * Normalize code-switched text for downstream processing.
     * Converts to a canonical form (primarily Swahili with business terms standardized).
     */
    fun normalize(segments: List<LanguageSegment>): String {
        return segments.joinToString(" ") { segment ->
            when (segment.language) {
                "en" -> {
                    // If short English phrase in otherwise Swahili context,
                    // it might be a business term — keep as-is for NLU
                    segment.text.trim()
                }
                "sw" -> segment.text.trim()
                else -> segment.text.trim()
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Normalize full text (convenience method).
     */
    fun normalizeText(text: String): String {
        val segments = segment(text)
        return normalize(segments)
    }

    /**
     * Extract business-relevant terms from code-switched text.
     * Returns terms in both Swahili and English for maximum NLU coverage.
     */
    fun extractBusinessTerms(text: String): List<BusinessTerm> {
        val terms = mutableListOf<BusinessTerm>()
        val words = text.lowercase().split(Regex("[\\s,;.!?]+")).filter { it.isNotBlank() }

        for (word in words) {
            val clean = word.replace(Regex("[^a-z]"), "")
            if (clean.isBlank()) continue

            // Check Swahili → English mapping
            businessTermMap[clean]?.let { english ->
                terms.add(BusinessTerm(
                    original = clean,
                    swahili = clean,
                    english = english,
                    language = "sw"
                ))
            }

            // Check English → Swahili mapping
            reverseTermMap[clean]?.let { swahili ->
                terms.add(BusinessTerm(
                    original = clean,
                    swahili = swahili,
                    english = clean,
                    language = "en"
                ))
            }

            // Check shared terms
            if (clean in sharedTerms) {
                terms.add(BusinessTerm(
                    original = clean,
                    swahili = reverseTermMap[clean] ?: clean,
                    english = clean,
                    language = "shared"
                ))
            }
        }

        return terms.distinctBy { it.swahili }
    }

    /**
     * Translate a Swahili business phrase to English.
     */
    fun translateToEnglish(swahiliText: String): String {
        val words = swahiliText.lowercase().split(Regex("\\s+"))
        return words.joinToString(" ") { word ->
            businessTermMap[word] ?: word
        }
    }

    /**
     * Translate an English business phrase to Swahili.
     */
    fun translateToSwahili(englishText: String): String {
        val words = englishText.lowercase().split(Regex("\\s+"))
        return words.joinToString(" ") { word ->
            reverseTermMap[word] ?: word
        }
    }

    // ── Tool interface ───────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "segment"
        return when (action.lowercase()) {
            "segment" -> {
                val text = params["text"]
                    ?: return ToolResult.error(name, "Text required", "MISSING_TEXT")
                val segments = segment(text)
                val data = segments.map { mapOf("text" to it.text, "language" to it.language, "confidence" to it.confidence) }
                ToolResult.success(name, data = data, message = "Found ${segments.size} segment(s)")
            }
            "normalize" -> {
                val text = params["text"]
                    ?: return ToolResult.error(name, "Text required", "MISSING_TEXT")
                val normalized = normalizeText(text)
                ToolResult.success(name, data = mapOf("original" to text, "normalized" to normalized), message = normalized)
            }
            "extract_terms" -> {
                val text = params["text"]
                    ?: return ToolResult.error(name, "Text required", "MISSING_TEXT")
                val terms = extractBusinessTerms(text)
                val data = terms.map { mapOf("original" to it.original, "swahili" to it.swahili, "english" to it.english, "language" to it.language) }
                ToolResult.success(name, data = data, message = "Found ${terms.size} business term(s)")
            }
            "translate" -> {
                val text = params["text"]
                    ?: return ToolResult.error(name, "Text required", "MISSING_TEXT")
                val direction = params["direction"] ?: "sw_to_en"
                val translated = when (direction) {
                    "sw_to_en", "sw-en" -> translateToEnglish(text)
                    "en_to_sw", "en-sw" -> translateToSwahili(text)
                    else -> return ToolResult.error(name, "Invalid direction: $direction. Use sw_to_en or en_to_sw", "INVALID_DIRECTION")
                }
                ToolResult.success(name, data = mapOf("original" to text, "translated" to translated, "direction" to direction), message = translated)
            }
            "shared_terms" -> {
                ToolResult.success(name, data = sharedTerms.toList(), message = "${sharedTerms.size} shared terms")
            }
            else -> ToolResult.error(name, "Unknown action: $action. Valid: segment, normalize, extract_terms, translate, shared_terms", "INVALID_ACTION")
        }
    }

    // ── Internal helpers ─────────────────────────────────────

    /**
     * Classify a single word's language.
     * Returns: "sw", "en", "sheng", "shared", "number", or "unknown"
     */
    private fun classifyWord(word: String): String {
        // Numbers
        if (word.all { it.isDigit() }) return "number"

        // Sheng vocabulary (check first — Sheng overlaps with Swahili)
        if (word in shengVocabulary) return "sheng"
        if (word in shengMoneyTerms) return "sheng"

        // Swahili function words
        if (word in swahiliFunctionWords) return "sw"

        // Swahili content words
        if (word in swahiliContentWords) return "sw"

        // English words
        if (word in englishWords) return "en"

        // Shared business terms
        if (word in sharedTerms) return "shared"

        // Swahili number words
        if (word in swahiliNumberWords) return "sw"

        // Morphological analysis
        if (hasSwahiliPrefix(word) || hasSwahiliEnding(word)) return "sw"
        if (hasEnglishPattern(word)) return "en"

        // Sheng verb pattern: ku- + English verb
        if (word.startsWith("ku") && word.length > 4) {
            val englishVerb = word.substring(2)
            if (englishVerb in setOf("chill", "vibe", "dinya", "mess", "catch", "drop", "pick", "order", "load", "post", "black", "hepa")) {
                return "sheng"
            }
        }

        return "unknown"
    }

    /**
     * Parse compound Swahili numbers like "elfu tano mia tatu" → 5300.
     * Handles: elfu N, laki N, mia N, and combinations with "na".
     */
    fun parseCompoundNumber(text: String): Long? {
        val words = text.lowercase().trim().split(Regex("[\\s,]+"))
        if (words.isEmpty()) return null

        // Single digit word
        if (words.size == 1) {
            swahiliNumberWords[words[0]]?.let { return it.toLong() }
            words[0].toLongOrNull()?.let { return it }
            shengMoneyTerms[words[0]]?.value?.takeIf { it > 0 }?.let { return it.toLong() }
            return null
        }

        var total = 0L
        var current = 0L

        for (word in words) {
            when {
                word == "na" -> continue // conjunction, skip
                word == "milioni" -> { current = if (current == 0L) 1_000_000L else current * 1_000_000L; total += current; current = 0L }
                word == "laki" -> { current = if (current == 0L) 100_000L else current * 100_000L; total += current; current = 0L }
                word == "elfu" -> { current = if (current == 0L) 1_000L else current * 1_000L; total += current; current = 0L }
                word == "mia" -> { current = if (current == 0L) 100L else current * 100L; total += current; current = 0L }
                word in swahiliNumberWords -> { current = swahiliNumberWords[word]!!.toLong() }
                word.toLongOrNull() != null -> { current = word.toLong() }
                word in shengMoneyTerms -> {
                    shengMoneyTerms[word]?.value?.takeIf { it > 0 }?.let {
                        current = it.toLong()
                    }
                }
            }
        }

        total += current
        return if (total > 0) total else null
    }

    /**
     * Expand Sheng money terms in text to standard Swahili.
     * "thao tatu" → "elfu tatu" (3000)
     */
    fun expandShengMoney(text: String): String {
        var result = text.lowercase()
        for ((sheng, info) in shengMoneyTerms) {
            result = result.replace(sheng, info.standardSwahili)
        }
        return result
    }

    private fun hasShengMoney(text: String): Boolean {
        val lower = text.lowercase()
        return shengMoneyTerms.keys.any { lower.contains(it) }
    }

    /**
     * Merge very short segments (< 2 words) with adjacent segments.
     */
    private fun mergeSmallSegments(segments: List<LanguageSegment>): List<LanguageSegment> {
        if (segments.size <= 1) return segments

        val merged = mutableListOf<LanguageSegment>()
        var i = 0

        while (i < segments.size) {
            val current = segments[i]
            val wordCount = current.text.split(Regex("\\s+")).filter { it.isNotBlank() }.size

            if (wordCount < 2 && i + 1 < segments.size) {
                // Merge with next segment
                val next = segments[i + 1]
                merged.add(LanguageSegment(
                    text = "${current.text} ${next.text}",
                    language = next.language, // Use the longer segment's language
                    confidence = (current.confidence + next.confidence) / 2
                ))
                i += 2
            } else if (wordCount < 2 && merged.isNotEmpty()) {
                // Merge with previous segment
                val prev = merged.removeAt(merged.size - 1)
                merged.add(LanguageSegment(
                    text = "${prev.text} ${current.text}",
                    language = prev.language,
                    confidence = (prev.confidence + current.confidence) / 2
                ))
                i++
            } else {
                merged.add(current)
                i++
            }
        }

        return merged
    }

    // ── Word classification helpers ──────────────────────────

    private val swahiliFunctionWords = setOf(
        "na", "ya", "za", "wa", "la", "cha", "kwa", "katika",
        "ni", "si", "hu", "lakini", "au", "ama", "pia", "tena",
        "sasa", "bado", "tayari", "sana", "hii", "huyo", "yule",
        "mimi", "wewe", "yeye", "sisi", "nyinyi", "wao",
        "angu", "ako", "ake", "etu", "enu", "ao"
    )

    private val swahiliContentWords = setOf(
        "nimeuza", "nimenunua", "nimetumia", "nimepata", "nimefanya",
        "ninaomba", "naomba", "nataka", "fanya", "nenda", "rudi",
        "pata", "leta", "toa", "chukua", "weka",
        "faida", "hasara", "deni", "mteja", "bidhaa", "gharama",
        "bei", "punguzo", "malipo", "pesa", "shilingi",
        "elfu", "mia", "laki", "milioni", "mkopo", "riba",
        "biashara", "duka", "soko", "habari", "asante", "karibu",
        "tafadhali", "pole", "sawa", "nzuri", "mbaya", "kubwa", "ndogo",
        "maji", "chakula", "nyumba", "gari", "simu", "kazi",
        "mtu", "watu", "rafiki"
    )

    // swahiliNumberWords moved to companion-level for reuse

    private val englishWords = setOf(
        "the", "is", "was", "are", "were", "be", "have", "has", "had",
        "do", "does", "did", "will", "would", "shall", "should",
        "and", "but", "or", "not", "for", "with", "from", "to", "of",
        "in", "on", "at", "by", "this", "that", "it", "its",
        "i", "me", "my", "we", "you", "your", "he", "she", "they",
        "what", "which", "who", "when", "where", "why", "how",
        "all", "some", "more", "than", "very", "just", "because",
        "profit", "loss", "expense", "revenue", "customer", "product",
        "stock", "inventory", "report", "total", "balance", "payment",
        "receipt", "business", "market", "price", "cost", "discount",
        "loan", "debt", "credit", "savings", "today", "yesterday",
        "tomorrow", "morning", "afternoon", "evening", "much", "many",
        "also", "already", "enough", "give", "take", "make", "want",
        "need", "know", "think", "come", "go", "get", "see", "tell"
    )

    private fun hasSwahiliPrefix(word: String): Boolean {
        if (word.length < 5) return false
        val prefixes = listOf("mwa", "mwa", "ki", "vi", "ma", "wa", "ya", "za", "la", "cha")
        return prefixes.any { word.startsWith(it) }
    }

    private fun hasSwahiliEnding(word: String): Boolean {
        if (word.length < 4) return false
        val endings = listOf("ile", "ana", "ea", "ia", "wa", "sha", "nja", "nga")
        return endings.any { word.endsWith(it) }
    }

    private fun hasEnglishPattern(word: String): Boolean {
        if (word.length < 4) return false
        val patterns = listOf("tion", "sion", "ment", "ness", "able", "ible", "ight", "ough")
        return patterns.any { word.contains(it) }
    }
}

/**
 * A contiguous segment of text in a single language.
 */
data class LanguageSegment(
    /** The text of this segment */
    val text: String,
    /** Language code: "sw", "en", "sheng", "number", "shared" */
    val language: String,
    /** Confidence in the language classification (0.0–1.0) */
    val confidence: Double
)

/**
 * A business-relevant term found in code-switched text.
 */
data class BusinessTerm(
    /** The original word as found in the text */
    val original: String,
    /** Swahili equivalent */
    val swahili: String,
    /** English equivalent */
    val english: String,
    /** Language the original word was in */
    val language: String
)

/** Sheng money term info */
data class ShengMoney(
    val standardSwahili: String,
    val value: Int // -1 means general money, no fixed amount
)

/** Intent pattern for Tier 3 code-switching */
data class IntentPattern(
    val pattern: Regex,
    val intent: String,
    val extractAmount: Boolean
)

/** Tier 2 phrase segment */
data class PhraseSegment(
    val text: String,
    val expandedText: String,
    val language: String,
    val confidence: Double,
    val containsShengMoney: Boolean
)

/** Tier 3 intent extraction result */
data class IntentExtraction(
    val intent: String,
    val amount: Long?,
    val rawText: String,
    val normalizedText: String,
    val confidence: Double
)

// Helper extension
private fun String.containsAny(vararg terms: String): Boolean =
    terms.any { this.contains(it) }

private val swahiliNumberWords = mapOf(
    "moja" to 1, "mbili" to 2, "tatu" to 3, "nne" to 4, "tano" to 5,
    "sita" to 6, "saba" to 7, "nane" to 8, "tisa" to 9, "kumi" to 10
)
