package com.msaidizi.agent.tools.voice

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.msaidizi.agent.tools.core.*

/**
 * LanguageDetector — On-device language identification for Swahili, English, and Sheng.
 *
 * Uses a combination of:
 * - Character n-gram frequency analysis
 * - Common word/particle matching
 * - Morphological pattern recognition
 * - Code-switching detection (mixed Swahili-English)
 *
 * Fully on-device — no network calls.
 *
 * Supported languages:
 * - "sw" — Kiswahili (Standard Swahili)
 * - "en" — English
 * - "sheng" — Sheng (Kenyan urban slang, Swahili-English creole)
 * - "mixed" — Code-switched Swahili-English
 */
@Singleton
class LanguageDetector @Inject constructor() : Tool {

    override val name = "language_detector"
    override val description = "Detect language of text: Swahili, English, Sheng, or code-mixed"

    override val argsSchema = argSchema {
        string("text", "Text to detect language of")
    }

    // ── Swahili vocabulary ───────────────────────────────────

    /** High-frequency Swahili function words (particles, conjunctions, prepositions) */
    private val swahiliFunctionWords = setOf(
        "na", "ya", "za", "wa", "la", "cha", "kwa", "katika", "kati",
        "ni", "si", "hu", "ku", "pa", "mu", "mwa",
        "lakini", "au", "ama", "ila", "hata", "pia", "tena", "bali",
        "kwa sababu", "kwa hivyo", "kwa hiyo", "hivyo", "hiyo",
        "hii", "huyo", "yule", "hile", "zile", "wale",
        "sasa", "bado", "tayari", "tu", "sana", "zaidi", "sasa hivi",
        "hapana", "ndiyo", "hapo", "hapa", "humo", "huko",
        "mimi", "wewe", "yeye", "sisi", "nyinyi", "wao",
        "angu", "ako", "ake", "etu", "enu", "ao"
    )

    /** High-frequency Swahili content words (Bantu-origin) */
    private val swahiliContentWords = setOf(
        // Common verbs
        "nimeuza", "nimenunua", "nimetumia", "nimepata", "nimefanya",
        "ninaomba", "naomba", "nataka", "nitakaa", "nafanya",
        "ninataka", "ninaenda", "ninarudi", "ninakula", "ninakunywa",
        "nimesema", "nimeona", "nimesikia", "nimejua", "nimeona",
        "fanya", "nenda", "rudi", "kuja", "kaa", "simama",
        "pata", "leta", "toa", "chukua", "weka", "jenga",
        // Business/finance terms
        "faida", "hasara", "deni", "mteja", "bidhaa", "stock",
        "gharama", "bei", "punguzo", "malipo", "pesa", "shilingi",
        "elfu", "mia", "laki", "milioni", "mkopo", "riba",
        "biashara", "duka", "soko", "mji", "kijiji",
        // Common nouns
        "habari", "asante", "karibu", "tafadhali", "pole", "shukrani",
        "sawa", "nzuri", "mbaya", "kubwa", "ndogo", "refu", "fupi",
        "maji", "chakula", "nyumba", "gari", "simu", "kazi",
        "mtu", "watu", "mwanamke", "mwanamume", "mtoto", "watoto",
        "rafiki", "jirani", "mwalimu", "daktari"
    )

    /** Sheng (Kenyan urban slang) markers — expanded with 2025-2026 vocabulary */
    private val shengWords = setOf(
        // Greetings & discourse
        "sasa", "niaje", "mambo", "vipi", "poa", "sijui", "ata", "juu",
        "ndio", "sio", "si", "manze", "bro", "dude", "fam",
        "maze", "aki", "eh", "eeh", "aai", "woiye",
        "ati", "bas", "sasa hivi",
        // People & things
        "mzing", "msee", "dem", "chali", "mresh",
        "mbogi", "genje",
        // Verbs (Sheng + ku-English constructions)
        "kush", "kudinya", "kuchill", "kuvibe", "kupiga",
        "kuoga", "kumess", "kucatch", "kudrop", "kupick",
        "kuhepa", "kublack",
        // Money terms (critical for business)
        "thao", "kibaki", "ngiri", "soo", "jaboya", "finje",
        "ndovu", "gunia", "robo", "kichele", "doe", "chingwa",
        "munde", "ngwizas", "ka-quarter"
    )

