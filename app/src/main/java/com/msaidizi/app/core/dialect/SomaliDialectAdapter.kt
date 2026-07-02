package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Somali dialect adapter for Horn of Africa.
 *
 * Somali is an Afro-Asiatic language spoken by ~22 million people
 * in Somalia, Djibouti, Somali Region (Ethiopia), and NE Kenya.
 *
 * Key features:
 * - Rich pastoral vocabulary (camels, goats, cattle)
 * - Arabic loanwords in business/religious contexts
 * - Click consonants (rare, from neighboring Cushitic languages)
 * - Long vowels distinctive in Somali (aa, ee, ii, oo, uu)
 * - Distinctive clan and social structure vocabulary
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object SomaliDialectAdapter {

    private const val TAG = "SomaliDialect"

    // ────────────────────── Somali Code-Switching Markers ──────────────────────

    private val somaliMarkers = setOf(
        // Discourse markers
        "waa",          // "is/are" — focus marker
        "oo",           // "and/who/which"
        "la",           // "with"
        "ka",           // "from"
        "ku",           // "in/at"
        "iyo",          // "and"
        "ama",          // "or"
        "laakiin",      // "but"
        "haddii",       // "if"
        "maxaa",        // "what"
        "xagee",        // "where"
        "goorma",       // "when"
        "sida",         // "how"
        "ma",           // "question particle"
        "haye",         // "okay"
        "mahadsanid",   // "thank you"
        "fadlan",       // "please"
        "waan",         // "I am/I have"
        "wuu",          // "he is/has"
        "way",          // "she is/has"
        "waxaa",        // "thing/what"
        "qof",          // "person"
        "dad",          // "people"
        "guri",         // "house"
        "suuq",         // "market"

        // Common nouns
        "biyo",         // "water"
        "caano",        // "milk"
        "hilib",        // "meat"
        "bariis",       // "rice"
        "burr",         // "bread"
        "shaah",        // "tea"
        "sonkor",       // "sugar"
        "saliid",       // "oil"
        "dhar",         // "cloth"
        "lacag",        // "money"
        "ganacsi",      // "business"
    )

    // ────────────────────── Somali Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Somali speakers may use Arabic-influenced Swahili
        "aka" to "taka",
        "oka" to "toka",

        // Long vowel patterns
        "saafi" to "safi",
    )

    // ────────────────────── Somali Business Vocabulary ──────────────────────

    private val somaliBusinessTerms = mapOf(
        // ── Pastoral products ──
        "caano" to "milk",
        "hilib" to "meat",
        "subag" to "butter/ghee",
        "lagh" to "camel_milk",
        "suqaar" to "dried_meat",
        "muqmad" to "preserved_meat",

        // ── Food & trade goods ──
        "bariis" to "rice",
        "burr" to "bread",
        "shaah" to "tea",
        "sonkor" to "sugar",
        "saliid" to "oil",
        "khudaar" to "vegetables",
        "khudaar_qalalan" to "dried_vegetables",
        "malab" to "honey",
        "qamadi" to "wheat",
        "bur_salid" to "flour",

        // ── Livestock ──
        "geel" to "camel",
        "arig" to "goat",
        "lo'" to "cattle",
        "idah" to "sheep",
        "faras" to "horse",
        "dameer" to "donkey",

        // ── Trade & business ──
        "ganacsi" to "business",
        "lacag" to "money",
        "suuq" to "market",
        "dukaan" to "shop",
        "dhar" to "cloth",
        "alwaax" to "wood",
        "bir" to "iron",
        "dahab" to "gold",

        // ── Measurements ──
        "kiilo" to "kilogram",
        "mitir" to "meter",
        "debe" to "debe",
        "gunia" to "sack",

        // ── Currency ──
        "shilin" to "shilling",
        "doolar" to "dollar",
        "mbao" to "twenty_shillings",
        "jeuri" to "fifty_shillings",
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
                dholuoWords = emptyList(),
                swahiliWords = emptyList(),
                confidence = 0.5f
            )
        }

        val somaliFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                somaliMarkers.contains(clean) -> somaliFound.add(clean)
                isSwahiliWord(clean) -> swahiliFound.add(clean)
                isSomaliBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val somaliRatio = somaliFound.size.toFloat() / totalWords
        val hasCodeSwitching = somaliFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (somaliRatio > 0.4f) "so" else "sw",
            dholuoWords = somaliFound,
            swahiliWords = swahiliFound,
            confidence = if (hasCodeSwitching) 0.8f else 0.6f
        )
    }

    // ────────────────────── Normalization ──────────────────────

    fun normalize(text: String): String {
        var normalized = text
        for ((somali, standard) in pronunciationVariations) {
            if (somali != standard) {
                normalized = normalized.replace(
                    Regex("\\b$somali\\b", RegexOption.IGNORE_CASE),
                    standard
                )
            }
        }
        return normalized
    }

    fun translateToStandard(term: String): String? {
        val lower = term.lowercase().trim()

        somaliBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val somaliToSwahili = mapOf(
            "waa" to "ni",
            "iyo" to "na",
            "laakiin" to "lakini",
            "haddii" to "kama",
            "mahadsanid" to "asante",
            "fadlan" to "tafadhali",
            "qof" to "mtu",
            "dad" to "watu",
            "guri" to "nyumba",
            "suuq" to "soko",
            "biyo" to "maji",
            "caano" to "maziwa",
            "hilib" to "nyama",
            "lacag" to "pesa",
            "ganacsi" to "biashara",
            "shaah" to "chai",
            "sonkor" to "sukari",
            "saliid" to "mafuta",
            "geel" to "ngamia",
            "arig" to "mbuzi",
            "lo'" to "ng'ombe",
        )
        somaliToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var somaliScore = 0

        for (term in somaliBusinessTerms.keys) {
            if (lower.contains(term)) somaliScore += 2
        }
        for (marker in somaliMarkers) {
            if (Regex("\\b$marker\\b").containsMatchIn(lower)) somaliScore += 3
        }

        return if (somaliScore > 5) DialectRegion.SOMALI else DialectRegion.STANDARD
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

    private fun isSwahiliWord(word: String): Boolean {
        val swahiliMarkers = setOf(
            "na", "ya", "wa", "za", "kwa", "ni", "la", "cha",
            "nime", "sija", "tuta", "wata", "nina", "tuna",
            "sana", "pia", "lakini", "kama", "au", "hata", "bado",
            "leo", "jana", "kesho", "sasa", "baada",
            "biashara", "bei", "faida", "hasara", "deni", "pesa"
        )
        return word in swahiliMarkers
    }

    private fun isSomaliBusinessTerm(word: String): Boolean {
        return somaliBusinessTerms.containsKey(word) ||
                somaliBusinessTerms.values.any { it == word }
    }
}
