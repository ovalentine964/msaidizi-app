package com.msaidizi.app.core.dialect

import com.msaidizi.app.core.model.DialectRegion
import timber.log.Timber

/**
 * Sheng dialect adapter for Kenyan urban slang.
 *
 * Sheng is a Swahili-based cant/slang spoken by ~20 million people
 * in Nairobi and other Kenyan cities. It blends Swahili, English,
 * Dholuo, Kikuyu, and other Kenyan languages.
 *
 * Key features:
 * - Rapid vocabulary evolution (new slang terms weekly)
 * - Heavy code-switching between Swahili, English, and local languages
 * - Youth culture and informal economy vocabulary
 * - Matatu (minibus) culture terminology
 * - Digital/mobile money vocabulary (M-Pesa)
 * - Food and street vendor terminology
 *
 * Designed for <1ms latency — pure code, no ML models.
 */
object ShengDialectAdapter {
    companion object {
        private val MARKERS = mapOf(
            "sasa" to Regex("\\bsasa\\b"),
            "poa" to Regex("\\bpoa\\b"),
            "fiti" to Regex("\\bfiti\\b"),
            "aje" to Regex("\\baje\\b"),
            "niaje" to Regex("\\bniaje\\b"),
            "mambo" to Regex("\\bmambo\\b"),
            "vipi" to Regex("\\bvipi\\b"),
            "wazi" to Regex("\\bwazi\\b"),
            "safi" to Regex("\\bsafi\\b"),
            "freshi" to Regex("\\bfreshi\\b"),
            "ndege" to Regex("\\bndege\\b"),
            "blaze" to Regex("\\bblaze\\b"),
            "mbogi" to Regex("\\bmbogi\\b"),
            "sonko" to Regex("\\bsonko\\b"),
            "mheshimiwa" to Regex("\\bmheshimiwa\\b"),
            "chapaa" to Regex("\\bchapaa\\b"),
            "munde" to Regex("\\bmunde\\b"),
            "pesa" to Regex("\\bpesa\\b"),
            "bao" to Regex("\\bbao\\b"),
            "ngiri" to Regex("\\bngiri\\b"),
            "thao" to Regex("\\bthao\\b"),
            "finje" to Regex("\\bfinje\\b"),
            "jeuri" to Regex("\\bjeuri\\b"),
            "kumi" to Regex("\\bkumi\\b"),
            "jabaa" to Regex("\\bjabaa\\b"),
            "mwaks" to Regex("\\bmwaks\\b"),
            "kanyang'u" to Regex("\\bkanyang'u\\b"),
            "kudevaa" to Regex("\\bkudevaa\\b"),
            "kukasirika" to Regex("\\bkukasirika\\b"),
            "kuja" to Regex("\\bkuja\\b"),
            "kuja_kuja" to Regex("\\bkuja_kuja\\b"),
            "kupiga" to Regex("\\bkupiga\\b"),
            "kuchapa" to Regex("\\bkuchapa\\b"),
            "kublaze" to Regex("\\bkublaze\\b"),
            "kutoka" to Regex("\\bkutoka\\b"),
            "kukula" to Regex("\\bkukula\\b"),
            "kunywa" to Regex("\\bkunywa\\b"),
            "kuvuta" to Regex("\\bkuvuta\\b"),
            "kusoma" to Regex("\\bkusoma\\b"),
            "kuskizaa" to Regex("\\bkuskizaa\\b"),
            "kuwatch" to Regex("\\bkuwatch\\b"),
            "msee" to Regex("\\bmsee\\b"),
            "msichana" to Regex("\\bmsichana\\b"),
            "kijana" to Regex("\\bkijana\\b"),
            "mubaba" to Regex("\\bmubaba\\b"),
            "mumama" to Regex("\\bmumama\\b"),
            "dame" to Regex("\\bdame\\b"),
            "boyi" to Regex("\\bboyi\\b"),
            "mbogi" to Regex("\\bmbogi\\b"),
            "kamuti" to Regex("\\bkamuti\\b"),
            "ndai" to Regex("\\bndai\\b"),
            "mzae" to Regex("\\bmzae\\b"),
            "base" to Regex("\\bbase\\b"),
            "ghetto" to Regex("\\bghetto\\b"),
            "mtaa" to Regex("\\bmtaa\\b"),
            "kanairo" to Regex("\\bkanairo\\b"),
            "ushago" to Regex("\\bushago\\b"),
            "mat" to Regex("\\bmat\\b"),
            "nduthi" to Regex("\\bnduthi\\b"),
            "gari" to Regex("\\bgari\\b"),
            "ndai" to Regex("\\bndai\\b")
        )
        private val PRONUNCIATION_REGEXES = mapOf(
            "chapaa" to Regex("\\bchapaa\\b", RegexOption.IGNORE_CASE),
            "munde" to Regex("\\bmunde\\b", RegexOption.IGNORE_CASE),
            "ndege" to Regex("\\bndege\\b", RegexOption.IGNORE_CASE),
            "mbogi" to Regex("\\bmbogi\\b", RegexOption.IGNORE_CASE),
            "sonko" to Regex("\\bsonko\\b", RegexOption.IGNORE_CASE),
            "kuchapa" to Regex("\\bkuchapa\\b", RegexOption.IGNORE_CASE),
            "kublaze" to Regex("\\bkublaze\\b", RegexOption.IGNORE_CASE),
            "kuwatch" to Regex("\\bkuwatch\\b", RegexOption.IGNORE_CASE),
            "nkt" to Regex("\\bnkt\\b", RegexOption.IGNORE_CASE),
            "wah" to Regex("\\bwah\\b", RegexOption.IGNORE_CASE)
        )
    }


