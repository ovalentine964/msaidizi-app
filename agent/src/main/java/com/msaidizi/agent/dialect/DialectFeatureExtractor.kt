package com.msaidizi.agent.dialect

import javax.inject.Inject
import javax.inject.Singleton

/**
 * DialectFeatureExtractor — Extracts phonetic, lexical, grammatical, and prosodic
 * features from voice interactions. Feeds into DialectProfile.
 *
 * Fully on-device, no network calls.
 */
@Singleton
class DialectFeatureExtractor @Inject constructor() {

    // Swahili morphological patterns for verb tense detection
    private val tenseMarkers = mapOf(
        "nime" to "perfect", "nili" to "past", "na" to "present",
        "nta" to "future", "niki" to "conditional", "hu" to "habitual",
        "si" to "negation", "u-me" to "perfect_2nd", "a-me" to "perfect_3rd",
        "tu-me" to "perfect_1st_pl", "wa-me" to "perfect_3rd_pl"
    )

    // Sheng detection markers
    private val shengMarkers = setOf(
        "sasa", "niaje", "mambo", "poa", "msee", "mbogi", "genje",
        "thao", "kibaki", "ngiri", "soo", "jaboya", "finje",
        "maze", "aki", "ati", "bas", "manze"
    )

    // Code-switch triggers — words that signal language transition
    private val codeSwitchTriggers = setOf(
        "customer", "stock", "receipt", "profit", "loss", "balance",
        "payment", "invoice", "report", "total", "please"
    )

    /**
     * Extract a DialectFeatureVector from a voice interaction.
     */
    fun extract(
        transcript: String,
        audioFeatures: FloatArray?,
        asrConfidence: Float,
        intent: String?
    ): DialectFeatureVector {
        val words = transcript.lowercase().split(Regex("[\\s,;.!?]+")).filter { it.isNotBlank() }

        return DialectFeatureVector(
            transcript = transcript,
            words = words,
            audioFeatures = audioFeatures,
            asrConfidence = asrConfidence,
            intent = intent,
            tenseMarkers = extractTenseMarkers(words),
            shengTermCount = words.count { it in shengMarkers },
            codeSwitchTriggerCount = words.count { it in codeSwitchTriggers },
            totalWords = words.size,
            hasShengMoney = words.any { it in shengMarkers && isShengMoney(it) }
        )
    }

    /**
     * Update phonetic profile with new observation.
     */
    fun updatePhonetic(current: PhoneticProfile, dfv: DialectFeatureVector): PhoneticProfile {
        // Phonetic analysis from audio features (if available)
        if (dfv.audioFeatures == null) return current

        val substitutions = current.substitutions.toMutableMap()
        // In a real implementation, this would do forced alignment
        // For now, track syllable patterns from transcript
        for (word in dfv.words) {
            val canonical = canonicalPhonemes(word)
            if (canonical.isNotEmpty()) {
                // Track vowel-heavy vs consonant-heavy patterns
                val vowelRatio = word.count { it in "aeiou" }.toFloat() / word.length
                val key = "vowel_ratio_${word.take(3)}"
                substitutions.getOrPut(key) { mutableMapOf() }
                    .merge("%.2f".format(vowelRatio), 1f) { a, b -> a + b }
            }
        }

        return PhoneticProfile(
            substitutions = substitutions,
            sampleCount = current.sampleCount + 1
        )
    }

    /**
     * Update vocabulary with new terms from interaction.
     */
    fun updateVocabulary(current: PersonalVocabulary, dfv: DialectFeatureVector): PersonalVocabulary {
        val vocab = current.copy()

        for (word in dfv.words) {
            val clean = word.replace(Regex("[^a-z]"), "")
            if (clean.length < 2) continue

            val language = classifyWordLanguage(clean)
            val category = classifyWordCategory(clean, dfv)

            if (clean !in vocab.terms) {
                vocab.addTerm(clean, language, category)
            } else {
                vocab.terms[clean] = vocab.terms[clean]!!.copy(
                    frequency = vocab.terms[clean]!!.frequency + 1
                )
            }
        }

        // Update code-switch ratio
        if (dfv.totalWords > 0) {
            val swCount = dfv.words.count { classifyWordLanguage(it) == "sw" }
            val enCount = dfv.words.count { classifyWordLanguage(it) == "en" }
            val shengCount = dfv.shengTermCount
            val nonSwahili = enCount + shengCount
            vocab.codeSwitchRatio = nonSwahili.toFloat() / dfv.totalWords
        }

        return vocab
    }