    /** English high-frequency words */
    private val englishWords = setOf(
        "the", "is", "was", "are", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would",
        "shall", "should", "may", "might", "must", "can", "could",
        "and", "but", "or", "nor", "not", "so", "yet", "both",
        "for", "with", "from", "to", "of", "in", "on", "at", "by",
        "this", "that", "these", "those", "it", "its",
        "i", "me", "my", "mine", "we", "us", "our", "ours",
        "you", "your", "yours", "he", "him", "his", "she", "her",
        "they", "them", "their", "theirs",
        "what", "which", "who", "whom", "when", "where", "why", "how",
        "all", "each", "every", "some", "any", "few", "more", "most",
        "other", "another", "such", "no", "only", "own", "same",
        "than", "too", "very", "just", "because", "as", "until",
        "about", "between", "through", "during", "before", "after",
        "above", "below", "up", "down", "out", "off", "over", "under",
        // Business English common in Kenya
        "profit", "loss", "expense", "revenue", "customer", "product",
        "stock", "inventory", "report", "total", "balance", "payment",
        "receipt", "invoice", "business", "market", "price", "cost",
        "discount", "loan", "debt", "credit", "savings", "bank"
    )

    // ── Swahili morphological patterns (Bantu noun class prefixes) ──

    /** Common Swahili noun class prefixes */
    private val swahiliPrefixes = listOf(
        "mwa", "mwa", "ki", "vi", "ma", "pa", "ku", "mu",
        "wa", "ya", "za", "la", "cha", "vy", "mi", "zi",
        "nji", "ndi", "ush", "uch", "uf", "ut", "uk", "ul"
    )

    /** Swahili verb conjugation patterns */
    private val swahiliVerbPatterns = listOf(
        "ni-me-", "ni-na-", "ni-ta-", "ni-ki-", "ni-",
        "u-me-", "u-na-", "u-ta-", "u-ki-",
        "a-me-", "a-na-", "a-ta-", "a-ki-",
        "tu-me-", "tu-na-", "tu-ta-", "tu-ki-",
        "wa-me-", "wa-na-", "wa-ta-", "wa-ki-",
        "ka-", "li-", "ta-", "ki-", "hu-", "si-"
    )

    // ── Detection API ────────────────────────────────────────

