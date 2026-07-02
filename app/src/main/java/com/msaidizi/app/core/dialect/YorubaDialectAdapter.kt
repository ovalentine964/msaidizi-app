package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Yoruba dialect adapter for Southwestern Nigeria.
 *
 * Yoruba is a Niger-Congo language spoken by ~45 million people
 * in Nigeria, Benin, and Togo.
 *
 * Key features:
 * - 3 lexical tones: High (H), Mid (M), Low (L) — collapsed by ASR
 * - Labial-velar stops /kp/, /gb/ — no European equivalent
 * - Rich market trade vocabulary (oja, ọja, alájà)
 * - Proverb-heavy communication in business contexts
 * - Open-air market (ọja) economy central to Yoruba life
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object YorubaDialectAdapter {

    private const val TAG = "YorubaDialect"

    // ────────────────────── Yoruba Code-Switching Markers ──────────────────────

    private val yorubaMarkers = setOf(
        // Discourse markers
        "ni",           // "is/are" — copula
        "si",           // "and/also"
        "ati",          // "and"
        "tabi",         // "or"
        "ṣugbọn",      // "but"
        "bi",           // "if/like"
        "nitori",       // "because"
        "nigbati",      // "when"
        "bẹẹni",       // "yes"
        "rara",         // "no"
        "jọwọ",        // "please"
        "e_se",         // "thank you"
        "e_ku_ọjọ",    // "greeting of the day"
        "e_ku_ọwọ",    // "greeting (evening)"
        "a_dupe",       // "we give thanks"

        // Common nouns
        "eniyan",       // "person"
        "awọn_eniyan",  // "people"
        "ile",          // "house"
        "oja",          // "market"
        "owo",          // "money"
        "iṣẹ",         // "work"
        "ounjẹ",       // "food"
        "omi",          // "water"
        "oṣu",          // "month"
        "ọdun",         // "year"
        "ọjọ",          // "day"
        "igba",         // "time"
    )

    // ────────────────────── Yoruba Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Labial-velar stops → individual consonants (ASR confusion)
        "kp" to "kp",   // preserve if ASR got it right
        "gb" to "gb",   // preserve if ASR got it right

        // Tone collapse: ASR drops tone markers
        "ọ" to "o",     // open-mid back → close-mid
        "ẹ" to "e",     // open-mid front → close-mid
        "ṣ" to "sh",    // postalveolar fricative
        "ṣ" to "s",     // alternative mapping

        // Subjunctive/conditional markers
        "àti" to "ati",
        "ní" to "ni",
    )

    // ────────────────────── Yoruba Business Vocabulary ──────────────────────

    private val yorubaBusinessTerms = mapOf(
        // ── Market & trade terms ──
        "oja" to "market",
        "alagẹẹrẹ" to "broker",
        "alájà" to "trader",
        "ọja" to "goods",
        "owo" to "money",
        "iṣẹ" to "work",
        "ojú_ọgbọn" to "business_strategy",

        // ── Food & agricultural products ──
        "iyan" to "pounded_yam",
        "ẹba" to "eba_cassava",
        "ẹgusi" to "melon_seed",
        "iru" to "locust_bean",
        "ogiri" to "fermented_locust_bean",
        "dawadawa" to "locust_bean_seasoning",
        "ọgẹdẹ" to "banana",
        "ọsẹ" to "soap",
        "ata" to "pepper",
        "tatashe" to "bell_pepper",
        "rodo" to "hot_pepper",
        "gbọngọn" to "palm_fruit",
        "epo" to "palm_oil",
        "ori" to "shea_butter",
        "iyu" to "cassava",
        "agbado" to "corn",
        "ewa" to "beans",
        "ayara" to "groundnut",

        // ── Clothing & textiles ──
        "aṣọ" to "cloth",
        "kente" to "woven_cloth",
        "adire" to "tie-dye",
        "fila" to "cap",
        "bùbá" to "top/blouse",
        "iro" to "wrapper",
        "gèlè" to "headwrap",

        // ── Currency & measurements ──
        "naira" to "naira",
        "kobo" to "kobo",
        "owó_ilé" to "house_money",
        "debe" to "debe",
        "gunia" to "sack",

        // ── Market roles ──
        "iyalẹja" to "market_mother",
        "babalawo" to "traditional_healer",
        "alága" to "chairperson",
        "onígun" to "stall_owner",
        "aládé" to "crown_owner",

        // ── Livestock ──
        "malu" to "cattle",
        "ewure" to "goat",
        "agutan" to "sheep",
        "adie" to "chicken",
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

        val yorubaFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                yorubaMarkers.contains(clean) -> yorubaFound.add(clean)
                isSwahiliWord(clean) -> swahiliFound.add(clean)
                isYorubaBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val yorubaRatio = yorubaFound.size.toFloat() / totalWords
        val hasCodeSwitching = yorubaFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (yorubaRatio > 0.4f) "yo" else "sw",
            dholuoWords = yorubaFound,
            swahiliWords = swahiliFound,
            confidence = if (hasCodeSwitching) 0.8f else 0.6f
        )
    }

    // ────────────────────── Normalization ──────────────────────

    fun normalize(text: String): String {
        var normalized = text
        for ((yoruba, standard) in pronunciationVariations) {
            if (yoruba != standard) {
                normalized = normalized.replace(yoruba, standard)
            }
        }
        return normalized
    }

    fun translateToStandard(term: String): String? {
        val lower = term.lowercase().trim()

        yorubaBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val yorubaToSwahili = mapOf(
            "bẹẹni" to "ndiyo",
            "rara" to "hapana",
            "jọwọ" to "tafadhali",
            "e_se" to "asante",
            "eniyan" to "mtu",
            "awọn_eniyan" to "watu",
            "ile" to "nyumba",
            "oja" to "soko",
            "owo" to "pesa",
            "iṣẹ" to "kazi",
            "ounjẹ" to "chakula",
            "omi" to "maji",
            "malu" to "ng'ombe",
            "ewure" to "mbuzi",
            "adie" to "kuku",
        )
        yorubaToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var yorubaScore = 0

        for (term in yorubaBusinessTerms.keys) {
            if (lower.contains(term)) yorubaScore += 2
        }
        for (marker in yorubaMarkers) {
            if (Regex("\\b$marker\\b").containsMatchIn(lower)) yorubaScore += 3
        }

        return if (yorubaScore > 5) DialectRegion.YORUBA else DialectRegion.STANDARD
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

    private fun isYorubaBusinessTerm(word: String): Boolean {
        return yorubaBusinessTerms.containsKey(word) ||
                yorubaBusinessTerms.values.any { it == word }
    }
}
