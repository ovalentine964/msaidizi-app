package com.msaidizi.app.core

import com.msaidizi.app.core.dialect.SwahiliMarketVocabulary

/**
 * Statistical language detector using character n-gram frequency analysis.
 *
 * Replaces the keyword-based [LanguageDetector] with proper statistical detection
 * inspired by AfroLID's approach. Uses character trigram frequency profiles
 * built from representative text samples for each language.
 *
 * Supported languages:
 * - sw (Swahili/Kiswahili)
 * - en (English)
 * - sheng (Nairobi street slang)
 * - dholuo (Luo)
 * - kikuyu (Gĩkũyũ)
 * - kalenjin (Kalenjin cluster)
 * - luhya (Oluluyia)
 * - so (Somali)
 *
 * Performance target: <50ms per detection (typically <5ms).
 *
 * Algorithm:
 * 1. Extract character trigrams from input text
 * 2. Score trigrams against pre-built language profiles
 * 3. Apply keyword boost for high-confidence markers
 * 4. Normalize scores and determine language + confidence
 * 5. For mixed text, return dominant language with code-switch metadata
 */
object LanguageDetectorV2 {

    // ────────────────────── Language Profiles ──────────────────────
    // Trigram frequency profiles built from representative text.
    // Each profile maps trigram → relative frequency (0.0-1.0).
    // Higher frequency = stronger indicator for that language.

    /**
     * Character trigram profiles for each language.
     * Built from common words, phrases, and sentence patterns.
     */
    private val languageProfiles: Map<String, Map<String, Float>> by lazy {
        buildLanguageProfiles()
    }

    /**
     * High-confidence keyword markers per language.
     * These get a detection boost when found.
     */
    private val keywordMarkers: Map<String, Set<String>> = mapOf(
        "sw" to setOf(
            "na", "ya", "wa", "za", "kwa", "ni", "la", "cha",
            "nime", "sija", "tuta", "wata", "nenda", "kuja", "nina", "tuna",
            "ame", "wame", "mta", "sita", "hata", "huwa",
            "sana", "pia", "lakini", "kama", "au", "bado",
            "leo", "jana", "kesho", "sasa", "baada", "kabla", "wakati",
            "asubuhi", "mchana", "jioni", "usiku",
            "hii", "hiyo", "ile", "hizi", "hizo",
            "nini", "gani", "wapi", "lini", "vipi", "kwanini",
            "hapa", "pale", "ndani", "nje", "juu", "chini",
            "biashara", "bidhaa", "bei", "faida", "hasara", "deni",
            "malipo", "pesa", "shilingi", "maelfu"
        ),
        "en" to setOf(
            "the", "and", "for", "with", "that", "this", "from", "have",
            "has", "had", "was", "were", "been", "being", "will", "would",
            "could", "should", "might", "shall",
            "about", "after", "before", "between", "through", "during",
            "sold", "bought", "paid", "spent", "received", "made",
            "how", "what", "when", "where", "why", "which", "who",
            "much", "many", "more", "most", "some", "any", "every",
            "profit", "loss", "revenue", "expense", "income", "cash",
            "stock", "inventory", "order", "customer", "supplier",
            "payment", "receipt", "balance", "total", "amount"
        ),
        "sheng" to setOf(
            "sasa", "poa", "fiti", "aje", "niaje", "mambo", "safi", "freshi",
            "boss", "mdau", "msee", "mbogi", "genje", "chali", "dem", "msupa",
            "chapaa", "ndege", "bao", "ngiri", "thao", "finje", "jeuri",
            "noma", "kiboko", "gora", "kanyaga", "piga", "chapa",
            "kali", "mbaya", "tamu", "heavy", "soft",
            "mat", "nduthi", "gari", "kanairo", "ushago", "mtaa", "ghetto"
        ),
        "dholuo" to setOf(
            "kendo", "to", "ka", "mano", "gi", "nyiso", "en", "ok",
            "kata", "chon", "nadi", "inyalo", "kia", "ber", "maber",
            "malo", "yawuoyo", "amos", "erokamano", "chuthi", "dhok",
            "ogo", "wuon", "min", "ja", "juak", "tich", "paro",
            "pod", "piny", "piyo", "rech", "uong", "rimo", "are",
            "ng'ato", "moko", "oduol", "ng'wen", "siru", "ot"
        ),
        "kikuyu" to setOf(
            "ni", "no", "ungi", "nake", "nayo", "nio", "tuika", "ri",
            "ngi", "muno", "hingo", "kana", "tiga", "ngu", "ndu",
            "nindu", "mundu", "andu", "nyumba", "mutuuri", "kihi",
            "ng'ombe", "mburi", "mugunda", "muti", "njeri",
            "gika", "muthuri", "wira", "mondeki", "nduma", "mukimo",
            "githeri", "irio", "mutura", "njahi", "kirima", "mukoru", "njohi"
        ),
        "kalenjin" to setOf(
            "amit", "mamit", "kogo", "kogoich", "koitoich", "mising",
            "chamgei", "chengo", "kainet", "kongoi", "murio", "ende",
            "amuno", "kipto", "kipsigis", "tugen", "nandi",
            "kapkoros", "kapchumba", "kaptich", "kapsirwet",
            "murenik", "tuiyotich", "mursik", "kimiet", "kabotet",
            "ng'atuny", "moit"
        ),
        "luhya" to setOf(
            "kha", "inga", "osi", "khutsia", "mulembe", "mushiambo",
            "khukhala", "khulola", "khuseva", "khukula", "khukhunda",
            "simba", "mukulu", "mukhulu", "mukasa", "omukhongo",
            "omusinde", "omwikale", "omundu", "abantu", "enyumba",
            "omugunda", "omukhuyu", "amatsi", "endekho", "omukhono",
            "eshiwi", "omukhwe", "omukimo", "amakunde", "omusonga",
            "obusuma", "omutsatsa", "endimi", "ebinyenya", "ebibisya"
        ),
        "so" to setOf(
            "waa", "oo", "iyo", "ama", "laakiin", "haddii", "maxaa",
            "xagee", "goorma", "sida", "haye", "mahadsanid", "fadlan",
            "waan", "wuu", "way", "waxaa", "qof", "dad", "guri",
            "suuq", "biyo", "caano", "hilib", "bariis", "burr",
            "shaah", "sonkor", "saliid", "dhar", "lacag", "ganacsi",
            "geel", "arig", "lo", "idah", "faras", "dameer"
        )
    )