    /**
     * Detect the language of input text.
     * Returns a [LanguageDetectionResult] with primary language, confidence,
     * and code-switching information.
     */
    fun detectLanguage(text: String): LanguageDetectionResult {
        if (text.isBlank()) {
            return LanguageDetectionResult(primary = "sw", confidence = 0.5, isCodeMixed = false)
        }

        val normalized = text.lowercase().trim()
        val words = normalized.split(Regex("[\\s,;.!?]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return LanguageDetectionResult(primary = "sw", confidence = 0.5, isCodeMixed = false)
        }

        // Score each language
        var swahiliScore = 0.0
        var englishScore = 0.0
        var shengScore = 0.0

        for (word in words) {
            val cleanWord = word.replace(Regex("[^a-z]"), "")
            if (cleanWord.isBlank()) continue

            // Swahili function words (high weight)
            if (cleanWord in swahiliFunctionWords) swahiliScore += 3.0

            // Swahili content words
            if (cleanWord in swahiliContentWords) swahiliScore += 2.0

            // English words
            if (cleanWord in englishWords) englishScore += 2.0

            // Sheng words
            if (cleanWord in shengWords) shengScore += 3.0

            // Swahili morphological patterns
            if (hasSwahiliMorphology(cleanWord)) swahiliScore += 1.5

            // Swahili character patterns (vowel-heavy, specific digraphs)
            if (hasSwahiliCharPattern(cleanWord)) swahiliScore += 0.5

            // English character patterns
            if (hasEnglishCharPattern(cleanWord)) englishScore += 0.5
        }

        // Boost scores based on n-gram analysis
        val ngramScores = analyzeNgrams(normalized)
        swahiliScore += ngramScores.first
        englishScore += ngramScores.second

        // Check for code-switching
        val totalWords = words.size.toDouble()
        val swahiliRatio = swahiliScore / (swahiliScore + englishScore + shengScore + 0.01)
        val englishRatio = englishScore / (swahiliScore + englishScore + shengScore + 0.01)
        val isCodeMixed = swahiliRatio > 0.2 && englishRatio > 0.2 &&
                (swahiliRatio < 0.8 && englishRatio < 0.8)

        // Determine primary language
        val primary = when {
            shengScore > swahiliScore && shengScore > englishScore -> "sheng"
            isCodeMixed -> "mixed"
            swahiliScore > englishScore -> "sw"
            englishScore > swahiliScore -> "en"
            else -> "sw" // Default to Swahili
        }

        val maxScore = maxOf(swahiliScore, englishScore, shengScore, 1.0)
        val confidence = when (primary) {
            "sw" -> (swahiliScore / maxScore).coerceIn(0.3, 1.0)
            "en" -> (englishScore / maxScore).coerceIn(0.3, 1.0)
            "sheng" -> (shengScore / maxScore).coerceIn(0.3, 1.0)
            "mixed" -> 0.6 // Moderate confidence for mixed
            else -> 0.5
        }

        Timber.d("Language detection: primary=%s, confidence=%.2f, codeMixed=%s (sw=%.1f, en=%.1f, sheng=%.1f)",
            primary, confidence, isCodeMixed, swahiliScore, englishScore, shengScore)

        return LanguageDetectionResult(
            primary = primary,
            confidence = confidence,
            isCodeMixed = isCodeMixed,
            swahiliScore = swahiliScore,
            englishScore = englishScore,
            shengScore = shengScore
        )
    }

    /**
     * Detect dialect variant: sw-KE-urban, sw-KE-urban-sheng, sw-TZ, etc.
     * Returns a dialect code from the taxonomy.
     */
    fun detectDialect(text: String): String {
        val result = detectLanguage(text)
        return when (result.primary) {
            "sheng" -> "sw-KE-urban-sheng"
            "mixed" -> {
                val ratio = result.swahiliScore / (result.swahiliScore + result.englishScore + 0.01)
                if (ratio > 0.6) "sw-KE-urban-mixed" else "sw-KE-urban-standard"
            }
            "sw" -> {
                // Distinguish Kenyan vs Tanzanian patterns
                val lower = text.lowercase()
                val hasNiliPast = lower.contains("nili") && !lower.contains("niliwa")
                val hasNimePast = lower.contains("nime")
                val hasEnglishInserts = result.englishScore > result.swahiliScore * 0.15
                when {
                    hasNimePast && hasEnglishInserts -> "sw-KE-urban"
                    hasNiliPast && !hasEnglishInserts -> "sw-TZ"
                    else -> "sw-KE-urban"
                }
            }
            "en" -> "en-KE"
            else -> "sw-KE-urban"
        }
    }

    /**
     * Quick check if text is Swahili.
     */
    fun isSwahili(text: String): Boolean = detectLanguage(text).primary == "sw"

    /**
     * Quick check if text is English.
     */
    fun isEnglish(text: String): Boolean = detectLanguage(text).primary == "en"

    /**
     * Quick check if text contains code-switching.
     */
    fun isCodeMixed(text: String): Boolean = detectLanguage(text).isCodeMixed

    // ── Tool interface ───────────────────────────────────────

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"] ?: "detect"
        return when (action.lowercase()) {
            "detect" -> {
                val text = params["text"]
                    ?: return ToolResult.error(name, "Text required for detection", "MISSING_TEXT")
                val result = detectLanguage(text)
                ToolResult.success(
                    name,
                    data = result.toMap(),
                    message = "Language: ${result.primary} (${(result.confidence * 100).toInt()}% confidence)"
                )
            }
            "batch" -> {
                val text = params["text"]
                    ?: return ToolResult.error(name, "Text required", "MISSING_TEXT")
                val sentences = text.split(Regex("[.!?]+")).filter { it.isNotBlank() }
                val results = sentences.map { sentence ->
                    val r = detectLanguage(sentence.trim())
                    mapOf("text" to sentence.trim(), "language" to r.primary, "confidence" to r.confidence)
                }
                ToolResult.success(name, data = results, message = "Analyzed ${results.size} sentences")
            }
            else -> ToolResult.error(name, "Unknown action: $action. Valid: detect, batch", "INVALID_ACTION")
        }
    }

    // ── Internal analysis ────────────────────────────────────