    private const val TAG = "ShengDialect"

    // ────────────────────── Sheng Code-Switching Markers ──────────────────────

    private val shengMarkers = setOf(
        // Greetings & social
        "sasa",         // "what's up"
        "poa",          // "cool/good"
        "fiti",         // "fine/good"
        "aje",          // "how"
        "niaje",        // "how are you"
        "mambo",        // "things/what's up"
        "vipi",         // "how"
        "wazi",         // "cool/open"
        "safi",         // "clean/good"
        "freshi",       // "fresh/cool"
        "ndege",        // "cool" (lit: bird/plane)
        "blaze",        // "cool/awesome"
        "mbogi",        // "crew/gang"
        "sonko",        // "rich person"
        "mheshimiwa",  // "honorable/rich"

        // Money & business
        "chapaa",       // "money"
        "munde",        // "money"
        "pesa",         // "money"
        "bao",          // "money (20 bob)"
        "ngiri",        // "1000 bob"
        "thao",         // "1000"
        "finje",        // "500"
        "jeuri",        // "50"
        "kumi",         // "10"
        "jabaa",        // "free/for nothing"
        "mwaks",        // "no money"
        "kanyang'u",   // "broke"
        "kudevaa",     // "broke"
        "kukasirika",  // "to be broke"

        // Actions & verbs
        "kuja",         // "come"
        "kuja_kuja",    // "coming"
        "kupiga",       // "to do/hit"
        "kuchapa",      // "to work hard"
        "kublaze",      // "to chill"
        "kutoka",       // "to leave"
        "kukula",       // "to eat"
        "kunywa",       // "to drink"
        "kuvuta",       // "to pull/smoke"
        "kusoma",       // "to read/study"
        "kuskizaa",     // "to listen"
        "kuwatch",      // "to watch"

        // People & roles
        "msee",         // "person/guy"
        "msichana",     // "girl"
        "kijana",       // "youth"
        "mubaba",       // "older man"
        "mumama",       // "older woman"
        "dame",         // "girl/woman"
        "boyi",         // "boy"
        "mbogi",        // "group/crew"
        "kamuti",       // "friend"
        "ndai",         // "friend"
        "mzae",         // "friend/old person"

        // Places
        "base",         // "home/place"
        "ghetto",       // "neighborhood"
        "mtaa",         // "neighborhood/street"
        "kanairo",      // "Nairobi"
        "ushago",       // "upcountry/rural",

        // Transport
        "mat",          // "matatu (minibus)"
        "nduthi",       // "motorcycle"
        "gari",         // "car",
        "ndai",         // "car",
    )

    // ────────────────────── Sheng Pronunciation Variations ──────────────────────

    private val pronunciationVariations = mapOf(
        // Sheng truncation patterns
        "chapaa" to "pesa",
        "munde" to "pesa",
        "ndege" to "nzuri",
        "mbogi" to "kundi",
        "sonko" to "tajiri",

        // English-Swahili blending
        "kuchapa" to "kufanya_kazi",
        "kublaze" to "kupumzika",
        "kuwatch" to "kutazama",

        // Common abbreviations
        "nkt" to "nkt",
        "wah" to "wah",
    )