    /**
     * Character pattern bonuses per language.
     * Trigrams/patterns that are strong indicators.
     */
    private val charPatterns: Map<String, Set<String>> = mapOf(
        "sw" to setOf(
            "ng'", "ny", "sh", "dh", "th", "kh", "gh",
            "mwa", "nza", "ali", "eni", "ika", "uko", "ila",
            "kea", "oea", "watu", "mtu", "kitu", "siku"
        ),
        "en" to setOf(
            "tion", "sion", "ment", "ness", "able", "ible",
            "ing", "ous", "ful", "less", "ally", "ight", "ough"
        ),
        "sheng" to setOf(
            "cha", "nde", "mba", "bog", "nge", "fin", "ngi"
        ),
        "dholuo" to setOf(
            "uon", "ero", "amo", "iny", "uok", "uoy", "uth",
            "inyalo", "okamo", "maber", "erok"
        ),
        "kikuyu" to setOf(
            "ũ", "ĩ", "ũĩ", "ũndũ", "ũmba", "ũgũ", "ũrĩ"
        ),
        "kalenjin" to setOf(
            "amit", "oich", "eit", "ung", "ket", "osik", "apt"
        ),
        "luhya" to setOf(
            "omu", "abu", "enu", "end", "khuk", "esh", "amak"
        ),
        "so" to setOf(
            "aad", "aan", "eeb", "ood", "uul", "iis", "oodh"
        )
    )

    // ────────────────────── Detection API ──────────────────────

    /**
     * Detect the primary language of input text.
     *
     * @param text Input text to analyze
     * @return Language code: "sw", "en", "sheng", "dholuo", "kikuyu", "kalenjin", "luhya", "so", or "mixed"
     */
    fun detect(text: String): String {
        val result = detectWithConfidence(text)
        return result.language
    }

