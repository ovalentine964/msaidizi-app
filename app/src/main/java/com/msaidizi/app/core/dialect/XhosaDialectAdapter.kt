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
    companion object {
        private val MARKERS = mapOf(
            "kwaye" to Regex("\\bkwaye\\b"),
            "kodwa" to Regex("\\bkodwa\\b"),
            "okanye" to Regex("\\bokanye\\b"),
            "ukuba" to Regex("\\bukuba\\b"),
            "kuba" to Regex("\\bkuba\\b"),
            "xa" to Regex("\\bxa\\b"),
            "esi" to Regex("\\besi\\b"),
            "eyo" to Regex("\\beyo\\b"),
            "namhlanje" to Regex("\\bnamhlanje\\b"),
            "ngomso" to Regex("\\bngomso\\b"),
            "izolo" to Regex("\\bizolo\\b"),
            "ndifuna" to Regex("\\bndifuna\\b"),
            "enkosi" to Regex("\\benkosi\\b"),
            "ndicela" to Regex("\\bndicela\\b"),
            "ewe" to Regex("\\bewe\\b"),
            "hayi" to Regex("\\bhayi\\b"),
            "molo" to Regex("\\bmolo\\b"),
            "molweni" to Regex("\\bmolweni\\b"),
            "unjani" to Regex("\\bunjani\\b"),
            "umntu" to Regex("\\bumntu\\b"),
            "abantu" to Regex("\\babantu\\b"),
            "indlu" to Regex("\\bindlu\\b"),
            "imarike" to Regex("\\bimarike\\b"),
            "imali" to Regex("\\bimali\\b"),
            "umsebenzi" to Regex("\\bumsebenzi\\b"),
            "ukutya" to Regex("\\bukutya\\b"),
            "amanzi" to Regex("\\bamanzi\\b"),
            "inyanga" to Regex("\\binyanga\\b"),
            "unyaka" to Regex("\\bunyaka\\b"),
            "usuku" to Regex("\\busuku\\b"),
            "ixesha" to Regex("\\bixesha\\b")
        )
        private val PRONUNCIATION_REGEXES = mapOf(
            "c" to Regex("\\bc\\b", RegexOption.IGNORE_CASE),
            "q" to Regex("\\bq\\b", RegexOption.IGNORE_CASE),
            "x" to Regex("\\bx\\b", RegexOption.IGNORE_CASE),
            "ch" to Regex("\\bch\\b", RegexOption.IGNORE_CASE),
            "qh" to Regex("\\bqh\\b", RegexOption.IGNORE_CASE),
            "xh" to Regex("\\bxh\\b", RegexOption.IGNORE_CASE),
            "gc" to Regex("\\bgc\\b", RegexOption.IGNORE_CASE),
            "gq" to Regex("\\bgq\\b", RegexOption.IGNORE_CASE),
            "gx" to Regex("\\bgx\\b", RegexOption.IGNORE_CASE),
            "ngc" to Regex("\\bngc\\b", RegexOption.IGNORE_CASE),
            "ngq" to Regex("\\bngq\\b", RegexOption.IGNORE_CASE),
            "ngx" to Regex("\\bngx\\b", RegexOption.IGNORE_CASE),
            "bh" to Regex("\\bbh\\b", RegexOption.IGNORE_CASE),
            "dh" to Regex("\\bdh\\b", RegexOption.IGNORE_CASE)
        )
    }


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
                dialectWords = emptyList(),
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
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isXhosaBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val xhosaRatio = xhosaFound.size.toFloat() / totalWords
        val hasCodeSwitching = xhosaFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (xhosaRatio > 0.4f) "xh" else "sw",
            dialectWords = xhosaFound,
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
        for ((_, regex) in MARKERS) {
            if (regex.containsMatchIn(lower)) xhosaScore += 3
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


    private fun isXhosaBusinessTerm(word: String): Boolean {
        return xhosaBusinessTerms.containsKey(word) ||
                xhosaBusinessTerms.values.any { it == word }
    }
}
