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
    companion object {
        private val MARKERS = mapOf(
            "waa" to Regex("\\bwaa\\b"),
            "oo" to Regex("\\boo\\b"),
            "la" to Regex("\\bla\\b"),
            "ka" to Regex("\\bka\\b"),
            "ku" to Regex("\\bku\\b"),
            "iyo" to Regex("\\biyo\\b"),
            "ama" to Regex("\\bama\\b"),
            "laakiin" to Regex("\\blaakiin\\b"),
            "haddii" to Regex("\\bhaddii\\b"),
            "maxaa" to Regex("\\bmaxaa\\b"),
            "xagee" to Regex("\\bxagee\\b"),
            "goorma" to Regex("\\bgoorma\\b"),
            "sida" to Regex("\\bsida\\b"),
            "ma" to Regex("\\bma\\b"),
            "haye" to Regex("\\bhaye\\b"),
            "mahadsanid" to Regex("\\bmahadsanid\\b"),
            "fadlan" to Regex("\\bfadlan\\b"),
            "waan" to Regex("\\bwaan\\b"),
            "wuu" to Regex("\\bwuu\\b"),
            "way" to Regex("\\bway\\b"),
            "waxaa" to Regex("\\bwaxaa\\b"),
            "qof" to Regex("\\bqof\\b"),
            "dad" to Regex("\\bdad\\b"),
            "guri" to Regex("\\bguri\\b"),
            "suuq" to Regex("\\bsuuq\\b"),
            "biyo" to Regex("\\bbiyo\\b"),
            "caano" to Regex("\\bcaano\\b"),
            "hilib" to Regex("\\bhilib\\b"),
            "bariis" to Regex("\\bbariis\\b"),
            "burr" to Regex("\\bburr\\b"),
            "shaah" to Regex("\\bshaah\\b"),
            "sonkor" to Regex("\\bsonkor\\b"),
            "saliid" to Regex("\\bsaliid\\b"),
            "dhar" to Regex("\\bdhar\\b"),
            "lacag" to Regex("\\blacag\\b"),
            "ganacsi" to Regex("\\bganacsi\\b")
        )
        private val PRONUNCIATION_REGEXES = mapOf(
            "aka" to Regex("\\baka\\b", RegexOption.IGNORE_CASE),
            "oka" to Regex("\\boka\\b", RegexOption.IGNORE_CASE),
            "saafi" to Regex("\\bsaafi\\b", RegexOption.IGNORE_CASE)
        )
    }


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
                dialectWords = emptyList(),
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
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isSomaliBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val somaliRatio = somaliFound.size.toFloat() / totalWords
        val hasCodeSwitching = somaliFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (somaliRatio > 0.4f) "so" else "sw",
            dialectWords = somaliFound,
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
        for ((_, regex) in MARKERS) {
            if (regex.containsMatchIn(lower)) somaliScore += 3
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


    private fun isSomaliBusinessTerm(word: String): Boolean {
        return somaliBusinessTerms.containsKey(word) ||
                somaliBusinessTerms.values.any { it == word }
    }
}
