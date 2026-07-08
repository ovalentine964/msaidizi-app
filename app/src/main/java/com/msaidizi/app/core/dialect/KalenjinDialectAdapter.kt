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
 * Kalenjin dialect adapter for Rift Valley Kenya.
 *
 * Kalenjin is a Nilotic language cluster spoken by ~5 million people
 * in the Rift Valley (Nandi, Baringo, Uasin Gishu, Elgeyo-Marakwet counties).
 *
 * Key features:
 * - Highland agricultural vocabulary (maize, wheat, tea, dairy)
 * - Nilotic phonology with ejectives and voiceless nasals
 * - Code-switching with Swahili and English
 * - Strong pastoral and farming economy vocabulary
 * - Distinctive age-set and clan terminology
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object KalenjinDialectAdapter : IDialectAdapter {
    override val asrLanguageHint: String = "sw"  // Kalenjin → use Swahili ASR (no Kalenjin Whisper model)
    override val ttsLanguage: String = "sw"
    private val MARKERS = mapOf(
        "amit" to Regex("\\bamit\\b"),
        "mamit" to Regex("\\bmamit\\b"),
        "kogo" to Regex("\\bkogo\\b"),
        "kogoich" to Regex("\\bkogoich\\b"),
        "koitoich" to Regex("\\bkoitoich\\b"),
        "mising" to Regex("\\bmising\\b"),
        "chamgei" to Regex("\\bchamgei\\b"),
        "chengo" to Regex("\\bchengo\\b"),
        "kainet" to Regex("\\bkainet\\b"),
        "kongoi" to Regex("\\bkongoi\\b"),
        "murio" to Regex("\\bmurio\\b"),
        "ende" to Regex("\\bende\\b"),
        "amuno" to Regex("\\bamuno\\b"),
        "kipto" to Regex("\\bkipto\\b"),
        "kipsigis" to Regex("\\bkipsigis\\b"),
        "tugen" to Regex("\\btugen\\b"),
        "nandi" to Regex("\\bnandi\\b"),
        "kapkoros" to Regex("\\bkapkoros\\b"),
        "kapchumba" to Regex("\\bkapchumba\\b"),
        "kaptich" to Regex("\\bkaptich\\b"),
        "kapsirwet" to Regex("\\bkapsirwet\\b"),
        "murenik" to Regex("\\bmurenik\\b"),
        "tuiyotich" to Regex("\\btuiyotich\\b"),
        "mursik" to Regex("\\bmursik\\b"),
        "kimiet" to Regex("\\bkimiet\\b"),
        "kabotet" to Regex("\\bkabotet\\b"),
        "ng'atuny" to Regex("\\bng'atuny\\b"),
        "moit" to Regex("\\bmoit\\b")
    )
    private val PRONUNCIATION_REGEXES = mapOf(
        "aka" to Regex("\\baka\\b", RegexOption.IGNORE_CASE),
        "oka" to Regex("\\boka\\b", RegexOption.IGNORE_CASE),
        "iga" to Regex("\\biga\\b", RegexOption.IGNORE_CASE),
        "saafi" to Regex("\\bsaafi\\b", RegexOption.IGNORE_CASE),
        "twaa" to Regex("\\btwaa\\b", RegexOption.IGNORE_CASE)
    )


    private const val TAG = "KalenjinDialect"

    // ────────────────────── Kalenjin Code-Switching Markers ──────────────────────

    private val kalenjinMarkers = setOf(
        // Discourse markers
        "amit",         // "yes"
        "mamit",        // "no"
        "kogo",         // "grandmother/elder woman"
        "kogoich",      // "greetings (to elder woman)"
        "koitoich",     // "greetings (to age-mates)"
        "mising",       // "peace"
        "chamgei",      // "greeting"
        "chengo",       // "greeting response"
        "kainet",       // "thank you"
        "kongoi",       // "thank you"
        "murio",        // "please"
        "ende",         // "what"
        "amuno",        // "also"
        "kipto",        // "person"

        // Common nouns
        "kipsigis",     // "Kalenjin sub-group"
        "tugen",        // "Kalenjin sub-group"
        "nandi",        // "Kalenjin sub-group"
        "kapkoros",     // "bush/shrine"
        "kapchumba",    // "house/home"
        "kaptich",      // "girl"
        "kapsirwet",    // "boy"
        "murenik",      // "warrior"
        "tuiyotich",    // "elders"

        // Agricultural terms
        "mursik",       // "fermented milk"
        "kimiet",       // "traditional beer"
        "kabotet",      // "traditional brew"
        "ng'atuny",     // "cow"
        "moit",         // "field"
    )

    // ────────────────────── Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Kalenjin speakers simplify Swahili clusters
        "aka" to "taka",
        "oka" to "toka",
        "iga" to "piga",

        // Vowel patterns from Nilotic prosody
        "saafi" to "safi",
        "twaa" to "twa",
    )

    // ────────────────────── Business Vocabulary ──────────────────────

    private val kalenjinBusinessTerms = mapOf(
        // ── Local foods & products ──
        "mursik" to "fermented_milk",
        "kimiet" to "traditional_beer",
        "kabotet" to "traditional_brew",
        "kobong'et" to "traditional_greens",
        "mursik_ka_mursik" to "sour_milk",
        "kapkolel" to "traditional_soup",

        // ── Agricultural products ──
        "moit" to "farm/field",
        "ng'atuny" to "cattle",
        "sigei" to "goat",
        "ruret" to "sheep",
        "kokoich" to "chicken",

        // ── Measurements ──
        "debe" to "debe",
        "gunia" to "sack",
        "fundo" to "bundle",
        "mfuko" to "bag",

        // ── Currency ──
        "mbao" to "twenty_shillings",
        "jeuri" to "fifty_shillings",
        "ngiri" to "thousand_shillings",
        "thao" to "thousand",

        // ── Market terms ──
        "soko" to "market",
        "duka" to "shop",
        "kibanda" to "stall",
        "boda_boda" to "motorcycle_taxi",

        // ── Dairy & livestock ──
        "mursik" to "fermented_milk",
        "ng'atuny" to "cattle",
        "sigei" to "goat",
    )

    // ────────────────────── Code-Switching Detection ──────────────────────

    override fun detectCodeSwitching(text: String): CodeSwitchResult {
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

        val kalenjinFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                kalenjinMarkers.contains(clean) -> kalenjinFound.add(clean)
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isKalenjinBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val kalenjinRatio = kalenjinFound.size.toFloat() / totalWords
        val hasCodeSwitching = kalenjinFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (kalenjinRatio > 0.4f) "kln" else "sw",
            dialectWords = kalenjinFound,
            swahiliWords = swahiliFound,
            confidence = if (hasCodeSwitching) 0.8f else 0.6f
        )
    }

    // ────────────────────── Normalization ──────────────────────

    override fun normalize(text: String): String {
        var normalized = text
        for ((key, regex) in PRONUNCIATION_REGEXES) {
            normalized = regex.replace(normalized, pronunciationVariations[key] ?: key)
        }
        return normalized
    }

    override fun translateToStandard(term: String): String? {
        val lower = term.lowercase().trim()

        kalenjinBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val kalenjinToSwahili = mapOf(
            "amit" to "ndiyo",
            "mamit" to "hapana",
            "mising" to "amani",
            "kainet" to "asante",
            "kongoi" to "asante",
            "murio" to "tafadhali",
            "ende" to "nini",
            "kipto" to "mtu",
            "moit" to "shamba",
            "mursik" to "maziwa",
            "ng'atuny" to "ng'ombe",
        )
        kalenjinToSwahili[lower]?.let { return it }

        return null
    }

    override fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var kalenjinScore = 0

        for (term in kalenjinBusinessTerms.keys) {
            if (lower.contains(term)) kalenjinScore += 2
        }
        for ((_, regex) in MARKERS) {
            if (regex.containsMatchIn(lower)) kalenjinScore += 3
        }

        return if (kalenjinScore > 5) DialectRegion.KALENJIN else DialectRegion.STANDARD
    }

    override fun process(text: String): ProcessedResult {
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


    private fun isKalenjinBusinessTerm(word: String): Boolean {
        return kalenjinBusinessTerms.containsKey(word) ||
                kalenjinBusinessTerms.values.any { it == word }
    }
}