    /**
     * Detect language with confidence score.
     *
     * @param text Input text
     * @return LanguageResult with language code, confidence, and per-language scores
     */
    fun detectWithConfidence(text: String): LanguageResult {
        if (text.isBlank()) return LanguageResult("sw", 0.5f, emptyMap())

        val normalized = text.lowercase().trim()
        val words = normalized.split(Regex("[^\\p{L}']+")).filter { it.length > 1 }

        if (words.isEmpty()) return LanguageResult("sw", 0.5f, emptyMap())

        // Step 1: Trigram scoring
        val trigramScores = scoreByTrigrams(normalized)

        // Step 2: Keyword scoring
        val keywordScores = scoreByKeywords(words)

        // Step 3: Pattern scoring
        val patternScores = scoreByPatterns(normalized)

        // Step 4: Combine scores (weighted)
        val languages = trigramScores.keys
        val combinedScores = mutableMapOf<String, Float>()
        for (lang in languages) {
            val trigram = trigramScores[lang] ?: 0f
            val keyword = keywordScores[lang] ?: 0f
            val pattern = patternScores[lang] ?: 0f
            // Trigrams: 40%, Keywords: 40%, Patterns: 20%
            combinedScores[lang] = trigram * 0.4f + keyword * 0.4f + pattern * 0.2f
        }

        // Step 5: Determine winner
        val total = combinedScores.values.sum().coerceAtLeast(0.001f)
        val normalizedScores = combinedScores.mapValues { it.value / total }
        val sorted = normalizedScores.entries.sortedByDescending { it.value }

        val best = sorted.first()
        val second = sorted.getOrNull(1)

        // Check for mixed/code-switched text
        if (second != null && best.value < 0.5f && second.value > 0.25f) {
            // Significant presence of two languages
            val mixedLang = determineMixedLanguage(best.key, second.key)
            val confidence = (best.value + second.value) / 2f
            return LanguageResult(mixedLang, confidence.coerceIn(0.3f, 0.9f), combinedScores)
        }

        val confidence = best.value.coerceIn(0.3f, 1.0f)
        return LanguageResult(best.key, confidence, combinedScores)
    }

    /**
     * Detect language at word level (for code-mixed text).
     * Returns list of (word, language) pairs.
     */
    fun detectPerWord(text: String): List<WordLanguage> {
        return text.split(Regex("[^\\p{L}']+"))
            .filter { it.isNotEmpty() }
            .map { word ->
                val clean = word.trim('\'', '"', '.', ',', '!', '?').lowercase()
                val lang = detectSingleWord(clean)
                WordLanguage(word, lang)
            }
    }

    /**
     * Check if text is code-mixed (contains multiple languages).
     */
    fun isCodeMixed(text: String): Boolean {
        val wordLangs = detectPerWord(text)
        val languages = wordLangs.map { it.language }.filter { it != "unknown" }.toSet()
        return languages.size > 1
    }

    /**
     * Get language distribution in text.
     */
    fun getLanguageDistribution(text: String): Map<String, Float> {
        val wordLangs = detectPerWord(text)
        val total = wordLangs.size.coerceAtLeast(1)
        return wordLangs.groupBy { it.language }
            .mapValues { (_, words) -> words.size.toFloat() / total }
    }

    /**
     * Check if text contains Sheng.
     */
    fun containsSheng(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^\\p{L}']+"))
        val shengKeywords = keywordMarkers["sheng"] ?: emptySet()
        return words.any { it.trim() in shengKeywords }
    }

    /**
     * Get the most appropriate TTS language for a text.
     */
    fun getTtsLanguage(text: String): String {
        val result = detectWithConfidence(text)
        return when (result.language) {
            "mixed" -> {
                val scores = result.scores
                val best = scores.maxByOrNull { it.value }?.key ?: "sw"
                if (best == "sheng") "sw" else best
            }
            "sheng" -> "sw"
            else -> result.language
        }
    }

    // ────────────────────── Internal Scoring ──────────────────────

    /**
     * Score text against language profiles using character trigrams.
     */
    private fun scoreByTrigrams(text: String): Map<String, Float> {
        val scores = mutableMapOf<String, Float>()
        val textTrigrams = extractTrigrams(text)

        for ((lang, profile) in languageProfiles) {
            var score = 0f
            for ((trigram, freq) in textTrigrams) {
                val profileFreq = profile[trigram] ?: continue
                score += freq * profileFreq
            }
            scores[lang] = score
        }

        return scores
    }

    /**
     * Score text using keyword markers.
     */
    private fun scoreByKeywords(words: List<String>): Map<String, Float> {
        val scores = mutableMapOf<String, Float>()

        for ((lang, keywords) in keywordMarkers) {
            var score = 0f
            for (word in words) {
                val clean = word.trim('\'', '"', '.', ',', '!', '?')
                if (clean in keywords) score += 3f
                // Partial match for longer keywords
                if (clean.length > 4 && keywords.any { it.contains(clean) || clean.contains(it) }) {
                    score += 1f
                }
            }
            scores[lang] = score
        }

        return scores
    }