    /**
     * Check if a word matches Swahili morphological patterns (Bantu prefixes).
     */
    private fun hasSwahiliMorphology(word: String): Boolean {
        if (word.length < 4) return false

        // Check for common Swahili verb endings
        val verbEndings = listOf("a", "e", "i", "ile", "ana", "ea", "ia", "wa")
        val hasVerbEnding = verbEndings.any { word.endsWith(it) && word.length > 3 }

        // Check for noun class prefixes
        val hasPrefix = swahiliPrefixes.any { word.startsWith(it) && word.length > 4 }

        // Check for typical Swahili consonant clusters
        val swahiliClusters = listOf("ng", "nd", "nj", "mb", "nz", "ny", "th", "dh", "ch")
        val hasSwahiliCluster = swahiliClusters.any { word.contains(it) }

        return (hasVerbEnding && hasPrefix) || (hasSwahiliCluster && hasPrefix)
    }

    /**
     * Check for Swahili character patterns (high vowel ratio, specific digraphs).
     */
    private fun hasSwahiliCharPattern(word: String): Boolean {
        if (word.length < 3) return false

        val vowels = word.count { it in "aeiou" }
        val vowelRatio = vowels.toDouble() / word.length

        // Swahili tends to have higher vowel ratio (CV syllable structure)
        // and specific letter combinations
        val hasSwahiliDigraph = listOf("aa", "ee", "oo", "ia", "ua", "ea", "oa").any { word.contains(it) }

        return vowelRatio > 0.45 || hasSwahiliDigraph
    }

    /**
     * Check for English character patterns (consonant clusters, silent letters).
     */
    private fun hasEnglishCharPattern(word: String): Boolean {
        if (word.length < 3) return false

        // English common consonant clusters
        val englishClusters = listOf(
            "str", "spr", "scr", "spl", "thr", "shr",
            "ght", "tch", "dge", "nce", "nge", "nt",
            "ough", "tion", "sion", "ment", "ness", "able", "ible"
        )
        return englishClusters.any { word.contains(it) }
    }

    /**
     * Analyze character bigrams/trigrams for language-specific frequency patterns.
     * Returns (swahiliScore, englishScore) pair.
     */
    private fun analyzeNgrams(text: String): Pair<Double, Double> {
        var swScore = 0.0
        var enScore = 0.0

        // Swahili-frequent bigrams
        val swBigrams = setOf("ng", "ny", "wa", "ya", "za", "la", "ma", "ki", "vi", "ch", "sh", "th", "dh")
        // English-frequent bigrams
        val enBigrams = setOf("th", "he", "in", "er", "an", "re", "on", "at", "en", "nd", "ti", "es", "or")

        val cleanText = text.replace(Regex("[^a-z]"), "")
        for (i in 0 until cleanText.length - 1) {
            val bigram = cleanText.substring(i, i + 2)
            if (bigram in swBigrams) swScore += 0.3
            if (bigram in enBigrams) enScore += 0.3
        }

        // Swahili-frequent trigrams
        val swTrigrams = setOf("ali", "ili", "ulu", "ama", "eni", "ani", "ika", "eka", "sha", "nja")
        val enTrigrams = setOf("the", "and", "ing", "her", "hat", "his", "tha", "ere", "ate", "ent")

        for (i in 0 until cleanText.length - 2) {
            val trigram = cleanText.substring(i, i + 3)
            if (trigram in swTrigrams) swScore += 0.5
            if (trigram in enTrigrams) enScore += 0.5
        }

        return Pair(swScore, enScore)
    }
}

/**
 * Result of language detection analysis.
 */
data class LanguageDetectionResult(
    /** Primary detected language code: "sw", "en", "sheng", or "mixed" */
    val primary: String,
    /** Confidence score (0.0–1.0) */
    val confidence: Double,
    /** Whether the text contains code-switching between languages */
    val isCodeMixed: Boolean,
    /** Raw Swahili score */
    val swahiliScore: Double = 0.0,
    /** Raw English score */
    val englishScore: Double = 0.0,
    /** Raw Sheng score */
    val shengScore: Double = 0.0
) {
    fun toMap(): Map<String, Any> = mapOf(
        "language" to primary,
        "confidence" to confidence,
        "is_code_mixed" to isCodeMixed,
        "scores" to mapOf("sw" to swahiliScore, "en" to englishScore, "sheng" to shengScore)
    )
}
