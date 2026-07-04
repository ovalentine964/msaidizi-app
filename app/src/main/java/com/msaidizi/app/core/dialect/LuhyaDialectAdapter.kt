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
 * Luhya dialect adapter for Western Kenya.
 *
 * Luhya (Oluluyia) is a cluster of Bantu languages spoken by ~6 million people
 * in Western Kenya (Kakamega, Bungoma, Busia, Vihiga counties).
 *
 * Key features:
 * - Multiple sub-dialects (Bukusu, Maragoli, Tiriki, etc.) with shared vocabulary
 * - Bantu phonology with prenasalized stops
 * - Code-switching with Swahili and English
 * - Rich agricultural vocabulary (sugarcane, maize, beans)
 * - Distinctive greeting protocols
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object LuhyaDialectAdapter {
    private val MARKERS = mapOf(
        "na" to Regex("\\bna\\b"),
        "ni" to Regex("\\bni\\b"),
        "kha" to Regex("\\bkha\\b"),
        "inga" to Regex("\\binga\\b"),
        "osi" to Regex("\\bosi\\b"),
        "khutsia" to Regex("\\bkhutsia\\b"),
        "mulembe" to Regex("\\bmulembe\\b"),
        "mushiambo" to Regex("\\bmushiambo\\b"),
        "khukhala" to Regex("\\bkhukhala\\b"),
        "khulola" to Regex("\\bkhulola\\b"),
        "khuseva" to Regex("\\bkhuseva\\b"),
        "khukula" to Regex("\\bkhukula\\b"),
        "khukhunda" to Regex("\\bkhukhunda\\b"),
        "simba" to Regex("\\bsimba\\b"),
        "mukulu" to Regex("\\bmukulu\\b"),
        "mumama" to Regex("\\bmumama\\b"),
        "mukhulu" to Regex("\\bmukhulu\\b"),
        "mukasa" to Regex("\\bmukasa\\b"),
        "omukhongo" to Regex("\\bomukhongo\\b"),
        "omusinde" to Regex("\\bomusinde\\b"),
        "omwikale" to Regex("\\bomwikale\\b"),
        "omundu" to Regex("\\bomundu\\b"),
        "abantu" to Regex("\\babantu\\b"),
        "enyumba" to Regex("\\benyumba\\b"),
        "omugunda" to Regex("\\bomugunda\\b"),
        "omukhuyu" to Regex("\\bomukhuyu\\b"),
        "amatsi" to Regex("\\bamatsi\\b"),
        "endekho" to Regex("\\bendekho\\b"),
        "omukhono" to Regex("\\bomukhono\\b"),
        "eshiwi" to Regex("\\beshiwi\\b"),
        "omukhwe" to Regex("\\bomukhwe\\b")
    )
    private val PRONUNCIATION_REGEXES = mapOf(
        "aka" to Regex("\\baka\\b", RegexOption.IGNORE_CASE),
        "oka" to Regex("\\boka\\b", RegexOption.IGNORE_CASE),
        "iga" to Regex("\\biga\\b", RegexOption.IGNORE_CASE),
        "saafi" to Regex("\\bsaafi\\b", RegexOption.IGNORE_CASE),
        "twaa" to Regex("\\btwaa\\b", RegexOption.IGNORE_CASE),
        "mboga" to Regex("\\bmboga\\b", RegexOption.IGNORE_CASE),
        "ng'ombe" to Regex("\\bng'ombe\\b", RegexOption.IGNORE_CASE)
    )


    private const val TAG = "LuhyaDialect"

    // ────────────────────── Luhya Code-Switching Markers ──────────────────────

    private val luhyaMarkers = setOf(
        // Discourse markers
        "na",           // "and/with"
        "ni",           // "is/are"
        "kha",          // "not"
        "inga",         // "if/when"
        "osi",          // "all"
        "khutsia",      // "sit down"
        "mulembe",      // "peace/greeting"
        "mushiambo",    // "greeting response"
        "khukhala",     // "to sit"
        "khulola",      // "to see"
        "khuseva",      // "to wash"
        "khukula",      // "to dig"
        "khukhunda",    // "to help"
        "simba",        // "lion/strong"
        "mukulu",       // "elder/big"
        "mumama",       // "mother"
        "mukhulu",      // "grandmother"
        "mukasa",       // "born during harvest"
        "omukhongo",    // "messenger"
        "omusinde",     // "young man"
        "omwikale",     // "neighbor"

        // Common nouns
        "omundu",       // "person"
        "abantu",       // "people"
        "enyumba",      // "house"
        "omugunda",     // "farm"
        "omukhuyu",     // "fig tree"
        "amatsi",       // "water"
        "endekho",      // "place"
        "omukhono",     // "hand/work"
        "eshiwi",       // "word"
        "omukhwe",      // "father-in-law"
    )

    // ────────────────────── Luhya Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Bantu consonant cluster simplification
        "aka" to "taka",
        "oka" to "toka",
        "iga" to "piga",

        // Vowel patterns
        "saafi" to "safi",
        "twaa" to "twa",

        // Nasal assimilation
        "mboga" to "mboga",
        "ng'ombe" to "ng'ombe",
    )

    // ────────────────────── Luhya Business Vocabulary ──────────────────────

    private val luhyaBusinessTerms = mapOf(
        // ── Local foods & products ──
        "omukimo" to "mashed_dish",
        "amakunde" to "beans",
        "omusonga" to "sugarcane",
        "obusuma" to "ugali",
        "omutsatsa" to "traditional_greens",
        "endimi" to "groundnuts",
        "ebinyenya" to "tomatoes",
        "ebibisya" to "pumpkin",
        "amashene" to "mushrooms",

        // ── Agricultural products ──
        "omugunda" to "farm",
        "omukhuyu" to "fig_tree",
        "omukhono" to "work",
        "amatsi" to "water",
        "ebibinga" to "bananas",
        "omukhaka" to "avocado",

        // ── Measurements ──
        "debe" to "debe",
        "gunia" to "sack",
        "fundo" to "bundle",
        "mfuko" to "bag",

        // ── Currency ──
        "mbao" to "twenty_shillings",
        "jeuri" to "fifty_shillings",
        "ngiri" to "thousand_shillings",
        "thao" to "thousand",

        // ── Market terms ──
        "soko" to "market",
        "duka" to "shop",
        "kibanda" to "stall",
        "boda_boda" to "motorcycle_taxi",
        "esirika" to "market_square",

        // ── Livestock ──
        "eng'ombe" to "cattle",
        "embuli" to "goat",
        "enkuku" to "chicken",
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

        val luhyaFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                luhyaMarkers.contains(clean) -> luhyaFound.add(clean)
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isLuhyaBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val luhyaRatio = luhyaFound.size.toFloat() / totalWords
        val hasCodeSwitching = luhyaFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (luhyaRatio > 0.4f) "luy" else "sw",
            dialectWords = luhyaFound,
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

        luhyaBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val luhyaToSwahili = mapOf(
            "mulembe" to "amani",
            "mushiambo" to "habari",
            "omundu" to "mtu",
            "abantu" to "watu",
            "enyumba" to "nyumba",
            "omugunda" to "shamba",
            "omukhono" to "kazi",
            "amatsi" to "maji",
            "eshiwi" to "neno",
            "kha" to "si",
            "osi" to "zote",
            "obusuma" to "ugali",
            "amakunde" to "maharagwe",
        )
        luhyaToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var luhyaScore = 0

        for (term in luhyaBusinessTerms.keys) {
            if (lower.contains(term)) luhyaScore += 2
        }
        for ((_, regex) in MARKERS) {
            if (regex.containsMatchIn(lower)) luhyaScore += 3
        }

        return if (luhyaScore > 5) DialectRegion.LUHYA else DialectRegion.STANDARD
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


    private fun isLuhyaBusinessTerm(word: String): Boolean {
        return luhyaBusinessTerms.containsKey(word) ||
                luhyaBusinessTerms.values.any { it == word }
    }
}