    /**
     * Score text using character patterns.
     */
    private fun scoreByPatterns(text: String): Map<String, Float> {
        val scores = mutableMapOf<String, Float>()

        for ((lang, patterns) in charPatterns) {
            var score = 0f
            for (pattern in patterns) {
                val count = text.split(pattern).size - 1
                score += count.toFloat()
            }
            scores[lang] = score
        }

        return scores
    }

    /**
     * Detect language of a single word.
     */
    private fun detectSingleWord(word: String): String {
        val clean = word.trim('\'', '"', '.', ',', '!', '?').lowercase()
        if (clean.length < 2) return "unknown"

        // Check keyword markers first (highest confidence)
        for ((lang, keywords) in keywordMarkers) {
            if (clean in keywords) return lang
        }

        // Check market vocabulary
        if (SwahiliMarketVocabulary.isMarketTerm(clean)) return "sw"

        // Trigram scoring for single word
        val trigramScores = scoreByTrigrams(clean)
        val best = trigramScores.maxByOrNull { it.value }
        if (best != null && best.value > 0.01f) return best.key

        // Pattern-based fallback
        val patternScores = scoreByPatterns(clean)
        val bestPattern = patternScores.maxByOrNull { it.value }
        if (bestPattern != null && bestPattern.value > 0f) return bestPattern.key

        return "unknown"
    }

    /**
     * Determine the mixed language code for two detected languages.
     */
    private fun determineMixedLanguage(lang1: String, lang2: String): String {
        val kenyanLangs = setOf("sw", "sheng", "dholuo", "kikuyu", "kalenjin", "luhya")
        return when {
            lang1 in kenyanLangs && lang2 == "en" -> "mixed"
            lang1 == "en" && lang2 in kenyanLangs -> "mixed"
            lang1 == "sheng" || lang2 == "sheng" -> "mixed"
            else -> "mixed"
        }
    }

    /**
     * Extract character trigrams from text with frequency counts.
     */
    private fun extractTrigrams(text: String): Map<String, Float> {
        val trigrams = mutableMapOf<String, Int>()
        val clean = text.replace(Regex("\\s+"), " ")

        for (i in 0 until clean.length - 2) {
            val trigram = clean.substring(i, i + 3)
            trigrams[trigram] = (trigrams[trigram] ?: 0) + 1
        }

        val total = trigrams.values.sum().coerceAtLeast(1)
        return trigrams.mapValues { it.value.toFloat() / total }
    }

    // ────────────────────── Profile Builder ──────────────────────

