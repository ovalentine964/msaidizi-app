package com.msaidizi.app.core.dialect

/**
 * TODO(refactor): Migrate to data-driven [DialectAdapter] base class.
 *
 * This adapter follows the legacy pattern with inline data. To migrate:
 * 1. Create '${f%Adapter.kt}Data.kt' with [DialectConfig] containing all maps/sets
 * 2. Replace this file with: object ${f%.kt} : DialectAdapter(${f%Adapter.kt}Data.config)
 * 3. See [ShengDialectAdapter], [AmharicDialectAdapter], or [DholuoDialectAdapter] for examples.
 */

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Kikuyu dialect adapter for Central Kenya.
 *
 * Kikuyu (Gĩkũyũ) is a Bantu language spoken by ~8 million people
 * in Central Kenya (Nyeri, Murang'a, Kiambu, Kirinyaga counties).
 *
 * Key features:
 * - Heavy code-switching with Swahili and English in business contexts
 * - Kikuyu vocabulary for local products (ndũma, mūkimo, gĩtheri)
 * - Phonological transfer: prenasalized stops, vowel length distinctions
 * - Simplified Swahili consonant clusters in Kikuyu-dominant speech
 * - Distinctive greeting protocols with extended call-and-response
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object KikuyuDialectAdapter {
    private val MARKERS = mapOf(
        "na" to Regex("\\bna\\b"),
        "nĩ" to Regex("\\bnĩ\\b"),
        "no" to Regex("\\bno\\b"),
        "ũngĩ" to Regex("\\bũngĩ\\b"),
        "o" to Regex("\\bo\\b"),
        "nake" to Regex("\\bnake\\b"),
        "nayo" to Regex("\\bnayo\\b"),
        "nĩo" to Regex("\\bnĩo\\b"),
        "tuĩka" to Regex("\\btuĩka\\b"),
        "rĩ" to Regex("\\brĩ\\b"),
        "ngĩ" to Regex("\\bngĩ\\b"),
        "mũno" to Regex("\\bmũno\\b"),
        "hingo" to Regex("\\bhingo\\b"),
        "ũngĩ" to Regex("\\bũngĩ\\b"),
        "kana" to Regex("\\bkana\\b"),
        "tiga" to Regex("\\btiga\\b"),
        "ngũ" to Regex("\\bngũ\\b"),
        "ndũ" to Regex("\\bndũ\\b"),
        "nĩndũ" to Regex("\\bnĩndũ\\b"),
        "mũndũ" to Regex("\\bmũndũ\\b"),
        "andũ" to Regex("\\bandũ\\b"),
        "nyũmba" to Regex("\\bnyũmba\\b"),
        "mũtũũrĩ" to Regex("\\bmũtũũrĩ\\b"),
        "kĩhĩ" to Regex("\\bkĩhĩ\\b"),
        "ng'ombe" to Regex("\\bng'ombe\\b"),
        "mbũri" to Regex("\\bmbũri\\b"),
        "mũgũnda" to Regex("\\bmũgũnda\\b"),
        "mũtĩ" to Regex("\\bmũtĩ\\b"),
        "njeri" to Regex("\\bnjeri\\b"),
        "gĩka" to Regex("\\bgĩka\\b"),
        "mũthũri" to Regex("\\bmũthũri\\b"),
        "wĩra" to Regex("\\bwĩra\\b"),
        "mũthondeki" to Regex("\\bmũthondeki\\b"),
        "ndũma" to Regex("\\bndũma\\b"),
        "mūkimo" to Regex("\\bmūkimo\\b"),
        "gĩtheri" to Regex("\\bgĩtheri\\b"),
        "irio" to Regex("\\birio\\b"),
        "mũtũra" to Regex("\\bmũtũra\\b"),
        "njahĩ" to Regex("\\bnjahĩ\\b"),
        "kĩrĩma" to Regex("\\bkĩrĩma\\b"),
        "mũkorũ" to Regex("\\bmũkorũ\\b"),
        "njohi" to Regex("\\bnjohi\\b"),
        "thabiti" to Regex("\\bthabiti\\b")
    )
    private val PRONUNCIATION_REGEXES = mapOf(
        "aka" to Regex("\\baka\\b", RegexOption.IGNORE_CASE),
        "apata" to Regex("\\bapata\\b", RegexOption.IGNORE_CASE),
        "oka" to Regex("\\boka\\b", RegexOption.IGNORE_CASE),
        "iga" to Regex("\\biga\\b", RegexOption.IGNORE_CASE),
        "saafi" to Regex("\\bsaafi\\b", RegexOption.IGNORE_CASE),
        "twaa" to Regex("\\btwaa\\b", RegexOption.IGNORE_CASE),
        "zaa" to Regex("\\bzaa\\b", RegexOption.IGNORE_CASE),
        "lali" to Regex("\\blali\\b", RegexOption.IGNORE_CASE),
        "leta" to Regex("\\bleta\\b", RegexOption.IGNORE_CASE),
        "mboga" to Regex("\\bmboga\\b", RegexOption.IGNORE_CASE),
        "ng'ombe" to Regex("\\bng'ombe\\b", RegexOption.IGNORE_CASE),
        "samahani" to Regex("\\bsamahani\\b", RegexOption.IGNORE_CASE),
        "sawa" to Regex("\\bsawa\\b", RegexOption.IGNORE_CASE)
    )


    private const val TAG = "KikuyuDialect"

    // ────────────────────── Kikuyu Code-Switching Markers ──────────────────────

    private val kikuyuMarkers = setOf(
        // Discourse markers & conjunctions
        "na",           // "and/with" (also Swahili, but tonal difference)
        "nĩ",           // "is/are" — copula
        "no",           // "it is" — emphatic copula
        "ũngĩ",         // "another/other"
        "o",            // "or"
        "nake",         // "and he/she"
        "nayo",         // "and it"
        "nĩo",          // "it is that"
        "tuĩka",        // "become"
        "rĩ",           // "when/that"
        "ngĩ",         // "when/while"
        "mũno",        // "very"
        "hingo",        // "sometimes"
        "ũngĩ",        // "else/other"
        "kana",         // "or/whether"
        "tiga",         // "stop/leave"
        "ngũ",         // "I will"
        "ndũ",         // "I not"
        "nĩndũ",       // "because"

        // Common nouns in code-switching
        "mũndũ",       // "person"
        "andũ",        // "people"
        "nyũmba",      // "home/family"
        "mũtũũrĩ",    // "neighbor"
        "kĩhĩ",       // "thing"
        "ng'ombe",     // "cow"
        "mbũri",       // "goat"
        "mũgũnda",    // "farm/garden"
        "mũtĩ",       // "tree"
        "njeri",       // "market day"

        // Business/market terms
        "gĩka",        // "market"
        "mũthũri",    // "trader"
        "wĩra",        // "work"
        "mũthondeki", // "seller"
        "ndũma",       // "yam/arrowroot"
        "mūkimo",      // "mashed dish"
        "gĩtheri",     // "bean mixture"
        "irio",        // "food/dishes"
        "mũtũra",     // "stomach contents (tripe)"
        "njahĩ",       // "caterpillar delicacy"
        "kĩrĩma",     // "sweet potato"
        "mũkorũ",     // "traditional beer"
        "njohi",       // "traditional beer"
        "thabiti",     // "reliable/firm"
    )

    // ────────────────────── Kikuyu Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Kikuyu speakers often simplify Swahili consonant clusters
        "aka" to "taka",              // want
        "apata" to "tapata",          // find
        "oka" to "toka",              // come from
        "iga" to "piga",              // do/hit

        // Vowel lengthening from Kikuyu prosody
        "saafi" to "safi",            // clean
        "twaa" to "twa",              // take
        "zaa" to "za",                // give birth

        // Kikuyu "r" and "l" variation
        "lali" to "rali",             // late
        "leta" to "leta",             // bring (stable)

        // Nasal assimilation
        "mboga" to "mboga",           // vegetable (stable)
        "ng'ombe" to "ng'ombe",       // cow (stable)

        // Kikuyu-specific Swahili adaptations
        "samahani" to "samahani",     // sorry (stable)
        "sawa" to "sawa",             // okay (stable)
    )

    // ────────────────────── Kikuyu Business Vocabulary ──────────────────────

    private val kikuyuBusinessTerms = mapOf(
        // ── Local foods & products ──
        "ndũma" to "arrowroot",
        "mūkimo" to "mashed_green_maize",
        "gĩtheri" to "bean_mixture",
        "irio" to "traditional_dishes",
        "njahĩ" to "caterpillar_delicacy",
        "kĩrĩma" to "sweet_potato",
        "mũkorũ" to "traditional_beer",
        "njohi" to "traditional_beer",
        "mũtũra" to "tripe",
        "kĩama" to "council_hall",
        "mũthondeki" to "seller",
        "mũthũri" to "trader",

        // ── Local measurements ──
        "debe" to "debe",
        "gunia" to "sack",
        "fundo" to "bundle",
        "mfuko" to "bag",
        "kibaba" to "small_measure",
        "ratili" to "pound",

        // ── Currency expressions ──
        "mbao" to "twenty_shillings",
        "jeuri" to "fifty_shillings",
        "kibabu" to "fifty_shillings",
        "ngiri" to "thousand_shillings",
        "thao" to "thousand",
        "finje" to "five_hundred_shillings",

        // ── Market terms ──
        "soko" to "market",
        "duka" to "shop",
        "kibanda" to "stall",
        "mama_mboga" to "vegetable_seller",
        "boda_boda" to "motorcycle_taxi",
        "mkokoteni" to "hand_cart",

        // ── Livestock ──
        "ng'ombe" to "cattle",
        "mbũri" to "goat",
        "kondoo" to "sheep",
        "kuku" to "chicken",
        "mũgũnda" to "farm",
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

        val kikuyuFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                kikuyuMarkers.any { clean.contains(it) } -> kikuyuFound.add(clean)
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isKikuyuBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val hasCodeSwitching = kikuyuFound.size >= 1 && swahiliFound.size >= 1
        val totalWords = words.size.coerceAtLeast(1)
        val kikuyuRatio = kikuyuFound.size.toFloat() / totalWords

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (kikuyuRatio > 0.4f) "ki" else "sw",
            dialectWords = kikuyuFound,
            swahiliWords = swahiliFound,
            confidence = if (hasCodeSwitching) 0.8f else 0.6f
        )
    }

    // ────────────────────── Normalization ──────────────────────

    fun normalize(text: String): String {
        var normalized = text
        for ((key, regex) in PRONUNCIATION_REGEXES) {
            normalized = regex.replace(normalized, pronunciationVariations[key] ?: key)
        }
        return normalized
    }

    fun translateToStandard(term: String): String? {
        val lower = term.lowercase().trim()

        kikuyuBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val kikuyuToSwahili = mapOf(
            "nĩ" to "ni",
            "mũndũ" to "mtu",
            "andũ" to "watu",
            "nyũmba" to "nyumba",
            "mũgũnda" to "shamba",
            "wĩra" to "kazi",
            "mũtĩ" to "mti",
            "gĩka" to "soko",
            "ndũma" to "kiazi",
            "irio" to "chakula",
            "njohi" to "pombe",
            "tiga" to "acha",
            "mũno" to "sana",
            "rĩ" to "wakati",
        )
        kikuyuToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var kikuyuScore = 0

        for (term in kikuyuBusinessTerms.keys) {
            if (lower.contains(term)) kikuyuScore += 2
        }
        for ((_, regex) in MARKERS) {
            if (regex.containsMatchIn(lower)) kikuyuScore += 3
        }

        return if (kikuyuScore > 5) DialectRegion.KIKUYU else DialectRegion.STANDARD
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


    private fun isKikuyuBusinessTerm(word: String): Boolean {
        return kikuyuBusinessTerms.containsKey(word) ||
                kikuyuBusinessTerms.values.any { it == word }
    }
}
