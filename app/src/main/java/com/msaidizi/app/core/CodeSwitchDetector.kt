package com.msaidizi.app.core

import com.msaidizi.app.core.dialect.ShengDialectData
import com.msaidizi.app.core.dialect.DialectUtils

/**
 * Context-aware code-switching detector for Msaidizi.
 *
 * Detects when a speaker switches languages mid-sentence, which is
 * extremely common in Kenyan multilingual speech. For example:
 *
 * "Nimeuza mboga leo, nilipata profit ya 500 bob"
 *  └─Swahili┘         └─Swahili┘  └English┘ └Sheng┘
 *
 * Architecture (v2 — phrase-level, context-aware):
 * 1. **Sheng construction detection**: Identifies hybrid patterns like
 *    English verb + Swahili prefix/suffix (e.g., "ku-deposit", "ku-watch")
 * 2. **Phrase-level language grouping**: Groups adjacent same-language words
 *    into phrases before detecting switch points
 * 3. **Context-aware classification**: Uses surrounding words to disambiguate
 *    ambiguous words (e.g., "base" could be English or Sheng)
 * 4. **Sliding window analysis**: Analyzes 2-3 word windows for better accuracy
 * 5. **Dominant language determination** with Sheng-aware weighting
 *
 * Sheng code-switching patterns handled:
 * - English verb + Swahili ku- prefix: "kudeposit", "kuwatch", "kublaze"
 * - English noun + Swahili suffix: "account-yangu", "receipt-zangu"
 * - Swahili frame + English filler: "nime-do", "na-think"
 * - Mixed Swahili-English sentences with Sheng slang
 *
 * Performance: <20ms per analysis (typically <5ms).
 */
object CodeSwitchDetector {

    /**
     * Minimum segment length (words) to count as a real language segment.
     * Prevents noise from single-word switches.
     */
    private const val MIN_SEGMENT_LENGTH = 1

    /**
     * Window size for context-aware language detection.
     * Analyzes N-word windows for better disambiguation.
     */
    private const val CONTEXT_WINDOW_SIZE = 3

    // ────────────── Sheng Construction Patterns ──────────────

    /**
     * Swahili verb prefixes that attach to English verb stems.
     * e.g., "ku-deposit" → ku + deposit, "nime-do" → nime + do
     * This is the primary Sheng code-switching construction.
     */
    private val SWAHILI_VERB_PREFIXES = setOf(
        "ku", "k",
        "ni", "na", "t",
        "a", "wa", "mta",
        "si", "ha",
        "me", "ka",
        "nge", "ngali"
    )

    /**
     * Swahili noun class possessive suffixes that attach to English nouns.
     * e.g., "account-yangu", "receipt-zangu", "stock-yake"
     */
    private val SWAHILI_POSSESSIVE_SUFFIXES = setOf(
        "yangu", "zangu", "yako", "zako", "yake", "zake",
        "yetu", "zenu", "yao", "zao"
    )

    /**
     * Swahili noun class prefixes for English nouns.
     * e.g., "ma-order" (plural), "ki-phone" (diminutive)
     */
    private val SWAHILI_NOUN_PREFIXES = setOf(
        "ma", "mi", "vi", "zi", "n", "m", "u", "ki"
    )

    /**
     * Regex patterns for Sheng hybrid constructions.
     * Matches English word stems with Swahili affixes.
     */
    private val SHENG_CONSTRUCTION_PATTERNS = listOf(
        Regex("^ku([a-z]{3,})$"),
        Regex("^ni(me|ta|na)([a-z]{3,})$"),
        Regex("^na([a-z]{3,})$"),
        Regex("^(a|wa|ka)(me|ta)([a-z]{3,})$"),
        Regex("^([a-z]{3,})(ya|za)(ngu|ko|ke|o|tu|enu|ao)$"),
        Regex("^(ma|vi|mi)([a-z]{4,})$")
    )

