package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Amharic dialect adapter for Ethiopia.
 *
 * Amharic is a Semitic language spoken by ~57 million people
 * in Ethiopia. It uses the Ge'ez script (Fidel) but this adapter
 * handles romanized (transliterated) input from ASR.
 *
 * Key features:
 * - Semitic root-and-pattern morphology
 * - Ejective consonants (p', t', k', ch') misrecognized by ASR
 * - 7 vowel system with length distinction
 * - Ge'ez numerals and date system
 * - Rich coffee trade vocabulary (Ethiopia is coffee's birthplace)
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object AmharicDialectAdapter {
    companion object {
        private val MARKERS = mapOf(
            "yä" to Regex("\\byä\\b"),
            "nä" to Regex("\\bnä\\b"),
            "wädä" to Regex("\\bwädä\\b"),
            "säbbäbä" to Regex("\\bsäbbäbä\\b"),
            "änd" to Regex("\\bänd\\b"),
            "gäza" to Regex("\\bgäza\\b"),
            "säw" to Regex("\\bsäw\\b"),
            "säwoc" to Regex("\\bsäwoc\\b"),
            "bet" to Regex("\\bbet\\b"),
            "shäro" to Regex("\\bshäro\\b"),
            "täffa" to Regex("\\btäffa\\b"),
            "mäläs" to Regex("\\bmäläs\\b"),
            "selam" to Regex("\\bselam\\b"),
            "tänässä" to Regex("\\btänässä\\b"),
            "äzbäyähäwür" to Regex("\\bäzbäyähäwür\\b"),
            "dä" to Regex("\\bdä\\b"),
            "yähon" to Regex("\\byähon\\b"),
            "käfätäri" to Regex("\\bkäfätäri\\b"),
            "hulä" to Regex("\\bhulä\\b"),
            "ämäsägn" to Regex("\\bämäsägn\\b"),
            "bärä" to Regex("\\bbärä\\b"),
            "zämän" to Regex("\\bzämän\\b"),
            "gäbrä" to Regex("\\bgäbrä\\b"),
            "täkäla" to Regex("\\btäkäla\\b"),
            "säb" to Regex("\\bsäb\\b"),
            "gäbäya" to Regex("\\bgäbäya\\b"),
            "täjäj" to Regex("\\btäjäj\\b"),
            "äsfä" to Regex("\\bäsfä\\b"),
            "bärr" to Regex("\\bbärr\\b"),
            "buna" to Regex("\\bbuna\\b"),
            "jäbäna" to Regex("\\bjäbäna\\b"),
            "säqäl" to Regex("\\bsäqäl\\b"),
            "käffä" to Regex("\\bkäffä\\b")
        )
        private val PRONUNCIATION_REGEXES = mapOf(
            "p'" to Regex("\\bp'\\b", RegexOption.IGNORE_CASE),
            "t'" to Regex("\\bt'\\b", RegexOption.IGNORE_CASE),
            "k'" to Regex("\\bk'\\b", RegexOption.IGNORE_CASE),
            "ch'" to Regex("\\bch'\\b", RegexOption.IGNORE_CASE),
            "ä" to Regex("\\bä\\b", RegexOption.IGNORE_CASE),
            "ë" to Regex("\\bë\\b", RegexOption.IGNORE_CASE),
            "ö" to Regex("\\bö\\b", RegexOption.IGNORE_CASE),
            "ss" to Regex("\\bss\\b", RegexOption.IGNORE_CASE),
            "tt" to Regex("\\btt\\b", RegexOption.IGNORE_CASE),
            "kk" to Regex("\\bkk\\b", RegexOption.IGNORE_CASE)
        )
    }


    private const val TAG = "AmharicDialect"

    // ────────────────────── Amharic Code-Switching Markers ──────────────────────

    private val amharicMarkers = setOf(
        // Discourse markers
        "yä",           // "of/belonging to" — possession marker
        "nä",           // "is/are"
        "wädä",         // "to/toward"
        "säbbäbä",      // "because"
        "änd",          // "if/when"
        "gäza",         // "time"
        "säw",          // "person"
        "säwoc",        // "people"
        "bet",          // "house"
        "shäro",        // "cloth"
        "täffa",        // "throw/throw away"
        "mäläs",        // "rest/peace"
        "selam",        // "peace"
        "tänässä",      // "thank you"
        "äzbäyähäwür",  // "excuse me"
        "dä",           // "this"
        "yähon",        // "let it be"
        "käfätäri",     // "first"
        "hulä",         // "all"
        "ämäsägn",      // "most/best"

        // Common nouns
        "bärä",         // "money"
        "zämän",        // "time"
        "gäbrä",        // "work"
        "täkäla",       // "market"
        "säb",          // "person"
        "gäbäya",       // "market square"
        "täjäj",        // "gold"
        "äsfä",         // "silver"
        "bärr",         // "land/country"

        // Coffee terms (Ethiopia's gift to the world)
        "buna",         // "coffee"
        "jäbäna",       // "coffee pot"
        "säqäl",        // "roast"
        "käffä",        // "coffee ceremony"
    )

    // ────────────────────── Amharic Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Ejective → plain stop (ASR drops ejectives)
        "p'" to "p",
        "t'" to "t",
        "k'" to "k",
        "ch'" to "ch",

        // Vowel simplification
        "ä" to "a",
        "ë" to "e",
        "ö" to "o",

        // Gemination patterns
        "ss" to "s",
        "tt" to "t",
        "kk" to "k",
    )

    // ────────────────────── Amharic Business Vocabulary ──────────────────────

    private val amharicBusinessTerms = mapOf(
        // ── Coffee trade (Ethiopia's #1 export) ──
        "buna" to "coffee",
        "jäbäna" to "coffee_pot",
        "säqäl" to "roast",
        "käffä" to "coffee_ceremony",
        "buna_kälu" to "coffee_seller",
        "buna_qäy" to "coffee_plant",
        "yätäfätäfo_buna" to "washed_coffee",
        "buna_gärra" to "coffee_trader",

        // ── Food & agriculture ──
        "täff" to "teff_grain",
        "däbo" to "bread",
        "injära" to "injera_bread",
        "wot" to "stew",
        "doro_wot" to "chicken_stew",
        "misir_wot" to "lentil_stew",
        "yämisir" to "lentil",
        "shimbra" to "chickpea",
        "qocho" to "false_banana",
        "ämbäza" to "corn",
        "goumen" to "spice_blend",

        // ── Trade & commerce ──
        "bärä" to "money",
        "täkäla" to "market",
        "gäbäya" to "market_square",
        "gäbrä" to "work",
        "akrabi" to "commission",
        "sänto" to "profit",
        "mäbr" to "debt",
        "täfäto" to "credit",

        // ── Livestock ──
        "läm" to "cattle",
        "ärb" to "goat",
        "täbot" to "sheep",
        "dämo" to "donkey",
        "yäfäräs" to "horse",

        // ── Measurements ──
        "kilo" to "kilogram",
        "litr" to "liter",
        "qänto" to "100_kg_sack",
        "däqo" to "half_qänto",

        // ── Currency ──
        "birr" to "birr",
        "santim" to "cent",
        "mbao" to "twenty_shillings",
        "ngiri" to "thousand_shillings",
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

        val amharicFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                amharicMarkers.contains(clean) -> amharicFound.add(clean)
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isAmharicBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val amharicRatio = amharicFound.size.toFloat() / totalWords
        val hasCodeSwitching = amharicFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (amharicRatio > 0.4f) "am" else "sw",
            dialectWords = amharicFound,
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

        amharicBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val amharicToSwahili = mapOf(
            "selam" to "amani",
            "tänässä" to "asante",
            "säw" to "mtu",
            "säwoc" to "watu",
            "bet" to "nyumba",
            "bärä" to "pesa",
            "gäbrä" to "kazi",
            "täkäla" to "soko",
            "buna" to "kahawa",
            "injära" to "chapati",
            "däbo" to "mkate",
            "wot" to "mchuzi",
            "läm" to "ng'ombe",
            "ärb" to "mbuzi",
            "mäbr" to "deni",
            "sänto" to "faida",
        )
        amharicToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var amharicScore = 0

        for (term in amharicBusinessTerms.keys) {
            if (lower.contains(term)) amharicScore += 2
        }
        for ((_, regex) in MARKERS) {
            if (regex.containsMatchIn(lower)) amharicScore += 3
        }

        return if (amharicScore > 5) DialectRegion.AMHARIC else DialectRegion.STANDARD
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

}

    private fun isAmharicBusinessTerm(word: String): Boolean {
        return amharicBusinessTerms.containsKey(word) ||
                amharicBusinessTerms.values.any { it == word }
    }
}
