package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Igbo dialect adapter for Southeastern Nigeria.
 *
 * Igbo is a Niger-Congo language spoken by ~45 million people
 * in southeastern Nigeria (Anambra, Enugu, Imo, Abia, Ebonyi states).
 *
 * Key features:
 * - Tonal language (2 tones: high, low)
 * - Syllable-timed with CV structure
 * - Rich market (ahịa) vocabulary and trade terminology
 * - Palm oil and yam economy vocabulary
 * - Proverb-heavy business communication ("Ilu")
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object IgboDialectAdapter {
    companion object {
        private val MARKERS = mapOf(
            "na" to Regex("\\bna\\b"),
            "bụ" to Regex("\\bbụ\\b"),
            "ga" to Regex("\\bga\\b"),
            "nke" to Regex("\\bnke\\b"),
            "ma" to Regex("\\bma\\b"),
            "ọ bụrụ" to Regex("\\bọ bụrụ\\b"),
            "n'ihi_na" to Regex("\\bn'ihi_na\\b"),
            "otú_ọ_nọ" to Regex("\\botú_ọ_nọ\\b"),
            "ịhụnanya" to Regex("\\bịhụnanya\\b"),
            "ndewo" to Regex("\\bndewo\\b"),
            "kedu" to Regex("\\bkedu\\b"),
            "daalụ" to Regex("\\bdaalụ\\b"),
            "biko" to Regex("\\bbiko\\b"),
            "ọ_dị_mma" to Regex("\\bọ_dị_mma\\b"),
            "ehee" to Regex("\\behee\\b"),
            "mba" to Regex("\\bmba\\b"),
            "mmadụ" to Regex("\\bmmadụ\\b"),
            "ndị" to Regex("\\bndị\\b"),
            "ụlọ" to Regex("\\bụlọ\\b"),
            "ahịa" to Regex("\\bahịa\\b"),
            "ego" to Regex("\\bego\\b"),
            "ọrụ" to Regex("\\bọrụ\\b"),
            "nri" to Regex("\\bnri\\b"),
            "mmiri" to Regex("\\bmmiri\\b"),
            "ọnwụ" to Regex("\\bọnwụ\\b"),
            "afọ" to Regex("\\bafọ\\b"),
            "ụbọchị" to Regex("\\bụbọchị\\b"),
            "oge" to Regex("\\boge\\b")
        )
        private val PRONUNCIATION_REGEXES = mapOf(
            "ọ" to Regex("\\bọ\\b", RegexOption.IGNORE_CASE),
            "ụ" to Regex("\\bụ\\b", RegexOption.IGNORE_CASE),
            "ị" to Regex("\\bị\\b", RegexOption.IGNORE_CASE),
            "ẹ" to Regex("\\bẹ\\b", RegexOption.IGNORE_CASE),
            "ụ" to Regex("\\bụ\\b", RegexOption.IGNORE_CASE),
            "m̄" to Regex("\\bm̄\\b", RegexOption.IGNORE_CASE),
            "n̄" to Regex("\\bn̄\\b", RegexOption.IGNORE_CASE)
        )
    }


    private const val TAG = "IgboDialect"

    // ────────────────────── Igbo Code-Switching Markers ──────────────────────

    private val igboMarkers = setOf(
        // Discourse markers
        "na",           // "is/and/in"
        "bụ",           // "is/are"
        "ga",           // "will"
        "nke",          // "that/which"
        "ma",           // "but/however"
        "ọ bụrụ",      // "if"
        "n'ihi_na",     // "because"
        "otú_ọ_nọ",    // "however"
        "ịhụnanya",    // "love"
        "ndewo",        // "hello"
        "kedu",         // "how are you"
        "daalụ",        // "thank you"
        "biko",         // "please"
        "ọ_dị_mma",    // "it's good/okay"
        "ehee",         // "yes"
        "mba",          // "no"

        // Common nouns
        "mmadụ",       // "person"
        "ndị",          // "people"
        "ụlọ",          // "house"
        "ahịa",         // "market"
        "ego",          // "money"
        "ọrụ",          // "work"
        "nri",          // "food"
        "mmiri",        // "water"
        "ọnwụ",         // "month"
        "afọ",          // "year"
        "ụbọchị",      // "day"
        "oge",          // "time"
    )

    // ────────────────────── Igbo Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Tone collapse from ASR
        "ọ" to "o",
        "ụ" to "u",
        "ị" to "i",
        "ẹ" to "e",
        "ụ" to "u",

        // Igbo nasal vowels
        "m̄" to "m",
        "n̄" to "n",
    )

    // ────────────────────── Igbo Business Vocabulary ──────────────────────

    private val igboBusinessTerms = mapOf(
        // ── Market & trade terms ──
        "ahịa" to "market",
        "onye_ahịa" to "trader/customer",
        "onye_ọrụ" to "worker",
        "ego" to "money",
        "ọrụ" to "work",
        "ụzọ" to "way/method",
        "ọgụ" to "competition",

        // ── Food & agricultural products ──
        "jị" to "yam",
        "akpụ" to "cocoyam",
        "akara" to "bean_cake",
        "ọha" to "oil_bean",
        "mkpụrụ_ọsịsị" to "palm_kernel",
        "mmà" to "oil/palm_oil",
        "ọsịsị" to "palm_tree",
        "azụ" to "fish",
        "anụ" to "meat",
        "edé" to "cocoyam",
        "ofe_owerri" to "owerri_soup",

        // ── Palm oil economy ──
        "mmà" to "palm_oil",
        "ọsịsị" to "palm_tree",
        "mkpụrụ" to "seed/fruit",
        "eji" to "oil_palm",

        // ── Textile & crafts ──
        "akwụkwọ" to "paper/cloth",
        "ugwu" to "title/honor",
        "ụkwụ" to "foot/measure",

        // ── Currency & measurements ──
        "naira" to "naira",
        "kobo" to "kobo",
        "debe" to "debe",
        "gunia" to "sack",

        // ── Market roles ──
        "nne_ahịa" to "market_mother",
        "nna_ahịa" to "market_father",
        "onye_nchịkwa" to "administrator",

        // ── Livestock ──
        "ehi" to "cattle",
        "ewu" to "goat",
        "atụrụ" to "sheep",
        "ọkụkọ" to "chicken",
    )

    // ────────────────────── Code-Switching Detection ──────────────────────

    fun detectCodeSwitching(text: String): CodeSwitchResult {
        val words = text.lowercase()
            .split(Regex("[^\\p{L}']+"))
            .filter { it.length > 1 }

        if (words.isEmpty()) {
            return CodeSwitchResult(
                hasCodeSwitching = false,
                primaryLanguage = "sw",
                dialectWords = emptyList(),
                swahiliWords = emptyList(),
                confidence = 0.5f
            )
        }

        val igboFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                igboMarkers.contains(clean) -> igboFound.add(clean)
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isIgboBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val igboRatio = igboFound.size.toFloat() / totalWords
        val hasCodeSwitching = igboFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (igboRatio > 0.4f) "ig" else "sw",
            dialectWords = igboFound,
            swahiliWords = swahiliFound,
            confidence = if (hasCodeSwitching) 0.8f else 0.6f
        )
    }

    // ────────────────────── Normalization ──────────────────────

    fun normalize(text: String): String {
        var normalized = text
        for ((key, regex) in PRONUNCIATION_REGEXES) {
            normalized = regex.replace(normalized, pronunciationVariations[key]!!)
        }
        return normalized
    }

    fun translateToStandard(term: String): String? {
        val lower = term.lowercase().trim()

        igboBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val igboToSwahili = mapOf(
            "ndewo" to "habari",
            "kedu" to "habari",
            "daalụ" to "asante",
            "biko" to "tafadhali",
            "ehee" to "ndiyo",
            "mba" to "hapana",
            "mmadụ" to "mtu",
            "ndị" to "watu",
            "ụlọ" to "nyumba",
            "ahịa" to "soko",
            "ego" to "pesa",
            "ọrụ" to "kazi",
            "nri" to "chakula",
            "mmiri" to "maji",
            "jị" to "viazi",
            "ehi" to "ng'ombe",
            "ewu" to "mbuzi",
            "ọkụkọ" to "kuku",
        )
        igboToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var igboScore = 0

        for (term in igboBusinessTerms.keys) {
            if (lower.contains(term)) igboScore += 2
        }
        for ((_, regex) in MARKERS) {
            if (regex.containsMatchIn(lower)) igboScore += 3
        }

        return if (igboScore > 5) DialectRegion.IGBO else DialectRegion.STANDARD
    }

    fun process(text: String): ProcessedResult {
        Timber.tag(TAG).d("Processing: '%s'", text)

        val codeSwitch = detectCodeSwitching(text)
        val normalized = normalize(text)
        val region = detectRegion(text)

        val translations = mutableMapOf<String, String>()
        val words = text.lowercase().split(Regex("[^\\p{L}']+"))
        for (word in words) {
            translateToStandard(word.trim())?.let { standard ->
                translations[word.trim()] = standard
            }
        }

        return ProcessedResult(
            originalText = text,
            normalizedText = normalized,
            codeSwitchResult = codeSwitch,
            dialectRegion = region,
            translations = translations,
            confidence = codeSwitch.confidence
        )
    }

    // ────────────────────── Helpers ──────────────────────


    private fun isIgboBusinessTerm(word: String): Boolean {
        return igboBusinessTerms.containsKey(word) ||
                igboBusinessTerms.values.any { it == word }
    }
}