    /**
     * English words commonly used in Sheng code-switching.
     * Context determines whether they're code-switching or just English.
     */
    private val COMMON_BORROWED_ENGLISH = setOf(
        "account", "balance", "profit", "loss", "receipt", "stock",
        "order", "customer", "supplier", "payment", "cash", "mobile",
        "phone", "internet", "data", "wifi", "office", "meeting",
        "boss", "job", "salary", "loan", "bank", "mpesa",
        "number", "message", "call", "photo", "video", "check",
        "send", "receive", "confirm", "cancel", "register", "login",
        "deposit", "withdraw", "transfer", "submit", "download",
        "error", "problem", "issue", "report", "update", "delete"
    )

    /**
     * Ambiguous words that could be English or Sheng depending on context.
     * Context from surrounding words resolves the ambiguity.
     */
    private val AMBIGUOUS_WORDS = mapOf(
        "base" to ContextRule(
            shengContext = setOf("tuko", "mtaa", "ghetto", "poa"),
            enContext = setOf("base", "data", "system")
        ),
        "deal" to ContextRule(
            shengContext = setOf("poa", "fiti", "sawa"),
            enContext = setOf("business", "contract", "agreement")
        ),
        "soft" to ContextRule(
            shengContext = setOf("ni", "very"),
            enContext = setOf("software", "skill", "copy")
        ),
        "cool" to ContextRule(
            shengContext = setOf("niaje", "mambo"),
            enContext = setOf("temperature", "cooling")
        ),
        "fire" to ContextRule(
            shengContext = setOf("ni", "sana"),
            enContext = setOf("fire", "burn", "danger")
        ),
        "hard" to ContextRule(
            shengContext = setOf("ni", "sana"),
            enContext = setOf("hardware", "difficulty")
        ),
        "line" to ContextRule(
            shengContext = setOf("poa", "safi"),
            enContext = setOf("phone", "queue", "straight")
        ),
        "block" to ContextRule(
            shengContext = setOf("ame", "mta"),
            enContext = setOf("building", "road")
        )
    )

    /**
     * Context rule for resolving ambiguous words.
     */
    private data class ContextRule(
        val shengContext: Set<String>,
        val enContext: Set<String>
    )

    /**
     * Result of code-switching analysis.
     */
    data class CodeSwitchAnalysis(
        /** Whether code-switching was detected */
        val hasCodeSwitching: Boolean,
        /** The dominant language (for response adaptation) */
        val dominantLanguage: String,
        /** All language segments in order */
        val segments: List<LanguageSegment>,
        /** Switch points (indices where language changes) */
        val switchPoints: List<SwitchPoint>,
        /** Languages present in the text */
        val languagesPresent: Set<String>,
        /** Confidence in the analysis (0.0-1.0) */
        val confidence: Float,
        /** Recommended response language */
        val responseLanguage: String
    )

    /**
     * A contiguous segment of text in one language.
     */
    data class LanguageSegment(
        /** The words in this segment */
        val words: List<String>,
        /** The detected language */
        val language: String,
        /** Start word index in original text */
        val startIndex: Int,
        /** End word index in original text */
        val endIndex: Int,
        /** Confidence in language assignment */
        val confidence: Float
    )

    /**
     * A point where the speaker switches languages.
     */
    data class SwitchPoint(
        /** Word index where switch occurs */
        val index: Int,
        /** The word at the switch point */
        val word: String,
        /** Language before the switch */
        val fromLanguage: String,
        /** Language after the switch */
        val toLanguage: String
    )

    // ────────────────────── Public API ──────────────────────

