package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Zulu dialect adapter for South Africa.
 *
 * Zulu (isiZulu) is a Bantu language spoken by ~12 million people
 * as a first language in South Africa (KwaZulu-Natal, Gauteng).
 *
 * Key features:
 * - Click consonants: 3 types (c, q, x) — no European equivalent
 * - Tonal language with 2 tones (high, low)
 * - Prenasalized stops and breathy voiced consonants
 * - Rich cattle and trade vocabulary
 * - E-commerce and taxi industry terminology
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object ZuluDialectAdapter {

    private const val TAG = "ZuluDialect"

    // ────────────────────── Zulu Code-Switching Markers ──────────────────────

    private val zuluMarkers = setOf(
        // Discourse markers
        "na",           // "and/with"
        "futhi",        // "also/again"
        "kodwa",        // "but"
        "noma",         // "or/even"
        "uma",          // "if"
        "ngoba",        // "because"
        "lapho",        // "when/where"
        "lokhu",        // "this"
        "lowo",         // "that"
        "namuhla",      // "today"
        "kusasa",       // "tomorrow"
        "izolo",        // "yesterday"
        "ngiyafuna",    // "I want"
        "ngiyabonga",   // "thank you"
        "ngicela",      // "please"
        "yebo",         // "yes"
        "cha",          // "no"
        "sawubona",     // "hello (to one)"
        "sanibonani",   // "hello (to many)"
        "unjani",       // "how are you"

        // Common nouns
        "umuntu",       // "person"
        "abantu",       // "people"
        "indlu",        // "house"
        "imakethe",     // "market"
        "imali",        // "money"
        "umsebenzi",    // "work"
        "ukudla",       // "food"
        "amanzi",       // "water"
        "inyanga",      // "month"
        "unyaka",       // "year"
        "usuku",        // "day"
        "isikhathi",    // "time"
    )

    // ────────────────────── Zulu Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Click consonants → ASR approximations
        "c" to "c",     // dental click /ǀ/
        "q" to "q",     // alveolar click /ǃ/
        "x" to "x",     // lateral click /ǁ/

        // Breatthy voiced → plain voiced
        "bh" to "b",
        "dh" to "d",
        "gh" to "g",

        // Prenasalized stops
        "mb" to "mb",
        "nd" to "nd",
        "ng" to "ng",
        "nj" to "nj",
    )

    // ────────────────────── Zulu Business Vocabulary ──────────────────────

    private val zuluBusinessTerms = mapOf(
        // ── Market & trade terms ──
        "imakethe" to "market",
        "umthengisi" to "seller",
        "umthengi" to "buyer",
        "imali" to "money",
        "umsebenzi" to "work",
        "inzuzo" to "profit",
        "ukulahlekelwa" to "loss",
        "isikweletu" to "debt",

        // ── Food & agricultural products ──
        "ukudla" to "food",
        "umbila" to "corn",
        "ubhatata" to "sweet_potato",
        "ijikijolo" to "spinach",
        "umhluzi" to "soup",
        "inyama" to "meat",
        "inhlanzi" to "fish",
        "ubisi" to "milk",
        "uju" to "honey",
        "ubhontshisi" to "beans",
        "uphuthu" to "pap",

        // ── Taxi & transport industry ──
        "itheksi" to "taxi",
        "imoto" to "car",
        "ibhasi" to "bus",
        "ibhayisikili" to "bicycle",
        "imoto_yokuthwala" to "cargo_vehicle",

        // ── Textile & crafts ──
        "ingubo" to "blanket/cloth",
        "isicoco" to "headring",
        "umceza" to "grass_mat",
        "imbenge" to "basket",

        // ── Currency & measurements ──
        "rand" to "rand",
        "cent" to "cent",
        "debe" to "debe",
        "isikhwama" to "bag",

        // ── Market roles ──
        "umama_wemakethe" to "market_mother",
        "ubaba_wemakethe" to "market_father",

        // ── Livestock ──
        "inkomo" to "cattle",
        "imbuzi" to "goat",
        "imvu" to "sheep",
        "inkukhu" to "chicken",
        "ihhashi" to "horse",
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

        val zuluFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                zuluMarkers.contains(clean) -> zuluFound.add(clean)
                isSwahiliWord(clean) -> swahiliFound.add(clean)
                isZuluBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val zuluRatio = zuluFound.size.toFloat() / totalWords
        val hasCodeSwitching = zuluFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (zuluRatio > 0.4f) "zu" else "sw",
            dholuoWords = zuluFound,
            swahiliWords = swahiliFound,
            confidence = if (hasCodeSwitching) 0.8f else 0.6f
        )
    }

    // ────────────────────── Normalization ──────────────────────

    fun normalize(text: String): String {
        var normalized = text
        for ((zulu, standard) in pronunciationVariations) {
            if (zulu != standard) {
                normalized = normalized.replace(zulu, standard)
            }
        }
        return normalized
    }

    fun translateToStandard(term: String): String? {
        val lower = term.lowercase().trim()

        zuluBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val zuluToSwahili = mapOf(
            "sawubona" to "habari",
            "sanibonani" to "habari",
            "ngiyabonga" to "asante",
            "ngicela" to "tafadhali",
            "yebo" to "ndiyo",
            "cha" to "hapana",
            "umuntu" to "mtu",
            "abantu" to "watu",
            "indlu" to "nyumba",
            "imakethe" to "soko",
            "imali" to "pesa",
            "umsebenzi" to "kazi",
            "ukudla" to "chakula",
            "amanzi" to "maji",
            "inkomo" to "ng'ombe",
            "imbuzi" to "mbuzi",
            "inkukhu" to "kuku",
            "inzuzo" to "faida",
            "isikweletu" to "deni",
        )
        zuluToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var zuluScore = 0

        for (term in zuluBusinessTerms.keys) {
            if (lower.contains(term)) zuluScore += 2
        }
        for (marker in zuluMarkers) {
            if (Regex("\\b$marker\\b").containsMatchIn(lower)) zuluScore += 3
        }

        return if (zuluScore > 5) DialectRegion.ZULU else DialectRegion.STANDARD
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

    private fun isZuluBusinessTerm(word: String): Boolean {
        return zuluBusinessTerms.containsKey(word) ||
                zuluBusinessTerms.values.any { it == word }
    }
}
