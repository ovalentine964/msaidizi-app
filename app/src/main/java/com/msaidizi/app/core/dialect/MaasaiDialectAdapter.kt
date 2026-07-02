package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Maasai dialect adapter for Southern Kenya and Northern Tanzania.
 *
 * Maasai (Maa/ɔl Maa) is a Nilotic language spoken by ~1.5 million people
 * in Kajiado, Narok (Kenya) and Arusha, Manyara (Tanzania).
 *
 * Key features:
 * - Pastoral economy vocabulary (cattle, goats, milk, blood)
 * - Click consonants in some dialects
 * - Tonal language with 3 tones
 * - Distinctive age-set (ilkeek) social terminology
 * - Trade vocabulary for livestock markets (kibera, enkang')
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object MaasaiDialectAdapter {

    private const val TAG = "MaasaiDialect"

    // ────────────────────── Maasai Code-Switching Markers ──────────────────────

    private val maasaiMarkers = setOf(
        // Discourse markers
        "sopa",         // "greeting (hello)"
        "yeyo",         // "response to sopa"
        "iko",          // "it is/okay"
        "meita",        // "what"
        "naikai",       // "why"
        "enaiki",       // "when"
        "kioku",        // "where"
        "ai",           // "no"
        "aiye",         // "yes"
        "keju",         // "thank you"
        "ashe",         // "thank you"
        "oleng",        // "person"
        "ilkeek",       // "age-set"
        "enkang'",      // "homestead/village"
        "enkaji",       // "house"
        "olchani",      // "chief"
        "laibon",       // "spiritual leader"
        "ilmurran",     // "warriors"
        "inkajijik",    // "women"
        "olotuno",      // "men"
        "enkare",       // "water"
        "olari",        // "rain"

        // Pastoral terms
        "enk'ee",       // "cow"
        "ore",          // "bull"
        "enk'ositon",   // "heifer"
        "entit",        // "bull (mature)"
        "ork'oiyotap",  // "calf"
        "enkejuk",      // "milk"
        "oret",         // "blood"
        "enk'ariak",    // "fat"
        "entulelei",    // "beadwork"
        "shuka",        // "cloth/blanket"
    )

    // ────────────────────── Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Maasai speakers simplify Swahili patterns
        "aka" to "taka",
        "oka" to "toka",
        "iga" to "piga",

        // Vowel lengthening from Maa prosody
        "saafi" to "safi",
    )

    // ────────────────────── Maasai Business Vocabulary ──────────────────────

    private val maasaiBusinessTerms = mapOf(
        // ── Pastoral products ──
        "enkejuk" to "milk",
        "oret" to "blood",
        "enk'ariak" to "fat",
        "enkashatai" to "butter",
        "olmarei" to "honey",
        "enk'ibishon" to "traditional_beer",

        // ── Livestock ──
        "enk'ee" to "cattle",
        "ore" to "bull",
        "entit" to "mature_bull",
        "ork'oiyotap" to "calf",
        "enk'ositon" to "heifer",
        "enkejuuk" to "goat",
        "orkejuuk" to "billy_goat",
        "enkeja" to "sheep",
        "enk'arash" to "donkey",

        // ── Handicrafts & trade ──
        "entulelei" to "beadwork",
        "shuka" to "blanket",
        "enk'ariwa" to "bracelet",
        "olariatai" to "necklace",

        // ── Measurements ──
        "debe" to "debe",
        "gunia" to "sack",

        // ── Currency ──
        "mbao" to "twenty_shillings",
        "jeuri" to "fifty_shillings",
        "ngiri" to "thousand_shillings",
        "thao" to "thousand",

        // ── Market terms ──
        "soko" to "market",
        "duka" to "shop",
        "enkang'" to "homestead",
        "enkaji" to "house",
        "olchani" to "chief",
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

        val maasaiFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                maasaiMarkers.contains(clean) -> maasaiFound.add(clean)
                isSwahiliWord(clean) -> swahiliFound.add(clean)
                isMaasaiBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val maasaiRatio = maasaiFound.size.toFloat() / totalWords
        val hasCodeSwitching = maasaiFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (maasaiRatio > 0.4f) "mas" else "sw",
            dholuoWords = maasaiFound,
            swahiliWords = swahiliFound,
            confidence = if (hasCodeSwitching) 0.8f else 0.6f
        )
    }

    // ────────────────────── Normalization ──────────────────────

    fun normalize(text: String): String {
        var normalized = text
        for ((maasai, standard) in pronunciationVariations) {
            if (maasai != standard) {
                normalized = normalized.replace(
                    Regex("\\b$maasai\\b", RegexOption.IGNORE_CASE),
                    standard
                )
            }
        }
        return normalized
    }

    fun translateToStandard(term: String): String? {
        val lower = term.lowercase().trim()

        maasaiBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val maasaiToSwahili = mapOf(
            "sopa" to "habari",
            "yeyo" to "nzuri",
            "iko" to "sawa",
            "ai" to "hapana",
            "aiye" to "ndiyo",
            "keju" to "asante",
            "ashe" to "asante",
            "oleng" to "mtu",
            "enkare" to "maji",
            "olari" to "mvua",
            "enk'ee" to "ng'ombe",
            "enkejuk" to "maziwa",
            "enkang'" to "kijiji",
            "enkaji" to "nyumba",
        )
        maasaiToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var maasaiScore = 0

        for (term in maasaiBusinessTerms.keys) {
            if (lower.contains(term)) maasaiScore += 2
        }
        for (marker in maasaiMarkers) {
            if (Regex("\\b$marker\\b").containsMatchIn(lower)) maasaiScore += 3
        }

        return if (maasaiScore > 5) DialectRegion.MAASAI else DialectRegion.STANDARD
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

    private fun isMaasaiBusinessTerm(word: String): Boolean {
        return maasaiBusinessTerms.containsKey(word) ||
                maasaiBusinessTerms.values.any { it == word }
    }
}
