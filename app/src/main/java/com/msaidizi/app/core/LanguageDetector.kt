package com.msaidizi.app.core

/**
 * Code-based language detection for Msaidizi.
 *
 * Uses character n-gram analysis + keyword matching to detect language.
 * No ML model needed — pure regex/string operations for <1ms latency.
 *
 * Detects: Swahili (sw), Sheng (sheng), English (en), Code-mixed (mixed)
 *
 * Accuracy target: >95% for sentences, >85% for single words.
 * Latency: <1ms per detection.
 *
 * Algorithm:
 * 1. Tokenize input into words
 * 2. Score each word against language keyword sets
 * 3. Apply character pattern bonuses (bigrams, suffixes)
 * 4. Normalize scores and determine language
 * 5. For code-mixed text, detect per-word language
 *
 * Sheng detection:
 * Sheng is Nairobi street slang that mixes Swahili, English, and
 * original coinages. It evolves rapidly. This detector uses a curated
 * set of common Sheng markers that are relatively stable.
 */
object LanguageDetector {

    // ────────────────────── Keyword Sets ──────────────────────

    /**
     * Strong Swahili markers — appear in >80% of Swahili text.
     * These are high-confidence indicators of Swahili.
     */
    private val swahiliStrong = setOf(
        // Grammar words (very frequent)
        "na", "ya", "wa", "za", "kwa", "ni", "la", "cha",
        // Verb prefixes
        "nime", "sija", "tuta", "wata", "nenda", "kuja", "nina", "tuna",
        "ame", "wame", "mta", "sita", "hata", "huwa",
        // Common adverbs/conjunctions
        "sana", "pia", "lakini", "kama", "au", "hata", "bado",
        "tu", "ndio", "hapana", "siyo",
        // Time words
        "leo", "jana", "kesho", "sasa", "baada", "kabla", "wakati",
        "asubuhi", "mchana", "jioni", "usiku",
        // Demonstratives
        "hii", "hiyo", "ile", "hizi", "hizo", "zile",
        "huyu", "yule", "hawa", "wale",
        // Question words
        "nini", "gani", "wapi", "lini", "vipi", "kwanini",
        // Prepositions/locations
        "hapa", "pale", "kule", "ndani", "nje", "juu", "chini",
        "mbele", "nyuma", "kati",
        // Common verbs
        "nenda", "kuja", "fanya", "sema", "ona", "pata", "jua",
        "ona", "nywa", "la", "kaa", "simama", "kusoma",
        // Business terms
        "biashara", "bidhaa", "bei", "faida", "hasara", "deni",
        "malipo", "pesa", "shilingi", "maelfu"
    )

    /**
     * Strong English markers — high-confidence indicators of English.
     */
    private val englishStrong = setOf(
        // Grammar words
        "the", "and", "for", "with", "that", "this", "from", "have",
        "has", "had", "was", "were", "been", "being", "will", "would",
        "could", "should", "might", "shall", "can", "may",
        // Prepositions
        "about", "after", "before", "between", "through", "during",
        "until", "against", "among", "upon", "within",
        // Common verbs
        "sold", "bought", "paid", "spent", "received", "made", "gave",
        "took", "came", "went", "said", "told", "asked", "thought",
        // Question words
        "how", "what", "when", "where", "why", "which", "who",
        // Quantifiers
        "much", "many", "more", "most", "some", "any", "every", "each",
        // Business English
        "profit", "loss", "revenue", "expense", "income", "cash",
        "stock", "inventory", "order", "customer", "supplier",
        "payment", "receipt", "balance", "total", "amount"
    )

    /**
     * Sheng markers — Nairobi street slang.
     * These are relatively stable Sheng words.
     * Note: Sheng evolves rapidly, so this set needs periodic updates.
     */
    private val shengMarkers = setOf(
        // Greetings & general
        "sasa", "poa", "fiti", "aje", "niaje", "vipi", "mambo",
        "safi", "freshi", "morale", "namna",
        // People
        "boss", "mdau", "ndebe", "fala", "msee", "mguys",
        "mbogi", "genje", "chali", "dem", "msupa",
        // Money & business
        "chapaa", "ndege", "bao", "mbuzi", "ngwai", "kush",
        "guarana", "kae", "kee", "ngiri",
        // Actions
        "noma", "kiboko", "gora", "sure", "santuri",
        "kanyaga", "piga", "chapa", "toka", "ingia",
        // Descriptors
        "kali", "mbaya", "poa", "fiti", "safi", "tamu",
        "rahisi", "gora", "heavy", "soft",
        // Food & drink
        "chips", "kuku", "mchele", "ugali", "nyama",
        "maji", "chai", "kahawa",
        // Technology
        "simu", "pikipiki", "gari", "nduthi"
    )

