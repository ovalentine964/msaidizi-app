package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Dholuo dialect adapter for Western Kenya.
 *
 * Dholuo (Luo) is a Nilotic language spoken by ~6 million people
 * around Lake Victoria (Kisumu, Siaya, Homa Bay, Migori counties).
 *
 * Key features:
 * - 4 lexical tones (H, L, HL, LH) — collapsed by ASR
 * - 8 vowels with ATR distinction (±Advanced Tongue Root)
 * - Implosive consonants /ɓ/, /ɗ/ misrecognized by ASR
 * - Heavy code-switching with Swahili in urban/business contexts
 * - Fish trade vocabulary central to economy
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object DholuoDialectAdapter {
    companion object {
        private val MARKERS = mapOf(
            "kendo" to Regex("\\bkendo\\b"),
            "to" to Regex("\\bto\\b"),
            "ka" to Regex("\\bka\\b"),
            "mano" to Regex("\\bmano\\b"),
            "gi" to Regex("\\bgi\\b"),
            "nyiso" to Regex("\\bnyiso\\b"),
            "en" to Regex("\\ben\\b"),
            "ok" to Regex("\\bok\\b"),
            "kata" to Regex("\\bkata\\b"),
            "chon" to Regex("\\bchon\\b"),
            "nadi" to Regex("\\bnadi\\b"),
            "inyalo" to Regex("\\binyalo\\b"),
            "kia" to Regex("\\bkia\\b"),
            "ber" to Regex("\\bber\\b"),
            "maber" to Regex("\\bmaber\\b"),
            "malo" to Regex("\\bmalo\\b"),
            "yawuoyo" to Regex("\\byawuoyo\\b"),
            "amos" to Regex("\\bamos\\b"),
            "erokamano" to Regex("\\berokamano\\b"),
            "wang'" to Regex("\\bwang'\\b"),
            "neno" to Regex("\\bneno\\b"),
            "kwee" to Regex("\\bkwee\\b"),
            "dhok" to Regex("\\bdhok\\b"),
            "ogo" to Regex("\\bogo\\b"),
            "wuon" to Regex("\\bwuon\\b"),
            "min" to Regex("\\bmin\\b"),
            "ja" to Regex("\\bja\\b"),
            "juak" to Regex("\\bjuak\\b"),
            "tich" to Regex("\\btich\\b"),
            "paro" to Regex("\\bparo\\b"),
            "pod" to Regex("\\bpod\\b"),
            "piny" to Regex("\\bpiny\\b"),
            "piyo" to Regex("\\bpiyo\\b"),
            "rech" to Regex("\\brech\\b"),
            "uong'" to Regex("\\buong'\\b"),
            "rimo" to Regex("\\brimo\\b"),
            "are" to Regex("\\bare\\b"),
            "ng'ato" to Regex("\\bng'ato\\b"),
            "moko" to Regex("\\bmoko\\b"),
            "dala" to Regex("\\bdala\\b"),
            "ot" to Regex("\\bot\\b"),
            "thur" to Regex("\\bthur\\b"),
            "siru" to Regex("\\bsiru\\b"),
            "ng'wen" to Regex("\\bng'wen\\b")
        )
        private val PRONUNCIATION_REGEXES = mapOf(
            "samini" to Regex("\\bsamini\\b", RegexOption.IGNORE_CASE),
            "sababu" to Regex("\\bsababu\\b", RegexOption.IGNORE_CASE),
            "sulu" to Regex("\\bsulu\\b", RegexOption.IGNORE_CASE),
            "aka" to Regex("\\baka\\b", RegexOption.IGNORE_CASE),
            "oka" to Regex("\\boka\\b", RegexOption.IGNORE_CASE),
            "iga" to Regex("\\biga\\b", RegexOption.IGNORE_CASE),
            "osha" to Regex("\\bosha\\b", RegexOption.IGNORE_CASE),
            "naani" to Regex("\\bnaani\\b", RegexOption.IGNORE_CASE),
            "saafi" to Regex("\\bsaafi\\b", RegexOption.IGNORE_CASE),
            "twaa" to Regex("\\btwaa\\b", RegexOption.IGNORE_CASE),
            "b" to Regex("\\bb\\b", RegexOption.IGNORE_CASE),
            "d" to Regex("\\bd\\b", RegexOption.IGNORE_CASE)
        )
    }


    private const val TAG = "DholuoDialect"

    // ────────────────────── Dholuo Code-Switching Markers ──────────────────────

    private val dholuoMarkers = setOf(
        // Discourse markers (most frequent in code-switching)
        "kendo",        // "also/and"
        "to",           // "then/so"
        "ka",           // "if/when"
        "mano",         // "that/those"
        "gi",           // "with"
        "nyiso",        // "show/reveal"
        "en",           // "is/are" — copula
        "ok",           // "not" — negation
        "kata",         // "even/even if"
        "chon",         // "because"
        "nadi",         // "now"
        "inyalo",       // "you can"
        "kia",          // "can/able"
        "ber",          // "good"
        "maber",        // "good/well"
        "malo",         // "thank you"
        "yawuoyo",      // "understood"
        "amos",         // "please"
        "erokamano",    // "thank you" (formal)
        "wang'",        // "of/belonging to"
        "neno",         // "say/word"
        "kwee",         // "no"

        // Common nouns
        "dhok",         // "fish"
        "ogo",          // "home/village"
        "wuon",         // "owner/father"
        "min",          // "mother"
        "ja",           // "person of" (agentive)
        "juak",         // "trick/cleverness"
        "tich",         // "teacher"
        "paro",         // "year"
        "pod",          // "place"
        "piny",         // "earth/ground/world"
        "piyo",         // "groundnuts"
        "rech",         // "food"
        "uong'",        // "cooking"
        "rimo",         // "one"
        "are",          // "he/she/it"
        "ng'ato",       // "one person"
        "moko",         // "people"
        "dala",         // "homestead"
        "ot",           // "home/shop"
        "thur",         // "trouble"
        "siru",         // "loan"
        "ng'wen",       // "debt"
    )

    // ────────────────────── Dholuo Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // "th" → "s" (Dholuo speakers in Swahili context)
        "samini" to "thamini",
        "sababu" to "thababu",
        "sulu" to "thuru",

        // Simplified consonant clusters
        "aka" to "taka",
        "oka" to "toka",
        "iga" to "piga",
        "osha" to "tosha",

        // Vowel lengthening from Dholuo prosody
        "naani" to "nani",
        "saafi" to "safi",
        "twaa" to "twa",

        // Implosive → standard (ASR writes same character)
        "b" to "b",  // /ɓ/ → /b/
        "d" to "d",  // /ɗ/ → /d/
    )

    // ────────────────────── Dholuo Business Vocabulary ──────────────────────

    private val dholuoBusinessTerms = mapOf(
        // ── Fish & lake products ──
        "dhok" to "fish",
        "rech" to "food",
        "piyo" to "groundnuts",
        "kuon" to "ugali",
        "atapa" to "traditional_bread",
        "nyoyo" to "cowpeas",
        "odo" to "traditional_greens",
        "apoth" to "leafy_vegetable",
        "ng'wen" to "debt",
        "siru" to "loan",

        // ── Local measurements ──
        "debe" to "debe",
        "gunia" to "sack",
        "fundo" to "bundle",
        "mfuko" to "bag",
        "kibaba" to "small_measure",
        "ratili" to "pound",
        "kibuyu" to "calabash",
        "goro" to "tin_measure",

        // ── Currency expressions ──
        "mbao" to "twenty_shillings",
        "jeuri" to "fifty_shillings",
        "kibabu" to "fifty_shillings",
        "ngiri" to "thousand_shillings",
        "thao" to "thousand",
        "finje" to "five_hundred_shillings",

        // ── Market terms ──
        "chuthi" to "market_day",
        "soko" to "market",
        "duka" to "shop",
        "kibanda" to "stall",
        "boda_boda" to "motorcycle_taxi",
        "mkokoteni" to "hand_cart",
        "ja_kuma" to "fish_trader",
        "ja_rech" to "food_vendor",
        "wuon_duka" to "shop_owner",
        "min_mboga" to "vegetable_mama",

        // ── Livestock ──
        "ng'ombe" to "cattle",
        "mbuzi" to "goat",
        "kondoo" to "sheep",
        "kuku" to "chicken",
        "ng'ina" to "hen",
        "dier" to "bull",
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

        val dholuoFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                dholuoMarkers.contains(clean) -> dholuoFound.add(clean)
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isDholuoBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val dholuoRatio = dholuoFound.size.toFloat() / totalWords
        val hasCodeSwitching = dholuoFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (dholuoRatio > 0.4f) "luo" else "sw",
            dialectWords = dholuoFound,
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

        dholuoBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val dholuoToSwahili = mapOf(
            "kendo" to "na",
            "to" to "kisha",
            "ka" to "kama",
            "gi" to "na",
            "ok" to "si",
            "kata" to "hata",
            "chon" to "kwa sababu",
            "nadi" to "sasa",
            "ber" to "nzuri",
            "maber" to "nzuri",
            "malo" to "asante",
            "erokamano" to "asante sana",
            "amos" to "tafadhali",
            "rech" to "chakula",
            "piyo" to "njugu",
            "kuon" to "ugali",
            "ogo" to "nyumbani",
            "wuon" to "mmiliki",
            "ng'wen" to "deni",
            "siru" to "mkopo",
            "ot" to "duka",
            "dhok" to "samaki",
            "en" to "ni",
            "are" to "yeye",
            "rimo" to "moja",
            "moko" to "watu",
        )
        dholuoToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var dholuoScore = 0

        for (term in dholuoBusinessTerms.keys) {
            if (lower.contains(term)) dholuoScore += 2
        }
        for ((_, regex) in MARKERS) {
            if (regex.containsMatchIn(lower)) dholuoScore += 3
        }

        return if (dholuoScore > 5) DialectRegion.DHOLUO else DialectRegion.STANDARD
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


    private fun isDholuoBusinessTerm(word: String): Boolean {
        return dholuoBusinessTerms.containsKey(word) ||
                dholuoBusinessTerms.values.any { it == word }
    }
}
