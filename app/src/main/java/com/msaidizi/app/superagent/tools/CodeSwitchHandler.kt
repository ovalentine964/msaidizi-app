package com.msaidizi.app.superagent.tools

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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
        "stock" to "stock", // Already English, used in Swahili context
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
        "kodi" to "tax",
        "usalama" to "security",
        "bima" to "insurance",
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

    /** Reverse mapping: English → Swahili */
    private val reverseTermMap = businessTermMap.entries
        .filter { it.value != "stock" } // Skip "stock" which maps to itself
        .associate { (k, v) -> v to k }

    /** Terms that are commonly used in both languages (code-switch anchors) */
    val sharedTerms = setOf(
        "stock", "customer", "business", "market", "payment", "receipt",
        "profit", "loss", "cost", "price", "loan", "savings",
        "bank", "mpesa", "mobile", "money", "cash"
    )

    // ── Segmentation ─────────────────────────────────────────

    /**
     * Segment code-switched text into language-homogeneous chunks.
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
            val cleanWord = word.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
            if (cleanWord.isBlank()) {
                currentSegment.add(word) // Keep punctuation/spaces
                continue
            }

            val wordLang = classifyWord(cleanWord)

            if (currentLang == null) {
                // First word — start segment
                currentLang = wordLang
                currentSegment.add(word)
            } else if (wordLang == currentLang || wordLang == "shared" || wordLang == "number") {
                // Same language or shared term — extend segment
                currentSegment.add(word)
            } else {
                // Language switch — finalize current segment
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

        // Finalize last segment
        val segmentText = currentSegment.joinToString("").trim()
        if (segmentText.isNotBlank() && currentLang != null) {
            segments.add(LanguageSegment(
                text = segmentText,
                language = currentLang,
                confidence = 0.8
            ))
        }

        // Merge very short segments with neighbors
        return mergeSmallSegments(segments)
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
     * Returns: "sw", "en", "shared", "number", or "unknown"
     */
    private fun classifyWord(word: String): String {
        // Numbers
        if (word.all { it.isDigit() }) return "number"

        // Swahili function words
        if (word in languageDetector.let {
            // Use LanguageDetector's word lists indirectly
            // For direct classification, check our own lists
            swahiliFunctionWords
        }) return "sw"

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

        // Default: unknown (don't force a switch)
        return "unknown"
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

    private val swahiliNumberWords = setOf(
        "moja", "mbili", "tatu", "nne", "tano",
        "sita", "saba", "nane", "tisa", "kumi"
    )

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
