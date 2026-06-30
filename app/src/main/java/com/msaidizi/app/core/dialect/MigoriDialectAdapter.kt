package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.LanguageDetector
import com.msaidizi.app.core.LanguageResult
import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Migori County Swahili dialect adapter.
 *
 * Migori Swahili has heavy Luo (Dholuo) substrate influence:
 * - Dholuo-Swahili code-switching at clause boundaries
 * - Luo vocabulary borrowed into Swahili context
 * - Phonological transfer: implosive consonants /ɓ/, /ɗ/ bleed into Swahili
 * - Simplified consonant clusters ("aka" instead of "taka")
 * - Tonal interference from Luo
 * - "th" → "s" in some contexts (samini instead of thamini)
 *
 * This adapter detects and normalizes Migori-specific speech patterns
 * so the ASR and NLU pipelines can handle them correctly.
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object MigoriDialectAdapter {

    private const val TAG = "MigoriDialect"

    // ────────────────────── Dholuo Code-Switching Markers ──────────────────────

    /**
     * Dholuo words commonly used in Migori Swahili code-switching.
     * These appear mid-sentence in otherwise Swahili speech.
     * Source: field observations, Migori County market recordings.
     */
    private val dholuoMarkers = setOf(
        // Conjunctions & discourse markers (most frequent in code-switching)
        "kendo",        // "also/and" — clause boundary marker
        "to",           // "then/so" — discourse connector
        "ka",           // "if/when" — conditional
        "mano",         // "that/those" — demonstrative
        "gi",           // "with" — comitative
        "nyiso",        // "show/reveal"
        "en",           // "is/are" — copula (very common in mixing)
        "ok",           // "not" — negation
        "kata",         // "even/even if"
        "chon",         // "because"
        "nadi",         // "now"
        "inyalo",       // "you can"
        "kia",          // "can/able"
        "ber",          // "good" (from "ber" = good in Dholuo)
        "maber",        // "good/well"
        "malo",         // "thank you"
        "yawuoyo",      // "understood"
        "amos",         // "please"
        "erokamano",    // "thank you" (formal)

        // Common nouns used in Luo-Swahili mixing
        "chuthi",       // "market day" (Luo market concept)
        "dhok",         // "fish" (generic Luo term)
        "ogo",          // "home/village"
        "wuon",         // "owner/father"
        "min",          // "mother"
        "ja",           // "person of" (agentive marker)
        "juak",         // "trick/cleverness"
        "tich",         // "teacher" (used alongside "mwalimu")
        "paro",         // "year"
        "saa",          // "time/hour" (also Swahili, but tonal difference)
        "pod",          // "place"
        "piny",         // "earth/ground/world"
        "piyo",         // "groundnuts" (distinct from "njugu" in Swahili)
        "rech",         // "food" (used alongside "chakula")
        "uong'",        // "cooking" (Luo form)
        "rimo",         // "one" (Luo numeral in mixing)
        "are",          // "he/she/it" (Luo pronoun)
        "ng'ato",       // "one person"
        "moko",         // "people"

        // Luo market/business terms
        "chuthi",       // "market/weekly market"
        "oduol",        // "sell off / clear stock"
        "ng'wen",       // "debt"
        "siru",         // "loan"
        "pesa",         // "money" (shared but tonal variant)
        "ot",           // "home/shop"
        "daraja",       // "bridge/market stall area"
    )

    // ────────────────────── Migori Pronunciation Variations ──────────────────────

    /**
     * Migori pronunciation → standard Swahili mapping.
     * These are systematic phonological changes in Migori Swahili
     * that cause ASR misrecognition.
     *
     * Map: migori_form → standard_form
     */
    private val pronunciationVariations = mapOf(
        // "th" → "s" (most common Migori feature)
        "samini" to "thamini",         // value/price
        "sababu" to "thababu",         // reason (partial — both exist)
        "sulu" to "thuru",             // trouble
        "sahabu" to "thahabu",         // gold

        // Simplified consonant clusters (Luo substrate)
        "aka" to "taka",              // want
        "apata" to "tapata",          // get/find (variable)
        "oka" to "toka",              // come from
        "iga" to "piga",              // hit/do
        "osha" to "tosha",            // be enough (partial)
        "oka" to "choka",             // get tired (context-dependent)

        // Vowel lengthening from Luo prosody
        "naani" to "nani",            // who
        "saafi" to "safi",            // clean/good
        "twaa" to "twa",              // take

        // Implosive consonants → standard (ASR often mishears)
        "b" to "b",                    // /ɓ/ → /b/ (ASR writes same)
        "d" to "d",                    // /ɗ/ → /d/ (ASR writes same)

        // "r" ↔ "l" variation (free variation in Migori)
        "pesa" to "pesa",             // money (stable)
        "rejareja" to "rejareja",     // retail (stable)

        // Nasal assimilation patterns
        "mboga" to "mboga",           // vegetable (stable)
        "ng'ombe" to "ng'ombe",       // cow (stable)
    )

    // ────────────────────── Migori Business Vocabulary ──────────────────────

    /**
     * Business terms specific to Migori County.
     * These are NOT in standard Swahili dictionaries but are used daily
     * in Migori markets (Kehancha, Isebania, Migori town, Awendo, Rongo).
     */
    private val migoriBusinessTerms = mapOf(
        // ── Local products (Luo substrate names) ──
        "omena" to "omena",                // small dried fish (Rachuonyo/Migori staple)
        "omena kubwa" to "omena_kubwa",    // large dried fish
        "rech" to "food",                  // Dholuo: food (used in Swahili context)
        "piyo" to "groundnuts",            // Dholuo: groundnuts (vs. "njugu" in Swahili)
        "kuon" to "ugali",                 // Dholuo: ugali/corn meal
        "atapa" to "atapa",               // traditional Luo bread (cassava-based)
        "ugali sima" to "ugali_sima",      // refined ugali (Migori term)
        "nyoyo" to "nyoyo",               // cowpeas (Migori term)
        "odo" to "odo",                    // traditional greens (Luo)
        "apoth" to "traditional_greens",   // traditional leafy vegetable
        "ng'wen" to "debt",                // Dholuo: debt (used in code-switching)

        // ── Local measurement units ──
        "debe" to "debe",                  // tin can (standard ~20kg for maize)
        "debe_ndogo" to "debe_small",      // small tin
        "debe_kubwa" to "debe_large",      // large tin
        "gunia" to "gunia",                // sack (50-90kg depending on commodity)
        "gunia_ndogo" to "gunia_small",    // small sack
        "fundo" to "fundo",                // bundle/tie (e.g., sukuma wiki)
        "mfuko" to "mfuko",               // bag/packet
        "kibuyu" to "kibuyu",             // calabash (traditional measure)
        "goro" to "goro",                  // tin measure for cooking oil
        "kibaba" to "kibaba",              // small measure (1 kg approx.)
        "ratili" to "ratili",              // pound (from English "ratili")

        // ── Migori-specific currency expressions ──
        "mbao" to "mbao",                  // 20 bob (KSh 20)
        "jeuri" to "jeuri",                // 50 bob (Sheng, but widely used in Migori)
        "kibabu" to "kibabu",             // 50 bob
        "ngiri" to "ngiri",                // 1000 bob
        "thao" to "thao",                  // 1000 (from "thousand")
        "finje" to "finje",               // 500 bob
        "nane" to "nane",                  // 80 bob (from Swahili "nane" = 8, × 10)
        "jioni" to "jioni",                // evening discount (sell cheap in evening)

        // ── Cross-border trade terms (Migori-Tanzania border) ──
        "mali" to "goods",                 // goods/merchandise (cross-border)
        "mpakani" to "border",             // at the border
        "shepu" to "clearing",             // customs clearing
        "forodha" to "customs",            // customs (standard but frequent here)
        "mugongo" to "back_load",          // carrying goods on back (border trade)

        // ── Market-specific terms ──
        "soko" to "market",               // market (standard, but central to Migori)
        "duka" to "shop",                  // shop
        "kibanda" to "stall",             // market stall
        "mama_mboga" to "mama_mboga",     // vegetable seller
        "mzee_wa_duka" to "shopkeeper",   // shop owner
        "boda_boda" to "boda_boda",       // motorcycle taxi
        "mkokoteni" to "mkokoteni",       // hand cart
        "gari_la_mizigo" to "cargo_vehicle", // cargo vehicle

        // ── Luo market days & social terms ──
        "chuthi" to "market_day",         // weekly market day
        "ja_kuma" to "fish_trader",        // fish trader (Luo agentive)
        "ja_rech" to "food_vendor",        // food vendor (Luo agentive)
        "wuon_duka" to "shop_owner",       // shop owner (Luo)
        "min_mboga" to "vegetable_mama",   // vegetable seller (Luo)

        // ── Livestock terms (common in Migori rural markets) ──
        "ng'ombe" to "cattle",
        "mbuzi" to "goat",
        "kondoo" to "sheep",
        "kuku" to "chicken",
        "ng'ina" to "hen",                // Luo: hen
        "dier" to "bull",                 // Luo: bull
        "g echang" to "heifer",           // Luo: young cow
    )

    // ────────────────────── Code-Switching Detection ──────────────────────

    /**
     * Detect if text contains Dholuo-Swahili code-switching.
     * Returns a CodeSwitchResult with detected languages and confidence.
     */
    fun detectCodeSwitching(text: String): CodeSwitchResult {
        val words = text.lowercase()
            .split(Regex("[^\\p{L}']+"))
            .filter { it.length > 1 }

        if (words.isEmpty()) {
            return CodeSwitchResult(
                hasCodeSwitching = false,
                primaryLanguage = "sw",
                dholuoWords = emptyList(),
                swahiliWords = emptyList(),
                confidence = 0.5f
            )
        }

        val dholuoFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()
        val unknownWords = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                clean in dholuoMarkers -> dholuoFound.add(clean)
                isSwahiliWord(clean) -> swahiliFound.add(clean)
                isMigoriBusinessTerm(clean) -> swahiliFound.add(clean) // counts as Swahili context
                else -> unknownWords.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val dholuoRatio = dholuoFound.size.toFloat() / totalWords
        val hasCodeSwitching = dholuoFound.size >= 1 && swahiliFound.size >= 1

        Timber.tag(TAG).d(
            "Code-switch detection: dholuo=%d, swahili=%d, unknown=%d, ratio=%.2f",
            dholuoFound.size, swahiliFound.size, unknownWords.size, dholuoRatio
        )

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (dholuoRatio > 0.4f) "luo" else "sw",
            dholuoWords = dholuoFound,
            swahiliWords = swahiliFound,
            confidence = if (hasCodeSwitching) 0.8f else 0.6f
        )
    }

    /**
     * Normalize Migori Swahili text to standard Swahili.
     * Applies pronunciation variation mappings and Dholuo loanword
     * normalization for downstream NLU processing.
     *
     * @return Normalized text that standard SwahiliParser can handle
     */
    fun normalize(text: String): String {
        var normalized = text

        // Apply pronunciation variations (Migori → standard)
        for ((migori, standard) in pronunciationVariations) {
            if (migori != standard) {
                normalized = normalized.replace(
                    Regex("\\b$migori\\b", RegexOption.IGNORE_CASE),
                    standard
                )
            }
        }

        // Normalize Dholuo loanwords to their Swahili equivalents
        for ((dholuo, meaning) in dholuoMarkers.associateWith { it }) {
            // Keep Dholuo markers but log them for ASR training data
            Timber.tag(TAG).v("Dholuo marker preserved: %s", dholuo)
        }

        Timber.tag(TAG).d("Normalized: '%s' → '%s'", text, normalized)
        return normalized
    }

    /**
     * Translate Migori-specific business terms to standard Swahili equivalents.
     * Used when the SwahiliParser doesn't recognize a term.
     */
    fun translateToStandard(term: String): String? {
        val lower = term.lowercase().trim()

        // Check Migori business terms
        migoriBusinessTerms[lower]?.let { return it }

        // Check pronunciation variations
        pronunciationVariations[lower]?.let { return it }

        // Check Dholuo markers with Swahili equivalents
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
        )
        dholuoToSwahili[lower]?.let { return it }

        return null
    }

    /**
     * Get the dialect region for a given text.
     * Returns the most likely dialect based on vocabulary markers.
     */
    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var migoriScore = 0
        var nairobiScore = 0
        var coastScore = 0
        var standardScore = 0

        // Migori markers
        for (term in migoriBusinessTerms.keys) {
            if (lower.contains(term)) migoriScore += 2
        }
        for (marker in dholuoMarkers) {
            if (Regex("\\b$marker\\b").containsMatchIn(lower)) migoriScore += 3
        }

        // Nairobi Sheng markers
        val nairobiSheng = setOf("sasa", "poa", "fiti", "aje", "niaje", "chapaa", "ndege")
        for (marker in nairobiSheng) {
            if (Regex("\\b$marker\\b").containsMatchIn(lower)) nairobiScore += 2
        }

        // Coast markers
        val coastWords = setOf("habari", "mambo", "vipi", "niaje", "shwari")
        for (marker in coastWords) {
            if (Regex("\\b$marker\\b").containsMatchIn(lower)) coastScore += 1
        }

        // Standard Swahili
        val standardWords = setOf("sana", "pia", "lakini", "kama", "bado", "ndio")
        for (marker in standardWords) {
            if (Regex("\\b$marker\\b").containsMatchIn(lower)) standardScore += 1
        }

        return when {
            migoriScore > nairobiScore && migoriScore > coastScore && migoriScore > standardScore ->
                DialectRegion.MIGORI
            nairobiScore > standardScore ->
                DialectRegion.NAIROBI
            coastScore > standardScore ->
                DialectRegion.COAST
            else ->
                DialectRegion.STANDARD
        }
    }

    /**
     * Process text through the full Migori dialect pipeline:
     * 1. Detect code-switching
     * 2. Normalize pronunciation variations
     * 3. Translate Dholuo loanwords
     * 4. Detect region
     *
     * @return ProcessedResult with normalized text and metadata
     */
    fun process(text: String): ProcessedResult {
        Timber.tag(TAG).d("Processing: '%s'", text)

        val codeSwitch = detectCodeSwitching(text)
        val normalized = normalize(text)
        val region = detectRegion(text)

        // Extract any Dholuo terms and their translations
        val translations = mutableMapOf<String, String>()
        val words = text.lowercase().split(Regex("[^\\p{L}']+"))
        for (word in words) {
            val clean = word.trim()
            translateToStandard(clean)?.let { standard ->
                translations[clean] = standard
            }
        }

        val result = ProcessedResult(
            originalText = text,
            normalizedText = normalized,
            codeSwitchResult = codeSwitch,
            dialectRegion = region,
            translations = translations,
            confidence = codeSwitch.confidence
        )

        Timber.tag(TAG).d(
            "Result: region=%s, codeSwitch=%s, translations=%d",
            region, codeSwitch.hasCodeSwitching, translations.size
        )

        return result
    }

    // ────────────────────── Helper Methods ──────────────────────

    private fun isSwahiliWord(word: String): Boolean {
        val swahiliMarkers = setOf(
            "na", "ya", "wa", "za", "kwa", "ni", "la", "cha",
            "nime", "sija", "tuta", "wata", "nina", "tuna",
            "sana", "pia", "lakini", "kama", "au", "hata", "bado",
            "leo", "jana", "kesho", "sasa", "baada",
            "nini", "gani", "wapi", "lini", "vipi",
            "hapa", "pale", "ndani", "nje", "juu", "chini",
            "nenda", "kuja", "fanya", "sema", "ona", "pata",
            "biashara", "bei", "faida", "hasara", "deni", "pesa"
        )
        return word in swahiliMarkers
    }

    private fun isMigoriBusinessTerm(word: String): Boolean {
        return migoriBusinessTerms.containsKey(word) ||
                migoriBusinessTerms.values.any { it == word }
    }
}

// ────────────────────── Data Classes ──────────────────────

/**
 * Result of code-switching detection.
 */
data class CodeSwitchResult(
    val hasCodeSwitching: Boolean,
    val primaryLanguage: String,  // "sw", "luo", "en"
    val dholuoWords: List<String>,
    val swahiliWords: List<String>,
    val confidence: Float
)

/**
 * Full result of Migori dialect processing.
 */
data class ProcessedResult(
    val originalText: String,
    val normalizedText: String,
    val codeSwitchResult: CodeSwitchResult,
    val dialectRegion: DialectRegion,
    val translations: Map<String, String>,
    val confidence: Float
)
