package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Xhosa dialect adapter for South Africa.
 *
 * Xhosa (isiXhosa) is a Bantu language spoken by ~8 million people
 * in South Africa (Eastern Cape, Western Cape).
 *
 * Key features:
 * - Most click-rich language: 18 click consonants (3 basic + combinations)
 * - 3 click types: dental (c), alveolar (q), lateral (x)
 * - Each click has 5 variants: plain, aspirated, nasalized, voiced, breathy
 * - Tonal language with 2 tones
 * - Pastoral and trading vocabulary
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object XhosaDialectAdapter {

    private const val TAG = "XhosaDialect"

    // ────────────────────── Xhosa Code-Switching Markers ──────────────────────

    private val xhosaMarkers = setOf(
        // Discourse markers
        "kwaye",        // "and/also"
        "kodwa",        // "but"
        "okanye",       // "or"
        "ukuba",        // "if/that"
        "kuba",         // "because"
        "xa",           // "when/if"
        "esi",          // "this"
        "eyo",          // "that"
        "namhlanje",    // "today"
        "ngomso",       // "tomorrow"
        "izolo",        // "yesterday"
        "ndifuna",      // "I want"
        "enkosi",       // "thank you"
        "ndicela",      // "please"
        "ewe",          // "yes"
        "hayi",         // "no"
        "molo",         // "hello (to one)"
        "molweni",      // "hello (to many)"
        "unjani",       // "how are you"

        // Common nouns
        "umntu",        // "person"
        "abantu",       // "people"
        "indlu",        // "house"
        "imarike",      // "market"
        "imali",        // "money"
        "umsebenzi",    // "work"
        "ukutya",       // "food"
        "amanzi",       // "water"
        "inyanga",      // "month"
        "unyaka",       // "year"
        "usuku",        // "day"
        "ixesha",       // "time"
    )

    // ────────────────────── Xhosa Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Click consonants → ASR approximations
        "c" to "c",     // dental click
        "q" to "q",     // alveolar click
        "x" to "x",     // lateral click

        // Click combinations
        "ch" to "ch",   // aspirated dental click
        "qh" to "qh",   // aspirated alveolar click
        "xh" to "xh",   // aspirated lateral click
        "gc" to "gc",   // voiced dental click
        "gq" to "gq",   // voiced alveolar click
        "gx" to "gx",   // voiced lateral click
        "ngc" to "ngc", // nasalized dental click
        "ngq" to "ngq", // nasalized alveolar click
        "ngx" to "ngx", // nasalized lateral click

        // Breatthy voiced
        "bh" to "b",
        "dh" to "d",
    )

    // ────────────────────── Xhosa Business Vocabulary ──────────────────────

    private val xhosaBusinessTerms = mapOf(
        // ── Market & trade terms ──
        "imarike" to "market",
        "umthengisi" to "seller",
        "umthengi" to "buyer",
        "imali" to "money",
        "umsebenzi" to "work",
        "inzuzo" to "profit",
        "ilahleko" to "loss",
        "isikweletu" to "debt",

        // ── Food & agricultural products ──
        "ukutya" to "food",
        "umbona" to "corn",
        "ibhatata" to "sweet_potato",
        "ikhowa" to "mushroom",
        "umhluzi" to "soup",
        "inyama" to "meat",
        "intlanzi" to "fish",
        "ubisi" to "milk",
        "utyisi" to "beans",
        "isophi" to "soap",

        // ── Livestock ──
        "inkomo" to "cattle",
        "ibhokhwe" to "goat",
        "igusha" to "sheep",
        "inkukhu" to "chicken",
        "ihhashi" to "horse",

        // ── Currency & measurements ──
        "rand" to "rand",
        "isent" to "cent",
        "debe" to "debe",
        "isikhwama" to "bag",

        // ── Market roles ──
        "umama_wemarike" to "market_mother",
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

        val xhosaFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                xhosaMarkers.contains(clean) -> xhosaFound.add(clean)
                isSwahiliWord(clean) -> swahiliFound.add(clean)
                isXhosaBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val xhosaRatio = xhosaFound.size.toFloat() / totalWords
        val hasCodeSwitching = xhosaFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (xhosaRatio > 0.4f) "xh" else "sw",
            dholuoWords = xhosaFound,
            swahiliWords = swahiliFound,
            confidence = if (hasCodeSwitching) 0.8f else 0.6f
        )
    }

    // ────────────────────── Normalization ──────────────────────

    fun normalize(text: String): String {
        var normalized = text
        for ((xhosa, standard) in pronunciationVariations) {
            if (xhosa != standard) {
                normalized = normalized.replace(xhosa, standard)
            }
        }
        return normalized
    }

    fun translateToStandard(term: String): String? {
        val lower = term.lowercase().trim()

        xhosaBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val xhosaToSwahili = mapOf(
            "molo" to "habari",
            "molweni" to "habari",
            "enkosi" to "asante",
            "ndicela" to "tafadhali",
            "ewe" to "ndiyo",
            "hayi" to "hapana",
            "umntu" to "mtu",
            "abantu" to "watu",
            "indlu" to "nyumba",
            "imarike" to "soko",
            "imali" to "pesa",
            "umsebenzi" to "kazi",
            "ukutya" to "chakula",
            "amanzi" to "maji",
            "inkomo" to "ng'ombe",
            "ibhokhwe" to "mbuzi",
            "inkukhu" to "kuku",
            "inzuzo" to "faida",
            "isikweletu" to "deni",
        )
        xhosaToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var xhosaScore = 0

        for (term in xhosaBusinessTerms.keys) {
            if (lower.contains(term)) xhosaScore += 2
        }
        for (marker in xhosaMarkers) {
            if (Regex("\\b$marker\\b").containsMatchIn(lower)) xhosaScore += 3
        }

        return if (xhosaScore > 5) DialectRegion.XHOSA else DialectRegion.STANDARD
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

    private fun isXhosaBusinessTerm(word: String): Boolean {
        return xhosaBusinessTerms.containsKey(word) ||
                xhosaBusinessTerms.values.any { it == word }
    }
}
