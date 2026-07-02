package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Hausa dialect adapter for West Africa.
 *
 * Hausa is an Afro-Asiatic language spoken by ~80 million people
 * as a first or second language across Nigeria, Niger, Ghana, Cameroon, and Chad.
 * It is the largest language in Africa by number of speakers.
 *
 * Key features:
 * - Pitch accent (not lexical tone)
 * - Implosive consonants /ɓ/, /ɗ/ — ASR maps to /b/, /d/
 * - Ejective consonants in some dialects
 * - Rich trade vocabulary (Hausa are historically great traders)
 * - Arabic loanwords in business and religious contexts
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object HausaDialectAdapter {
    companion object {
        private val MARKERS = mapOf(
            "da" to Regex("\\bda\\b"),
            "kuma" to Regex("\\bkuma\\b"),
            "amma" to Regex("\\bamma\\b"),
            "ko" to Regex("\\bko\\b"),
            "idã" to Regex("\\bidã\\b"),
            "sabõda" to Regex("\\bsabõda\\b"),
            "lokacin_da" to Regex("\\blokacin_da\\b"),
            "wannan" to Regex("\\bwannan\\b"),
            "wancan" to Regex("\\bwancan\\b"),
            "yau" to Regex("\\byau\\b"),
            "gobe" to Regex("\\bgobe\\b"),
            "jiya" to Regex("\\bjiya\\b"),
            "ina_son" to Regex("\\bina_son\\b"),
            "na_gode" to Regex("\\bna_gode\\b"),
            "don_allah" to Regex("\\bdon_allah\\b"),
            "eh" to Regex("\\beh\\b"),
            "a'a" to Regex("\\ba'a\\b"),
            "sannu" to Regex("\\bsannu\\b"),
            "yaya_kake" to Regex("\\byaya_kake\\b"),
            "yaya_kike" to Regex("\\byaya_kike\\b"),
            "mutum" to Regex("\\bmutum\\b"),
            "mutane" to Regex("\\bmutane\\b"),
            "gida" to Regex("\\bgida\\b"),
            "kasuwa" to Regex("\\bkasuwa\\b"),
            "kudi" to Regex("\\bkudi\\b"),
            "aiki" to Regex("\\baiki\\b"),
            "abinci" to Regex("\\babinci\\b"),
            "ruwa" to Regex("\\bruwa\\b"),
            "wata" to Regex("\\bwata\\b"),
            "shekara" to Regex("\\bshekara\\b"),
            "rana" to Regex("\\brana\\b"),
            "lokaci" to Regex("\\blokaci\\b")
        )
        private val PRONUNCIATION_REGEXES = mapOf(
            "ɓ" to Regex("\\bɓ\\b", RegexOption.IGNORE_CASE),
            "ɗ" to Regex("\\bɗ\\b", RegexOption.IGNORE_CASE),
            "t'" to Regex("\\bt'\\b", RegexOption.IGNORE_CASE),
            "k'" to Regex("\\bk'\\b", RegexOption.IGNORE_CASE),
            "aa" to Regex("\\baa\\b", RegexOption.IGNORE_CASE),
            "ee" to Regex("\\bee\\b", RegexOption.IGNORE_CASE),
            "ii" to Regex("\\bii\\b", RegexOption.IGNORE_CASE),
            "oo" to Regex("\\boo\\b", RegexOption.IGNORE_CASE),
            "uu" to Regex("\\buu\\b", RegexOption.IGNORE_CASE),
            "kw" to Regex("\\bkw\\b", RegexOption.IGNORE_CASE),
            "gw" to Regex("\\bgw\\b", RegexOption.IGNORE_CASE)
        )
    }


    private const val TAG = "HausaDialect"

    // ────────────────────── Hausa Code-Switching Markers ──────────────────────

    private val hausaMarkers = setOf(
        // Discourse markers
        "da",           // "and/with"
        "kuma",         // "also/again"
        "amma",         // "but"
        "ko",           // "or/even"
        "idã",          // "if"
        "sabõda",       // "because"
        "lokacin_da",   // "when"
        "wannan",       // "this"
        "wancan",       // "that"
        "yau",          // "today"
        "gobe",         // "tomorrow"
        "jiya",         // "yesterday"
        "ina_son",      // "I want"
        "na_gode",      // "thank you"
        "don_allah",    // "please"
        "eh",           // "yes"
        "a'a",          // "no"
        "sannu",        // "hello"
        "yaya_kake",    // "how are you (m)"
        "yaya_kike",    // "how are you (f)"

        // Common nouns
        "mutum",        // "person"
        "mutane",       // "people"
        "gida",         // "house"
        "kasuwa",       // "market"
        "kudi",         // "money"
        "aiki",         // "work"
        "abinci",       // "food"
        "ruwa",         // "water"
        "wata",         // "month"
        "shekara",      // "year"
        "rana",         // "day"
        "lokaci",       // "time"
    )

    // ────────────────────── Hausa Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Implosive → stop (ASR drops implosives)
        "ɓ" to "b",
        "ɗ" to "d",

        // Ejective → plain stop
        "t'" to "t",
        "k'" to "k",

        // Long vowels (ASR often collapses)
        "aa" to "a",
        "ee" to "e",
        "ii" to "i",
        "oo" to "o",
        "uu" to "u",

        // Labialization
        "kw" to "kw",
        "gw" to "gw",
    )

    // ────────────────────── Hausa Business Vocabulary ──────────────────────

    private val hausaBusinessTerms = mapOf(
        // ── Market & trade terms ──
        "kasuwa" to "market",
        "mai_kasuwa" to "market_person",
        "dillali" to "broker",
        "mai_sana'a" to "artisan",
        "kudi" to "money",
        "aiki" to "work",
        "riba" to "profit",
        "asara" to "loss",
        "bashin" to "debt",
        "bini" to "credit",

        // ── Food & agricultural products ──
        "abinci" to "food",
        "masara" to "corn",
        "gyada" to "groundnut",
        "wake" to "beans",
        "shinkafa" to "rice",
        "tuwo" to "swallow_food",
        "fura" to "millet_ball",
        "kunu" to "porridge",
        "kilishi" to "dried_meat",
        "suya" to "grilled_meat",
        "daddawa" to "fermented_bean",
        "mai" to "oil",
        "giya" to "beer",
        "ruwa" to "water",

        // ── Textile & crafts ──
        "turmi" to "cloth",
        "babban_riga" to "large_robe",
        "hula" to "cap",
        "yar_gyada" to "trouser",

        // ── Currency & measurements ──
        "naira" to "naira",
        "kobo" to "kobo",
        "debe" to "debe",
        "jaka" to "bag",
        "kuru" to "basket",

        // ── Market roles ──
        "mai_gida" to "landlord",
        "baƙo" to "customer/guest",
        "abokin_kasuwa" to "market_friend",

        // ── Livestock ──
        "shanu" to "cattle",
        "akuya" to "goat",
        "tunkiya" to "sheep",
        "kaza" to "chicken",
        "doki" to "horse",
        "jaƙi" to "donkey",
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

        val hausaFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                hausaMarkers.contains(clean) -> hausaFound.add(clean)
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isHausaBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val hausaRatio = hausaFound.size.toFloat() / totalWords
        val hasCodeSwitching = hausaFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (hausaRatio > 0.4f) "ha" else "sw",
            dialectWords = hausaFound,
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

        hausaBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val hausaToSwahili = mapOf(
            "sannu" to "habari",
            "na_gode" to "asante",
            "don_allah" to "tafadhali",
            "eh" to "ndiyo",
            "a'a" to "hapana",
            "mutum" to "mtu",
            "mutane" to "watu",
            "gida" to "nyumba",
            "kasuwa" to "soko",
            "kudi" to "pesa",
            "aiki" to "kazi",
            "abinci" to "chakula",
            "ruwa" to "maji",
            "riba" to "faida",
            "asara" to "hasara",
            "bashin" to "deni",
            "shanu" to "ng'ombe",
            "akuya" to "mbuzi",
            "kaza" to "kuku",
        )
        hausaToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var hausaScore = 0

        for (term in hausaBusinessTerms.keys) {
            if (lower.contains(term)) hausaScore += 2
        }
        for ((_, regex) in MARKERS) {
            if (regex.containsMatchIn(lower)) hausaScore += 3
        }

        return if (hausaScore > 5) DialectRegion.HAUSA else DialectRegion.STANDARD
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

    private fun isHausaBusinessTerm(word: String): Boolean {
        return hausaBusinessTerms.containsKey(word) ||
                hausaBusinessTerms.values.any { it == word }
    }
}