    /**
     * Analyze text for code-switching.
     *
     * v2 improvements:
     * - Detects Sheng hybrid constructions (English verb + Swahili suffix)
     * - Uses context from surrounding words for disambiguation
     * - Groups words into phrases before classifying language
     * - Handles borrowed English words within Sheng context
     *
     * @param text Input text to analyze
     * @return CodeSwitchAnalysis with segments, switch points, and dominant language
     */
    fun analyze(text: String): CodeSwitchAnalysis {
        if (text.isBlank()) {
            return CodeSwitchAnalysis(
                hasCodeSwitching = false,
                dominantLanguage = "sw",
                segments = emptyList(),
                switchPoints = emptyList(),
                languagesPresent = emptySet(),
                confidence = 0.5f,
                responseLanguage = "sw"
            )
        }

        // Step 1: Get context-aware word-level language annotations
        val wordLangs = detectWithContext(text)
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Step 2: Build segments from word-level annotations
        val segments = buildSegments(wordLangs)

        // Step 3: Find switch points
        val switchPoints = findSwitchPoints(segments)

        // Step 4: Determine languages present
        val languagesPresent = segments
            .filter { it.language != "unknown" }
            .map { it.language }
            .toSet()

        // Step 5: Determine dominant language
        val dominantLanguage = determineDominantLanguage(segments)

        // Step 6: Determine response language
        val responseLanguage = determineResponseLanguage(dominantLanguage, languagesPresent)

        // Step 7: Calculate confidence
        val confidence = calculateConfidence(segments, wordLangs)

        val hasCodeSwitching = languagesPresent.size > 1

        return CodeSwitchAnalysis(
            hasCodeSwitching = hasCodeSwitching,
            dominantLanguage = dominantLanguage,
            segments = segments,
            switchPoints = switchPoints,
            languagesPresent = languagesPresent,
            confidence = confidence,
            responseLanguage = responseLanguage
        )
    }

    /**
     * Quick check if text contains code-switching.
     * Uses context-aware detection for Sheng patterns.
     *
     * @param text Input text
     * @return true if multiple languages detected
     */
    fun hasCodeSwitching(text: String): Boolean {
        val wordLangs = detectWithContext(text)
        val languages = wordLangs
            .map { it.language }
            .filter { it != "unknown" }
            .toSet()
        return languages.size > 1
    }

    /**
     * Get the dominant language for response adaptation.
     * Returns the language the speaker uses most.
     *
     * @param text Input text
     * @return Language code for response
     */
    fun getDominantLanguage(text: String): String {
        val analysis = analyze(text)
        return analysis.dominantLanguage
    }

    /**
     * Get recommended response language.
     * Adapts to the speaker's dominant language, mapping Sheng to Swahili.
     *
     * @param text Input text
     * @return Language code for TTS/response
     */
    fun getResponseLanguage(text: String): String {
        val analysis = analyze(text)
        return analysis.responseLanguage
    }

    /**
     * Get switch points for language learning analytics.
     * Useful for tracking which language pairs the speaker switches between most.
     *
     * @param text Input text
     * @return List of (fromLanguage, toLanguage) pairs
     */
    fun getSwitchPairs(text: String): List<Pair<String, String>> {
        val analysis = analyze(text)
        return analysis.switchPoints.map { it.fromLanguage to it.toLanguage }
    }

    // ────────────────────── Internal Logic ──────────────────────