    // ────────────────────── Sheng Business Vocabulary ──────────────────────

    private val shengBusinessTerms = mapOf(
        // ── Money & currency ──
        "chapaa" to "money",
        "munde" to "money",
        "bao" to "twenty_shillings",
        "jeuri" to "fifty_shillings",
        "kumi" to "ten_shillings",
        "ngiri" to "thousand_shillings",
        "thao" to "thousand",
        "finje" to "five_hundred_shillings",
        "jabaa" to "free",
        "mwaks" to "no_money",
        "kanyang'u" to "broke",
        "kudevaa" to "broke",

        // ── Food & street food ──
        "choma" to "roasted_meat",
        "mutura" to "blood_sausage",
        "smocha" to "smokie_chapati",
        "mayai_boiled" to "boiled_egg",
        "rolex" to "chapati_egg_wrap",
        "kachumbari" to "tomato_onion_salad",
        "ndazi" to "mandazi_donut",
        "uji" to "porridge",
        "chai" to "tea",
        "mtura" to "street_sausage",
        "samosa" to "samosa",

        // ── Transport ──
        "mat" to "matatu",
        "nduthi" to "motorcycle",
        "ndai" to "car",
        "gari" to "car",
        "boda" to "motorcycle_taxi",

        // ── M-Pesa & digital ──
        "mpesa" to "mobile_money",
        "kutoa" to "withdraw",
        "kutuma" to "send",
        "kudeposit" to "deposit",
        "stima" to "electricity",
        "data" to "internet_data",
        "bundles" to "internet_bundles",

        // ── Housing ──
        "base" to "home",
        "ghetto" to "neighborhood",
        "ploti" to "plot/house",
        "single_room" to "single_room",
        "bedsitter" to "bedsitter",

        // ── Market terms ──
        "duka" to "shop",
        "kibanda" to "stall",
        "soko" to "market",
        "mama_mboga" to "vegetable_seller",
        "mzae" to "shopkeeper",
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

        val shengFound = mutableListOf<String>()
        val swahiliFound = mutableListOf<String>()

        for (word in words) {
            val clean = word.trim('\'', '"', '.', ',', '!', '?')
            when {
                shengMarkers.contains(clean) -> shengFound.add(clean)
                DialectUtils.isSwahiliWord(clean) -> swahiliFound.add(clean)
                isShengBusinessTerm(clean) -> swahiliFound.add(clean)
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val shengRatio = shengFound.size.toFloat() / totalWords
        val hasCodeSwitching = shengFound.size >= 1 && swahiliFound.size >= 1

        return CodeSwitchResult(
            hasCodeSwitching = hasCodeSwitching,
            primaryLanguage = if (shengRatio > 0.4f) "sheng" else "sw",
            dialectWords = shengFound,
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

        shengBusinessTerms[lower]?.let { return it }
        pronunciationVariations[lower]?.let { return it }

        val shengToSwahili = mapOf(
            "sasa" to "habari",
            "poa" to "nzuri",
            "fiti" to "nzuri",
            "niaje" to "habari",
            "wazi" to "sawa",
            "safi" to "nzuri",
            "chapaa" to "pesa",
            "munde" to "pesa",
            "msee" to "mtu",
            "dame" to "msichana",
            "mbogi" to "kundi",
            "base" to "nyumba",
            "ghetto" to "mtaa",
            "kanairo" to "nairobi",
            "mat" to "matatu",
            "nduthi" to "pikipiki",
            "choma" to "nyama_choma",
            "jabaa" to "bure",
            "kuchapa" to "kufanya_kazi",
        )
        shengToSwahili[lower]?.let { return it }

        return null
    }

    fun detectRegion(text: String): DialectRegion {
        val lower = text.lowercase()
        var shengScore = 0

        for (term in shengBusinessTerms.keys) {
            if (lower.contains(term)) shengScore += 2
        }
        for ((_, regex) in MARKERS) {
            if (regex.containsMatchIn(lower)) shengScore += 3
        }

        return if (shengScore > 5) DialectRegion.SHENG else DialectRegion.STANDARD
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


    private fun isShengBusinessTerm(word: String): Boolean {
        return shengBusinessTerms.containsKey(word) ||
                shengBusinessTerms.values.any { it == word }
    }
}