    /**
     * Update grammar profile with tense and word order patterns.
     */
    fun updateGrammar(current: GrammarProfile, dfv: DialectFeatureVector): GrammarProfile {
        val patterns = current.tensePatterns.toMutableMap()
        val triggers = current.codeSwitchTriggers.toMutableMap()

        for ((marker, tense) in dfv.tenseMarkers) {
            patterns.merge(tense, 1f) { a, b -> a + b }
        }

        // Track code-switch trigger words
        for (word in dfv.words) {
            if (word in codeSwitchTriggers) {
                triggers.merge(word, 1f) { a, b -> a + b }
            }
        }

        return GrammarProfile(
            tensePatterns = patterns,
            codeSwitchTriggers = triggers,
            sampleCount = current.sampleCount + 1
        )
    }

    /**
     * Update prosodic profile from audio features.
     */
    fun updateProsody(current: ProsodicProfile, dfv: DialectFeatureVector): ProsodicProfile {
        if (dfv.audioFeatures == null) return current

        // Estimate syllable rate from word count and audio duration
        val audioDurationSec = dfv.audioFeatures.size / 16000f // assuming 16kHz
        val syllableEstimate = dfv.totalWords * 2.5f // avg 2.5 syllables per word
        val rate = if (audioDurationSec > 0) syllableEstimate / audioDurationSec else 0f

        // Track pause fillers
        val fillers = current.pauseFillers.toMutableMap()
        val fillerWords = setOf("eh", "aa", "um", "eeh", "aai", "woiye")
        for (word in dfv.words) {
            if (word in fillerWords) {
                fillers.merge(word, 1) { a, b -> a + b }
            }
        }

        // Detect rhythm class based on syllable rate
        val rhythm = when {
            rate > 7f -> "fast-sheng"
            rate > 5.5f -> "moderate-kenyan"
            rate > 0f -> "slow-standard"
            else -> current.rhythmClass
        }

        return ProsodicProfile(
            meanSyllableRate = if (current.sampleCount == 0) rate
                else (current.meanSyllableRate * current.sampleCount + rate) / (current.sampleCount + 1),
            rhythmClass = rhythm,
            pauseFillers = fillers,
            sampleCount = current.sampleCount + 1
        )
    }

    // ── Private helpers ──────────────────────────────────────

    private fun extractTenseMarkers(words: List<String>): Map<String, Int> {
        val found = mutableMapOf<String, Int>()
        for (word in words) {
            for ((prefix, tense) in tenseMarkers) {
                if (word.startsWith(prefix) && word.length > prefix.length + 1) {
                    found.merge(tense, 1) { a, b -> a + b }
                }
            }
        }
        return found
    }

    private fun isShengMoney(word: String): Boolean {
        return word in setOf("thao", "kibaki", "ngiri", "soo", "jaboya", "finje",
            "ndovu", "gunia", "robo", "kichele", "doe")
    }

    private fun canonicalPhonemes(word: String): String {
        // Simplified phoneme mapping for Bantu words
        return word.replace("ng'", "ŋ")
            .replace("ny", "ɲ")
            .replace("th", "t̪")
            .replace("dh", "d̪")
    }

    private fun classifyWordLanguage(word: String): String {
        val clean = word.replace(Regex("[^a-z]"), "")
        return when {
            clean.all { it.isDigit() } -> "number"
            clean in shengMarkers -> "sheng"
            clean in swahiliFunctionWords -> "sw"
            clean in swahiliContentWords -> "sw"
            clean in englishWords -> "en"
            clean in codeSwitchTriggers -> "shared"
            hasSwahiliMorphology(clean) -> "sw"
            hasEnglishPattern(clean) -> "en"
            else -> "unknown"
        }
    }