    // ────────────────────── Character Patterns ──────────────────────

    /**
     * Swahili character patterns (bigrams/trigrams) that are common
     * in Swahili but rare in English.
     */
    private val swahiliCharPatterns = setOf(
        "ng'", "ng\u2019",  // ng' with apostrophe
        "ny", "sh", "dh", "th", "kh", "gh",
        "mwa", "nza", "ali", "eni", "ika", "uko", "ila",
        "kea", "oea", "ea", "ia", "ua",
        "watu", "mtu", "kitu", "siku", "nyumba"
    )

    /**
     * English character patterns common in English but rare in Swahili.
     */
    private val englishCharPatterns = setOf(
        "tion", "sion", "ment", "ness", "able", "ible",
        "ing", "ous", "ful", "less", "ally",
        "ight", "ough", "atch", "etch"
    )

    /**
     * Swahili suffixes that strongly indicate Swahili words.
     */
    private val swahiliSuffixes = setOf(
        "ni", "na", "wa", "ya", "za", "la", "cha",
        "eni", "ika", "eza", "ana", "ele", "iza"
    )

    /**
     * English suffixes that strongly indicate English words.
     */
    private val englishSuffixes = setOf(
        "ing", "tion", "ness", "ment", "able", "ible",
        "ful", "ous", "ive", "ent", "ant", "est"
    )

    // ────────────────────── Detection API ──────────────────────

    /**
     * Detect the primary language of input text.
     *
     * @param text Input text to analyze
     * @return Language code: "sw", "en", "sheng", or "mixed"
     */
    fun detect(text: String): String {
        val words = text.lowercase()
            .split(Regex("[^\\p{L}']+"))
            .filter { it.length > 1 }

        if (words.isEmpty()) return "sw"  // Default to Swahili

        var swScore = 0
        var enScore = 0
        var shScore = 0

        for (word in words) {
            val cleanWord = word.trim('\'', '"', '.', ',', '!', '?')

            when {
                // Strong keyword matches (3 points each)
                cleanWord in swahiliStrong -> swScore += 3
                cleanWord in englishStrong -> enScore += 3
                cleanWord in shengMarkers -> shScore += 3

                // Character pattern matches (1 point each)
                swahiliCharPatterns.any { cleanWord.contains(it) } -> swScore += 1
                englishCharPatterns.any { cleanWord.contains(it) } -> enScore += 1

                // Suffix analysis (1 point)
                swahiliSuffixes.any { cleanWord.endsWith(it) } -> swScore += 1
                englishSuffixes.any { cleanWord.endsWith(it) } -> enScore += 1

                // Length-based heuristic
                cleanWord.length <= 3 && cleanWord.matches(Regex("[aeiou]+[bcdfghjklmnpqrstvwxyz]")) -> swScore += 1
            }
        }

        // Normalize scores
        val total = (swScore + enScore + shScore).coerceAtLeast(1)
        val swPct = swScore.toFloat() / total
        val enPct = enScore.toFloat() / total
        val shPct = shScore.toFloat() / total

        return when {
            // Sheng detection: significant Sheng markers present
            shPct > 0.3f && shScore >= 2 -> "sheng"
            // Strong Swahili
            swPct > 0.6f -> "sw"
            // Strong English
            enPct > 0.6f -> "en"
            // Mixed: both Swahili and English present
            swPct > 0.3f && enPct > 0.3f -> "mixed"
            // Default based on highest score
            swScore >= enScore -> "sw"
            else -> "en"
        }
    }

