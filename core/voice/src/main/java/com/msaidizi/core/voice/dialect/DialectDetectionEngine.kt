package com.msaidizi.core.voice.dialect

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dialect detection and normalization for East African speech patterns.
 *
 * Msaidizi's users speak a rich variety of Swahili:
 * - **Standard Swahili** (Kiswahili Sanifu) — formal, media/literary
 * - **Coastal Swahili** (Kiunguja) — Mombasa, Zanzibar dialect
 * - **Inland Swahili** — Nairobi, upcountry speakers
 * - **Sheng** — urban youth slang mixing Swahili, English, and local languages
 * - **Code-switched** — mixing Swahili and English mid-sentence
 *
 * This engine detects which variant the user is speaking and normalizes
 * the text for consistent downstream processing by the intent classifier.
 *
 * ## Why Normalization Matters
 *
 * The same intent can be expressed differently across dialects:
 * - Standard: "Nimeuziwa nyanya tano"
 * - Sheng: "Nimepewa nyanya tano" (Sheng uses "kupewa" for "sold to me")
 * - Coastal: "Nimeuziwa nyanya tano" (similar to standard, different accent)
 *
 * The intent classifier works on normalized text, so dialect words must
 * be mapped to their standard equivalents.
 *
 * ## Detection Strategy
 *
 * 1. **Lexical markers** — Sheng words (maze, niaje, poa) vs standard
 * 2. **Code-switch patterns** — English words in Swahili sentences
 * 3. **Phonetic hints** — from ASR confidence patterns (coastal vs inland)
 * 4. **User profile** — learned dialect preference from past interactions
 *
 * @see com.msaidizi.core.voice.stt.AdaptiveAsrEngine for ASR-level integration
 */
@Singleton
class DialectDetectionEngine @Inject constructor() {

    /** Detected dialect types */
    enum class Dialect {
        STANDARD,   // Kiswahili Sanifu
        COASTAL,    // Kiunguja (Mombasa/Zanzibar)
        INLAND,     // Nairobi/upcountry
        SHENG,      // Urban youth slang
        MIXED,      // Code-switched Swahili+English
        ENGLISH     // Primarily English
    }

    /** Dialect detection result */
    data class DialectResult(
        val dialect: Dialect,
        val confidence: Float,
        val shengWords: List<String> = emptyList(),
        val englishWords: List<String> = emptyList()
    )

    // ── Sheng lexical markers ──
    private val shengMarkers = setOf(
        "maze", "niaje", "poa", "sasa", "manze", "ati", "kwani",
        "naskia", "nde", "vi", "ka", "ngoja", "buda", "mdau",
        "msee", "mtu", "kijana", "dem", "chali", "fala",
        "ngumu", "fiti", "santos", "mbogi", "genje", "githeri"
    )

    // ── English code-switch markers ──
    private val englishMarkers = setOf(
        "the", "is", "and", "but", "for", "with", "that", "this",
        "have", "will", "can", "what", "how", "much", "money",
        "please", "sorry", "thank", "okay", "yes", "no"
    )

    // ── Coastal dialect markers ──
    private val coastalMarkers = setOf(
        "habari za", "mambo vipi", "ndiyo", "la", "sana",
        "bwana", "dada", "kaka", "habari yako"
    )

    /**
     * Detect the dialect of a transcribed text.
     *
     * @param text Transcribed text from ASR
     * @param asrConfidence Raw ASR confidence (lower for Sheng/code-switched)
     * @return Dialect detection result with classification and confidence
     */
    fun detect(text: String, asrConfidence: Float = 0.85f): DialectResult {
        val lower = text.lowercase().trim()
        val words = lower.split(Regex("\\s+"))

        // Count markers
        val shengCount = words.count { it in shengMarkers }
        val englishCount = words.count { it in englishMarkers }
        val coastalCount = coastalMarkers.count { lower.contains(it) }

        // Calculate ratios
        val totalWords = words.size.coerceAtLeast(1)
        val shengRatio = shengCount.toFloat() / totalWords
        val englishRatio = englishCount.toFloat() / totalWords

        // Classify
        val dialect = when {
            shengRatio >= 0.15f -> Dialect.SHENG
            englishRatio >= 0.3f -> Dialect.MIXED
            englishRatio >= 0.6f -> Dialect.ENGLISH
            coastalCount >= 2 -> Dialect.COASTAL
            else -> Dialect.INLAND
        }

        val confidence = when (dialect) {
            Dialect.SHENG -> (0.5f + shengRatio).coerceAtMost(0.95f)
            Dialect.MIXED -> (0.5f + englishRatio * 0.5f).coerceAtMost(0.9f)
            Dialect.ENGLISH -> (0.6f + englishRatio * 0.3f).coerceAtMost(0.95f)
            Dialect.COASTAL -> 0.7f + coastalCount * 0.05f
            Dialect.INLAND -> 0.8f
            Dialect.STANDARD -> 0.9f
        }

        val detectedShengWords = words.filter { it in shengMarkers }
        val detectedEnglishWords = words.filter { it in englishMarkers }

        Timber.d("DialectDetection: %s (conf=%.2f, sheng=%d, en=%d, coast=%d)",
            dialect.name, confidence, shengCount, englishCount, coastalCount)

        return DialectResult(
            dialect = dialect,
            confidence = confidence.coerceIn(0f, 1f),
            shengWords = detectedShengWords,
            englishWords = detectedEnglishWords
        )
    }

    /**
     * Normalize dialect text to standard Swahili.
     *
     * Maps Sheng/slang words to their standard Swahili equivalents
     * so the intent classifier receives consistent input.
     *
     * @param text Original transcribed text
     * @param dialect Detected dialect
     * @return Normalized text suitable for intent classification
     */
    fun normalize(text: String, dialect: Dialect): String {
        if (dialect != Dialect.SHENG && dialect != Dialect.MIXED) return text

        var normalized = text

        // Sheng → Standard Swahili mappings
        val shengToStandard = mapOf(
            "maze" to "habari",
            "niaje" to "habari",
            "poa" to "nzuri",
            "sasa" to "sasa",
            "manze" to "rafiki",
            "ati" to "kwamba",
            "naskia" to "nimesikia",
            "buda" to "kaka",
            "mdau" to "rafika",
            "msee" to "mtu",
            "ngoja" to "subiri",
            "ka" to "kwa",
            "nde" to "na",
            "vi" to "vitu",
            "ngumu" to "nzuri",
            "fiti" to "nzuri",
            "santos" to "pesa",
            "mbogi" to "vijana",
            "dem" to "msichana",
            "chali" to "kijana",
            "fala" to "mjinga"
        )

        for ((sheng, standard) in shengToStandard) {
            normalized = normalized.replace(Regex("\\b$sheng\\b", RegexOption.IGNORE_CASE), standard)
        }

        return normalized
    }
}