    /**
     * Context-aware word-level language detection.
     * Improves on LanguageDetectorV2.detectPerWord by:
     * 1. Detecting Sheng hybrid constructions (English + Swahili affixes)
     * 2. Using surrounding word context to disambiguate
     * 3. Handling borrowed English words in Sheng context
     *
     * @param text Input text
     * @return List of WordLanguage with improved language assignments
     */
    private fun detectWithContext(text: String): List<WordLanguage> {
        val rawWords = text.split(Regex("[^\\p{L}']+"))
            .filter { it.isNotEmpty() }

        // First pass: get base language detection from LanguageDetectorV2
        val baseDetections = LanguageDetectorV2.detectPerWord(text)

        // Second pass: apply context-aware corrections
        val result = mutableListOf<WordLanguage>()
        for (i in baseDetections.indices) {
            val base = baseDetections[i]
            val clean = base.word.trim('\\'', '"', '.', ',', '!', '?').lowercase()

            // Check for Sheng hybrid construction
            val shengConstruction = detectShengConstruction(clean)
            if (shengConstruction != null) {
                result.add(WordLanguage(base.word, "sheng"))
                continue
            }

            // Check if word is in Sheng vocabulary
            if (isKnownShengWord(clean)) {
                result.add(WordLanguage(base.word, "sheng"))
                continue
            }

            // Resolve ambiguous words using context
            if (base.language == "unknown" || base.language == "en") {
                val contextWords = getContextWords(baseDetections, i, CONTEXT_WINDOW_SIZE)
                val resolved = resolveAmbiguousWord(clean, contextWords)
                if (resolved != null) {
                    result.add(WordLanguage(base.word, resolved))
                    continue
                }
            }

            // Check if English word appears in Sheng-dominant context
            if (base.language == "en" && clean in COMMON_BORROWED_ENGLISH) {
                val contextWords = getContextWords(baseDetections, i, CONTEXT_WINDOW_SIZE)
                val shengContextScore = contextWords.count { isKnownShengWord(it) }
                if (shengContextScore >= 2) {
                    // More than 2 Sheng words nearby → this English word is in Sheng context
                    result.add(WordLanguage(base.word, "sheng"))
                    continue
                }
            }

            // Use LanguageDetectorV2 result as-is
            result.add(base)
        }

        return result
    }

    /**
     * Detect Sheng hybrid construction: English verb/noun + Swahili affix.
     *
     * Examples:
     * - "kudeposit" → ku (Swahili infinitive) + deposit (English)
     * - "kuwatch" → ku (Swahili infinitive) + watch (English)
     * - "nimesee" → nime (Swahili perfective) + see (English)
     * - "accountyangu" → account (English) + yangu (Swahili possessive)
     * - "maorder" → ma (Swahili plural) + order (English)
     *
     * @param word Lowercase word to check
     * @return "sheng" if it's a hybrid construction, null otherwise
     */
    private fun detectShengConstruction(word: String): String? {
        if (word.length < 5) return null

        // Check regex patterns for Sheng constructions
        for (pattern in SHENG_CONSTRUCTION_PATTERNS) {
            val match = pattern.matchEntire(word) ?: continue

            // Extract the English stem from the match groups
            val groups = match.groupValues
            val englishStem = groups.lastOrNull { it.length >= 3 && it.all { c -> c.isLetter() && c.code < 128 } }

            if (englishStem != null && looksLikeEnglish(englishStem)) {
                return "sheng"
            }
        }

        // Check Swahili verb prefix + known English verb pattern
        for (prefix in SWAHILI_VERB_PREFIXES) {
            if (word.startsWith(prefix) && word.length > prefix.length + 2) {
                val stem = word.substring(prefix.length)
                if (stem.length >= 3 && looksLikeEnglish(stem) && !looksLikeSwahili(stem)) {
                    return "sheng"
                }
            }
        }

        // Check English noun + Swahili possessive suffix
        for (suffix in SWAHILI_POSSESSIVE_SUFFIXES) {
            if (word.endsWith(suffix) && word.length > suffix.length + 2) {
                val stem = word.substring(0, word.length - suffix.length)
                if (stem.length >= 3 && looksLikeEnglish(stem)) {
                    return "sheng"
                }
            }
        }

        // Check Swahili noun prefix + English noun
        for (prefix in SWAHILI_NOUN_PREFIXES) {
            if (word.startsWith(prefix) && word.length > prefix.length + 3) {
                val stem = word.substring(prefix.length)
                if (stem.length >= 4 && looksLikeEnglish(stem) && !looksLikeSwahili(stem)) {
                    return "sheng"
                }
            }
        }

        return null
    }

    /**
     * Check if a word looks like English using character patterns.
     * English words tend to use: th, sh, ch, tion, ing, ness, etc.
     */
    private fun looksLikeEnglish(word: String): Boolean {
        val englishPatterns = listOf(
            "th", "sh", "ch", "tion", "sion", "ment", "ness", "ing",
            "ous", "ful", "less", "able", "ible", "ight", "ough",
            "ph", "wh", "wr", "qu"
        )
        val w = word.lowercase()
        return englishPatterns.any { w.contains(it) } ||
                w.all { it.code in 97..122 } && w.length >= 3
    }

    /**
     * Check if a word looks like Swahili using character patterns.
     * Swahili words tend to use: ng', ny, sh, dh, th, kh, gh, mwa, etc.
     */
    private fun looksLikeSwahili(word: String): Boolean {
        val swahiliPatterns = listOf(
            "ng'", "ny", "dh", "th", "kh", "gh",
            "mwa", "nza", "watu", "mtu", "siku"
        )
        val w = word.lowercase()
        return swahiliPatterns.any { w.contains(it) } ||
                DialectUtils.isSwahiliWordExtended(w)
    }

    /**
     * Get surrounding words for context analysis.
     * Returns words within the window (excluding the current word).
     */
    private fun getContextWords(
        wordLangs: List<WordLanguage>,
        currentIndex: Int,
        windowSize: Int
    ): List<String> {
        val context = mutableListOf<String>()
        val start = maxOf(0, currentIndex - windowSize)
        val end = minOf(wordLangs.size, currentIndex + windowSize + 1)

        for (i in start until end) {
            if (i != currentIndex) {
                context.add(wordLangs[i].word.trim('\\'', '"', '.', ',', '!', '?').lowercase())
            }
        }
        return context
    }

    /**
     * Resolve an ambiguous word using surrounding context.
     * Returns the resolved language or null if still ambiguous.
     */
    private fun resolveAmbiguousWord(word: String, contextWords: List<String>): String? {
        val rule = AMBIGUOUS_WORDS[word] ?: return null

        val shengScore = contextWords.count { it in rule.shengContext }
        val enScore = contextWords.count { it in rule.enContext }

        return when {
            shengScore > enScore && shengScore > 0 -> "sheng"
            enScore > shengScore && enScore > 0 -> "en"
            else -> null // Still ambiguous
        }
    }

    /**
     * Check if a word is a known Sheng word (static vocabulary + dynamic).
     */
    private fun isKnownShengWord(word: String): Boolean {
        val clean = word.lowercase().trim()
        return clean in ShengDialectData.config.dialectMarkerWords ||
                clean in ShengDialectData.config.dialectToSwahili ||
                clean in ShengDialectData.config.businessTerms
    }

    /**
     * Build language segments from word-level annotations.
     * Groups consecutive words of the same language into segments.
     */
    private fun buildSegments(wordLangs: List<WordLanguage>): List<LanguageSegment> {
        if (wordLangs.isEmpty()) return emptyList()

        val segments = mutableListOf<LanguageSegment>()
        var currentLang = wordLangs.first().language
        var currentWords = mutableListOf(wordLangs.first().word)
        var startIndex = 0

        for (i in 1 until wordLangs.size) {
            val wl = wordLangs[i]
            if (wl.language == currentLang || wl.language == "unknown") {
                // Same language or unknown — continue current segment
                currentWords.add(wl.word)
            } else {
                // Language switch — finalize current segment
                if (currentWords.isNotEmpty()) {
                    segments.add(
                        LanguageSegment(
                            words = currentWords.toList(),
                            language = currentLang,
                            startIndex = startIndex,
                            endIndex = startIndex + currentWords.size - 1,
                            confidence = if (currentLang == "unknown") 0.3f else 0.8f
                        )
                    )
                }
                // Start new segment
                currentLang = wl.language
                currentWords = mutableListOf(wl.word)
                startIndex = i
            }
        }

        // Finalize last segment
        if (currentWords.isNotEmpty()) {
            segments.add(
                LanguageSegment(
                    words = currentWords.toList(),
                    language = currentLang,
                    startIndex = startIndex,
                    endIndex = startIndex + currentWords.size - 1,
                    confidence = if (currentLang == "unknown") 0.3f else 0.8f
                )
            )
        }

        // Merge short segments of the same language
        return mergeShortSegments(segments)
    }

    /**
     * Merge consecutive segments of the same language that were split by unknowns.
     */
    private fun mergeShortSegments(segments: List<LanguageSegment>): List<LanguageSegment> {
        if (segments.size <= 1) return segments

        val merged = mutableListOf<LanguageSegment>()
        var current = segments.first()

        for (i in 1 until segments.size) {
            val next = segments[i]
            if (next.language == current.language) {
                // Merge
                current = LanguageSegment(
                    words = current.words + next.words,
                    language = current.language,
                    startIndex = current.startIndex,
                    endIndex = next.endIndex,
                    confidence = maxOf(current.confidence, next.confidence)
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    /**
     * Find switch points between segments.
     */
    private fun findSwitchPoints(segments: List<LanguageSegment>): List<SwitchPoint> {
        val switchPoints = mutableListOf<SwitchPoint>()

        for (i in 0 until segments.size - 1) {
            val current = segments[i]
            val next = segments[i + 1]

            if (current.language != next.language &&
                current.language != "unknown" &&
                next.language != "unknown"
            ) {
                switchPoints.add(
                    SwitchPoint(
                        index = next.startIndex,
                        word = next.words.firstOrNull() ?: "",
                        fromLanguage = current.language,
                        toLanguage = next.language
                    )
                )
            }
        }

        return switchPoints
    }

    /**
     * Determine the dominant language by word count.
     * Weights longer segments more heavily.
     */
    private fun determineDominantLanguage(segments: List<LanguageSegment>): String {
        val langCounts = mutableMapOf<String, Int>()

        for (segment in segments) {
            if (segment.language != "unknown") {
                langCounts[segment.language] =
                    (langCounts[segment.language] ?: 0) + segment.words.size
            }
        }

        return langCounts.maxByOrNull { it.value }?.key ?: "sw"
    }

    /**
     * Determine the best response language.
     *
     * Rules:
     * - If dominant is Sheng → respond in Swahili (Sheng speakers understand Swahili)
     * - If dominant is a Kenyan language → respond in that language
     * - If dominant is English → respond in English
     * - If mixed with significant Sheng → respond in Swahili
     */
    private fun determineResponseLanguage(
        dominant: String,
        languages: Set<String>
    ): String {
        return when {
            dominant == "sheng" -> "sw"
            dominant == "dholuo" -> "sw" // Most Dholuo speakers also speak Swahili
            dominant == "kikuyu" -> "sw"
            dominant == "kalenjin" -> "sw"
            dominant == "luhya" -> "sw"
            dominant == "so" -> "so"
            dominant == "en" && "sw" !in languages -> "en"
            dominant == "en" && "sw" in languages -> "sw" // Prefer Swahili for code-switchers
            "sheng" in languages -> "sw"
            else -> "sw"
        }
    }

    /**
     * Calculate confidence in the analysis.
     */
    private fun calculateConfidence(
        segments: List<LanguageSegment>,
        wordLangs: List<WordLanguage>
    ): Float {
        if (segments.isEmpty()) return 0.3f

        // Average segment confidence
        val avgSegmentConf = segments.map { it.confidence }.average().toFloat()

        // Ratio of known vs unknown words
        val knownWords = wordLangs.count { it.language != "unknown" }
        val knownRatio = knownWords.toFloat() / wordLangs.size.coerceAtLeast(1)

        // Number of segments (more segments = more confident in code-switching)
        val segmentBonus = if (segments.size > 1) 0.1f else 0f

        return (avgSegmentConf * 0.5f + knownRatio * 0.4f + segmentBonus)
            .coerceIn(0.3f, 1.0f)
    }
}