    /**
     * Detect language with confidence score.
     *
     * @param text Input text
     * @return LanguageResult with language code and confidence
     */
    fun detectWithConfidence(text: String): LanguageResult {
        val words = text.lowercase()
            .split(Regex("[^\\p{L}']+"))
            .filter { it.length > 1 }

        if (words.isEmpty()) return LanguageResult("sw", 0.5f, emptyMap())

        var swScore = 0
        var enScore = 0
        var shScore = 0
        val wordScores = mutableMapOf<String, Triple<Int, Int, Int>>()  // word → (sw, en, sh)

        for (word in words) {
            val cleanWord = word.trim('\'', '"', '.', ',', '!', '?')
            var wSw = 0; var wEn = 0; var wSh = 0

            when {
                cleanWord in swahiliStrong -> wSw = 3
                cleanWord in englishStrong -> wEn = 3
                cleanWord in shengMarkers -> wSh = 3
                swahiliCharPatterns.any { cleanWord.contains(it) } -> wSw = 1
                englishCharPatterns.any { cleanWord.contains(it) } -> wEn = 1
                swahiliSuffixes.any { cleanWord.endsWith(it) } -> wSw = 1
                englishSuffixes.any { cleanWord.endsWith(it) } -> wEn = 1
            }

            swScore += wSw; enScore += wEn; shScore += wSh
            wordScores[cleanWord] = Triple(wSw, wEn, wSh)
        }

        val total = (swScore + enScore + shScore).coerceAtLeast(1)
        val lang = detect(text)

        val confidence = when (lang) {
            "sw" -> swScore.toFloat() / total
            "en" -> enScore.toFloat() / total
            "sheng" -> shScore.toFloat() / total
            "mixed" -> maxOf(swScore, enScore).toFloat() / total
            else -> 0.5f
        }.coerceIn(0.3f, 1.0f)

        val scores = mapOf("sw" to swScore, "en" to enScore, "sheng" to shScore)
        return LanguageResult(lang, confidence, scores)
    }

    /**
     * Detect language at word level (for code-mixed text).
     * Returns list of (word, language) pairs.
     *
     * Useful for:
     * - Language-specific TTS (speak each word in its language)
     * - Code-mixing analysis
     * - Training data collection
     */
    fun detectPerWord(text: String): List<WordLanguage> {
        return text.split(Regex("[^\\p{L}']+"))
            .filter { it.isNotEmpty() }
            .map { word ->
                val cleanWord = word.trim('\'', '"', '.', ',', '!', '?').lowercase()
                val lang = when {
                    cleanWord in swahiliStrong -> "sw"
                    cleanWord in englishStrong -> "en"
                    cleanWord in shengMarkers -> "sheng"
                    swahiliCharPatterns.any { cleanWord.contains(it) } -> "sw"
                    englishCharPatterns.any { cleanWord.contains(it) } -> "en"
                    swahiliSuffixes.any { cleanWord.endsWith(it) } -> "sw"
                    englishSuffixes.any { cleanWord.endsWith(it) } -> "en"
                    else -> "unknown"
                }
                WordLanguage(word, lang)
            }
    }

    /**
     * Check if text is code-mixed (contains both Swahili and English).
     */
    fun isCodeMixed(text: String): Boolean {
        val wordLangs = detectPerWord(text)
        val languages = wordLangs.map { it.language }.filter { it != "unknown" }.toSet()
        return "sw" in languages && "en" in languages
    }

    /**
     * Get language distribution in text.
     * Returns percentage of each language.
     */
    fun getLanguageDistribution(text: String): Map<String, Float> {
        val wordLangs = detectPerWord(text)
        val total = wordLangs.size.coerceAtLeast(1)

        return wordLangs.groupBy { it.language }
            .mapValues { (_, words) -> words.size.toFloat() / total }
    }

    /**
     * Detect if text contains Sheng.
     * Sheng often mixes with Swahili, so we check for Sheng markers
     * even in predominantly Swahili text.
     */
    fun containsSheng(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^\\p{L}']+"))
        return words.any { it.trim() in shengMarkers }
    }

    /**
     * Get the most appropriate TTS language for a text.
     * For code-mixed text, returns the dominant language.
     */
    fun getTtsLanguage(text: String): String {
        val result = detectWithConfidence(text)
        return when (result.language) {
            "mixed" -> {
                // Return the language with higher score
                val scores = result.scores
                if ((scores["sw"] ?: 0) >= (scores["en"] ?: 0)) "sw" else "en"
            }
            "sheng" -> "sw"  // TTS Sheng with Swahili voice
            else -> result.language
        }
    }
}

// ────────────────────── Data Classes ──────────────────────

/**
 * Language detection result with confidence.
 */
data class LanguageResult(
    val language: String,
    val confidence: Float,
    val scores: Map<String, Int>
)

/**
 * Word-level language annotation.
 */
data class WordLanguage(
    val word: String,
    val language: String  // "sw", "en", "sheng", "unknown"
)