    private fun classifyWordCategory(word: String, dfv: DialectFeatureVector): String {
        return when {
            word in setOf("faida", "hasara", "deni", "mkopo", "riba", "akiba", "mshahara",
                "thao", "kibaki", "ngiri", "soo", "jaboya", "finje") -> "financial"
            word in setOf("mteja", "bidhaa", "duka", "soko", "biashara", "jumla", "rejareja") -> "business"
            word in shengMarkers -> "slang"
            word in codeSwitchTriggers -> "shared"
            else -> "general"
        }
    }

    private fun hasSwahiliMorphology(word: String): Boolean {
        if (word.length < 4) return false
        val prefixes = listOf("mwa", "ki", "vi", "ma", "wa", "ya", "za", "la", "cha")
        val endings = listOf("ile", "ana", "ea", "ia", "wa", "sha", "nja", "nga")
        return prefixes.any { word.startsWith(it) && word.length > 4 } &&
               endings.any { word.endsWith(it) }
    }

    private fun hasEnglishPattern(word: String): Boolean {
        if (word.length < 4) return false
        val patterns = listOf("tion", "sion", "ment", "ness", "able", "ible", "ight", "ough")
        return patterns.any { word.contains(it) }
    }

    companion object {
        private val swahiliFunctionWords = setOf(
            "na", "ya", "za", "wa", "la", "cha", "kwa", "katika",
            "ni", "si", "hu", "lakini", "au", "ama", "pia", "tena",
            "sasa", "bado", "tayari", "sana", "hii", "huyo", "yule",
            "mimi", "wewe", "yeye", "sisi", "nyinyi", "wao",
            "angu", "ako", "ake", "etu", "enu", "ao"
        )

        private val swahiliContentWords = setOf(
            "nimeuza", "nimenunua", "nimetumia", "nimepata", "nimefanya",
            "ninaomba", "naomba", "nataka", "fanya", "nenda", "rudi",
            "pata", "leta", "toa", "chukua", "weka",
            "faida", "hasara", "deni", "mteja", "bidhaa", "gharama",
            "bei", "punguzo", "malipo", "pesa", "shilingi",
            "elfu", "mia", "laki", "milioni", "mkopo", "riba",
            "biashara", "duka", "soko", "habari", "asante", "karibu",
            "tafadhali", "pole", "sawa", "nzuri", "mbaya", "kubwa", "ndogo"
        )

        private val englishWords = setOf(
            "the", "is", "was", "are", "were", "be", "have", "has", "had",
            "do", "does", "did", "will", "would", "shall", "should",
            "and", "but", "or", "not", "for", "with", "from", "to", "of",
            "in", "on", "at", "by", "this", "that", "it", "its",
            "i", "me", "my", "we", "you", "your", "he", "she", "they",
            "what", "which", "who", "when", "where", "why", "how",
            "all", "some", "more", "than", "very", "just", "because",
            "profit", "loss", "expense", "revenue", "customer", "product",
            "stock", "inventory", "report", "total", "balance", "payment",
            "receipt", "business", "market", "price", "cost", "discount",
            "loan", "debt", "credit", "savings"
        )
    }
}

/**
 * DialectFeatureVector — compressed representation of one voice interaction.
 */
data class DialectFeatureVector(
    val transcript: String,
    val words: List<String>,
    val audioFeatures: FloatArray?,
    val asrConfidence: Float,
    val intent: String?,
    val tenseMarkers: Map<String, Int>,
    val shengTermCount: Int,
    val codeSwitchTriggerCount: Int,
    val totalWords: Int,
    val hasShengMoney: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DialectFeatureVector) return false
        return transcript == other.transcript
    }

    override fun hashCode(): Int = transcript.hashCode()
}