    /**
     * Build language profiles from representative text samples.
     * These profiles capture the character n-gram distribution for each language.
     */
    private fun buildLanguageProfiles(): Map<String, Map<String, Float>> {
        val profiles = mutableMapOf<String, Map<String, Float>>()

        // Swahili profile
        profiles["sw"] = buildProfile(listOf(
            "habari za asubuhi, habari za mchana, habari za jioni",
            "ninaomba kununua mboga, bei ya mboga ni ngapi",
            "biashara yangu inaenda vizuri, nimepata faida kubwa leo",
            "pesa za kesho zitatosha kununua bidhaa mpya",
            "watu wanasema kwamba soko la kesho litakuwa kubwa",
            "mteja amenunua bidhaa nyingi, amelipa pesa zote",
            "niko hapa sokoni, ninasubiri mteja wangu",
            "leo nimeuza mboga nyingi, faida ni nzuri sana",
            "nenda dukani ununue unga wa ugali, mchele na mafuta",
            "kesho tutaenda soko kuu kununua bidhaa kwa wingi"
        ))

        // English profile
        profiles["en"] = buildProfile(listOf(
            "good morning, how are you today, business is good",
            "i sold vegetables today and made a profit of five hundred",
            "the customer bought many items and paid in cash",
            "i need to buy stock for tomorrow, the market will be busy",
            "the supplier delivered goods this morning, all items received",
            "my business is growing, revenue increased this month",
            "please check the inventory and record all transactions",
            "the balance sheet shows profit for this quarter",
            "i will pay the supplier tomorrow when i receive payment",
            "the market price has increased, customers are complaining"
        ))

        // Sheng profile
        profiles["sheng"] = buildProfile(listOf(
            "sasa boss, mambo poa, niaje na wewe",
            "nimechapaa leo, nimepata chapaa nyingi",
            "mbogi yangu tuko base, tukona morale",
            "ndege imekuja, thao moja imeisha",
            "msee amenunua mboga, amelipa cash",
            "tuko kanairo, maisha ni noma lakini tunajitahidi",
            "chapaa iko, mambo ni poa, life ni fiti",
            "boss wangu amesema ataniwekea order kubwa",
            "leo nimepiga sale nyingi, profit iko poa",
            "tuko mtaa, nduthi imetoka, tuko na gari mpya"
        ))

        // Dholuo profile
        profiles["dholuo"] = buildProfile(listOf(
            "erokamano, maber ahinya, kendo nadi",
            "adiera piyo e chuthi, maber ahinya",
            "wuon duka en ogo, kendo en gi rech maber",
            "ka inyalo kuonya nyiso en, ber ahinya",
            "malo kendo malo, yawuoyo, nadi kata",
            "en gi dhok maber e chuthi, maber ahinya",
            "ja rech en ogo, kendo en gi rech piyo",
            "amos ka inyalo kia, maber ahinya",
            "ng'wen en siru, to kendo nadi malo",
            "wuon min en ogo, kendo en gi rech maber"
        ))

        // Kikuyu profile
        profiles["kikuyu"] = buildProfile(listOf(
            "ni wega, muno, nĩ ndũ mũndũ",
            "nĩ nake agũũra ndũma e gĩka",
            "mũthondeki agũũra irio mūkimo gĩtheri",
            "ũngĩ mũndũ nĩo arĩ mũgũnda",
            "tiga hingo, mũno rĩ ngĩ",
            "nĩ ndũ nĩndũ, mũtũũrĩ wĩra",
            "gĩka nĩ mũthũri agũũra mbũri",
            "kĩhĩ nĩ njohi, mũkorũ nyũmba",
            "nake nayo nĩo tuĩka wĩra",
            "mũgũnda nĩ ng'ombe mbũri mũtĩ"
        ))

        // Kalenjin profile
        profiles["kalenjin"] = buildProfile(listOf(
            "amit, kainet, kongoi, mising",
            "kogo ich, koitoich, chamgei chengo",
            "mursik kimiet kabotet ng'atuny",
            "kipto kipsigis tugen nandi",
            "kapkoros kapchumba kaptich kapsirwet",
            "murenik tuiyotich moit ng'atuny",
            "amit mamit kogo ende amuno",
            "mursik ka mursik, kimiet kabotet",
            "kainet kongoi murio ende amuno",
            "chamgei chengo mising amit kogo"
        ))

        // Luhya profile
        profiles["luhya"] = buildProfile(listOf(
            "mulembe mushiambo khutsia khukhala",
            "omundu abantu enyumba omugunda",
            "khulola khuseva khukula khukhunda",
            "omukimo amakunde omusonga obusuma",
            "omutsatsa endimi ebinyenya ebibisya",
            "omukhongo omusinde omwikale omukhwe",
            "mukulu mukhulu mukasa simba",
            "amatsi endekho omukhono eshiwi",
            "khukhala khulola khuseva khukula",
            "omugunda omukhuyu enyumba abantu"
        ))

        // Somali profile
        profiles["so"] = buildProfile(listOf(
            "waa haye mahadsanid fadlan",
            "qof dad guri suuq biyo",
            "caano hilib bariis bur shaah",
            "sonkor saliid dhar lacag ganacsi",
            "geel arig lo idah faras dameer",
            "laakiin haddii maxaa xagee goorma",
            "waan wuu way waxaa oo iyo",
            "suuq ganacsi lacag kudi dukaan",
            "caano hilib subag suqaar muqmad",
            "shaah sonkor saliid khudaar malab"
        ))

        return profiles
    }

    /**
     * Build a trigram frequency profile from sample text.
     */
    private fun buildProfile(samples: List<String>): Map<String, Float> {
        val trigrams = mutableMapOf<String, Int>()
        for (sample in samples) {
            val clean = sample.lowercase()
            for (i in 0 until clean.length - 2) {
                val trigram = clean.substring(i, i + 3)
                trigrams[trigram] = (trigrams[trigram] ?: 0) + 1
            }
        }
        val total = trigrams.values.sum().coerceAtLeast(1)
        return trigrams.mapValues { it.value.toFloat() / total }
    }
}
